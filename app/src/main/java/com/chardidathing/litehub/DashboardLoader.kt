package com.chardidathing.litehub

import android.util.DisplayMetrics
import com.chardidathing.litehub.core.config.ConfigCodec
import com.chardidathing.litehub.core.config.ConfigException
import com.chardidathing.litehub.core.config.Themes
import com.chardidathing.litehub.core.model.Page
import com.chardidathing.litehub.ui.components.Icons
import com.chardidathing.litehub.ui.tokens.Presets
import com.chardidathing.litehub.ui.tokens.ResolvedTheme
import com.chardidathing.litehub.ui.widgets.WidgetCatalog
import java.io.File
import java.io.IOException

sealed interface Screen {
    val theme: ResolvedTheme

    class Ready(override val theme: ResolvedTheme, val pages: List<Page>, val icons: Icons) : Screen

    class Failed(override val theme: ResolvedTheme, val reason: String) : Screen
}

// does all the disk work for a launch, call it off the main thread
class DashboardLoader(private val app: LitehubApp) {

    suspend fun load(systemDark: Boolean, metrics: DisplayMetrics): Screen = try {
        importDropped()
        val text = File(app.filesDir, LitehubApp.CONFIG_FILE).takeIf { it.exists() }?.readText()
            ?: app.assets.open("default_config.json").bufferedReader().use { it.readText() }
        val config = ConfigCodec.decode(text)
        val dashboard = config.dashboards.first { it.id == config.activeDashboard }
        val theme = Themes(Presets.all, config.themes).select(dashboard.theme, systemDark)
        // every page, so a swipe lands on cached state rather than "connecting"
        val entityIds = dashboard.pages.flatMap { it.widgets }.mapNotNull(WidgetCatalog::entityId)
        if (entityIds.isNotEmpty()) app.ha.preload(entityIds)
        Screen.Ready(ResolvedTheme(theme, metrics, app.fonts, DeviceTier.isLow(app)), dashboard.pages, app.icons)
    } catch (e: ConfigException) {
        failed(e.message.orEmpty(), systemDark, metrics)
    } catch (e: IOException) {
        failed("couldn't read config.json, ${e.message}", systemDark, metrics)
    }

    // adb can't reach private storage on a release build, so files are dropped in the app's
    // external folder and moved in here. the external copy goes, other apps can read it on old android
    private fun importDropped() {
        val dropbox = app.getExternalFilesDir(null) ?: return
        for (name in listOf(LitehubApp.HA_FILE, LitehubApp.CONFIG_FILE, LitehubApp.SOURCES_FILE)) {
            val dropped = File(dropbox, name).takeIf { it.exists() } ?: continue
            val partial = File(app.filesDir, "$name.partial")
            dropped.copyTo(partial, overwrite = true)
            partial.renameTo(File(app.filesDir, name))
            dropped.delete()
        }
    }

    private fun failed(reason: String, systemDark: Boolean, metrics: DisplayMetrics): Screen {
        val theme = if (systemDark) Presets.fallbackDark else Presets.fallbackLight
        return Screen.Failed(ResolvedTheme(theme, metrics, app.fonts, DeviceTier.isLow(app)), reason)
    }
}
