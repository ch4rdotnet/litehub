package com.chardidathing.litehub.source.calendar

import java.time.Duration
import java.time.Period

// the parts of a VEVENT we use, still in feed terms (wall times and tzids)
internal class IcsEvent(
    val uid: String,
    val summary: String,
    val location: String?,
    val cancelled: Boolean,
    val start: IcsTime,
    val end: IcsTime?,
    val duration: Duration?,
    val days: Period?,
    val rrule: String?,
    val rdates: List<IcsTime>,
    val exdates: List<IcsTime>,
    val recurrenceId: IcsTime?,
) {

    companion object {
        // the longest real duration ("-P52W6DT23H59M59S") is well under this
        private const val MAX_DURATION_CHARS = 32
        private const val DAYS_PER_WEEK = 7L

        fun from(c: Component): IcsEvent? {
            val start = c.first("DTSTART")?.let { IcsTime.parse(it).firstOrNull() } ?: return null
            val (duration, days) = c.first("DURATION")?.value?.let(::parseDuration) ?: (null to null)
            return IcsEvent(
                // no UID happens in hand rolled feeds, fall back to something stable
                uid = c.first("UID")?.value?.trim()?.ifEmpty { null } ?: "${c.first("SUMMARY")?.value}@${start.local}",
                summary = c.first("SUMMARY")?.value?.let(IcsReader::unescape)?.trim().orEmpty(),
                location = c.first("LOCATION")?.value?.let(IcsReader::unescape)?.trim()?.ifEmpty { null },
                cancelled = c.first("STATUS")?.value.equals("CANCELLED", ignoreCase = true),
                start = start,
                end = c.first("DTEND")?.let { IcsTime.parse(it).firstOrNull() },
                duration = duration,
                days = days,
                rrule = c.first("RRULE")?.value?.trim(),
                rdates = c.all("RDATE").flatMap(IcsTime::parse),
                exdates = c.all("EXDATE").flatMap(IcsTime::parse),
                recurrenceId = c.first("RECURRENCE-ID")?.let { IcsTime.parse(it).firstOrNull() },
            )
        }

        // "P1W", "P2D", "PT1H30M", "-PT15M". days come back as a Period so they follow the
        // calendar across dst, time parts as an exact Duration. anything too long or too big to
        // be a real duration is no duration, it comes from someone else's server
        fun parseDuration(raw: String): Pair<Duration?, Period?> {
            if (raw.length > MAX_DURATION_CHARS) return null to null
            return try {
                parseBounded(raw)
            } catch (e: ArithmeticException) {
                null to null
            } catch (e: NumberFormatException) {
                null to null
            }
        }

        private fun parseBounded(raw: String): Pair<Duration?, Period?> {
            val v = raw.trim().uppercase()
            val negative = v.startsWith("-")
            val body = v.removePrefix("-").removePrefix("+").removePrefix("P")
            var weeks = 0L
            var dayCount = 0L
            var time = Duration.ZERO
            val datePart = body.substringBefore('T')
            Regex("(\\d+)([WD])").findAll(datePart).forEach { m ->
                val n = m.groupValues[1].toLong()
                if (m.groupValues[2] == "W") weeks = n else dayCount = n
            }
            if ('T' in body) {
                Regex("(\\d+)([HMS])").findAll(body.substringAfter('T')).forEach { m ->
                    val n = m.groupValues[1].toLong()
                    time = when (m.groupValues[2]) {
                        "H" -> time.plusHours(n)
                        "M" -> time.plusMinutes(n)
                        else -> time.plusSeconds(n)
                    }
                }
            }
            val sign = if (negative) -1 else 1
            val period = Period.ofDays(Math.toIntExact(Math.addExact(Math.multiplyExact(weeks, DAYS_PER_WEEK), dayCount) * sign))
            return time.multipliedBy(sign.toLong()) to period
        }
    }
}
