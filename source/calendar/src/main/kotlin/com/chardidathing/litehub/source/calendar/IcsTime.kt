package com.chardidathing.litehub.source.calendar

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

// a DATE or DATE-TIME value as written. utc for a trailing Z, tzid from the param,
// neither means floating (device local)
internal class IcsTime(val local: LocalDateTime, val date: Boolean, val utc: Boolean, val tzid: String?) {

    companion object {
        fun parse(line: ContentLine): List<IcsTime> {
            val tzid = line.param("TZID")
            val forceDate = line.param("VALUE").equals("DATE", ignoreCase = true)
            // RDATE can also hold PERIODs, only their start matters here
            return line.value.split(',').mapNotNull { parseOne(it.substringBefore('/').trim(), tzid, forceDate) }
        }

        fun parseOne(raw: String, tzid: String?, forceDate: Boolean = false): IcsTime? {
            if (raw.length < 8) return null
            val y = raw.substring(0, 4).toIntOrNull() ?: return null
            val m = raw.substring(4, 6).toIntOrNull() ?: return null
            val d = raw.substring(6, 8).toIntOrNull() ?: return null
            val date = try {
                LocalDate.of(y, m, d)
            } catch (e: java.time.DateTimeException) {
                return null
            }
            if (forceDate || raw.length == 8) return IcsTime(date.atStartOfDay(), true, false, null)
            if (raw.length < 15 || raw[8] != 'T') return null
            val hh = raw.substring(9, 11).toIntOrNull() ?: return null
            val mm = raw.substring(11, 13).toIntOrNull() ?: return null
            // leap seconds show up as 60 now and then
            val ss = (raw.substring(13, 15).toIntOrNull() ?: return null).coerceAtMost(59)
            val time = try {
                LocalTime.of(hh, mm, ss)
            } catch (e: java.time.DateTimeException) {
                return null
            }
            val utc = raw.endsWith("Z") || raw.endsWith("z")
            return IcsTime(LocalDateTime.of(date, time), false, utc, if (utc) null else tzid)
        }
    }
}
