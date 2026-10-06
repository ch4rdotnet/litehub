package com.chardidathing.litehub.source.weather

import android.content.Context
import com.chardidathing.litehub.core.model.EntitySnapshot
import com.chardidathing.litehub.core.model.Forecast
import com.chardidathing.litehub.core.model.Location
import com.chardidathing.litehub.core.model.Weather
import com.chardidathing.litehub.core.model.WeatherSnapshot
import com.chardidathing.litehub.source.fetch.Fetcher
import com.chardidathing.litehub.source.fetch.RefreshLoop
import com.chardidathing.litehub.source.ha.EntityRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

// weather for each ha weather entity a widget shows, or open-meteo for the location when a
// widget has none. now is live (the entity's state), forecasts refresh on an interval. the last
// good weather is kept on disk so it draws on boot and offline
class WeatherRepository(
    context: Context,
    private val ha: EntityRepository,
    private val fetcher: Fetcher,
    private val location: Location?,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val dir = File(context.cacheDir, "weather").apply { mkdirs() }
    private val confined = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + confined)
    private val json = Json { ignoreUnknownKeys = true }
    private val flows = HashMap<String, MutableStateFlow<WeatherSnapshot>>()
    private val forecasts = HashMap<String, Pair<List<Forecast>, List<Forecast>>>()
    private val failures = HashMap<String, String>()
    private val live = HashMap<String, Job>()
    private var loop: RefreshLoop? = null

    // the key for a widget, its entity or open-meteo
    fun key(entity: String?) = entity ?: OPEN_METEO

    // every key the dashboard needs, call before start
    suspend fun prepare(keys: Set<String>) = withContext(confined) {
        loop?.stop()
        for (k in keys) {
            flows.getOrPut(k) { MutableStateFlow(WeatherSnapshot.Loading) }
            cached(k)?.let { flows.getValue(k).value = WeatherSnapshot.Ready(it) }
        }
        loop = RefreshLoop(scope, keys.associateWith { REFRESH }, now, ::refresh)
    }

    fun watch(key: String): StateFlow<WeatherSnapshot> =
        synchronized(flows) { flows.getOrPut(key) { MutableStateFlow(WeatherSnapshot.Loading) } }

    fun start() {
        loop?.start()
        // ha entities' current conditions follow the entity, forecasts follow the loop
        scope.launch {
            for (k in flows.keys.filter { it != OPEN_METEO }) {
                if (live[k]?.isActive == true) continue
                live[k] = scope.launch { ha.snapshot(k).collectLatest { publish(k) } }
            }
        }
    }

    fun stop() {
        loop?.stop()
        live.values.forEach(Job::cancel)
        live.clear()
    }

    fun close() = scope.cancel()

    private suspend fun refresh(key: String): Boolean {
        val result = if (key == OPEN_METEO) openMeteo() else haForecasts(key)
        result.onSuccess { failures.remove(key) }.onFailure { failures[key] = it.message ?: "couldn't fetch the weather" }
        publish(key)
        return result.isSuccess
    }

    private suspend fun openMeteo(): Result<Unit> {
        val loc = location ?: return Result.failure(IOException("no location in sources.json for open-meteo"))
        return fetcher.get(OpenMeteo.url(loc)) { reader, _ -> OpenMeteo.parse(reader.readText(), now()) }.map { weather ->
            forecasts[OPEN_METEO] = weather.hourly to weather.daily
            store(OPEN_METEO, weather)
        }
    }

    private suspend fun haForecasts(entity: String): Result<Unit> {
        // the loop's first pass lands right after boot, give the websocket a moment to come up
        withTimeoutOrNull(CONNECT_WAIT) { ha.connected.first { it } }
        val hourly = ha.query("weather", "get_forecasts", entity, buildJsonObject { put("type", "hourly") })
            .map { HaWeather.forecasts(it, entity, zone()).filter { f -> f.timeMs >= now() - HOUR_MS }.take(OpenMeteo.HOURS) }
        val daily = ha.query("weather", "get_forecasts", entity, buildJsonObject { put("type", "daily") })
            .map { HaWeather.forecasts(it, entity, zone()).take(OpenMeteo.DAYS) }
        // some integrations only do one of the two, that's fine as long as one answered
        if (hourly.isFailure && daily.isFailure) return Result.failure(hourly.exceptionOrNull()!!)
        forecasts[entity] = hourly.getOrDefault(emptyList()) to daily.getOrDefault(emptyList())
        return Result.success(Unit)
    }

    private fun publish(key: String) {
        val flow = flows[key] ?: return
        val failure = failures[key]
        val weather: Weather? = if (key == OPEN_METEO) cached(key) else haWeather(key)
        flow.value = when {
            weather != null -> WeatherSnapshot.Ready(weather, stale = failure ?: staleReason(key))
            failure != null -> WeatherSnapshot.Failed(failure)
            else -> WeatherSnapshot.Loading
        }
    }

    private fun haWeather(entity: String): Weather? {
        val snapshot = ha.snapshot(entity).value
        val e = when (snapshot) {
            is EntitySnapshot.Live -> snapshot.entity
            is EntitySnapshot.Stale -> snapshot.entity
            else -> return cached(entity)
        }
        val (hourly, daily) = forecasts[entity] ?: (cached(entity)?.let { it.hourly to it.daily } ?: (emptyList<Forecast>() to emptyList()))
        return HaWeather.current(e, hourly, daily).also { store(entity, it) }
    }

    private fun staleReason(key: String): String? =
        if (key == OPEN_METEO) null else (ha.snapshot(key).value as? EntitySnapshot.Stale)?.reason

    private fun file(key: String) = File(dir, key.replace(Regex("[^a-z0-9_.]"), "_") + ".json")

    private fun cached(key: String): Weather? = try {
        file(key).takeIf { it.exists() }?.readText()?.let { json.decodeFromString(Weather.serializer(), it) }
    } catch (e: SerializationException) {
        null
    } catch (e: IOException) {
        null
    }

    private fun store(key: String, weather: Weather) {
        try {
            file(key).writeText(json.encodeToString(Weather.serializer(), weather))
        } catch (e: IOException) {
            // a cache that can't be written is a slower boot, nothing worse
        }
    }

    companion object {
        const val OPEN_METEO = "open-meteo"
        private val REFRESH = 30.minutes
        private val CONNECT_WAIT = 30.seconds
        private const val HOUR_MS = 3_600_000L
    }
}
