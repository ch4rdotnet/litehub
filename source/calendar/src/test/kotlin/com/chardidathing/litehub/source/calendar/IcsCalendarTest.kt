package com.chardidathing.litehub.source.calendar

import com.chardidathing.litehub.core.model.CalendarEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class IcsCalendarTest {

    private val adelaide = ZoneId.of("Australia/Adelaide")
    private val from = LocalDate.of(2026, 10, 1).atStartOfDay(adelaide).toInstant()
    private val until = LocalDate.of(2026, 12, 1).atStartOfDay(adelaide).toInstant()

    private fun parse(name: String): List<CalendarEvent> {
        val text = checkNotNull(javaClass.classLoader.getResource("ics/$name")) { name }.readText()
        return IcsCalendar.parse(StringReader(text), "test", adelaide, from, until).sortedBy { it.startMs }
    }

    private fun utc(s: String) = Instant.parse(s).toEpochMilli()

    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun `google weekly rule with an exdate and a moved instance`() {
        val standups = parse("google.ics").filter { it.title.startsWith("standup") }
        // mondays and wednesdays in oct and nov, less the 7th
        assertEquals(16, standups.size)
        assertEquals(utc("2026-10-04T22:30:00Z"), standups[0].startMs) // 9:00 acdt, the day after dst starts
        assertTrue(standups.none { it.startMs == utc("2026-10-06T22:30:00Z") })
        val moved = standups.single { it.title == "standup (moved)" }
        assertEquals(utc("2026-10-11T23:30:00Z"), moved.startMs)
        assertTrue(standups.none { it.title == "standup" && it.startMs == utc("2026-10-11T22:30:00Z") })
    }

    @Test
    fun `google all day, utc, escapes and folding`() {
        val events = parse("google.ics")
        val holiday = events.single { it.title == "Labour Day" }
        assertTrue(holiday.allDay)
        assertEquals(day(2026, 10, 5), holiday.startMs)
        assertEquals(day(2026, 10, 6), holiday.endMs)
        val coffee = events.single { it.title.startsWith("Coffee") }
        assertEquals("Coffee, cake and a very long title that google folds across more than one line", coffee.title)
        assertEquals("Rundle St; upstairs", coffee.location)
        assertEquals(utc("2026-10-20T01:30:00Z"), coffee.startMs)
        assertTrue(events.none { it.title == "last year" })
    }

    @Test
    fun `outlook windows zone from its vtimezone, utc until, cancelled`() {
        val events = parse("outlook.ics")
        val gym = events.filter { it.title == "gym" }
        assertEquals(listOf("2026-10-05T07:30:00Z", "2026-10-12T07:30:00Z", "2026-10-19T07:30:00Z").map(::utc), gym.map { it.startMs })
        assertEquals(utc("2026-10-05T08:30:00Z"), gym[0].endMs)
        assertTrue(events.none { it.title == "called off" })
    }

    @Test
    fun `icloud multi day, duration and rdates`() {
        val events = parse("icloud.ics")
        val trip = events.single { it.title == "trip" }
        assertEquals(day(2026, 10, 30), trip.startMs)
        assertEquals(day(2026, 11, 2), trip.endMs)
        val lunch = events.single { it.title == "lunch" }
        assertEquals(utc("2026-11-01T01:30:00Z"), lunch.startMs)
        assertEquals(utc("2026-11-01T03:00:00Z"), lunch.endMs)
        val market = events.filter { it.title == "market" }
        // the first is before dst starts on the 4th, the others after
        assertEquals(listOf("2026-10-03T00:30:00Z", "2026-10-09T23:30:00Z", "2026-10-16T23:30:00Z").map(::utc), market.map { it.startMs })
    }

    @Test
    fun `nextcloud mozilla tzid, monthly last friday with count, floating, crlf`() {
        val events = parse("nextcloud.ics")
        val drinks = events.filter { it.title == "drinks" }
        assertEquals(listOf("2026-10-30T06:30:00Z", "2026-11-27T06:30:00Z").map(::utc), drinks.map { it.startMs })
        val floating = events.single { it.title == "floating" }
        assertEquals(utc("2026-10-14T21:30:00Z"), floating.startMs)
    }

    @Test
    fun `occurrence ids are unique`() {
        val events = listOf("google.ics", "outlook.ics", "icloud.ics", "nextcloud.ics").flatMap(::parse)
        assertEquals(events.size, events.map { it.id }.toSet().size)
    }

    @Test
    fun `tolerates bare newlines, junk and a missing end`() {
        val text = "BEGIN:VCALENDAR\nnot a property\nBEGIN:VEVENT\nUID:x\nDTSTART:20261010T010000Z\nSUMMARY:ok\nEND:VEVENT\n"
        val events = IcsCalendar.parse(StringReader(text), "test", adelaide, from, until)
        assertEquals(listOf("ok"), events.map { it.title })
    }
}
