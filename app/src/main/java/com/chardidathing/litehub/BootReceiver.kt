package com.chardidathing.litehub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// starts the dashboard after boot. android 10 and up ignore this from the background, there
// litehub has to be the home app (which boots straight into it anyway)
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
