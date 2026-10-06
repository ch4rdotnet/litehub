package com.chardidathing.litehub

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

// device admin with one policy, turning the screen off. without it blanking is a black
// overlay at the lowest backlight instead
class ScreenAdmin : DeviceAdminReceiver() {

    companion object {
        fun component(context: Context) = ComponentName(context, ScreenAdmin::class.java)

        fun active(context: Context) = context.getSystemService(DevicePolicyManager::class.java).isAdminActive(component(context))

        // for firmwares that hide the android dialog
        const val ADB = "adb shell dpm set-active-admin com.chardidathing.litehub/.ScreenAdmin"
    }
}
