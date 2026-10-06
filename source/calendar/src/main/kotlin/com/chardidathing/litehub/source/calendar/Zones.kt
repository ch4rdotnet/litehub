package com.chardidathing.litehub.source.calendar

import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.zone.ZoneOffsetTransitionRule
import java.time.zone.ZoneRules

// turns a TZID into rules. the device tz database first, then the path tail of ids like
// "/mozilla.org/20050126_1/America/New_York", then the feed's own VTIMEZONE (outlook writes
// windows names like "AUS Central Standard Time" and defines them inline). unknown means device
internal class Zones(vtimezones: List<Component>, val device: ZoneId) {

    private val inline: Map<String, ZoneRules> =
        vtimezones.mapNotNull { c -> c.first("TZID")?.value?.let { id -> fromVtimezone(c)?.let { id to it } } }.toMap()
    private val cache = HashMap<String?, ZoneRules>()

    fun rules(time: IcsTime): ZoneRules = when {
        time.utc -> ZoneOffset.UTC.rules
        else -> cache.getOrPut(time.tzid) { resolve(time.tzid) }
    }

    fun instant(time: IcsTime): Instant = instant(time.local, rules(time))

    private fun resolve(tzid: String?): ZoneRules {
        if (tzid == null) return device.rules
        known(tzid)?.let { return it }
        val parts = tzid.split('/')
        for (i in 1 until parts.size) known(parts.drop(i).joinToString("/"))?.let { return it }
        return inline[tzid] ?: device.rules
    }

    private fun known(id: String): ZoneRules? = try {
        ZoneId.of(id).rules
    } catch (e: DateTimeException) {
        null
    }

    // only the newest STANDARD and DAYLIGHT matter for a window around today
    private fun fromVtimezone(c: Component): ZoneRules? {
        val standard = newest(c, "STANDARD") ?: return null
        val standardOffset = offset(standard.first("TZOFFSETTO")) ?: return null
        val daylight = newest(c, "DAYLIGHT")
        val daylightOffset = daylight?.let { offset(it.first("TZOFFSETTO")) }
        if (daylight == null || daylightOffset == null) return ZoneRules.of(standardOffset)
        val toDaylight = rule(daylight, standardOffset, standardOffset, daylightOffset) ?: return ZoneRules.of(standardOffset)
        val toStandard = rule(standard, standardOffset, daylightOffset, standardOffset) ?: return ZoneRules.of(standardOffset)
        // ZoneRules wants its yearly rules in calendar order, and ignores them unless at least
        // one real transition comes before
        val seedYear = 2000
        val yearly = listOf(toDaylight, toStandard).sortedBy { it.createTransition(seedYear) }
        val seed = yearly.map { it.createTransition(seedYear) }
        return ZoneRules.of(standardOffset, standardOffset, emptyList(), seed, yearly)
    }

    private fun newest(c: Component, name: String) =
        c.children.filter { it.name == name }.maxByOrNull { it.first("DTSTART")?.value.orEmpty() }

    private fun offset(line: ContentLine?): ZoneOffset? {
        val v = line?.value?.trim() ?: return null
        return try {
            ZoneOffset.of(v)
        } catch (e: DateTimeException) {
            null
        }
    }

    // yearly rule like FREQ=YEARLY;BYMONTH=10;BYDAY=1SU at the component's DTSTART time
    private fun rule(c: Component, std: ZoneOffset, before: ZoneOffset, after: ZoneOffset): ZoneOffsetTransitionRule? {
        val start = c.first("DTSTART")?.let { IcsTime.parse(it).firstOrNull() } ?: return null
        val parts = c.first("RRULE")?.value?.split(';')?.associate { it.substringBefore('=').uppercase() to it.substringAfter('=') }
            ?: return null
        val month = parts["BYMONTH"]?.toIntOrNull()?.let(Month::of) ?: return null
        val byDay = parts["BYDAY"] ?: return null
        val dow = DAYS[byDay.takeLast(2).uppercase()] ?: return null
        val n = byDay.dropLast(2).ifEmpty { "1" }.toIntOrNull() ?: return null
        val indicator = if (n > 0) 1 + 7 * (n - 1) else -1 - 7 * (-n - 1)
        val time: LocalTime = start.local.toLocalTime()
        return ZoneOffsetTransitionRule.of(month, indicator, dow, time, false, ZoneOffsetTransitionRule.TimeDefinition.WALL, std, before, after)
    }

    companion object {
        private val DAYS = mapOf(
            "MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY,
            "TH" to DayOfWeek.THURSDAY, "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY, "SU" to DayOfWeek.SUNDAY,
        )

        // wall time that falls in a dst gap moves forward by the gap, like ZonedDateTime does
        fun instant(local: LocalDateTime, rules: ZoneRules): Instant {
            val valid = rules.getValidOffsets(local)
            if (valid.isNotEmpty()) return local.toInstant(valid[0])
            val gap = rules.getTransition(local)
            return local.plus(gap.duration).toInstant(gap.offsetAfter)
        }
    }
}
