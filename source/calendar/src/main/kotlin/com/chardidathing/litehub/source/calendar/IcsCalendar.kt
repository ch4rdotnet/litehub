package com.chardidathing.litehub.source.calendar

import com.chardidathing.litehub.core.model.CalendarEvent
import java.io.Reader
import java.time.Instant
import java.time.ZoneId

object IcsCalendar {

    // occurrences of every event in [from, until). one pass, one-off events far outside the
    // window are dropped as they stream past so a big feed stays small in memory
    fun parse(reader: Reader, source: String, device: ZoneId, from: Instant, until: Instant): List<CalendarEvent> {
        val vtimezones = ArrayList<Component>()
        val events = ArrayList<IcsEvent>()
        // a day of slack either side, the event's own zone isn't known until the end
        val earliest = from.atZone(device).toLocalDate().minusDays(1)
        val latest = until.atZone(device).toLocalDate().plusDays(1)
        for (c in IcsReader(reader).components()) {
            when (c.name) {
                "VTIMEZONE" -> vtimezones += c
                "VEVENT" -> {
                    val e = IcsEvent.from(c) ?: continue
                    val recurs = e.rrule != null || e.rdates.isNotEmpty() || e.recurrenceId != null
                    val day = e.start.local.toLocalDate()
                    val endDay = e.end?.local?.toLocalDate() ?: day.plus(e.days ?: java.time.Period.ZERO).plusDays(1)
                    if (!recurs && (endDay.isBefore(earliest) || day.isAfter(latest))) continue
                    events += e
                }
            }
        }
        return Expander(Zones(vtimezones, device), from, until).expand(source, events)
    }
}
