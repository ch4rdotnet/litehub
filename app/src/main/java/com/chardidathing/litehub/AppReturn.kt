package com.chardidathing.litehub

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import com.chardidathing.litehub.core.model.ComeBack

// brings the dashboard back a while after an app opened from the hub, counted from when it
// opened or from the last touch in it. only apps litehub opened count, android settings opened
// from the menu don't. android only lets it come forward from the background when it may show
// over other apps or has TouchWatch bound, being the home app isn't enough
class AppReturn(private val app: LitehubApp) {

    private val handler = Handler(Looper.getMainLooper())
    private val check = Runnable { check() }
    private var openedAt = 0L
    private var away = false

    fun opened() {
        openedAt = SystemClock.elapsedRealtime()
        away = true
        check()
    }

    // the dashboard's on screen again, however it got there
    fun back() {
        away = false
        handler.removeCallbacks(check)
    }

    private fun check() {
        handler.removeCallbacks(check)
        val s = app.settings.launcher
        if (!away || s.comeBack == ComeBack.OFF) return
        val from = if (s.comeBack == ComeBack.UNTOUCHED && TouchWatch.running) maxOf(openedAt, TouchWatch.lastTouch) else openedAt
        val wait = from + s.comeBackMinutes * MS_PER_MINUTE - SystemClock.elapsedRealtime()
        if (wait > 0) {
            handler.postDelayed(check, wait)
            return
        }
        away = false
        if (!allowed()) {
            AppLog.add("couldn't come back to the dashboard, litehub isn't allowed over other apps")
            return
        }
        AppLog.add("back to the dashboard after ${if (s.comeBackMinutes == 1) "a minute" else "${s.comeBackMinutes} minutes"} in an app")
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    // whether android will let it come forward on its own
    fun allowed(): Boolean = Settings.canDrawOverlays(app) || TouchWatch.running

    private companion object {
        const val MS_PER_MINUTE = 60_000L
    }
}
