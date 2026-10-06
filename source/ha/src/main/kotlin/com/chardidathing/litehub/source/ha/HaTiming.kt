package com.chardidathing.litehub.source.ha

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// every network tuning value for the ha connection, tests pass shorter ones
data class HaTiming(
    val backoffMin: Duration = 1.seconds,
    val backoffMax: Duration = 60.seconds,
    // okhttp websocket ping, a dead wifi link shows up as a failed ping
    val ping: Duration = 30.seconds,
    val handshakeTimeout: Duration = 10.seconds,
    val commandTimeout: Duration = 10.seconds,
    // batches state changes so the slow emmc isn't written on every update
    val cacheFlush: Duration = 5.seconds,
    // how long a toggle shows its guessed state if ha never sends the real one
    val optimisticHold: Duration = 10.seconds,
) {
    fun backoff(attempt: Int): Duration =
        (backoffMin * (1 shl attempt.coerceAtMost(16)).toDouble()).coerceAtMost(backoffMax)
}
