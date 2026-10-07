package com.chardidathing.litehub

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

// notices touches in other apps, so the dashboard can come back once an app's been left
// untouched. it only keeps when something last happened, never what's on screen or where.
// android binding it is also what lets litehub come back from the background
class TouchWatch : AccessibilityService() {

    private var watcher: View? = null

    // a pixel in the corner that's told about every touch outside it. view events alone miss
    // apps drawn without android views (maps, games, most calendars)
    @SuppressLint("ClickableViewAccessibility")
    override fun onServiceConnected() {
        running = true
        val view = View(this).apply {
            setOnTouchListener { _, _ ->
                lastTouch = SystemClock.elapsedRealtime()
                false
            }
        }
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        getSystemService(WindowManager::class.java).addView(view, params)
        watcher = view
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        lastTouch = SystemClock.elapsedRealtime()
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        running = false
        watcher?.let { getSystemService(WindowManager::class.java).removeView(it) }
        watcher = null
        return super.onUnbind(intent)
    }

    companion object {
        // elapsedRealtime
        @Volatile var lastTouch = 0L
            private set

        // turned on in android's accessibility settings and bound
        @Volatile var running = false
            private set
    }
}
