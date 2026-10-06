package com.chardidathing.litehub.source.calendar

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class HaCalendarTest {

    @Test
    fun `timed and all day events from get_events`() {
        val response = Json.parseToJsonElement(
            """{"calendar.bins":{"events":[
                {"start":"2026-10-07T19:00:00+10:30","end":"2026-10-07T19:15:00+10:30","summary":"bins out","location":"kerb"},
                {"start":"2026-10-09","end":"2026-10-10","summary":"recycling"},
                {"start":"not a date","summary":"broken"}]}}""",
        ).jsonObject
        val events = HaCalendar.parse(response, "calendar.bins", "bins")
        assertEquals(2, events.size)
        val bins = events[0]
        assertEquals(Instant.parse("2026-10-07T08:30:00Z").toEpochMilli(), bins.startMs)
        assertEquals("kerb", bins.location)
        val recycling = events[1]
        assertTrue(recycling.allDay)
        assertEquals(LocalDate.of(2026, 10, 9).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(), recycling.startMs)
        assertTrue(events.all { it.source == "bins" })
    }

    @Test
    fun `missing entity is no events, not a crash`() {
        assertEquals(0, HaCalendar.parse(null, "calendar.x", "x").size)
    }
}
