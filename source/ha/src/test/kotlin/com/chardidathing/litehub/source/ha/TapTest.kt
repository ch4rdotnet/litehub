package com.chardidathing.litehub.source.ha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TapTest {

    @Test
    fun `locks lock and unlock rather than toggle`() {
        assertTrue(EntityRepository.TOGGLE_DOMAINS.contains("lock"))
        assertEquals("unlock" to "unlocking", EntityRepository.tap("lock.back_door", "locked"))
        assertEquals("lock" to "locking", EntityRepository.tap("lock.back_door", "unlocked"))
        // jammed, open or not known yet all lock, never a surprise unlock
        assertEquals("lock" to "locking", EntityRepository.tap("lock.back_door", "jammed"))
        assertEquals("lock" to "locking", EntityRepository.tap("lock.back_door", null))
    }

    @Test
    fun `everything else toggles with a guess when it's on or off`() {
        assertEquals("toggle" to "off", EntityRepository.tap("light.lamp", "on"))
        assertEquals("toggle" to null, EntityRepository.tap("light.lamp", "unavailable"))
    }
}
