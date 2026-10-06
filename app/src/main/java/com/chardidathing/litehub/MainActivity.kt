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
import com.chardidathing.litehub.core.model.Density
import com.chardidathing.litehub.ui.components.NoticeStack
import com.chardidathing.litehub.ui.components.NowPlayingView
import com.chardidathing.litehub.ui.components.VideoFrame
import com.chardidathing.litehub.dlna.RendererState
import com.chardidathing.litehub.dlna.Transport
import com.chardidathing.litehub.ui.components.ScreensaverView
import com.chardidathing.litehub.ui.components.ShadeView
import com.chardidathing.litehub.ui.widgets.NotificationsConfig
import com.chardidathing.litehub.ui.widgets.NotificationsWidget
import android.view.Gravity
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import com.chardidathing.litehub.ui.editor.TextPrompt
import android.view.inputmethod.InputMethodManager
import java.io.File
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.net.Uri
import android.util.Size
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
        const val PERMISSIONS = 1
        const val PERCENT = 100f
        const val MS_PER_S = 1000L
        const val MAX_LEVEL = 255f
        val ACTIVE = setOf(Transport.TRANSITIONING, Transport.PLAYING, Transport.PAUSED)
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
    private var notices: NoticeStack? = null
    private var tts: TextToSpeech? = null
    private val chime by lazy { Chime(this) }
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
        // a panel turned off by device admin comes back on to this, over the keyguard
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        scope.launch {
            app.screensaver.mode.collect { m ->
                companion.update { it.copy(display = m.label, screenOn = m != ScreensaverController.Mode.BLANK) }
            }
        }
        scope.launch(Dispatchers.IO) { app.web.apply() }
        scope.launch(Dispatchers.IO) { app.dlna.apply() }
        scope.launch { combine(app.dlna.renderer.state, app.dlna.playback.video, ::Pair).collect { (s, size) -> showMedia(s, size) } }
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
            shade != null -> shade?.close()
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
        app.screensaver.display = ScreenDisplay()
        app.screensaver.start()
        askPermissions()
        if (firstFrameDone) startSources()
        pager?.let { binder?.show(it.current) }
    }

    override fun onStop() {
        started = false
        binder?.stop()
        ticker.stop()
        companion.stop()
        app.screensaver.display = null
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
            val sleeping = app.screensaver.mode.value != ScreensaverController.Mode.AWAKE
            app.screensaver.interacted()
            // the touch that wakes the screen doesn't also tap whatever was under it
            if (sleeping) return true
        }
        if (watchEdge(ev)) return true
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        // the video goes with the activity, the screensaver mustn't stay held for it
        if (video != null) app.screensaver.hold(false)
        tts?.shutdown()
        chime.release()
        scope.cancel()
        super.onDestroy()
    }

    // what ha can ask of the screen through notify.mobile_app_litehub
    private inner class Commands : CompanionBridge.Commands {
        override fun screen(on: Boolean) = if (on) app.screensaver.wake("home assistant") else app.screensaver.force(ScreensaverController.Mode.BLANK)

        override fun screensaver(on: Boolean) = if (on) app.screensaver.force(ScreensaverController.Mode.SCREENSAVER) else app.screensaver.wake("home assistant")

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

        override fun notify(title: String?, message: String, chime: Boolean, tag: String?) {
            // an alert is worth seeing, it wakes a dimmed or blank screen
            app.screensaver.wake("a notification")
            app.notifications.add(title, message, tag)
            showNotice(title, message)
            if (chime && app.settings.chime) this@MainActivity.chime.play()
        }
    }

    // what the screensaver controller asks of the screen
    private inner class ScreenDisplay : ScreensaverController.Display {
        override fun showScreensaver() {
            val theme = this@MainActivity.theme ?: return
            if (saver != null) return
            val view = ScreensaverView(this@MainActivity, theme)
            saver = view
            root.addView(view)
            saverJobs += scope.launch { ticker.now.collect { view.showTime(it.time(it.nowMs), it.longDate()) } }
            val settings = app.settings.screensaver
            // no photos (none set up, or a low ram device) is just the clock over black
            val frame = app.photoFrame().getOrNull()
            if (frame != null) saverJobs += scope.launch {
                while (true) {
                    val w = root.width
                    val h = root.height
                    frame.next(w, h).fold(view::showPhoto) {
                        AppLog.add("photo frame stopped, ${it.message}")
                        return@launch
                    }
                    delay(settings.photoSeconds * MS_PER_S)
                }
            }
        }

        override fun hideScreensaver() {
            saverJobs.forEach(Job::cancel)
            saverJobs.clear()
            saver?.let(root::removeView)
            saver = null
        }

        override fun dim(percent: Int?) {
            window.attributes = window.attributes.apply {
                screenBrightness = percent?.let { it / PERCENT } ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }

        override fun overlayBlank(on: Boolean) {
            val theme = this@MainActivity.theme ?: return
            if (!on) {
                screenOff?.let(root::removeView)
                screenOff = null
                window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            } else if (screenOff == null) {
                // no device admin, so the panel stays powered. black and the lowest backlight is as off as it gets
                screenOff = View(this@MainActivity).apply { setBackgroundColor(theme.screenOff) }.also(root::addView)
                window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF }
            }
        }
    }

    private var saver: ScreensaverView? = null
    private val saverJobs = ArrayList<Job>()

    // the screensaver's photo folder and camera need asking for once
    private fun askPermissions() {
        val s = app.settings.screensaver
        val wanted = buildList {
            if (s.photos?.folder != null) add(if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) android.Manifest.permission.READ_MEDIA_IMAGES else android.Manifest.permission.READ_EXTERNAL_STORAGE)
            if (s.cameraWake) add(android.Manifest.permission.CAMERA)
        }.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), PERMISSIONS)
    }

    // top right, slides in, slides out again after a few seconds. a newer one takes its place
    private fun showNotice(title: String?, message: String) {
        val theme = this.theme ?: return
        val n = app.settings.notifications
        val stack = notices ?: NoticeStack(this, theme, n.maxBanners, n.bannerSeconds * MS_PER_S).also { stack ->
            notices = stack
            val margin = theme.spacing.m.toInt()
            root.addView(stack, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                setMargins(margin, margin, margin, margin)
            })
        }
        stack.max = n.maxBanners
        stack.holdMs = n.bannerSeconds * MS_PER_S
        stack.push(title, message)
    }

    // the pull down list of notifications, opened by dragging from the top edge
    private var shade: ShadeView? = null
    private var shadeJob: Job? = null
    private var edgeDownY = -1f

    private fun openShade() {
        val theme = this.theme ?: return
        if (shade != null) return
        val list = NotificationsWidget(this, theme, NotificationsConfig()).apply {
            onRemove = app.notifications::remove
            onClear = app.notifications::clear
        }
        shadeJob = scope.launch { combine(app.notifications.items, ticker.now, ::Pair).collect { (n, m) -> list.show(n, m) } }
        val renderer = app.dlna.renderer
        val playing = NowPlayingView(
            this,
            theme,
            onToggle = { scope.launch(Dispatchers.IO) { renderer.togglePause() } },
            onStop = { scope.launch(Dispatchers.IO) { renderer.stopHere() } },
        )
        nowPlaying = playing
        bindNowPlaying()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(playing, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = theme.spacing.m.toInt()
            })
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        val view = ShadeView(this, theme, content) { closeShadeNow() }
        shade = view
        root.addView(view)
        view.open()
    }

    private fun closeShadeNow() {
        nowPlaying = null
        shadeJob?.cancel()
        shade?.let(root::removeView)
        shade = null
    }

    // dlna media. a video covers the dashboard, audio only shows up in the shade
    private var video: VideoFrame? = null
    private var nowPlaying: NowPlayingView? = null
    private var media: Pair<RendererState, Size?> = RendererState() to null

    private fun showMedia(state: RendererState, size: Size?) {
        media = state to size
        val show = state.transport in ACTIVE && size != null
        val frame = video
        if (show && frame == null) {
            val next = VideoFrame(this, app.dlna.playback::attach) { scope.launch(Dispatchers.IO) { app.dlna.renderer.stopHere() } }
            video = next
            root.addView(next)
            app.screensaver.hold(true)
        } else if (!show && frame != null) {
            root.removeView(frame)
            video = null
            app.screensaver.hold(false)
        }
        video?.videoSize = size
        bindNowPlaying()
    }

    private fun bindNowPlaying() {
        val view = nowPlaying ?: return
        val s = media.first
        view.visibility = if (s.transport in ACTIVE) View.VISIBLE else View.GONE
        val title = s.track?.title ?: Uri.parse(s.uri).lastPathSegment ?: s.uri
        view.show(title, s.track?.artist, playing = s.transport == Transport.PLAYING)
    }

    // a finger that starts on the top edge and pulls down opens the shade instead of touching the page
    private fun watchEdge(ev: MotionEvent): Boolean {
        val theme = this.theme ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> edgeDownY = if (ev.y < theme.spacing.xl && shade == null) ev.y else -1f
            MotionEvent.ACTION_MOVE -> if (edgeDownY >= 0 && ev.y - edgeDownY > theme.touchTarget) {
                edgeDownY = -1f
                // the page already saw the start of the touch, tell it the gesture is over
                super.dispatchTouchEvent(MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL })
                openShade()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> edgeDownY = -1f
        }
        return false
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
                    val pageTheme = if (page.density == Density.COMPACT) theme.compact else theme
                    val view = PageView(this, page.columns, page.rows, theme.spacing.m, pageTheme.spacing.m)
                    val made = page.widgets.map { p ->
                        WidgetCatalog.create(this, pageTheme, screen.icons, screen.legend, p).also { view.addWidget(it, p.x, p.y, p.w, p.h) }
                    }
                    widgets += made
                    view
                }
                val b = DashboardBinder(app.ha, app.calendars, app.feeds, app.weather, ::askText, app.notifications, app::photoFrame, { app.settings.screensaver.photoSeconds }, ticker.now, widgets, scope)
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
        // everything but a playing video, taking its surface away would end it
        for (i in root.childCount - 1 downTo 0) if (root.getChildAt(i) !== video) root.removeViewAt(i)
        root.addView(view, 0)
        screenOff = null
        notices = null
        closeShadeNow()
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
