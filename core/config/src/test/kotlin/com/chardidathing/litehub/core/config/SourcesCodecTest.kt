package com.chardidathing.litehub.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcesCodecTest {

    private fun failure(text: String): ConfigException =
        runCatching { SourcesCodec.decode(text) }.exceptionOrNull() as? ConfigException
            ?: throw AssertionError("expected a ConfigException")

    @Test
    fun `decodes calendars and feeds`() {
        val s = SourcesCodec.decode(
            """{"version":1,"calendars":[{"id":"family","name":"family","url":"webcal://x/y.ics","color":"#ff9800"},
               {"id":"ha","name":"bins","entity":"calendar.bins"}],"feeds":[{"id":"news","name":"news","url":"https://x/rss"}]}""",
        )
        assertEquals(2, s.calendars.size)
        assertEquals(0xFFFF9800.toInt(), s.calendars[0].color)
        assertEquals(null, s.calendars[1].color)
        assertEquals("news", s.feeds.single().id)
    }

    @Test
    fun `a calendar needs exactly one of url or entity`() {
        assertTrue(failure("""{"version":1,"calendars":[{"id":"a","name":"a"}]}""").message!!.contains("url or an entity"))
        failure("""{"version":1,"calendars":[{"id":"a","name":"a","url":"x","entity":"calendar.a"}]}""")
    }

    @Test
    fun `ids are unique across calendars and feeds`() {
        failure("""{"version":1,"calendars":[{"id":"a","name":"a","url":"x"}],"feeds":[{"id":"a","name":"a","url":"y"}]}""")
    }
}
