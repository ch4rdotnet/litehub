package com.chardidathing.litehub.source.calendar

import com.chardidathing.litehub.core.model.CalendarEvent
import org.dmfs.rfc5545.DateTime
import org.dmfs.rfc5545.InstanceIterator
import org.dmfs.rfc5545.recur.InvalidRecurrenceRuleException
import org.dmfs.rfc5545.recur.RecurrenceRule
import org.dmfs.rfc5545.recurrenceset.FastForwarded
import org.dmfs.rfc5545.recurrenceset.Merged
import org.dmfs.rfc5545.recurrenceset.OfList
import org.dmfs.rfc5545.recurrenceset.OfRuleAndFirst
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// expands events into occurrences inside [from, until). recurrences are expanded in the event's
// own wall time (what rfc 5545 means) and only then turned into instants
internal class Expander(private val zones: Zones, private val from: Instant, private val until: Instant) {

    private val fromDay = from.atZone(zones.device).toLocalDate()
    private val untilDay = until.atZone(zones.device).toLocalDate()

    fun expand(source: String, events: List<IcsEvent>): List<CalendarEvent> {
        val out = ArrayList<CalendarEvent>()
        val (overrides, masters) = events.partition { it.recurrenceId != null }
        val byUid = overrides.groupBy { it.uid }
        val mastered = HashSet<String>()
        for (master in masters) {
            if (master.cancelled) continue
            mastered += master.uid
            val replaced = byUid[master.uid].orEmpty()
                .mapNotNullTo(HashSet()) { o -> o.recurrenceId?.let { local(it, master) } }
            for (start in starts(master)) {
                if (start in replaced) continue
                occurrence(source, master, start)?.let(out::add)
            }
        }
        // overrides stand alone, an orphan one (its master was deleted) still happened
        for (o in overrides) {
            if (o.cancelled) continue
            occurrence(source, o, o.start.local)?.let(out::add)
        }
        return out
    }

    // a time from EXDATE or RECURRENCE-ID, moved into the master's wall clock
    private fun local(t: IcsTime, master: IcsEvent): LocalDateTime = when {
        master.start.date -> t.local.toLocalDate().atStartOfDay()
        else -> LocalDateTime.ofInstant(zones.instant(t), zones.rules(master.start).getOffset(zones.instant(t)))
    }

    private fun starts(e: IcsEvent): Sequence<LocalDateTime> {
        val excluded = e.exdates.mapTo(HashSet()) { local(it, e) }
        val extra = e.rdates.map { local(it, e) }
        val rule = e.rrule?.let { parse(it, e) }
        if (rule == null) return (sequenceOf(e.start.local) + extra).filter { it !in excluded }.distinct()
        val first = dateTime(e.start.local, e.start.date)
        var set: org.dmfs.rfc5545.RecurrenceSet = OfRuleAndFirst(rule, first)
        if (extra.isNotEmpty()) set = Merged(set, OfList(extra.map { dateTime(it, e.start.date) }))
        // skip straight to just before the window, a daily rule from 1998 shouldn't walk 28 years
        val skipTo = from.atZone(zones.rules(e.start).getOffset(from)).toLocalDateTime().minus(length(e)).minusDays(1)
        if (skipTo.isAfter(e.start.local)) set = FastForwarded(dateTime(skipTo, e.start.date), set)
        val last = until.atZone(zones.rules(e.start).getOffset(until)).toLocalDateTime().plusDays(1)
        return sequence {
            val it: InstanceIterator = set.iterator()
            var n = 0
            while (it.hasNext() && n++ < MAX_INSTANCES) {
                val t = local(it.next())
                if (t.isAfter(last)) break
                if (t !in excluded) yield(t)
            }
        }
    }

    private fun occurrence(source: String, e: IcsEvent, start: LocalDateTime): CalendarEvent? {
        if (e.start.date) {
            val day = start.toLocalDate()
            val end = when {
                e.end != null -> day.plusDays(ChronoUnit.DAYS.between(e.start.local.toLocalDate(), e.end.local.toLocalDate()))
                e.days != null -> day.plus(e.days)
                else -> day.plusDays(1)
            }.let { if (it.isAfter(day)) it else day.plusDays(1) }
            if (!end.isAfter(fromDay) || day.isAfter(untilDay)) return null
            return CalendarEvent(source, "${e.uid}/$day", e.summary, e.location, true, utcMidnight(day), utcMidnight(end))
        }
        val rules = zones.rules(e.start)
        val startInstant = Zones.instant(start, rules)
        val endInstant = when {
            e.end != null && e.end.tzid == e.start.tzid && e.end.utc == e.start.utc ->
                Zones.instant(start.plus(Duration.between(e.start.local, e.end.local)), rules)
            e.end != null -> startInstant.plus(Duration.between(zones.instant(e.start), zones.instant(e.end)))
            else -> {
                // days in a DURATION follow the wall clock, hours are exact
                val wall = e.days?.let { start.plus(it) } ?: start
                Zones.instant(wall, rules).plus(e.duration ?: Duration.ZERO)
            }
        }.let { if (it.isBefore(startInstant)) startInstant else it }
        // zero length events (reminders) count if they start inside the window
        val inside = startInstant < until && (endInstant > from || endInstant == startInstant && startInstant >= from)
        if (!inside) return null
        return CalendarEvent(source, "${e.uid}/${startInstant.toEpochMilli()}", e.summary, e.location, false, startInstant.toEpochMilli(), endInstant.toEpochMilli())
    }

    private fun length(e: IcsEvent): Duration = when {
        e.end != null -> Duration.between(e.start.local, e.end.local).abs()
        else -> (e.duration ?: Duration.ZERO).plusDays(e.days?.days?.toLong() ?: 0)
    }

    // UNTIL has to match DTSTART's kind for lib-recur, feeds mix them freely
    private fun parse(rrule: String, e: IcsEvent): RecurrenceRule? {
        val fixed = rrule.split(';').joinToString(";") { part ->
            if (!part.startsWith("UNTIL=", ignoreCase = true)) return@joinToString part
            val until = IcsTime.parseOne(part.substringAfter('='), null) ?: return@joinToString part
            val wall = when {
                until.date && e.start.date -> return@joinToString "UNTIL=" + DAY.format(until.local)
                until.date -> until.local.toLocalDate().atTime(23, 59, 59)
                until.utc -> LocalDateTime.ofInstant(until.local.toInstant(ZoneOffset.UTC), zones.rules(e.start).getOffset(until.local.toInstant(ZoneOffset.UTC)))
                else -> until.local
            }
            "UNTIL=" + if (e.start.date) DAY.format(wall) else FLOATING.format(wall)
        }
        return try {
            RecurrenceRule(fixed, RecurrenceRule.RfcMode.RFC5545_LAX)
        } catch (ex: InvalidRecurrenceRuleException) {
            null
        } catch (ex: IllegalArgumentException) {
            null
        }
    }

    private fun dateTime(t: LocalDateTime, date: Boolean): DateTime =
        if (date) DateTime(t.year, t.monthValue - 1, t.dayOfMonth)
        else DateTime(t.year, t.monthValue - 1, t.dayOfMonth, t.hour, t.minute, t.second)

    private fun local(d: DateTime): LocalDateTime =
        if (d.isAllDay) LocalDate.of(d.year, d.month + 1, d.dayOfMonth).atStartOfDay()
        else LocalDateTime.of(d.year, d.month + 1, d.dayOfMonth, d.hours, d.minutes, d.seconds)

    private fun utcMidnight(day: LocalDate) = day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

    private companion object {
        // a rule that would emit more than this inside the window is broken or minutely
        const val MAX_INSTANCES = 2000
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        val FLOATING: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    }
}
