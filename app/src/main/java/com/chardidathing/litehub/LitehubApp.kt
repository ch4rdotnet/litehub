package com.chardidathing.litehub

import android.app.Application
import android.os.Build
import com.chardidathing.litehub.core.config.ConfigCodec
import com.chardidathing.litehub.core.config.ConfigException
import com.chardidathing.litehub.core.config.HubSettings
import com.chardidathing.litehub.core.config.PinThrottle
import com.chardidathing.litehub.core.config.SavedSettings
import com.chardidathing.litehub.core.config.SettingsCodec
import com.chardidathing.litehub.core.config.SettingsForm
import com.chardidathing.litehub.core.config.SourcesCodec
import com.chardidathing.litehub.core.config.Themes
import com.chardidathing.litehub.core.model.DeviceSettings
import com.chardidathing.litehub.core.model.Sources
import com.chardidathing.litehub.source.calendar.CalendarRepository
import com.chardidathing.litehub.source.calendar.CalendarStore
import com.chardidathing.litehub.source.feed.FeedRepository
import com.chardidathing.litehub.source.feed.FeedStore
import com.chardidathing.litehub.source.fetch.Fetcher
import com.chardidathing.litehub.source.ha.EntityCache
import com.chardidathing.litehub.source.ha.EntityRepository
import com.chardidathing.litehub.source.ha.HaCredentials
import com.chardidathing.litehub.source.weather.WeatherRepository
import com.chardidathing.litehub.ui.components.Icons
import com.chardidathing.litehub.ui.tokens.Fonts
import com.chardidathing.litehub.ui.tokens.Presets
import java.io.File
import java.io.IOException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import okhttp3.Cache
import okhttp3.OkHttpClient

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
    private var _weather: WeatherRepository? = null
    private var _settings: DeviceSettings? = null

    override fun onCreate() {
        super.onCreate()
        Watchdog.install(this)
        AppLog.add("started, version ${BuildConfig.VERSION_NAME}")
    }

    val web by lazy { WebHost(this) }

    // for things that outlive the activity, the screensaver keeps watching while it's stopped
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)

    val screensaver by lazy { ScreensaverController(this, scope) }

    val notifications by lazy { NotificationCenter(filesDir, scope) { settings.notifications.keep } }

    val dlna by lazy { DlnaHost(this) }

    val status by lazy { HubStatus(this) }

    val updater by lazy { Updater(this) }

    val backups by lazy { Backups(this) }

    // wrong pin guesses, counted across the device's pin pad and the web login together
    val pinThrottle = PinThrottle()

    // what's on screen, for the status page
    data class HubState(val dashboard: String = "", val page: Int = 1, val screenOn: Boolean = true)

    @Volatile var hubState = HubState()

    // the theme on screen as the model, the web editor styles itself from it
    @Volatile var currentTheme: com.chardidathing.litehub.core.model.Theme? = null

    val ha: EntityRepository
        @Synchronized get() = _ha ?: EntityRepository(
            credentials = HaCredentials.load(File(filesDir, HA_FILE)),
            http = http,
            cache = EntityCache(this),
            log = AppLog::add,
        ).also { _ha = it }

    // no file is no sources, a broken one is a failure the widgets show
    val sources: Result<Sources>
        @Synchronized get() = _sources ?: readSources().also { _sources = it }

    val calendars: CalendarRepository
        @Synchronized get() = _calendars
            ?: CalendarRepository(sources.getOrNull()?.calendars.orEmpty(), Fetcher(http), CalendarStore(this), ha, log = AppLog::add).also { _calendars = it }

    val feeds: FeedRepository
        @Synchronized get() = _feeds
            ?: FeedRepository(sources.getOrNull()?.feeds.orEmpty(), Fetcher(http), FeedStore(this), log = AppLog::add).also { _feeds = it }

    val weather: WeatherRepository
        @Synchronized get() = _weather
            ?: WeatherRepository(this, ha, Fetcher(http), sources.getOrNull()?.location).also { _weather = it }

    // a broken settings.json falls back to defaults, the admin menu must always be reachable
    val settings: DeviceSettings
        @Synchronized get() = _settings ?: readSettings().also { _settings = it }

    @Synchronized
    fun saveSettings(settings: DeviceSettings) {
        File(filesDir, SETTINGS_FILE).writeAtomic(SettingsCodec.encode(settings))
        _settings = settings
    }

    // saves and tells everything that reads settings. blocking (the servers bind sockets), so not on main
    fun updateSettings(next: DeviceSettings) {
        saveSettings(next)
        web.apply()
        dlna.apply()
        scope.launch { screensaver.reload() }
    }

    // photos from the screensaver's source, for the screensaver and photo tiles. low ram devices keep
    // them, a screen sized photo is a few mb and the whole hub sits around 50
    fun photoFrame(): Result<PhotoFrame> {
        val s = settings.screensaver
        val photos = s.photos ?: return Result.failure(IOException("no photos set up, pick a source in the screensaver settings"))
        return Result.success(PhotoFrame(photos, s.photoRefreshMinutes * MINUTE_MS, ha, http))
    }

    // what the settings screens edit, read from disk. a broken sources.json shows as empty and is
    // only written over if the lists are actually changed. blocking
    fun hubSettings(): HubSettings {
        val ha = HaCredentials.load(File(filesDir, HA_FILE)).getOrNull()
        return HubSettings(settings, sources.getOrElse { Sources(SourcesCodec.VERSION) }, ha?.url, !ha?.token.isNullOrEmpty())
    }

    // writes whatever a save changed. true when sources or ha changed, the dashboard then has to
    // reload to pick them up. blocking
    fun saveAll(before: HubSettings, saved: SavedSettings): Boolean {
        if (saved.device != before.device) updateSettings(saved.device)
        // the screensaver's weather is fetched with the dashboard's, a new source means a reload
        var reload = saved.device.screensaver.weather != before.device.screensaver.weather
        if (saved.sources != before.sources) {
            File(filesDir, SOURCES_FILE).writeAtomic(SourcesCodec.encode(saved.sources))
            reload = true
        }
        saved.ha?.let { edit ->
            File(filesDir, HA_FILE).writeAtomic(Json.encodeToString(HaCredentials.serializer(), HaCredentials(edit.url, edit.token)))
            reload = true
        }
        return reload
    }

    // the buttons in settings sections that fill fields in, the same for the device and the web
    suspend fun settingsAction(id: String): Result<JsonObject> = when (id) {
        SettingsForm.HA_HOME -> ha.command("get_config", JsonObject(emptyMap())).mapCatching { result ->
            val config = result as? JsonObject ?: throw IOException("home assistant didn't send its config")
            val lat = (config["latitude"] as? JsonPrimitive)?.doubleOrNull ?: throw IOException("home assistant has no home location")
            val lon = (config["longitude"] as? JsonPrimitive)?.doubleOrNull ?: throw IOException("home assistant has no home location")
            JsonObject(mapOf("location.set" to JsonPrimitive(true), "location.latitude" to JsonPrimitive(lat), "location.longitude" to JsonPrimitive(lon)))
        }
        else -> Result.failure(IOException("there's no action called $id"))
    }

    // config.json as it would load, the codec doesn't know the built in themes so they're checked
    // here. throws ConfigException naming the problem
    fun checkConfig(text: String) {
        val config = ConfigCodec.decode(text)
        val themes = Themes(Presets.all, config.themes)
        config.dashboards.forEach { d ->
            themes[d.theme.light]
            themes[d.theme.dark]
        }
    }

    // forget everything read from ha.json, sources.json and settings.json, next use reads again
    @Synchronized
    fun reload() {
        _ha?.close()
        _calendars?.close()
        _feeds?.close()
        _weather?.close()
        _weather = null
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
        const val SOURCES_FILE = "sources.json"
        const val SETTINGS_FILE = "settings.json"
        const val HTTP_CACHE_BYTES = 10L * 1024 * 1024
        const val MINUTE_MS = 60_000L
        // what ha calls the hub, as a companion device and as a dlna renderer
        val DEVICE_NAME = "litehub ${Build.MODEL}".lowercase()
    }
}
