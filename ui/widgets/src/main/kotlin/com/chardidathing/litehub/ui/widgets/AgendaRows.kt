package com.chardidathing.litehub.ui.widgets

import com.chardidathing.litehub.core.model.CalendarEvent
import com.chardidathing.litehub.core.model.CalendarSnapshot
import com.chardidathing.litehub.ui.components.RowList.Kind
import com.chardidathing.litehub.ui.components.RowList.Row
import java.time.LocalDate

internal object AgendaRows {

    fun build(snapshot: CalendarSnapshot, sources: List<String>, days: Int, legend: Legend, now: Moment): List<Row> {
        val today = now.today
        val last = today.plusDays(days.toLong() - 1)
        val upcoming = snapshot.events
            .filter { it.source in sources && !ended(it, now, today) }
            .map { (if (startDay(it, now).isBefore(today)) today else startDay(it, now)) to it }
            .filter { !it.first.isAfter(last) }
            .sortedWith(compareBy({ it.first }, { !it.second.allDay }, { it.second.startMs }))

        val rows = ArrayList<Row>()
        var current: LocalDate? = null
        for ((day, e) in upcoming) {
            if (day != current) {
                rows += Row(Kind.HEADER, now.day(day))
                current = day
            }
            val lead = if (e.allDay) "all day" else now.time(e.startMs)
            rows += Row(Kind.ITEM, e.title.ifEmpty { "untitled" }, lead, legend.colors[e.source])
        }
        rows += ListWidget.notes(sources, snapshot.status, legend)
        if (upcoming.isEmpty() && ListWidget.allAnswered(sources, snapshot.status)) {
            rows += Row(Kind.NOTE, if (days == 1) "nothing on today" else "nothing in the next $days days")
        }
        return rows
    }

    // zero length events (reminders) are over once their moment passes
    private fun ended(e: CalendarEvent, now: Moment, today: LocalDate): Boolean = when {
        e.allDay -> !LocalDate.ofEpochDay(e.endMs / DAY_MS).isAfter(today)
        e.endMs == e.startMs -> e.startMs < now.nowMs
        else -> e.endMs <= now.nowMs
    }

    private fun startDay(e: CalendarEvent, now: Moment): LocalDate =
        if (e.allDay) LocalDate.ofEpochDay(e.startMs / DAY_MS)
        else java.time.Instant.ofEpochMilli(e.startMs).atZone(now.zone).toLocalDate()
}
