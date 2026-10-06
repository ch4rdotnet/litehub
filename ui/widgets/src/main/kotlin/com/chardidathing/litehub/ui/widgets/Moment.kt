package com.chardidathing.litehub.ui.widgets

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// all day events are stored as utc midnights, this turns them back into dates
internal const val DAY_MS = 86_400_000L

// now, as time based widgets need it. a new one arrives every minute and on clock changes
data class Moment(val nowMs: Long, val zone: ZoneId, val hour24: Boolean, val locale: Locale) {

    val today: LocalDate get() = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()

    // copy is lowercase, day and month names included
    fun day(date: LocalDate): String = when (date) {
        today -> "today"
        today.plusDays(1) -> "tomorrow"
        else -> DateTimeFormatter.ofPattern("EEE d MMM", locale).format(date).lowercase(locale)
    }

    fun time(ms: Long): String {
        val t = Instant.ofEpochMilli(ms).atZone(zone)
        val pattern = if (hour24) "H:mm" else "h:mma"
        return DateTimeFormatter.ofPattern(pattern, locale).format(t).lowercase(locale)
    }

    fun weekday(date: LocalDate): String = DateTimeFormatter.ofPattern("EEE", locale).format(date).lowercase(locale)

    // on the hour it's just the hour ("7am", "19"), for strips with little room
    fun hour(ms: Long): String {
        val t = Instant.ofEpochMilli(ms).atZone(zone)
        if (t.minute != 0) return time(ms)
        return DateTimeFormatter.ofPattern(if (hour24) "H" else "ha", locale).format(t).lowercase(locale)
    }

    fun month(date: LocalDate): String = DateTimeFormatter.ofPattern("MMMM yyyy", locale).format(date).lowercase(locale)

    // how long ago, as short as it can be
    fun age(ms: Long): String {
        val minutes = (nowMs - ms) / 60_000
        return when {
            minutes < 1 -> "now"
            minutes < 60 -> "${minutes}m"
            minutes < 24 * 60 -> "${minutes / 60}h"
            else -> "${minutes / (24 * 60)}d"
        }
    }
}

// display names and colours for every calendar and feed source, fixed for a dashboard.
// problem is set when sources.json itself couldn't be used
data class Legend(
    val names: Map<String, String>,
    val colors: Map<String, Int>,
    val calendars: List<String>,
    val feeds: List<String>,
    val problem: String? = null,
)
