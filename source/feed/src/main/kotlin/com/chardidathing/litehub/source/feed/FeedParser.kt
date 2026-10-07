package com.chardidathing.litehub.source.feed

import com.chardidathing.litehub.core.model.FeedItem
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.IOException
import java.io.Reader
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.DateTimeException
import javax.xml.parsers.ParserConfigurationException
import javax.xml.parsers.SAXParserFactory

// rss 2.0, rss 1.0 (rdf) and atom in one pass. sax rather than XmlPullParser because it's built
// into both android and the jvm, so the parser runs in plain unit tests
object FeedParser {

    // newest items only, a feed with years of archive shouldn't fill the store
    const val MAX_ITEMS = 50

    // a feed is text from someone else's server, these bound what one can make the hub hold
    private const val MAX_FEED_CHARS = 4 * 1024 * 1024
    private const val MAX_TEXT_CHARS = 16 * 1024

    fun parse(reader: Reader, source: String): List<FeedItem> {
        val body = readBounded(reader)
        // an internal dtd subset is how entity expansion bombs work, and no real feed needs one.
        // a plain doctype line (old rss 0.91) is fine, external dtds are never loaded
        if (INTERNAL_SUBSET.containsMatchIn(body.take(DOCTYPE_SCAN_CHARS))) throw IOException("feeds that declare their own entities aren't read")
        val handler = Handler(source)
        try {
            val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
            // feeds come from the internet, never let one pull in other files or urls
            for (feature in NO_EXTERNAL) runCatching { factory.setFeature(feature, false) }
            factory.newSAXParser().parse(InputSource(java.io.StringReader(body)), handler)
        } catch (e: SAXException) {
            // a feed cut off part way still has its first items, anything less is a failure
            if (handler.items.isEmpty()) throw IOException("the feed isn't valid xml", e)
        } catch (e: ParserConfigurationException) {
            throw IOException("couldn't read the feed", e)
        }
        if (!handler.sawFeed) throw IOException("not an rss or atom feed")
        return handler.items.take(MAX_ITEMS)
    }

    private fun readBounded(reader: Reader): String {
        val out = StringBuilder()
        val buffer = CharArray(BUFFER_CHARS)
        while (true) {
            val n = reader.read(buffer)
            if (n < 0) return out.toString()
            if (out.length + n > MAX_FEED_CHARS) throw IOException("the feed is too big to read")
            out.append(buffer, 0, n)
        }
    }

    // the doctype sits before the root element, a little way in is plenty to find it
    private const val DOCTYPE_SCAN_CHARS = 4096
    private const val BUFFER_CHARS = 8192
    private val INTERNAL_SUBSET = Regex("<!DOCTYPE[^>\\[]*\\[", RegexOption.IGNORE_CASE)

    private val NO_EXTERNAL = listOf(
        "http://xml.org/sax/features/external-general-entities",
        "http://xml.org/sax/features/external-parameter-entities",
        "http://apache.org/xml/features/nonvalidating/load-external-dtd",
    )

    private class Handler(private val source: String) : DefaultHandler() {
        val items = ArrayList<FeedItem>()
        var sawFeed = false
        private var inItem = false
        private val text = StringBuilder()
        private var title: String? = null
        private var link: String? = null
        private var guid: String? = null
        private var published: Long? = null

        override fun startElement(uri: String?, localName: String, qName: String?, attrs: Attributes) {
            when (localName) {
                "rss", "feed", "RDF" -> sawFeed = true
                "item", "entry" -> {
                    inItem = true
                    title = null
                    link = null
                    guid = null
                    published = null
                }
                // atom puts the url in an attribute, the alternate (or unmarked) one is the page
                "link" -> if (inItem) attrs.getValue("href")?.let { href ->
                    val rel = attrs.getValue("rel")
                    if (rel == null || rel == "alternate") link = link ?: href
                }
            }
            text.setLength(0)
        }

        // past the cap the rest is dropped, a title or date is never this long
        override fun characters(ch: CharArray, start: Int, length: Int) {
            val room = MAX_TEXT_CHARS - text.length
            if (room > 0) text.append(ch, start, minOf(length, room))
        }

        override fun endElement(uri: String?, localName: String, qName: String?) {
            if (!inItem) return
            val value = text.toString().trim()
            when (localName) {
                "title" -> title = clean(value)
                "link" -> if (value.isNotEmpty()) link = link ?: value
                "guid", "id" -> guid = value.ifEmpty { null }
                "pubDate", "published", "updated", "date" -> if (published == null || localName == "published") {
                    date(value)?.let { published = it }
                }
                "item", "entry" -> {
                    inItem = false
                    val t = title
                    if (!t.isNullOrEmpty()) items += FeedItem(source, guid ?: link ?: t, t, link, published)
                }
            }
        }

        // titles sometimes carry html, keep the words. one pass, a regex here backtracks badly
        // on a run of '<' with no '>'
        private fun clean(s: String): String {
            val out = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '<') {
                    val close = s.indexOf('>', i)
                    if (close < 0) {
                        out.append(s, i, s.length)
                        break
                    }
                    i = close + 1
                    continue
                }
                if (c.isWhitespace()) {
                    if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
                } else {
                    out.append(c)
                }
                i++
            }
            return out.toString().trim()
        }

        // a date that won't parse, or won't fit in epoch millis, is no date
        private fun date(s: String): Long? = parse { ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
            ?: parse { OffsetDateTime.parse(s).toInstant().toEpochMilli() }

        private inline fun parse(block: () -> Long): Long? = try {
            block()
        } catch (e: DateTimeException) {
            null
        } catch (e: ArithmeticException) {
            null
        }
    }
}
