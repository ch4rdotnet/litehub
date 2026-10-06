package com.chardidathing.litehub.source.calendar

import com.chardidathing.litehub.core.model.CalendarEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

// ha's calendar.get_events, which hands back occurrences already expanded
internal object HaCalendar {

    fun request(from: Instant, until: Instant) = buildJsonObject {
        put("start_date_time", from.toString())
        put("end_date_time", until.toString())
    }

    fun parse(response: JsonObject?, entity: String, source: String): List<CalendarEvent> {
        val events = ((response?.get(entity) as? JsonObject)?.get("events") as? JsonArray) ?: return emptyList()
        return events.mapNotNull { element ->
            val e = element as? JsonObject ?: return@mapNotNull null
            val start = e.string("start") ?: return@mapNotNull null
            val end = e.string("end") ?: start
            val title = e.string("summary").orEmpty()
            // ha doesn't give a uid for every integration, start and title are unique enough
            val id = e.string("uid")?.let { "$it/$start" } ?: "$start/$title"
            try {
                if (start.length == 10) {
                    val s = LocalDate.parse(start)
                    val en = LocalDate.parse(end).let { if (it.isAfter(s)) it else s.plusDays(1) }
                    CalendarEvent(source, id, title, e.string("location"), true, utcMidnight(s), utcMidnight(en))
                } else {
                    val s = OffsetDateTime.parse(start).toInstant().toEpochMilli()
                    val en = OffsetDateTime.parse(end).toInstant().toEpochMilli().coerceAtLeast(s)
                    CalendarEvent(source, id, title, e.string("location"), false, s, en)
                }
            } catch (ex: DateTimeParseException) {
                null
            }
        }
    }

    private fun utcMidnight(d: LocalDate) = d.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.ifBlank { null }
}
