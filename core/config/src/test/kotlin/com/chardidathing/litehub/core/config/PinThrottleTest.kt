package com.chardidathing.litehub.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinThrottleTest {

    private var now = 1_000L
    private val throttle = PinThrottle { now }

    @Test
    fun `every fifth attempt starts a minute's cooldown`() {
        repeat(PinThrottle.ATTEMPTS) { assertTrue(throttle.begin()) }
        assertFalse(throttle.begin())
        assertEquals(PinThrottle.COOLDOWN_MS, throttle.waitMs())
        now += PinThrottle.COOLDOWN_MS
        assertEquals(0, throttle.waitMs())
        repeat(PinThrottle.ATTEMPTS) { assertTrue(throttle.begin()) }
        assertFalse(throttle.begin())
    }

    @Test
    fun `the right pin clears the count and the cooldown`() {
        repeat(PinThrottle.ATTEMPTS) { throttle.begin() }
        throttle.succeeded()
        assertTrue(throttle.begin())
    }

    @Test
    fun `guesses at the same moment can't get past the count`() {
        val results = java.util.concurrent.ConcurrentLinkedQueue<Boolean>()
        val threads = (1..50).map { Thread { results += throttle.begin() } }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        assertEquals(PinThrottle.ATTEMPTS, results.count { it })
    }
}
