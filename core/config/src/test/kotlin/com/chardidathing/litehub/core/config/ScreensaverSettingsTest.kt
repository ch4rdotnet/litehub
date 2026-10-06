package com.chardidathing.litehub.core.config

import com.chardidathing.litehub.core.model.NightMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ScreensaverSettingsTest {

    private fun decode(screensaver: String) = SettingsCodec.decode("""{"version":1,"screensaver":$screensaver}""")

    @Test
    fun `a full screensaver block round trips`() {
        val s = decode(
            """{"enabled":true,"idleMinutes":5,"photos":{"immich":{"url":"http://i","apiKey":"k","albumId":"a"}},
               "night":{"start":"22:00","end":"06:30","mode":"blank"},"wakeEntities":["binary_sensor.hall"]}""",
        )
        assertEquals(NightMode.BLANK, s.screensaver.night!!.mode)
        assertEquals(s, SettingsCodec.decode(SettingsCodec.encode(s)))
    }

    @Test
    fun `photos needs exactly one source and night needs real times`() {
        assertThrows(ConfigException::class.java) { decode("""{"photos":{"folder":"/a","haMedia":"media-source://x"}}""") }
        assertThrows(ConfigException::class.java) { decode("""{"night":{"start":"late","end":"06:30"}}""") }
    }
}
