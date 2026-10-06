package com.chardidathing.litehub.core.config

import com.chardidathing.litehub.core.model.DeviceSettings
import com.chardidathing.litehub.core.model.ScreensaverSettings
import java.time.LocalTime
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

object SettingsCodec {

    const val VERSION = 1

    val defaults = DeviceSettings(VERSION)

    fun decode(text: String): DeviceSettings {
        val settings = try {
            ConfigCodec.json.decodeFromString(DeviceSettings.serializer(), text)
        } catch (e: SerializationException) {
            throw ConfigException("settings.json isn't valid, ${e.summary()}", e)
        } catch (e: IllegalArgumentException) {
            throw ConfigException("settings.json isn't valid, ${e.summary()}", e)
        }
        if (settings.version > VERSION) {
            throw ConfigException("settings.json version ${settings.version} is newer than this app supports ($VERSION)")
        }
        validate(settings.screensaver)
        if (settings.dlna.port !in PORTS) throw ConfigException("dlna port is 1024 to 65535")
        if (settings.dlna.port == settings.web.port) throw ConfigException("dlna and the web editor can't share a port")
        return settings
    }

    // just the screensaver block, for the web editor, checked like a whole file would be
    fun decodeScreensaver(text: String): ScreensaverSettings {
        val s = try {
            ConfigCodec.json.decodeFromString(ScreensaverSettings.serializer(), text)
        } catch (e: SerializationException) {
            throw ConfigException("screensaver settings aren't valid, ${e.summary()}", e)
        } catch (e: IllegalArgumentException) {
            throw ConfigException("screensaver settings aren't valid, ${e.summary()}", e)
        }
        validate(s)
        return s
    }

    // defaults written out, the web editor shows every setting there is to change
    fun encodeScreensaver(s: ScreensaverSettings): String = withDefaults.encodeToString(ScreensaverSettings.serializer(), s)

    private val withDefaults = Json(ConfigCodec.json) { encodeDefaults = true }

    // "HH:mm", null when it isn't one
    fun time(text: String): LocalTime? = runCatching { LocalTime.parse(text) }.getOrNull()

    private fun validate(s: ScreensaverSettings) {
        if (s.idleMinutes < 1) throw ConfigException("the screensaver needs at least a minute of idle")
        if (s.photoSeconds < MIN_PHOTO_SECONDS) throw ConfigException("photos change at most every $MIN_PHOTO_SECONDS seconds")
        s.photos?.let { p ->
            if (listOfNotNull(p.folder, p.immich, p.haMedia).size != 1) {
                throw ConfigException("photos needs exactly one of folder, immich or haMedia")
            }
        }
        s.night?.let { n ->
            if (time(n.start) == null || time(n.end) == null) throw ConfigException("night start and end are times like 22:00")
            if (n.dimPercent !in 1..100) throw ConfigException("night dimPercent is 1 to 100")
        }
    }

    // anything faster is a slideshow, not a frame, and costs a decode every few seconds
    private const val MIN_PHOTO_SECONDS = 5
    private val PORTS = 1024..65535

    fun encode(settings: DeviceSettings): String = ConfigCodec.json.encodeToString(DeviceSettings.serializer(), settings)
}
