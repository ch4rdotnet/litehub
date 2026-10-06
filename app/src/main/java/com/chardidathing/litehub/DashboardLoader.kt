package com.chardidathing.litehub

import android.content.Context
import android.util.DisplayMetrics
import com.chardidathing.litehub.core.config.ConfigCodec
import com.chardidathing.litehub.core.config.ConfigException
import com.chardidathing.litehub.core.config.Themes
import com.chardidathing.litehub.core.model.Page
import com.chardidathing.litehub.ui.tokens.Fonts
import com.chardidathing.litehub.ui.tokens.Presets
import com.chardidathing.litehub.ui.tokens.ResolvedTheme
import java.io.File
import java.io.IOException

sealed interface Screen {
    val theme: ResolvedTheme

    class Ready(override val theme: ResolvedTheme, val page: Page) : Screen

    class Failed(override val theme: ResolvedTheme, val reason: String) : Screen
}

// does all the disk work for a launch, call it off the main thread
class DashboardLoader(private val context: Context) {

    private val fonts = Fonts(context.assets)

    // the user's file wins, the bundled one is only for a fresh install
    private val userConfig get() = File(context.filesDir, "config.json")

    fun load(systemDark: Boolean, metrics: DisplayMetrics): Screen = try {
        val text = userConfig.takeIf { it.exists() }?.readText()
            ?: context.assets.open("default_config.json").bufferedReader().use { it.readText() }
        val config = ConfigCodec.decode(text)
        val dashboard = config.dashboards.first { it.id == config.activeDashboard }
        val theme = Themes(Presets.all, config.themes).select(dashboard.theme, systemDark)
        Screen.Ready(ResolvedTheme(theme, metrics, fonts), dashboard.pages.first())
    } catch (e: ConfigException) {
        failed(e.message.orEmpty(), systemDark, metrics)
    } catch (e: IOException) {
        failed("couldn't read config.json, ${e.message}", systemDark, metrics)
    }

    private fun failed(reason: String, systemDark: Boolean, metrics: DisplayMetrics): Screen {
        val theme = if (systemDark) Presets.fallbackDark else Presets.fallbackLight
        return Screen.Failed(ResolvedTheme(theme, metrics, fonts), reason)
    }
}
