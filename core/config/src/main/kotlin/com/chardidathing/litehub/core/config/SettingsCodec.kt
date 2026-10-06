package com.chardidathing.litehub.core.config

import com.chardidathing.litehub.core.model.DeviceSettings
import kotlinx.serialization.SerializationException

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
        return settings
    }

    fun encode(settings: DeviceSettings): String = ConfigCodec.json.encodeToString(DeviceSettings.serializer(), settings)
}
