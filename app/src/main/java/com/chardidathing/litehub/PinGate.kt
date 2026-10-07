package com.chardidathing.litehub

import android.app.Activity
import com.chardidathing.litehub.core.config.Pin
import com.chardidathing.litehub.ui.components.PinPadView
import com.chardidathing.litehub.ui.tokens.ResolvedTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// the pin pad in front of anything behind the device pin (the menu, locked apps), sharing one
// wrong guess throttle with the web login
class PinGate(private val activity: Activity, private val app: LitehubApp, private val scope: CoroutineScope) {

    // null when there's no pin set, the caller goes straight on
    fun pad(theme: ResolvedTheme, title: String, onOk: () -> Unit, onCancel: () -> Unit): PinPadView? {
        val stored = app.settings.pin ?: return null
        lateinit var pad: PinPadView
        pad = PinPadView(activity, theme, title, onEnter = { pin ->
            val throttle = app.pinThrottle
            if (!throttle.begin()) {
                pad.say("too many wrong pins, try again in ${throttle.waitSeconds()} seconds", error = true)
                return@PinPadView
            }
            scope.launch {
                val ok = withContext(Dispatchers.Default) { Pin.matches(pin, stored) }
                if (ok) {
                    throttle.succeeded()
                    onOk()
                } else {
                    pad.say("wrong pin", error = true)
                }
            }
        }, onCancel = onCancel)
        return pad
    }
}
