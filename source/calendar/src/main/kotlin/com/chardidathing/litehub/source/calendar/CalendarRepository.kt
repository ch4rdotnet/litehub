package com.chardidathing.litehub.source.calendar

import android.database.sqlite.SQLiteException
import com.chardidathing.litehub.core.model.CalendarEvent
import com.chardidathing.litehub.core.model.CalendarSnapshot
import com.chardidathing.litehub.core.model.CalendarSource
import com.chardidathing.litehub.core.model.SourceStatus
import com.chardidathing.litehub.source.fetch.Fetcher
import com.chardidathing.litehub.source.fetch.RefreshLoop
import com.chardidathing.litehub.source.ha.EntityRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes

// every calendar source, refreshed on its own interval while started. the store keeps the last
// good result, a failed refresh keeps showing it and says why
class CalendarRepository(
    private val sources: List<CalendarSource>,
    private val fetcher: Fetcher,
    private val store: CalendarStore,
    private val ha: EntityRepository?,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val confined = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + confined)
    private val byId = sources.associateBy { it.id }
    private val windowDays = HashMap<String, Long?>()
    private val _snapshot = MutableStateFlow(CalendarSnapshot(emptyList(), emptyMap()))
    val snapshot: StateFlow<CalendarSnapshot> = _snapshot

    private val loop = RefreshLoop(
        scope = scope,
        intervals = sources.associate { it.id to it.refreshMinutes.minutes },
        now = now,
        refresh = ::refresh,
    )

    // reads what's stored, call before the first frame so calendars draw with data
    suspend fun preload() = withContext(confined) {
        try {
            store.keepOnly(byId.keys)
            val statuses = store.statuses()
            statuses.forEach { (id, s) -> windowDays[id] = s.windowDay }
            loop.seed(statuses.mapValues { it.value.status.lastGood })
        } catch (e: SQLiteException) {
            // no store just means fetching everything fresh
        }
        publish()
    }

    fun start() = loop.start()

    fun stop() = loop.stop()

    private suspend fun refresh(id: String): Boolean {
        val source = byId[id] ?: return true
        val zone = zone()
        val today = LocalDate.now(zone)
        val day = today.toEpochDay()
        val from = CalendarWindow.from(today, zone)
        val until = CalendarWindow.until(today, zone)
        val url = source.url
        val entity = source.entity
        val result = when {
            url != null -> fetcher.get(url) { reader, changed ->
                // an unchanged feed still needs expanding again once the window has moved on
                if (!changed && windowDays[id] == day) null else IcsCalendar.parse(reader, id, zone, from, until)
            }
            entity != null -> fetchHa(entity, id, from, until)
            else -> Result.failure(IOException("no url or entity"))
        }
        try {
            result.fold(
                onSuccess = { events ->
                    if (events == null) store.unchanged(id, now(), day) else store.replace(id, events, now(), day)
                    windowDays[id] = day
                },
                onFailure = { store.failed(id, it.message ?: "couldn't fetch") },
            )
        } catch (e: SQLiteException) {
            // the data was fine, the cache couldn't take it, still worth showing next time
        }
        publish()
        return result.isSuccess
    }

    private suspend fun fetchHa(entity: String, id: String, from: Instant, until: Instant) =
        ha?.query("calendar", "get_events", entity, HaCalendar.request(from, until))
            ?.map { HaCalendar.parse(it, entity, id) }
            ?: Result.failure(IOException("home assistant isn't set up"))

    private fun publish() {
        val (events, statuses) = try {
            store.events() to store.statuses().mapValues { it.value.status }
        } catch (e: SQLiteException) {
            emptyList<CalendarEvent>() to emptyMap<String, SourceStatus>()
        }
        _snapshot.value = CalendarSnapshot(events.filter { it.source in byId }, statuses)
    }
}
