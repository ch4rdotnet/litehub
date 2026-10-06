package com.chardidathing.litehub.core.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinTest {

    @Test
    fun `a hash matches its own pin and nothing else`() {
        val stored = Pin.hash("2468")
        assertTrue(Pin.matches("2468", stored))
        assertFalse(Pin.matches("2469", stored))
        assertFalse(Pin.matches("", stored))
    }

    @Test
    fun `the same pin hashes differently each time`() {
        assertNotEquals(Pin.hash("1111"), Pin.hash("1111"))
    }

    @Test
    fun `garbage in settings never matches`() {
        assertFalse(Pin.matches("1234", "1234"))
        assertFalse(Pin.matches("1234", "pbkdf2\$x\$y\$z"))
    }

    @Test
    fun `settings round trip`() {
        val s = SettingsCodec.defaults.copy(pin = Pin.hash("1234"))
        assertTrue(Pin.matches("1234", SettingsCodec.decode(SettingsCodec.encode(s)).pin!!))
    }
}
