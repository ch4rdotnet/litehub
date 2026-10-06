package com.chardidathing.litehub.source.ha

import android.database.sqlite.SQLiteException
import com.chardidathing.litehub.core.model.Entity
import com.chardidathing.litehub.core.model.EntityChoice
import com.chardidathing.litehub.core.model.EntitySnapshot
import com.chardidathing.litehub.core.model.TodoItem
import com.chardidathing.litehub.core.model.TodoSnapshot
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import kotlinx.serialization.json.JsonObject
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

    // the rest side of mobile_app, null without working credentials
    val mobileApp: MobileApp? = credentials.getOrNull()?.let { MobileApp(it, http) }

    private var onPush: ((JsonObject) -> Unit)? = null
    private var onPushRejected: (() -> Unit)? = null

    // notifications for this hub's mobile_app device, null webhook stops them. rejected means
    // ha no longer knows the device
    fun setPushChannel(webhookId: String?, rejected: () -> Unit = {}, handler: (JsonObject) -> Unit) {
        onPush = handler
        onPushRejected = rejected
        scope.launch { client?.setPushChannel(webhookId) }
    }
    private var status: HaClient.Status = when (client) {
        null -> HaClient.Status.Failed(credentials.exceptionOrNull()?.message ?: "home assistant isn't set up")
        else -> HaClient.Status.Connecting
    }

    private val _connected = MutableStateFlow(false)

    // whether the websocket is up and authenticated, for work that's pointless before it is
    val connected: StateFlow<Boolean> = _connected

    private val entities = HashMap<String, Entity>()
    // seen since the current subscription started, anything else is from the cache
    private val fresh = HashSet<String>()
    private val notFound = HashSet<String>()
    private val optimistic = HashMap<String, Entity>()
    // what's on screen and subscribed, everything else is shown as last known
    private var visible: Set<String> = emptySet()
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
        if (started) return
        started = true
        client?.start()
    }

    // drops the connection for good, a reload builds a new repository
    fun close() {
        scope.cancel()
        cache.close()
    }

    private var started = false

    fun snapshot(id: String): StateFlow<EntitySnapshot> =
        flows[id] ?: flows.computeIfAbsent(id) { MutableStateFlow(EntitySnapshot.Connecting) }
            .also { scope.launch { publish(id) } }

    fun setVisible(ids: Set<String>) {
        scope.launch {
            val dropped = visible - ids
            visible = ids
            client?.setEntityIds(ids)
            dropped.forEach(::publish)
        }
    }

    fun canToggle(id: String) = id.substringBefore('.') in TOGGLE_DOMAINS

    private val todos = HashMap<String, MutableStateFlow<TodoSnapshot>>()
    private val todoItems = HashMap<String, List<TodoItem>>()
    private val todoErrors = HashMap<String, String>()

    // a todo list's items, live. subscribing costs ha a little, release it when it's off screen
    fun todo(entity: String): StateFlow<TodoSnapshot> {
        val flow = synchronized(todos) { todos.getOrPut(entity) { MutableStateFlow(TodoSnapshot.Loading) } }
        scope.launch {
            publishTodo(entity)
            client?.subscribe("todo:$entity", "todo/item/subscribe", buildJsonObject { put("entity_id", entity) }) { event ->
                val items = (event["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { i ->
                    val uid = i.str("uid") ?: return@mapNotNull null
                    TodoItem(uid, i.str("summary").orEmpty(), i.str("status") == "completed")
                }
                todoItems[entity] = items
                todoErrors.remove(entity)
                publishTodo(entity)
            }
        }
        return flow
    }

    fun releaseTodo(entity: String) {
        scope.launch { client?.unsubscribe("todo:$entity") }
    }

    // ticks straight away, ha's next event confirms it or the error puts it back
    suspend fun todoSetDone(entity: String, uid: String, done: Boolean): Result<Unit> = withContext(confined) {
        val before = todoItems[entity].orEmpty()
        todoItems[entity] = before.map { if (it.uid == uid) it.copy(done = done) else it }
        publishTodo(entity)
        val data = buildJsonObject {
            put("item", uid)
            put("status", if (done) "completed" else "needs_action")
        }
        val result = client?.callService("todo", "update_item", entity, data)?.map { } ?: Result.failure(IOException(statusReason()))
        result.onFailure {
            todoItems[entity] = before
            todoErrors[entity] = it.message ?: "home assistant refused"
            publishTodo(entity)
        }
        result
    }

    suspend fun todoAdd(entity: String, text: String): Result<Unit> = withContext(confined) {
        val result = client?.callService("todo", "add_item", entity, buildJsonObject { put("item", text) })?.map { }
            ?: Result.failure(IOException(statusReason()))
        result.onFailure {
            todoErrors[entity] = it.message ?: "home assistant refused"
            publishTodo(entity)
        }
        result
    }

    private fun publishTodo(entity: String) {
        val flow = synchronized(todos) { todos[entity] } ?: return
        val items = todoItems[entity]
        val s = status
        flow.value = when {
            items != null -> TodoSnapshot.Ready(
                items,
                stale = (s as? HaClient.Status.Failed)?.reason ?: if (s is HaClient.Status.Connecting) CONNECTING else null,
                error = todoErrors[entity],
            )
            s is HaClient.Status.Failed -> TodoSnapshot.Failed(s.reason)
            else -> TodoSnapshot.Loading
        }
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    // every entity ha knows, named and grouped by area, for the editor's picker
    suspend fun catalogue(): Result<List<EntityChoice>> = withContext(confined) {
        val c = client ?: return@withContext Result.failure(IOException(statusReason()))
        val states = c.command("get_states").getOrElse { return@withContext Result.failure(it) }
        // the registries are nice to have, an old ha without them still gets a flat list
        val entities = c.command("config/entity_registry/list_for_display").getOrNull()
        val devices = c.command("config/device_registry/list").getOrNull()
        val areas = c.command("config/area_registry/list").getOrNull()
        Result.success(EntityCatalogue.build(states, entities, devices, areas))
    }

    // a service call that answers with data, fails straight away while ha isn't connected
    suspend fun query(domain: String, service: String, entityId: String, data: JsonObject): Result<JsonObject?> =
        withContext(confined) {
            val c = client ?: return@withContext Result.failure(IOException(statusReason()))
            c.callService(domain, service, entityId, data, returnResponse = true)
        }

    // flips the tile straight away and rolls back if ha says no
    suspend fun toggle(id: String): Result<JsonObject?> = withContext(confined) {
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
        override fun onPush(message: JsonObject) {
            onPush?.invoke(message)
        }

        override fun onPushRejected() {
            onPushRejected?.invoke()
        }

        override fun onStatus(status: HaClient.Status) {
            this@EntityRepository.status = status
            _connected.value = status is HaClient.Status.Connected
            if (status !is HaClient.Status.Connected) fresh.clear()
            flows.keys.forEach(::publish)
            synchronized(todos) { todos.keys.toList() }.forEach(::publishTodo)
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
                // a neighbouring page, ha isn't sending it but nothing's wrong either
                entity != null && id !in visible -> EntitySnapshot.Live(entity, error)
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
