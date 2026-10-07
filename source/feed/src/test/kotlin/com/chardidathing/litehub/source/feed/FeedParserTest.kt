package com.chardidathing.litehub.source.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.StringReader
import java.time.Instant
import org.junit.Assert.assertThrows

class FeedParserTest {

    private fun parse(name: String) =
        FeedParser.parse(StringReader(checkNotNull(javaClass.classLoader.getResource(name)).readText()), "news")

    @Test
    fun `rss items with cdata, entities and both date styles`() {
        val items = parse("rss.xml")
        assertEquals(3, items.size)
        assertEquals("Council approves new bike lanes", items[0].title)
        assertEquals("bikes-1", items[0].id)
        assertEquals(Instant.parse("2026-10-06T22:30:00Z").toEpochMilli(), items[0].publishedMs)
        assertEquals("Fish & chips shop reopens", items[1].title)
        assertEquals(Instant.parse("2026-10-06T08:00:00Z").toEpochMilli(), items[1].publishedMs)
        assertNull(items[2].publishedMs)
    }

    @Test
    fun `atom prefers the alternate link and the published date`() {
        val entry = parse("atom.xml").single()
        assertEquals("https://example.org/2.0", entry.link)
        assertEquals("tag:example.org,2026:2.0", entry.id)
        assertEquals(Instant.parse("2026-10-04T22:30:00Z").toEpochMilli(), entry.publishedMs)
    }

    @Test
    fun `html instead of a feed is a failure`() {
        val e = runCatching { FeedParser.parse(StringReader("<html><body>login</body></html>"), "x") }.exceptionOrNull()
        assertTrue(e is IOException)
    }

    @Test
    fun `external entities are never fetched`() {
        val xxe = """<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]>
            <rss><channel><item><title>a &x; b</title></item></channel></rss>"""
        val items = runCatching { FeedParser.parse(StringReader(xxe), "x") }.getOrNull().orEmpty()
        assertTrue(items.none { "root:" in it.title })
    }

    @Test
    fun `a date past what millis hold is no date, not a crash`() {
        val rss = """<rss><channel><item><title>far off</title><pubDate>+999999999-01-01T00:00:00Z</pubDate></item></channel></rss>"""
        val item = FeedParser.parse(StringReader(rss), "x").single()
        assertEquals("far off", item.title)
        assertNull(item.publishedMs)
    }

    @Test
    fun `a feed declaring its own entities is refused, a plain doctype isn't`() {
        val bomb = """<?xml version="1.0"?><!DOCTYPE rss [<!ENTITY a "aaaaaaaaaa"><!ENTITY b "&a;&a;&a;&a;&a;">]>
            <rss><channel><item><title>&b;</title></item></channel></rss>"""
        assertThrows(IOException::class.java) { FeedParser.parse(StringReader(bomb), "x") }
        val old = """<?xml version="1.0"?><!DOCTYPE rss PUBLIC "-//Netscape Communications//DTD RSS 0.91//EN" "http://my.netscape.com/publish/formats/rss-0.91.dtd">
            <rss><channel><item><title>still read</title></item></channel></rss>"""
        assertEquals("still read", FeedParser.parse(StringReader(old), "x").single().title)
    }

    @Test
    fun `a title made of open brackets is stripped in one pass`() {
        val rss = "<rss><channel><item><title><![CDATA[" + "<".repeat(200_000) + "]]></title></item></channel></rss>"
        val started = System.nanoTime()
        runCatching { FeedParser.parse(StringReader(rss), "x") }
        // the old regex took minutes on this, a linear pass is well under a second
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
    }

    @Test
    fun `tags are stripped and space collapsed`() {
        val rss = "<rss><channel><item><title><![CDATA[<b>big</b>   news <i>today</i> a < b]]></title></item></channel></rss>"
        assertEquals("big news today a < b", FeedParser.parse(StringReader(rss), "x").single().title)
    }
}
