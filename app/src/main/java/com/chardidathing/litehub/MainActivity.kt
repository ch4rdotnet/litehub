package com.chardidathing.litehub

import android.app.Activity
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.ViewTreeObserver
import com.chardidathing.litehub.ui.components.MessageView
import com.chardidathing.litehub.ui.components.PageView
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
    private val loader by lazy { DashboardLoader(applicationContext) }
    private var loading: Job? = null
    private var reportedDrawn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        load()
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
        // the window background is the dashboard background, so nothing else has to paint it
        window.setBackgroundDrawable(ColorDrawable(theme.colors.background))
        val view = when (screen) {
            is Screen.Ready -> PageView(this, screen.page.columns, screen.page.rows, theme.spacing.m).apply {
                for (p in screen.page.widgets) addWidget(WidgetCatalog.create(context, theme, p), p.x, p.y, p.w, p.h)
            }
            is Screen.Failed -> MessageView(this, theme, "config couldn't be loaded", screen.reason)
        }
        setContentView(view)
        if (!reportedDrawn) {
            reportedDrawn = true
            view.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    view.viewTreeObserver.removeOnPreDrawListener(this)
                    view.post { reportFullyDrawn() }
                    return true
                }
            })
        }
    }
}
