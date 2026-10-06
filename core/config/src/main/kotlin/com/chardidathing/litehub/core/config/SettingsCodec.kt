package com.chardidathing.litehub.core.config

import com.chardidathing.litehub.core.model.DeviceSettings
import java.time.LocalTime
import kotlinx.serialization.SerializationException

object SettingsCodec {

    const val VERSION = 1

    val defaults = DeviceSettings(VERSION)

    // a hand edited file gets the same checks as the settings screens
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
        SettingsForm.check(settings)
        return settings
    }

    // "HH:mm", null when it isn't one
    fun time(text: String): LocalTime? = runCatching { LocalTime.parse(text) }.getOrNull()

    fun encode(settings: DeviceSettings): String = ConfigCodec.json.encodeToString(DeviceSettings.serializer(), settings)
}
