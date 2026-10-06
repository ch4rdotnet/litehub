package com.chardidathing.litehub.source.ha

import android.database.sqlite.SQLiteException
import com.chardidathing.litehub.core.model.Entity
import com.chardidathing.litehub.core.model.EntitySnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

// live entity state for whatever is on screen, backed by the cache while ha is away.
// all state is confined to one thread, the public calls hop onto it
class EntityRepository(
    credentials: Result<HaCredentials>,
    http: OkHttpClient,
    private val cache: EntityCache,
    private val timing: HaTiming = HaTiming(),
) {

    private val confined = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + confined)

    private val client = credentials.getOrNull()?.let { HaClient(it, http, scope, timing, Events()) }
    private var status: HaClient.Status = when (client) {
        null -> HaClient.Status.Failed(credentials.exceptionOrNull()?.message ?: "home assistant isn't set up")
        else -> HaClient.Status.Connecting
    }

    private val entities = HashMap<String, Entity>()
    // seen since the current subscription started, anything else is from the cache
    private val fresh = HashSet<String>()
    private val notFound = HashSet<String>()
    private val optimistic = HashMap<String, Entity>()
    private val errors = HashMap<String, String>()
    private val flows = ConcurrentHashMap<String, MutableStateFlow<EntitySnapshot>>()
    private val dirty = HashSet<String>()
    private var flushing = false

    // reads the cache for these ids, call before the first frame so it draws with data
    suspend fun preload(ids: Collection<String>) = withContext(confined) {
        try {
            entities.putAll(cache.load(ids.filter { it !in entities }))
        } catch (e: SQLiteException) {
            // no cache just means waiting for ha
        }
        ids.forEach(::publish)
    }

    // network starts here and never before, so startup stays offline until the first frame
    fun connect() {
        client?.start()
    }

    fun snapshot(id: String): StateFlow<EntitySnapshot> =
        flows[id] ?: flows.computeIfAbsent(id) { MutableStateFlow(EntitySnapshot.Connecting) }
            .also { scope.launch { publish(id) } }

    fun setVisible(ids: Set<String>) {
        scope.launch { client?.setEntityIds(ids) }
    }

    fun canToggle(id: String) = id.substringBefore('.') in TOGGLE_DOMAINS

    // flips the tile straight away and rolls back if ha says no
    suspend fun toggle(id: String): Result<Unit> = withContext(confined) {
        val c = client ?: return@withContext Result.failure(IOException(statusReason()))
        val current = entities[id]
        val guess = when (current?.state) {
            "on" -> current.copy(state = "off")
            "off" -> current.copy(state = "on")
            else -> null
        }
        errors.remove(id)
        if (guess != null) optimistic[id] = guess
        publish(id)
        val result = c.callService(id.substringBefore('.'), "toggle", id)
        if (result.isFailure) {
            if (guess != null) optimistic.remove(id)
            val message = result.exceptionOrNull()?.message ?: "home assistant refused"
            errors[id] = message
            expire(id, timing.errorHold) { errors[id] === message && errors.remove(id) != null }
        } else if (guess != null) {
            // only drop our own guess, a later toggle may have replaced it
            expire(id, timing.optimisticHold) { optimistic[id] === guess && optimistic.remove(id) != null }
        }
        publish(id)
        result
    }

    private fun expire(id: String, after: Duration, drop: () -> Boolean) {
        scope.launch {
            delay(after)
            if (drop()) publish(id)
        }
    }

    private inner class Events : HaClient.Listener {
        override fun onStatus(status: HaClient.Status) {
            this@EntityRepository.status = status
            if (status !is HaClient.Status.Connected) fresh.clear()
            flows.keys.forEach(::publish)
        }

        override fun onEntities(event: HaClient.EntityEvent) {
            val result = EntityDiff.apply(entities, event.body)
            val touched = result.added + result.changed + result.removed
            if (event.initial) {
                fresh.clear()
                notFound.removeAll(event.requested)
                notFound.addAll(event.requested.filter { it !in result.added })
            }
            notFound.addAll(result.removed)
            fresh.addAll(result.added + result.changed)
            touched.forEach {
                optimistic.remove(it)
                errors.remove(it)
            }
            val affected = if (event.initial) touched + event.requested else touched
            markDirty(affected)
            affected.forEach(::publish)
        }
    }

    private fun publish(id: String) {
        val flow = flows.computeIfAbsent(id) { MutableStateFlow(EntitySnapshot.Connecting) }
        flow.value = compute(id)
    }

    private fun compute(id: String): EntitySnapshot {
        val entity = optimistic[id] ?: entities[id]
        val error = errors[id]
        return when (val s = status) {
            HaClient.Status.Connected -> when {
                id in notFound -> EntitySnapshot.NotFound
                entity != null && id in fresh -> EntitySnapshot.Live(entity, error)
                entity != null -> EntitySnapshot.Stale(entity, CONNECTING, error)
                else -> EntitySnapshot.Connecting
            }
            HaClient.Status.Connecting -> entity?.let { EntitySnapshot.Stale(it, CONNECTING, error) } ?: EntitySnapshot.Connecting
            is HaClient.Status.Failed -> entity?.let { EntitySnapshot.Stale(it, s.reason, error) } ?: EntitySnapshot.Failed(s.reason)
        }
    }

    private fun statusReason() = (status as? HaClient.Status.Failed)?.reason ?: "not connected to home assistant"

    private fun markDirty(ids: Collection<String>) {
        dirty.addAll(ids)
        if (flushing) return
        flushing = true
        scope.launch {
            delay(timing.cacheFlush)
            val save = dirty.mapNotNull { entities[it] }
            val delete = dirty.filter { it !in entities }
            dirty.clear()
            flushing = false
            try {
                cache.write(save, delete)
            } catch (e: SQLiteException) {
                // best effort, a full disk shouldn't take the dashboard down
            }
        }
    }

    private companion object {
        const val CONNECTING = "connecting"

        val TOGGLE_DOMAINS = setOf("light", "switch", "fan", "input_boolean", "automation", "siren", "humidifier")
    }
}
