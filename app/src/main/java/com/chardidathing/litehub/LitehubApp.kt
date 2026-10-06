package com.chardidathing.litehub

import android.app.Application
import com.chardidathing.litehub.core.config.ConfigException
import com.chardidathing.litehub.core.config.SettingsCodec
import com.chardidathing.litehub.core.config.SourcesCodec
import com.chardidathing.litehub.core.model.DeviceSettings
import com.chardidathing.litehub.core.model.Sources
import com.chardidathing.litehub.source.calendar.CalendarRepository
import com.chardidathing.litehub.source.calendar.CalendarStore
import com.chardidathing.litehub.source.fetch.Fetcher
import com.chardidathing.litehub.source.feed.FeedRepository
import com.chardidathing.litehub.source.feed.FeedStore
import com.chardidathing.litehub.source.ha.EntityCache
import com.chardidathing.litehub.source.ha.EntityRepository
import com.chardidathing.litehub.source.ha.HaCredentials
import com.chardidathing.litehub.ui.components.Icons
import com.chardidathing.litehub.ui.tokens.Fonts
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException

// hand wired singletons. every one of these reads disk, so first touch them off the main thread.
// the source backed ones can be dropped and rebuilt by reload() after their files change
class LitehubApp : Application() {

    val fonts by lazy { Fonts(assets) }

    val icons by lazy { Icons(assets) }

    // one connection pool for everything, the disk cache is what turns unchanged feeds into 304s
    val http by lazy { OkHttpClient.Builder().cache(Cache(File(cacheDir, "http"), HTTP_CACHE_BYTES)).build() }

    private var _ha: EntityRepository? = null
    private var _sources: Result<Sources>? = null
    private var _calendars: CalendarRepository? = null
    private var _feeds: FeedRepository? = null
    private var _settings: DeviceSettings? = null

    override fun onCreate() {
        super.onCreate()
        Watchdog.install(this)
    }

    val ha: EntityRepository
        @Synchronized get() = _ha ?: EntityRepository(
            credentials = HaCredentials.load(File(filesDir, HA_FILE)),
            http = http,
            cache = EntityCache(this),
        ).also { _ha = it }

    // no file is no sources, a broken one is a failure the widgets show
    val sources: Result<Sources>
        @Synchronized get() = _sources ?: readSources().also { _sources = it }

    val calendars: CalendarRepository
        @Synchronized get() = _calendars
            ?: CalendarRepository(sources.getOrNull()?.calendars.orEmpty(), Fetcher(http), CalendarStore(this), ha).also { _calendars = it }

    val feeds: FeedRepository
        @Synchronized get() = _feeds
            ?: FeedRepository(sources.getOrNull()?.feeds.orEmpty(), Fetcher(http), FeedStore(this)).also { _feeds = it }

    // a broken settings.json falls back to defaults, the admin menu must always be reachable
    val settings: DeviceSettings
        @Synchronized get() = _settings ?: readSettings().also { _settings = it }

    @Synchronized
    fun saveSettings(settings: DeviceSettings) {
        File(filesDir, SETTINGS_FILE).writeAtomic(SettingsCodec.encode(settings))
        _settings = settings
    }

    // forget everything read from ha.json, sources.json and settings.json, next use reads again
    @Synchronized
    fun reload() {
        _ha?.close()
        _calendars?.close()
        _feeds?.close()
        _ha = null
        _calendars = null
        _feeds = null
        _sources = null
        _settings = null
    }

    private fun readSources(): Result<Sources> {
        val file = File(filesDir, SOURCES_FILE)
        if (!file.exists()) return Result.success(Sources(SourcesCodec.VERSION))
        return try {
            Result.success(SourcesCodec.decode(file.readText()))
        } catch (e: ConfigException) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(IOException("couldn't read sources.json", e))
        }
    }

    private fun readSettings(): DeviceSettings {
        val file = File(filesDir, SETTINGS_FILE)
        if (!file.exists()) return SettingsCodec.defaults
        return try {
            SettingsCodec.decode(file.readText())
        } catch (e: ConfigException) {
            SettingsCodec.defaults
        } catch (e: IOException) {
            SettingsCodec.defaults
        }
    }

    companion object {
        const val HA_FILE = "ha.json"
        const val CONFIG_FILE = "config.json"
        const val PREVIOUS_CONFIG_FILE = "config.prev.json"
        const val SOURCES_FILE = "sources.json"
        const val SETTINGS_FILE = "settings.json"
        const val HTTP_CACHE_BYTES = 10L * 1024 * 1024
    }
}
