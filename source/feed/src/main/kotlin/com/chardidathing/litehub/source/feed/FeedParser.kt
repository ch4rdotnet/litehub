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
import java.time.format.DateTimeParseException
import javax.xml.parsers.ParserConfigurationException
import javax.xml.parsers.SAXParserFactory

// rss 2.0, rss 1.0 (rdf) and atom in one pass. sax rather than XmlPullParser because it's built
// into both android and the jvm, so the parser runs in plain unit tests
object FeedParser {

    // newest items only, a feed with years of archive shouldn't fill the store
    const val MAX_ITEMS = 50

    fun parse(reader: Reader, source: String): List<FeedItem> {
        val handler = Handler(source)
        try {
            val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
            // feeds come from the internet, never let one pull in other files or urls
            for (feature in NO_EXTERNAL) runCatching { factory.setFeature(feature, false) }
            factory.newSAXParser().parse(InputSource(reader), handler)
        } catch (e: SAXException) {
            // a feed cut off part way still has its first items, anything less is a failure
            if (handler.items.isEmpty()) throw IOException("the feed isn't valid xml", e)
        } catch (e: ParserConfigurationException) {
            throw IOException("couldn't read the feed", e)
        }
        if (!handler.sawFeed) throw IOException("not an rss or atom feed")
        return handler.items.take(MAX_ITEMS)
    }

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

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.append(ch, start, length)
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

        // titles sometimes carry html, keep the words
        private fun clean(s: String) = s.replace(Regex("<[^>]+>"), "").replace(Regex("\\s+"), " ").trim()

        private fun date(s: String): Long? = try {
            ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            try {
                OffsetDateTime.parse(s).toInstant().toEpochMilli()
            } catch (e: DateTimeParseException) {
                null
            }
        }
    }
}
