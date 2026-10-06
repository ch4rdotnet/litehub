package com.chardidathing.litehub

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.chardidathing.litehub.core.config.Pin
import com.chardidathing.litehub.ui.components.MenuView
import com.chardidathing.litehub.ui.components.PinPadView
import com.chardidathing.litehub.ui.tokens.ResolvedTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// the long press menu over the dashboard. asks for the pin first when one is set, and closes
// itself after a minute untouched so a kiosk never sits on it
class AdminFlow(
    private val activity: Activity,
    private val app: LitehubApp,
    private val container: FrameLayout,
    private val scope: CoroutineScope,
    private val onReload: () -> Unit,
) {

    private var overlay: View? = null
    private var theme: ResolvedTheme? = null
    // set when the dashboard couldn't run, shown under the menu title
    var notice: String? = null

    private val idle = Runnable { close() }

    val isOpen get() = overlay != null

    fun open(theme: ResolvedTheme) {
        this.theme = theme
        val stored = app.settings.pin
        if (stored == null) menu() else askPin(stored)
    }

    fun close() {
        container.removeCallbacks(idle)
        overlay?.let(container::removeView)
        overlay = null
    }

    private fun askPin(stored: String) {
        val t = theme ?: return
        lateinit var pad: PinPadView
        pad = PinPadView(activity, t, "enter pin", onEnter = { pin ->
            scope.launch {
                val ok = withContext(Dispatchers.Default) { Pin.matches(pin, stored) }
                if (ok) menu() else pad.say("wrong pin", error = true)
            }
        }, onCancel = ::close)
        show(pad)
    }

    private fun menu() {
        val t = theme ?: return
        val hasPin = app.settings.pin != null
        val items = buildList {
            add("reload" to onReload)
            add((if (hasPin) "change pin" else "set pin") to ::newPin)
            if (hasPin) add("remove pin" to ::removePin)
            add("home app settings" to { settings(Settings.ACTION_HOME_SETTINGS) })
            add("android settings" to { settings(Settings.ACTION_SETTINGS) })
            add("close" to ::close)
        }
        show(MenuView(activity, t, "litehub", notice ?: "version ${BuildConfig.VERSION_NAME}", items.map { it.first }) { i ->
            items[i].second()
        })
    }

    private fun newPin() {
        val t = theme ?: return
        lateinit var pad: PinPadView
        var first: String? = null
        pad = PinPadView(activity, t, "new pin", onEnter = { pin ->
            val earlier = first
            when {
                earlier == null && pin.length < Pin.MIN_LENGTH -> pad.say("at least ${Pin.MIN_LENGTH} digits", error = true)
                earlier == null -> {
                    first = pin
                    pad.say("the same pin again", error = false)
                }
                earlier != pin -> {
                    first = null
                    pad.say("those didn't match, start again", error = true)
                }
                else -> scope.launch {
                    val hashed = withContext(Dispatchers.Default) { Pin.hash(pin) }
                    withContext(Dispatchers.IO) { app.saveSettings(app.settings.copy(pin = hashed)) }
                    menu()
                }
            }
        }, onCancel = ::menu)
        pad.say("at least ${Pin.MIN_LENGTH} digits", error = false)
        show(pad)
    }

    private fun removePin() {
        scope.launch {
            withContext(Dispatchers.IO) { app.saveSettings(app.settings.copy(pin = null)) }
            menu()
        }
    }

    private fun settings(action: String) {
        close()
        try {
            activity.startActivity(Intent(action))
        } catch (e: ActivityNotFoundException) {
            // some stripped firmwares have no home settings, the full settings app still works
            activity.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun show(view: View) {
        overlay?.let(container::removeView)
        overlay = view
        // any touch on the overlay restarts the idle countdown, then carries on as normal
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) restartIdle()
            false
        }
        container.addView(view)
        restartIdle()
    }

    private fun restartIdle() {
        container.removeCallbacks(idle)
        container.postDelayed(idle, IDLE_CLOSE_MS)
    }

    private companion object {
        const val IDLE_CLOSE_MS = 60_000L
    }
}
