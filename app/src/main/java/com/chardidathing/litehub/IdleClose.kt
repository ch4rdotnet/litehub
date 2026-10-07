package com.chardidathing.litehub

import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

// closes an admin screen left open on a wall. a wrapped view restarts the wait on every touch,
// its children's included, and the wait can be longer for a form someone's typing into
class IdleClose(private val container: View, private val onIdle: () -> Unit) {

    private val idle = Runnable { onIdle() }

    fun wrap(view: View, ms: Long): View {
        val watched = object : FrameLayout(view.context) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) restart(ms)
                return super.dispatchTouchEvent(ev)
            }
        }
        (view.parent as? FrameLayout)?.removeView(view)
        watched.addView(view)
        restart(ms)
        return watched
    }

    fun restart(ms: Long) {
        container.removeCallbacks(idle)
        container.postDelayed(idle, ms)
    }

    fun stop() = container.removeCallbacks(idle)

    companion object {
        // how long a screen over the dashboard waits for a touch before it goes
        const val SCREEN_MS = 60_000L
    }
}
