package com.chardidathing.litehub.source.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// how much of each calendar is kept expanded, around today
object CalendarWindow {
    const val PAST_DAYS = 7L
    const val FUTURE_DAYS = 60L

    fun from(today: LocalDate, zone: ZoneId): Instant = today.minusDays(PAST_DAYS).atStartOfDay(zone).toInstant()

    fun until(today: LocalDate, zone: ZoneId): Instant = today.plusDays(FUTURE_DAYS + 1).atStartOfDay(zone).toInstant()
}
