package com.chardidathing.litehub

import android.app.Activity
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.content.Intent
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
        admin = AdminFlow(this, app, root, scope, onReload = ::reload, onEdit = ::edit, onRestore = ::restorePrevious)
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
        if (firstFrameDone) startSources()
        pager?.let { binder?.show(it.current) }
    }

    override fun onStop() {
        started = false
        binder?.stop()
        ticker.stop()
        app.calendars.stop()
        app.feeds.stop()
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        load()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startSources() {
        app.calendars.start()
        app.feeds.start()
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

    private fun reload() {
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

    private fun load() {
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
                val b = DashboardBinder(app.ha, app.calendars, app.feeds, ticker.now, widgets, scope)
                binder = b
                PagerView(this, theme, pages).also {
                    pager = it
                    it.onLongPress = { admin.open(theme) }
                    it.onSettled = { page -> if (started) b.show(page) }
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
                        if (started) startSources()
                    }
                    return true
                }
            })
        }
    }
}
