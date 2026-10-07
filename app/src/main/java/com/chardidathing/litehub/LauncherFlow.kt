package com.chardidathing.litehub

import android.app.Activity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import com.chardidathing.litehub.core.model.LauncherSettings
import com.chardidathing.litehub.ui.components.MenuView
import com.chardidathing.litehub.ui.launcher.AppDrawerView
import com.chardidathing.litehub.ui.launcher.AppEntry
import com.chardidathing.litehub.ui.tokens.ResolvedTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// the app drawer over the dashboard, and opening apps from it or from an app tile. holding an
// app in the drawer hides it or puts it behind the pin, which asks for the pin first when there
// is one. closes itself after a minute untouched like the menu does
class LauncherFlow(
    private val activity: Activity,
    private val app: LitehubApp,
    private val container: FrameLayout,
    private val scope: CoroutineScope,
    private val pins: PinGate,
) {

    private var overlay: View? = null
    private var theme: ResolvedTheme? = null
    private val idle = IdleClose(container) { close() }

    val isOpen get() = overlay != null

    fun open(theme: ResolvedTheme) {
        this.theme = theme
        drawer()
    }

    fun close() {
        idle.stop()
        hideKeyboard()
        overlay?.let(container::removeView)
        overlay = null
    }

    // from the drawer or a tile, a locked app asks for the pin first
    fun launch(theme: ResolvedTheme, key: String) {
        this.theme = theme
        if (key !in app.settings.launcher.locked) return go(key)
        val pad = pins.pad(theme, "enter pin to open", onOk = { go(key) }, onCancel = ::close) ?: return go(key)
        show(pad)
    }

    private fun go(key: String) {
        val t = theme ?: return
        app.apps.launch(key).onSuccess {
            close()
            app.appReturn.opened()
        }.onFailure {
            show(MenuView(activity, t, "couldn't open it", it.message ?: "android wouldn't open it", listOf("ok")) { close() })
        }
    }

    private fun drawer() {
        val t = theme ?: return
        val view = AppDrawerView(activity, t, ::loadIcon, onPick = { launch(t, it.key) }, onHold = ::hold, onClose = ::close)
        show(view)
        scope.launch {
            val hidden = app.settings.launcher.hidden.toSet()
            view.show(list().map { all -> all.filter { it.key !in hidden } })
        }
    }

    // the same grid without holds, for a tile's settings to pick from
    fun picker(theme: ResolvedTheme, onPicked: (String) -> Unit, onCancel: () -> Unit): View {
        val view = AppDrawerView(activity, theme, ::loadIcon, onPick = { onPicked(it.key) }, onHold = null, onClose = onCancel)
        scope.launch { view.show(list()) }
        return view
    }

    private suspend fun list(): Result<List<AppEntry>> = withContext(Dispatchers.IO) {
        try {
            Result.success(app.apps.list())
        } catch (e: SecurityException) {
            Result.failure(e)
        }
    }

    private fun loadIcon(entry: AppEntry, size: Int, done: (android.graphics.Bitmap?) -> Unit) {
        scope.launch { done(withContext(Dispatchers.IO) { app.apps.icon(entry.key, size) }) }
    }

    private fun hold(entry: AppEntry) {
        val t = theme ?: return
        hideKeyboard()
        val pad = pins.pad(t, "enter pin to change ${entry.label}", onOk = { manage(entry) }, onCancel = ::drawer) ?: return manage(entry)
        show(pad)
    }

    private fun manage(entry: AppEntry) {
        val t = theme ?: return
        val s = app.settings.launcher
        val locked = entry.key in s.locked
        val items = buildList {
            add("hide it from the drawer" to { save(s.copy(hidden = s.hidden + entry.key)) })
            // without a pin there's nothing to ask for
            if (app.settings.pin != null) {
                if (locked) add("open it without the pin" to { save(s.copy(locked = s.locked - entry.key)) })
                else add("ask for the pin to open it" to { save(s.copy(locked = s.locked + entry.key)) })
            }
            if (s.hidden.isNotEmpty()) add("show hidden apps" to ::hidden)
            add("back" to ::drawer)
        }
        val detail = if (locked) "asks for the pin to open" else null
        show(MenuView(activity, t, entry.label, detail, items.map { it.first }) { i -> items[i].second() })
    }

    // the hidden ones by name, picking one puts it back in the drawer
    private fun hidden() {
        val t = theme ?: return
        scope.launch {
            val hidden = app.settings.launcher.hidden
            val names = withContext(Dispatchers.IO) { hidden.map { app.apps.label(it) ?: "$it (not installed)" } }
            show(MenuView(activity, t, "hidden apps", "pick one to show it in the drawer again", names + "back") { i ->
                if (i < hidden.size) save(app.settings.launcher.let { it.copy(hidden = it.hidden - hidden[i]) }) else drawer()
            })
        }
    }

    private fun save(launcher: LauncherSettings) {
        scope.launch {
            withContext(Dispatchers.IO) { app.saveSettings(app.settings.copy(launcher = launcher)) }
            drawer()
        }
    }

    private fun show(view: View) {
        overlay?.let(container::removeView)
        val watched = idle.wrap(view, IdleClose.SCREEN_MS)
        overlay = watched
        container.addView(watched)
    }

    private fun hideKeyboard() {
        activity.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(container.windowToken, 0)
    }
}
