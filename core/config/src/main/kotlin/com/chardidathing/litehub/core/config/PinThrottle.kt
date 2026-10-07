package com.chardidathing.litehub.core.config

// a cooldown after every few wrong pins, one shared by everywhere a pin is typed (the pad on the
// device and the web login) so they can't be used to double up guesses. an attempt is counted
// before it's checked, so guesses sent all at once can't slip past the count
class PinThrottle(private val clock: () -> Long = System::currentTimeMillis) {

    private var attempts = 0
    private var lockedUntil = 0L

    // ms until the next guess is allowed, 0 when one is
    @Synchronized
    fun waitMs(): Long = (lockedUntil - clock()).coerceAtLeast(0)

    // for telling a person, rounded up so it never says 0 while still locked
    fun waitSeconds(): Long = (waitMs() + MS_PER_S - 1) / MS_PER_S

    // false while cooling down, otherwise the attempt counts and may start the cooldown
    @Synchronized
    fun begin(): Boolean {
        val now = clock()
        if (now < lockedUntil) return false
        if (++attempts >= ATTEMPTS) {
            attempts = 0
            lockedUntil = now + COOLDOWN_MS
        }
        return true
    }

    // the right pin clears the count, and the cooldown its own attempt may have started
    @Synchronized
    fun succeeded() {
        attempts = 0
        lockedUntil = 0
    }

    companion object {
        const val ATTEMPTS = 5
        const val COOLDOWN_MS = 60_000L
        private const val MS_PER_S = 1000L
    }
}
