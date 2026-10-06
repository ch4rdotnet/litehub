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
    private val onEdit: () -> Unit,
    private val onRestore: () -> Unit,
    private val companion: CompanionBridge,
) {

    private var overlay: View? = null
    private var theme: ResolvedTheme? = null
    // set when the dashboard couldn't run, shown under the menu title
    var notice: String? = null

    // edit layout needs a dashboard that loaded
    var canEdit = false

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
        val previous = java.io.File(app.filesDir, LitehubApp.PREVIOUS_CONFIG_FILE).exists()
        val items = buildList {
            if (canEdit) add("edit layout" to { close(); onEdit() })
            if (previous) add("restore previous layout" to { close(); onRestore() })
            add("reload" to onReload)
            if (companion.registration == null) add("add to home assistant" to ::register)
            else add("unregister" to ::forget)
            val saver = app.settings.screensaver
            add((if (saver.enabled) "screensaver: on" else "screensaver: off") to {
                scope.launch {
                    withContext(Dispatchers.IO) { app.saveSettings(app.settings.copy(screensaver = saver.copy(enabled = !saver.enabled))) }
                    app.screensaver.reload()
                    menu()
                }
            })
            add((if (ScreenAdmin.active(app)) "real screen off: on" else "real screen off: off") to ::screenAdmin)
            add((if (app.settings.chime) "chime: on" else "chime: off") to {
                scope.launch {
                    withContext(Dispatchers.IO) { app.saveSettings(app.settings.copy(chime = !app.settings.chime)) }
                    menu()
                }
            })
            val web = app.settings.web
            add((if (web.editor) "web editor: on" else "web editor: off") to { toggleWeb { it.copy(editor = !it.editor) } })
            add((if (web.status) "status page: on" else "status page: off") to { toggleWeb { it.copy(status = !it.status) } })
            add((if (hasPin) "change pin" else "set pin") to ::newPin)
            if (hasPin) add("remove pin" to ::removePin)
            add("home app settings" to { settings(Settings.ACTION_HOME_SETTINGS) })
            add("android settings" to { settings(Settings.ACTION_SETTINGS) })
            add("close" to ::close)
        }
        val web = app.settings.web
        val address = if (web.editor || web.status) app.web.address()?.let { "  ·  open $it" }.orEmpty() else ""
        show(MenuView(activity, t, "litehub", notice ?: "version ${BuildConfig.VERSION_NAME}$address", items.map { it.first }) { i ->
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

    private fun register() {
        val t = theme ?: return
        show(MenuView(activity, t, "registering", "adding this hub to home assistant as a device", emptyList()) {})
        scope.launch {
            val result = companion.register()
            val (title, detail) = result.fold(
                { "registered" to "home assistant now has this hub as a device. ${companion.notifyService} sends to this screen" },
                { "couldn't register" to (it.message ?: "home assistant refused") },
            )
            show(MenuView(activity, t, title, detail, listOf("ok")) { menu() })
        }
    }

    private fun forget() {
        scope.launch {
            companion.forget()
            menu()
        }
    }

    private fun toggleWeb(change: (com.chardidathing.litehub.core.model.WebSettings) -> com.chardidathing.litehub.core.model.WebSettings) {
        scope.launch {
            withContext(Dispatchers.IO) {
                app.saveSettings(app.settings.copy(web = change(app.settings.web)))
                app.web.apply()
            }
            menu()
        }
    }

    // device admin, with both ways to grant it spelled out
    private fun screenAdmin() {
        val t = theme ?: return
        val dpm = activity.getSystemService(android.app.admin.DevicePolicyManager::class.java)
        if (ScreenAdmin.active(app)) {
            dpm.removeActiveAdmin(ScreenAdmin.component(app))
            menu()
            return
        }
        val detail = "with it, a blank screen really turns the panel off and ha can still turn it back on. " +
            "android asks to let litehub lock the screen, nothing else. if that never shows on this firmware, run this from a computer: ${ScreenAdmin.ADB}"
        show(MenuView(activity, t, "real screen off", detail, listOf("ask android", "back")) { i ->
            if (i == 0) {
                close()
                activity.startActivity(
                    Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                        .putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, ScreenAdmin.component(app))
                        .putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "lets litehub turn the screen off at night and wake it again"),
                )
            } else {
                menu()
            }
        })
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
