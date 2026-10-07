package com.chardidathing.litehub

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import com.chardidathing.litehub.core.config.ConfigException
import com.chardidathing.litehub.core.config.HubSettings
import com.chardidathing.litehub.core.config.SettingsForm
import com.chardidathing.litehub.core.config.Pin
import com.chardidathing.litehub.ui.editor.EntityPicker
import com.chardidathing.litehub.ui.editor.SettingsScreen
import android.view.inputmethod.InputMethodManager
import kotlinx.serialization.json.JsonObject
import java.io.IOException
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
    private val companion: CompanionBridge,
) {

    private var overlay: View? = null
    private var theme: ResolvedTheme? = null
    // set when the dashboard couldn't run, shown under the menu title
    var notice: String? = null

    // edit layout needs a dashboard that loaded
    var canEdit = false

    private val idle = IdleClose(container) { close() }

    // between open() and close(). work still waiting on the network or disk when the screens
    // close doesn't get to reopen them for whoever walks up next
    private var live = false

    val isOpen get() = overlay != null

    fun open(theme: ResolvedTheme) {
        this.theme = theme
        live = true
        val stored = app.settings.pin
        if (stored == null) menu() else askPin(stored)
    }

    fun close() {
        live = false
        idle.stop()
        overlay?.let(container::removeView)
        overlay = null
        screen = null
    }

    private fun askPin(stored: String) {
        val t = theme ?: return
        lateinit var pad: PinPadView
        pad = PinPadView(activity, t, "enter pin", onEnter = { pin ->
            val throttle = app.pinThrottle
            if (!throttle.begin()) {
                pad.say("too many wrong pins, try again in ${throttle.waitSeconds()} seconds", error = true)
                return@PinPadView
            }
            scope.launch {
                val ok = withContext(Dispatchers.Default) { Pin.matches(pin, stored) }
                if (ok) {
                    throttle.succeeded()
                    menu()
                } else {
                    pad.say("wrong pin", error = true)
                }
            }
        }, onCancel = ::close)
        show(pad)
    }

    private fun menu() {
        val t = theme ?: return
        screen = null
        val items = buildList {
            if (canEdit) add("edit layout" to { close(); onEdit() })
            add("settings" to ::settings)
            add("reload" to onReload)
            add("close" to ::close)
        }
        val web = app.settings.web
        val address = if (web.editor || web.status) app.web.address()?.let { "  ·  open $it" }.orEmpty() else ""
        show(MenuView(activity, t, "litehub", notice ?: "version ${BuildConfig.VERSION_NAME}$address", items.map { it.first }) { i ->
            items[i].second()
        })
    }

    // the settings screen stays put while a pin pad or the entity list is over it, edits and all
    private var screen: SettingsScreen? = null

    private fun settings() {
        val t = theme ?: return
        scope.launch {
            val before = withContext(Dispatchers.IO) { app.hubSettings() }
            val s = SettingsScreen(activity, t, SettingsForm.sections, SettingsForm.values(before), listOf(device(), log()), object : SettingsScreen.Host {
                override fun pickEntity(domains: List<String>, onPicked: (String) -> Unit) = pick(t, domains, onPicked)
                override fun newItem(section: String) = SettingsForm.newItem(section)
                override fun action(id: String, done: (Result<JsonObject>) -> Unit) {
                    scope.launch { done(app.settingsAction(id)) }
                }
                override fun save(values: JsonObject) {
                    hideKeyboard()
                    scope.launch { save(before, values) }
                }
                override fun cancel() {
                    hideKeyboard()
                    menu()
                }
            }, extras = mapOf("ha" to ::registration))
            screen = s
            show(s, SETTINGS_IDLE_MS)
        }
    }

    // sources or ha changing means the dashboard reloads, anything else applies in place
    private suspend fun save(before: HubSettings, values: JsonObject) {
        val outcome = withContext(Dispatchers.IO) {
            try {
                Result.success(app.saveAll(before, SettingsForm.apply(before, values)))
            } catch (e: ConfigException) {
                Result.failure(e)
            } catch (e: IOException) {
                Result.failure(IOException("couldn't save, ${e.message}", e))
            }
        }
        outcome.onSuccess { reload -> if (reload) onReload() else menu() }
            .onFailure { screen?.say(it.message ?: "couldn't save", error = true) }
    }

    private fun backToSettings() {
        val s = screen ?: return menu()
        s.refresh()
        show(s, SETTINGS_IDLE_MS)
    }

    // things about this device that aren't settings.json values, then everything the status
    // page says and what the hardware is
    private fun device() = SettingsScreen.ActionSection(
        "this device",
        items = {
            val hasPin = app.settings.pin != null
            buildList {
                add((if (hasPin) "change pin" else "set pin") to ::newPin)
                if (hasPin) add("remove pin" to ::removePin)
                add((if (ScreenAdmin.active(app)) "real screen off: on" else "real screen off: off") to ::screenAdmin)
                add("home app settings" to { openAndroid(Settings.ACTION_HOME_SETTINGS) })
                add("android settings" to { openAndroid(Settings.ACTION_SETTINGS) })
            }
        },
        info = { done ->
            scope.launch {
                val snapshot = withContext(Dispatchers.IO) { app.status.snapshot() }
                done(app.status.rows(snapshot))
            }
        },
    )

    // newest first, the time beside each line
    private fun log() = SettingsScreen.ActionSection(
        "log",
        info = { done -> done(AppLog.recent().asReversed().map { SettingsScreen.InfoRow(it.substringBefore(' '), it.substringAfter(' ')) }) },
    )

    private fun pick(theme: ResolvedTheme, domains: List<String>, onPicked: (String) -> Unit) {
        hideKeyboard()
        val picker = EntityPicker(activity, theme, domains, onPicked = { id ->
            hideKeyboard()
            backToSettings()
            onPicked(id)
        }, onCancel = {
            hideKeyboard()
            backToSettings()
        })
        show(picker, SETTINGS_IDLE_MS)
        scope.launch { picker.show(app.ha.catalogue()) }
    }

    private fun hideKeyboard() {
        activity.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(container.windowToken, 0)
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
                    backToSettings()
                }
            }
        }, onCancel = ::backToSettings)
        pad.say("at least ${Pin.MIN_LENGTH} digits", error = false)
        show(pad)
    }

    // the companion device, under the home assistant section
    private fun registration() =
        if (companion.registration == null) listOf("add to home assistant" to ::register)
        else listOf("unregister from home assistant" to ::forget)

    private fun register() {
        val t = theme ?: return
        show(MenuView(activity, t, "registering", "adding this hub to home assistant as a device", emptyList()) {})
        scope.launch {
            val result = companion.register()
            val (title, detail) = result.fold(
                { "registered" to "home assistant now has this hub as a device. ${companion.notifyService} sends to this screen" },
                { "couldn't register" to (it.message ?: "home assistant refused") },
            )
            show(MenuView(activity, t, title, detail, listOf("ok")) { backToSettings() })
        }
    }

    private fun forget() {
        scope.launch {
            companion.forget()
            backToSettings()
        }
    }

    // device admin, with both ways to grant it spelled out
    private fun screenAdmin() {
        val t = theme ?: return
        val dpm = activity.getSystemService(android.app.admin.DevicePolicyManager::class.java)
        if (ScreenAdmin.active(app)) {
            dpm.removeActiveAdmin(ScreenAdmin.component(app))
            backToSettings()
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
                backToSettings()
            }
        })
    }

    private fun removePin() {
        scope.launch {
            withContext(Dispatchers.IO) { app.saveSettings(app.settings.copy(pin = null)) }
            backToSettings()
        }
    }

    private fun openAndroid(action: String) {
        close()
        try {
            activity.startActivity(Intent(action))
        } catch (e: ActivityNotFoundException) {
            // some stripped firmwares have no home settings, the full settings app still works
            activity.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun show(view: View, idleMs: Long = IDLE_CLOSE_MS) {
        if (!live) return
        overlay?.let(container::removeView)
        val watched = idle.wrap(view, idleMs)
        overlay = watched
        container.addView(watched)
    }

    private companion object {
        const val IDLE_CLOSE_MS = 60_000L
        // typing on the keyboard isn't a touch on the screen, a form gets longer
        const val SETTINGS_IDLE_MS = 5 * 60_000L
    }
}
