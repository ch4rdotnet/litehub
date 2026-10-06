package com.chardidathing.litehub

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// wakes the process when the night window starts or ends. a panel turned off by device admin
// lets the cpu sleep, so a plain timer can't be trusted to fire
class NightAlarm : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as LitehubApp).screensaver.evaluate()
    }

    companion object {
        private const val WINDOW_MS = 10 * 60_000L

        fun schedule(context: Context, atMs: Long?) {
            val alarms = context.getSystemService(AlarmManager::class.java)
            val pending = PendingIntent.getBroadcast(context, 0, Intent(context, NightAlarm::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            alarms.cancel(pending)
            if (atMs == null) return
            // exact needs the alarms permission from android 12, without it android may push a plain
            // alarm back by most of an hour, a ten minute window is the tightest it allows
            val exact = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
            if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending)
            else alarms.setWindow(AlarmManager.RTC_WAKEUP, atMs, WINDOW_MS, pending)
        }
    }
}
