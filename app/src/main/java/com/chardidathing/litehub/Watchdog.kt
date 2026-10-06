package com.chardidathing.litehub

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import java.io.File

// brings the dashboard back after a crash. three crashes inside a minute means something in
// the config or a source is killing it, so the next start is safe mode instead of a loop
object Watchdog {

    private const val FILE = "crashes.txt"
    private const val LOOP_COUNT = 3
    private const val LOOP_WINDOW_MS = 60_000L
    private const val RESTART_DELAY_MS = 2_000L

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // nothing here may throw, it's the last code that runs
            runCatching { record(app) }
            runCatching { scheduleRestart(app) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun inCrashLoop(context: Context): Boolean = recent(context).size >= LOOP_COUNT

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }

    private fun recent(context: Context): List<Long> {
        val now = System.currentTimeMillis()
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { it.trim().toLongOrNull() }.filter { now - it < LOOP_WINDOW_MS }
    }

    private fun record(context: Context) {
        val kept = recent(context) + System.currentTimeMillis()
        File(context.filesDir, FILE).writeText(kept.joinToString("\n"))
    }

    // as the home app android brings us back on its own, this covers being launched any other way
    private fun scheduleRestart(context: Context) {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + RESTART_DELAY_MS, pending)
    }
}
