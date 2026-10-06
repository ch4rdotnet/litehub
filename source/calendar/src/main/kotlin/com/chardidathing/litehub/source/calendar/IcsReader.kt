package com.chardidathing.litehub.source.calendar

import java.io.BufferedReader
import java.io.Reader

// one "NAME;PARAM=x:value" line, params keyed upper case, value still escaped
internal class ContentLine(val name: String, val params: Map<String, String>, val value: String) {
    fun param(key: String) = params[key]
}

// a component and its own properties, children only for the ones we keep nested (VTIMEZONE)
internal class Component(val name: String, val lines: List<ContentLine>, val children: List<Component>) {
    fun first(name: String) = lines.firstOrNull { it.name == name }

    fun all(name: String) = lines.filter { it.name == name }
}

// streams the direct children of VCALENDAR one at a time, so a 10k event feed never sits in
// memory as a tree. tolerant of the usual export sloppiness, bare LF, junk lines, missing ENDs
internal class IcsReader(reader: Reader) {

    private val lines = BufferedReader(reader)
    private var pending: String? = null

    fun components(): Sequence<Component> = sequence {
        while (true) {
            val line = next() ?: break
            if (line.name == "BEGIN" && !line.value.equals("VCALENDAR", ignoreCase = true)) {
                yield(read(line.value.uppercase()))
            }
        }
    }

    private fun read(name: String): Component {
        val props = ArrayList<ContentLine>()
        val children = ArrayList<Component>()
        while (true) {
            val line = next() ?: break
            when {
                line.name == "BEGIN" -> children += read(line.value.uppercase())
                line.name == "END" -> break
                else -> props += line
            }
        }
        return Component(name, props, children)
    }

    private fun next(): ContentLine? {
        while (true) {
            val raw = unfolded() ?: return null
            parse(raw)?.let { return it }
        }
    }

    // a line starting with a space or tab continues the previous one
    private fun unfolded(): String? {
        val first = pending ?: lines.readLine() ?: return null
        pending = null
        val sb = StringBuilder(first)
        while (true) {
            val nextLine = lines.readLine() ?: break
            if (nextLine.isNotEmpty() && (nextLine[0] == ' ' || nextLine[0] == '\t')) {
                sb.append(nextLine, 1, nextLine.length)
            } else {
                pending = nextLine
                break
            }
        }
        return sb.toString()
    }

    private fun parse(raw: String): ContentLine? {
        var i = 0
        var quoted = false
        var colon = -1
        while (i < raw.length) {
            val c = raw[i]
            if (c == '"') quoted = !quoted else if (c == ':' && !quoted) {
                colon = i
                break
            }
            i++
        }
        if (colon <= 0) return null
        val head = raw.substring(0, colon)
        val parts = splitUnquoted(head, ';')
        val name = parts[0].trim().uppercase()
        if (name.isEmpty()) return null
        val params = HashMap<String, String>()
        for (p in parts.drop(1)) {
            val eq = p.indexOf('=')
            if (eq <= 0) continue
            params[p.substring(0, eq).trim().uppercase()] = p.substring(eq + 1).trim().removeSurrounding("\"")
        }
        return ContentLine(name, params, raw.substring(colon + 1))
    }

    private fun splitUnquoted(s: String, sep: Char): List<String> {
        val out = ArrayList<String>()
        var quoted = false
        var start = 0
        for (i in s.indices) {
            if (s[i] == '"') quoted = !quoted
            if (s[i] == sep && !quoted) {
                out += s.substring(start, i)
                start = i + 1
            }
        }
        out += s.substring(start)
        return out
    }

    companion object {
        // TEXT values escape \, \; \, and \n
        fun unescape(value: String): String {
            if ('\\' !in value) return value
            val sb = StringBuilder(value.length)
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '\\' && i + 1 < value.length) {
                    when (val n = value[i + 1]) {
                        'n', 'N' -> sb.append('\n')
                        else -> sb.append(n)
                    }
                    i += 2
                } else {
                    sb.append(c)
                    i++
                }
            }
            return sb.toString()
        }
    }
}
