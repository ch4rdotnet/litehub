package com.chardidathing.litehub.core.config

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigCodecTest {

    @Test
    fun `decodes a valid config`() {
        val config = ConfigCodec.decode(Fixtures.config)
        val page = config.dashboards.single().pages.single()
        assertEquals(2, page.widgets.size)
        assertEquals("clock", page.widgets[0].config.getValue("title").jsonPrimitive.content)
    }

    @Test
    fun `round trips`() {
        val config = ConfigCodec.decode(Fixtures.config)
        assertEquals(config, ConfigCodec.decode(ConfigCodec.encode(config)))
    }

    @Test
    fun `rejects a newer version`() {
        val e = failure(Fixtures.config.replace("\"version\": 1", "\"version\": 99"))
        assertTrue(e.message!!.contains("newer"))
    }

    @Test
    fun `rejects a missing active dashboard`() {
        val e = failure(Fixtures.config.replace("\"activeDashboard\": \"kitchen\"", "\"activeDashboard\": \"nope\""))
        assertTrue(e.message!!.contains("nope"))
    }

    @Test
    fun `rejects a widget outside the grid`() {
        val e = failure(Fixtures.config.replace("\"x\": 2, \"y\": 0, \"w\": 2", "\"x\": 3, \"y\": 0, \"w\": 2"))
        assertTrue(e.message!!.contains("outside"))
    }

    @Test
    fun `rejects unknown keys`() {
        failure(Fixtures.config.replace("\"version\": 1", "\"version\": 1, \"verison\": 1"))
    }

    private fun failure(text: String): ConfigException =
        runCatching { ConfigCodec.decode(text) }.exceptionOrNull() as? ConfigException
            ?: throw AssertionError("expected a ConfigException")
}
