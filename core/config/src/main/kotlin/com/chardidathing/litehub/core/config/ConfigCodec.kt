package com.chardidathing.litehub.core.config

import com.chardidathing.litehub.core.model.Config
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

object ConfigCodec {

    const val VERSION = 1

    // strict on purpose, an unknown key in a hand edited file is usually a typo
    internal val json = Json { prettyPrint = true }

    fun decode(text: String): Config {
        val config = try {
            json.decodeFromString<Config>(text)
        } catch (e: SerializationException) {
            throw ConfigException("config isn't valid, ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw ConfigException("config isn't valid, ${e.message}", e)
        }
        validate(config)
        return config
    }

    fun encode(config: Config): String = json.encodeToString(Config.serializer(), config)

    private fun validate(config: Config) {
        if (config.version > VERSION) {
            throw ConfigException("config version ${config.version} is newer than this app supports ($VERSION)")
        }
        if (config.dashboards.none { it.id == config.activeDashboard }) {
            throw ConfigException("active dashboard \"${config.activeDashboard}\" doesn't exist")
        }
        for (dashboard in config.dashboards) {
            if (dashboard.pages.isEmpty()) {
                throw ConfigException("dashboard \"${dashboard.id}\" has no pages")
            }
            for (page in dashboard.pages) {
                if (page.columns < 1 || page.rows < 1) {
                    throw ConfigException("page \"${page.id}\" needs at least one column and row")
                }
                for (w in page.widgets) {
                    val fits = w.x >= 0 && w.y >= 0 && w.w >= 1 && w.h >= 1 &&
                        w.x + w.w <= page.columns && w.y + w.h <= page.rows
                    if (!fits) {
                        throw ConfigException("${w.type} at ${w.x},${w.y} (${w.w}x${w.h}) is outside page \"${page.id}\"")
                    }
                }
            }
        }
    }
}
