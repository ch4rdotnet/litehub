package com.chardidathing.litehub.dlna

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

internal object Xml {

    fun escape(text: String): String = buildString(text.length) {
        for (c in text) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }

    // anything with a doctype is refused before parsing, that's where entity tricks live
    fun parse(text: String): Element? {
        if (text.contains("<!DOCTYPE", ignoreCase = true) || text.contains("<!ENTITY", ignoreCase = true)) return null
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            factory.newDocumentBuilder().parse(InputSource(StringReader(text))).documentElement
        } catch (e: Exception) {
            // the parser throws a handful of unrelated types for bad input, all of them mean "not xml"
            null
        }
    }

    fun children(e: Element): List<Element> {
        val out = ArrayList<Element>()
        val nodes = e.childNodes
        for (i in 0 until nodes.length) (nodes.item(i) as? Element)?.let(out::add)
        return out
    }

    fun name(e: Element): String = e.localName ?: e.tagName.substringAfter(':')

    fun first(e: Element, local: String): Element? {
        val nodes = e.getElementsByTagNameNS("*", local)
        return if (nodes.length > 0) nodes.item(0) as Element else null
    }
}
