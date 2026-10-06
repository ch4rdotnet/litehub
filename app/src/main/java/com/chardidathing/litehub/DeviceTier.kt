package com.chardidathing.litehub

import android.app.ActivityManager
import android.content.Context

// the plan's floor is 1gb devices, they still get the dashboard but lose the extras
object DeviceTier {

    private const val LOW_RAM_BYTES = 1536L * 1024 * 1024

    fun isLow(context: Context): Boolean {
        val am = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        return am.isLowRamDevice || info.totalMem < LOW_RAM_BYTES
    }
}
