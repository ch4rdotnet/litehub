package com.chardidathing.litehub.ui.tokens

import android.view.animation.Interpolator

// motion curves, the only place they're defined
object Easing {

    // above this a settle would overshoot, at it the curve is a plain cubic ease out
    const val MAX_START_SLOPE = 3f

    // a settle that picks up a moving finger. startSlope is the finger's speed as a multiple of
    // the average speed the settle needs (velocity * duration / distance), so the motion starts
    // at exactly the finger's speed, eases to rest, and is a smooth ease in and out from 0
    fun settle(startSlope: Float): Interpolator {
        val m = startSlope.coerceIn(0f, MAX_START_SLOPE)
        // cubic hermite from 0 to 1, slope m at the start and 0 at the end
        return Interpolator { t -> m * (t * t * t - 2 * t * t + t) + (-2 * t * t * t + 3 * t * t) }
    }
}
