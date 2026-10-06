package com.chardidathing.litehub

import android.app.Activity
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.chardidathing.litehub.core.config.ConfigCodec
import com.chardidathing.litehub.ui.components.NoticeView
import com.chardidathing.litehub.ui.editor.TextPrompt
import android.view.inputmethod.InputMethodManager
import java.io.File
import android.os.Bundle
import android.widget.FrameLayout
import android.view.ViewTreeObserver
import com.chardidathing.litehub.ui.components.MessageView
import com.chardidathing.litehub.ui.components.PageView
import com.chardidathing.litehub.ui.components.PagerView
import com.chardidathing.litehub.ui.components.WidgetView
import com.chardidathing.litehub.ui.tokens.ResolvedTheme
import com.chardidathing.litehub.ui.widgets.WidgetCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {

    private companion object {
        const val MAX_LEVEL = 255f
        // a notification banner stays this long unless it's tapped away
        const val NOTICE_MS = 15_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as LitehubApp
    private val loader by lazy { DashboardLoader(app) }
    private var loading: Job? = null
    private var binder: DashboardBinder? = null
    private var pager: PagerView? = null
    private val ticker by lazy { Ticker(this) }
    private lateinit var root: FrameLayout
    private lateinit var admin: AdminFlow
    private lateinit var editor: EditorFlow
    private lateinit var companion: CompanionBridge
    private var screenOff: View? = null
    private var notice: View? = null
    private var tts: TextToSpeech? = null
    private val chime by lazy { Chime(this) }
    private val hideNotice = Runnable { notice?.let(root::removeView); notice = null }
    private var ready: Screen.Ready? = null
    private var theme: ResolvedTheme? = null
    private var started = false
    private var reportedDrawn = false
    // nothing touches the network until the cached dashboard is on screen
    private var firstFrameDone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        setContentView(root)
        // after setContentView, the insets controller needs the decor view to exist
        Kiosk.immerse(window)
        editor = EditorFlow(this, app, root, scope, onSaved = ::load)
        companion = CompanionBridge(app, scope, Commands())
        admin = AdminFlow(this, app, root, scope, onReload = ::reload, onEdit = ::edit, onRestore = ::restorePrevious, companion = companion)
        app.web.attach(this)
        scope.launch(Dispatchers.IO) { app.web.apply() }
        load()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // bars come back after dialogs and system ui, hide them again
        if (hasFocus) Kiosk.immerse(window)
    }

    // a launcher has nowhere to go back to, back only closes the menu
    @Deprecated("still the only back hook on api 28")
    override fun onBackPressed() {
        when {
            prompt != null -> closePrompt()
            editor.isOpen -> editor.back()
            admin.isOpen -> admin.close()
        }
    }

    // home pressed while already home
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        admin.close()
    }

    override fun onStart() {
        super.onStart()
        started = true
        ticker.start()
        if (firstFrameDone) companion.start()
        if (firstFrameDone) startSources()
        pager?.let { binder?.show(it.current) }
    }

    override fun onStop() {
        started = false
        binder?.stop()
        ticker.stop()
        companion.stop()
        app.calendars.stop()
        app.feeds.stop()
        app.weather.stop()
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        load()
    }

    // every touch counts as someone being there, and wakes a screen ha turned off
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            companion.interacted()
            if (screenOff != null) {
                setScreen(true)
                return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        tts?.shutdown()
        chime.release()
        scope.cancel()
        super.onDestroy()
    }

    // what ha can ask of the screen through notify.mobile_app_litehub
    private inner class Commands : CompanionBridge.Commands {
        override fun screen(on: Boolean) = setScreen(on)

        override fun brightness(level: Int) {
            // 0 would be the same as off, ha's companion app treats it as the dimmest
            window.attributes = window.attributes.apply { screenBrightness = level.coerceAtLeast(1) / MAX_LEVEL }
            companion.update { it.copy(brightness = level) }
        }

        override fun dashboard(id: String) = switchDashboard(id)

        override fun page(number: Int) {
            pager?.jumpTo(number - 1)
        }

        override fun reload() = this@MainActivity.reload()

        override fun speak(text: String) {
            val engine = tts
            if (engine != null) {
                engine.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
                return
            }
            // the engine starts asynchronously, the first message waits for it
            tts = TextToSpeech(app) { status ->
                if (status == TextToSpeech.SUCCESS) tts?.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
                else showNotice("couldn't speak", "this device has no text to speech engine")
            }
        }

        override fun notify(title: String?, message: String, chime: Boolean) {
            showNotice(title, message)
            if (chime && app.settings.chime) this@MainActivity.chime.play()
        }
    }

    private fun setScreen(on: Boolean) {
        val theme = this.theme ?: return
        if (on) {
            screenOff?.let(root::removeView)
            screenOff = null
            window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        } else if (screenOff == null) {
            // no device admin, so the panel stays powered. black and the lowest backlight is as off as it gets
            screenOff = View(this).apply { setBackgroundColor(theme.screenOff) }.also(root::addView)
            window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF }
        }
        companion.update { it.copy(screenOn = on) }
        app.hubState = app.hubState.copy(screenOn = on)
    }

    private fun showNotice(title: String?, message: String) {
        val theme = this.theme ?: return
        notice?.let(root::removeView)
        root.removeCallbacks(hideNotice)
        val view = NoticeView(this, theme, title, message) { hideNotice.run() }
        notice = view
        root.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        root.postDelayed(hideNotice, NOTICE_MS)
    }

    // ha asked for another dashboard, it sticks like a choice made on the device would
    private fun switchDashboard(id: String) {
        val screen = ready ?: return
        if (screen.config.dashboards.none { it.id == id }) {
            showNotice("no such dashboard", "home assistant asked for \"$id\", this hub doesn't have it")
            return
        }
        scope.launch {
            withContext(Dispatchers.IO) {
                File(app.filesDir, LitehubApp.CONFIG_FILE).writeAtomic(ConfigCodec.encode(screen.config.copy(activeDashboard = id)))
            }
            load()
        }
    }

    private fun startSources() {
        app.calendars.start()
        app.feeds.start()
        app.weather.start()
    }

    private var prompt: TextPrompt? = null

    private fun askText(title: String, onText: (String) -> Unit) {
        val theme = this.theme ?: return
        closePrompt()
        val view = TextPrompt(this, theme, title, onDone = { text ->
            closePrompt()
            onText(text)
        }, onCancel = ::closePrompt)
        prompt = view
        root.addView(view)
        view.input.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(view.input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closePrompt() {
        val view = prompt ?: return
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken, 0)
        root.removeView(view)
        prompt = null
    }

    private fun edit() {
        val screen = ready ?: return
        editor.open(screen.theme, screen.config, pager?.current ?: 0, screen.legend)
    }

    // swaps config.json with the one saved before the last edit, so it can be swapped back too
    private fun restorePrevious() {
        scope.launch {
            withContext(Dispatchers.IO) {
                val current = java.io.File(app.filesDir, LitehubApp.CONFIG_FILE)
                val previous = java.io.File(app.filesDir, LitehubApp.PREVIOUS_CONFIG_FILE)
                val text = previous.readText()
                if (current.exists()) previous.writeAtomic(current.readText()) else previous.delete()
                current.writeAtomic(text)
            }
            load()
        }
    }

    fun reload() {
        admin.close()
        binder?.stop()
        app.calendars.stop()
        app.feeds.stop()
        Watchdog.clear(app)
        scope.launch {
            withContext(Dispatchers.IO) { app.reload() }
            load()
        }
    }

    fun load() {
        val safe = Watchdog.inCrashLoop(app)
        val systemDark = resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val metrics = resources.displayMetrics
        loading?.cancel()
        loading = scope.launch {
            val screen = withContext(Dispatchers.IO) { loader.load(systemDark, metrics, safe) }
            show(screen)
        }
    }

    private fun show(screen: Screen) {
        val theme = screen.theme
        this.theme = theme
        admin.notice = (screen as? Screen.Failed)?.reason
        ready = screen as? Screen.Ready
        admin.canEdit = ready != null
        binder?.stop()
        binder = null
        pager = null
        // the window background is the dashboard background, so nothing else has to paint it
        window.setBackgroundDrawable(ColorDrawable(theme.colors.background))
        val view = when (screen) {
            is Screen.Ready -> {
                val widgets = ArrayList<List<WidgetView>>()
                val pages = screen.pages.map { page ->
                    val view = PageView(this, page.columns, page.rows, theme.spacing.m)
                    val made = page.widgets.map { p ->
                        WidgetCatalog.create(this, theme, screen.icons, screen.legend, p).also { view.addWidget(it, p.x, p.y, p.w, p.h) }
                    }
                    widgets += made
                    view
                }
                val b = DashboardBinder(app.ha, app.calendars, app.feeds, app.weather, ::askText, ticker.now, widgets, scope)
                binder = b
                PagerView(this, theme, pages).also {
                    pager = it
                    it.onLongPress = { admin.open(theme) }
                    it.onSettled = { page ->
                        if (started) b.show(page)
                        companion.update { c -> c.copy(page = page + 1) }
                        app.hubState = app.hubState.copy(page = page + 1)
                    }
                    if (started) b.show(it.current)
                }
            }
            is Screen.Failed -> MessageView(this, theme, if (screen.reason == DashboardLoader.SAFE_MODE) "safe mode" else "config couldn't be loaded", screen.reason).apply {
                // a broken config still has to reach the menu
                setOnLongClickListener {
                    admin.open(theme)
                    true
                }
            }
        }
        root.removeAllViews()
        root.addView(view)
        screenOff = null
        notice = null
        ready?.let { r ->
            companion.update { it.copy(dashboard = r.config.activeDashboard, page = (pager?.current ?: 0) + 1) }
            app.hubState = app.hubState.copy(dashboard = r.config.activeDashboard, page = (pager?.current ?: 0) + 1)
        }
        if (firstFrameDone) {
            // a reload built new repositories, they need starting like the first ones were
            app.ha.connect()
            if (started) startSources()
        }
        if (!reportedDrawn) {
            reportedDrawn = true
            view.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    view.viewTreeObserver.removeOnPreDrawListener(this)
                    view.post {
                        reportFullyDrawn()
                        firstFrameDone = true
                        app.ha.connect()
                        if (started) {
                            startSources()
                            companion.start()
                        }
                    }
                    return true
                }
            })
        }
    }
}
