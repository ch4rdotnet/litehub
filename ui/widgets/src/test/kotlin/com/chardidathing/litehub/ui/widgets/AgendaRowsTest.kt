package com.chardidathing.litehub.ui.widgets

import com.chardidathing.litehub.core.model.CalendarEvent
import com.chardidathing.litehub.core.model.CalendarSnapshot
import com.chardidathing.litehub.core.model.SourceStatus
import com.chardidathing.litehub.ui.components.RowList.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

class AgendaRowsTest {

    private val zone = ZoneId.of("Australia/Adelaide")
    private val now = Moment(at(2026, 10, 7, 12, 0), zone, hour24 = true, locale = Locale.UK)
    private val legend = Legend(mapOf("family" to "family", "work" to "work"), mapOf("family" to 1, "work" to 2), listOf("family", "work"), emptyList())
    private val good = SourceStatus(lastGood = 1L)

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int) = LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun timed(title: String, start: Long, end: Long, source: String = "family") = CalendarEvent(source, title, title, null, false, start, end)

    @Test
    fun `groups by day, all day first, skips what's over`() {
        val events = listOf(
            timed("dentist", at(2026, 10, 8, 9, 0), at(2026, 10, 8, 10, 0)),
            timed("breakfast", at(2026, 10, 7, 8, 0), at(2026, 10, 7, 9, 0)),
            timed("lunch", at(2026, 10, 7, 11, 30), at(2026, 10, 7, 13, 0), source = "work"),
            CalendarEvent("family", "school", "school holidays", null, true, day(2026, 10, 5), day(2026, 10, 10)),
            timed("far away", at(2026, 10, 20, 9, 0), at(2026, 10, 20, 10, 0)),
        )
        val rows = AgendaRows.build(CalendarSnapshot(events, mapOf("family" to good, "work" to good)), listOf("family", "work"), 7, legend, now)
        assertEquals(
            listOf("today", "school holidays", "lunch", "tomorrow", "dentist"),
            rows.map { it.text },
        )
        assertEquals("all day", rows[1].lead)
        assertEquals("11:30", rows[2].lead)
        assertEquals(2, rows[2].accent)
    }

    @Test
    fun `nothing on is only said when every calendar answered`() {
        val ok = AgendaRows.build(CalendarSnapshot(emptyList(), mapOf("family" to good, "work" to good)), listOf("family", "work"), 7, legend, now)
        assertEquals(listOf("nothing in the next 7 days"), ok.map { it.text })

        val failed = AgendaRows.build(
            CalendarSnapshot(emptyList(), mapOf("family" to good, "work" to SourceStatus(lastGood = null, error = "timed out"))),
            listOf("family", "work"), 7, legend, now,
        )
        assertEquals(listOf("couldn't fetch work, timed out"), failed.map { it.text })
        assertTrue(failed.single().error)
    }

    @Test
    fun `a calendar that never answered is loading, not empty`() {
        val rows = AgendaRows.build(CalendarSnapshot(emptyList(), emptyMap()), listOf("family"), 7, legend, now)
        assertEquals(listOf("loading family"), rows.map { it.text })
        assertEquals(Kind.NOTE, rows.single().kind)
    }
}
