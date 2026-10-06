package com.chardidathing.litehub

import android.app.Activity
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.ViewTreeObserver
import com.chardidathing.litehub.ui.components.MessageView
import com.chardidathing.litehub.ui.components.PageView
import com.chardidathing.litehub.ui.components.PagerView
import com.chardidathing.litehub.ui.widgets.EntityWidget
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
    private var started = false
    private var reportedDrawn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        load()
    }

    override fun onStart() {
        super.onStart()
        started = true
        pager?.let { binder?.show(it.firstVisible, it.lastVisible) }
    }

    override fun onStop() {
        started = false
        binder?.stop()
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

    private fun load() {
        val systemDark = resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val metrics = resources.displayMetrics
        loading?.cancel()
        loading = scope.launch {
            val screen = withContext(Dispatchers.IO) { loader.load(systemDark, metrics) }
            show(screen)
        }
    }

    private fun show(screen: Screen) {
        val theme = screen.theme
        binder?.stop()
        binder = null
        pager = null
        // the window background is the dashboard background, so nothing else has to paint it
        window.setBackgroundDrawable(ColorDrawable(theme.colors.background))
        val view = when (screen) {
            is Screen.Ready -> {
                val entityWidgets = ArrayList<List<EntityWidget>>()
                val pages = screen.pages.map { page ->
                    val view = PageView(this, page.columns, page.rows, theme.spacing.m)
                    val bound = ArrayList<EntityWidget>()
                    for (p in page.widgets) {
                        val widget = WidgetCatalog.create(this, theme, screen.icons, p)
                        if (widget is EntityWidget) bound += widget
                        view.addWidget(widget, p.x, p.y, p.w, p.h)
                    }
                    entityWidgets += bound
                    view
                }
                val b = DashboardBinder(app.ha, entityWidgets, scope)
                binder = b
                PagerView(this, theme, pages).also {
                    pager = it
                    it.onVisible = { first, last -> if (started) b.show(first, last) }
                    if (started) b.show(it.firstVisible, it.lastVisible)
                }
            }
            is Screen.Failed -> MessageView(this, theme, "config couldn't be loaded", screen.reason)
        }
        setContentView(view)
        if (!reportedDrawn) {
            reportedDrawn = true
            view.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    view.viewTreeObserver.removeOnPreDrawListener(this)
                    view.post {
                        reportFullyDrawn()
                        // network waits until the cached dashboard is on screen
                        app.ha.connect()
                    }
                    return true
                }
            })
        }
    }
}
