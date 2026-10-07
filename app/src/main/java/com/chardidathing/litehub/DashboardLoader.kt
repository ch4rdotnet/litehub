package com.chardidathing.litehub

import android.os.Build
import android.util.DisplayMetrics
import com.chardidathing.litehub.core.config.ConfigCodec
import com.chardidathing.litehub.core.config.ConfigException
import com.chardidathing.litehub.core.config.SourcesCodec
import com.chardidathing.litehub.core.config.Themes
import com.chardidathing.litehub.core.model.Config
import com.chardidathing.litehub.core.model.Page
import com.chardidathing.litehub.core.model.Theme
import com.chardidathing.litehub.source.ha.HaCredentials
import com.chardidathing.litehub.ui.components.Icons
import com.chardidathing.litehub.ui.tokens.Presets
import com.chardidathing.litehub.ui.tokens.ResolvedTheme
import com.chardidathing.litehub.ui.widgets.Legend
import com.chardidathing.litehub.ui.widgets.WidgetCatalog

import java.io.File
import java.io.IOException

sealed interface Screen {
    val theme: ResolvedTheme

    class Ready(override val theme: ResolvedTheme, val config: Config, val pages: List<Page>, val icons: Icons, val legend: Legend) : Screen

    class Failed(override val theme: ResolvedTheme, val reason: String) : Screen
}

// does all the disk work for a launch, call it off the main thread
class DashboardLoader(private val app: LitehubApp) {

    // safe skips the config and every source, it's what a crash loop gets
    suspend fun load(systemDark: Boolean, metrics: DisplayMetrics, safe: Boolean): Screen =
        if (safe) failed(SAFE_MODE, systemDark, metrics) else loadDashboard(systemDark, metrics)

    private suspend fun loadDashboard(systemDark: Boolean, metrics: DisplayMetrics): Screen = try {
        importDropped()
        val text = File(app.filesDir, LitehubApp.CONFIG_FILE).takeIf { it.exists() }?.readText()
            ?: app.assets.open("default_config.json").bufferedReader().use { it.readText() }
        val config = ConfigCodec.decode(text)
        val dashboard = config.dashboards.first { it.id == config.activeDashboard }
        val theme = Themes(Presets.all, config.themes).select(dashboard.theme, systemDark)
        app.currentTheme = theme
        // every page, so a swipe lands on cached state rather than "connecting"
        val entityIds = dashboard.pages.flatMap { it.widgets }.mapNotNull(WidgetCatalog::entityId)
        if (entityIds.isNotEmpty()) app.ha.preload(entityIds)
        app.calendars.preload()
        app.feeds.preload()
        val weather = dashboard.pages.flatMap { it.widgets }.filter(WidgetCatalog::isWeather)
        val keys = weather.mapTo(HashSet()) { app.weather.key(WidgetCatalog.weatherEntity(it)) }
        // the screensaver's weather refreshes with the tiles', it's shown over this same dashboard
        app.settings.screensaver.weather?.let { keys += app.weather.key(it.entity) }
        app.weather.prepare(keys)
        Screen.Ready(ResolvedTheme(theme, metrics, app.fonts, DeviceTier.isLow(app)), config, dashboard.pages, app.icons, legend(theme))
    } catch (e: ConfigException) {
        failed(e.message.orEmpty(), systemDark, metrics)
    } catch (e: IOException) {
        failed("couldn't read config.json, ${e.message}", systemDark, metrics)
    }

    // adb can't reach private storage on a release build, so files are dropped in the app's
    // external folder and moved in here. the external copy goes, other apps can read it on old
    // android. before 11 other apps could write it too, so there the drop box is shut
    private fun importDropped() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val dropbox = app.getExternalFilesDir(null) ?: return
        for (name in listOf(LitehubApp.HA_FILE, LitehubApp.CONFIG_FILE, LitehubApp.SOURCES_FILE)) {
            val dropped = File(dropbox, name).takeIf { it.exists() } ?: continue
            // a file that wouldn't load stays where it was dropped, the working one is kept
            val problem = droppedProblem(name, dropped)
            if (problem != null) {
                AppLog.add("$name in the drop folder wasn't imported, $problem")
                continue
            }
            val partial = File(app.filesDir, "$name.partial")
            dropped.copyTo(partial, overwrite = true)
            partial.renameTo(File(app.filesDir, name))
            dropped.delete()
        }
    }

    private fun droppedProblem(name: String, file: File): String? = try {
        val text = file.readText()
        when (name) {
            LitehubApp.HA_FILE -> HaCredentials.load(file).exceptionOrNull()?.message
            LitehubApp.CONFIG_FILE -> {
                ConfigCodec.decode(text)
                null
            }
            else -> {
                SourcesCodec.decode(text)
                null
            }
        }
    } catch (e: ConfigException) {
        e.message
    } catch (e: IOException) {
        "it couldn't be read"
    }

    // calendars without their own colour take the theme palette in order
    fun legend(theme: Theme): Legend {
        val sources = app.sources.getOrNull()
        val calendars = sources?.calendars.orEmpty()
        val feeds = sources?.feeds.orEmpty()
        return Legend(
            names = calendars.associate { it.id to it.name } + feeds.associate { it.id to it.name },
            colors = calendars.mapIndexed { i, c -> c.id to (c.color ?: theme.palette[i % theme.palette.size]) }.toMap(),
            calendars = calendars.map { it.id },
            feeds = feeds.map { it.id },
            problem = app.sources.exceptionOrNull()?.message,
        )
    }

    companion object {
        const val SAFE_MODE = "litehub crashed 3 times in a minute, so it isn't loading the dashboard. hold anywhere for the menu, reload tries again"
    }

    private fun failed(reason: String, systemDark: Boolean, metrics: DisplayMetrics): Screen {
        val theme = if (systemDark) Presets.fallbackDark else Presets.fallbackLight
        return Screen.Failed(ResolvedTheme(theme, metrics, app.fonts, DeviceTier.isLow(app)), reason)
    }
}
