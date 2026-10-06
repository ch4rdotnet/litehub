package com.chardidathing.litehub.ui.widgets

import com.chardidathing.litehub.core.model.Entity
import com.chardidathing.litehub.ui.components.Icons

// picks an mdi icon the way the ha frontend does, the config's icon, then the entity's,
// then a default for its device class or domain. anything not bundled falls through
internal object EntityIcons {

    private const val FALLBACK = "bookmark"

    fun name(entityId: String, entity: Entity?, override: String?, icons: Icons): String {
        listOf(override, entity?.attribute("icon"))
            .mapNotNull { it?.removePrefix("mdi:") }
            .firstOrNull(icons::has)
            ?.let { return it }
        return default(entityId.substringBefore('.'), entity).takeIf(icons::has) ?: FALLBACK
    }

    private fun default(domain: String, entity: Entity?): String {
        val state = entity?.state
        val on = state == "on"
        val deviceClass = entity?.attribute("device_class")
        return when (domain) {
            "light" -> if (on) "lightbulb" else "lightbulb-off"
            "switch" -> if (on) "toggle-switch-variant" else "toggle-switch-variant-off"
            "input_boolean" -> if (on) "toggle-switch-outline" else "toggle-switch-off-outline"
            "fan" -> if (on) "fan" else "fan-off"
            "automation" -> if (on) "robot" else "robot-off"
            "lock" -> if (state == "locked") "lock" else "lock-open"
            "cover" -> if (state == "open" || state == "opening") "window-open" else "window-closed"
            "binary_sensor" -> binarySensor(deviceClass, on)
            "sensor" -> sensor(deviceClass)
            "climate" -> "thermostat"
            "media_player" -> "cast"
            "person", "device_tracker" -> "account"
            "scene" -> "palette"
            "script" -> "script-text"
            "sun" -> if (state == "above_horizon") "white-balance-sunny" else "weather-night"
            "weather" -> "weather-partly-cloudy"
            "camera" -> "video"
            "vacuum" -> "robot-vacuum"
            "calendar" -> "calendar"
            "todo" -> "clipboard-list"
            "alarm_control_panel" -> when (state) {
                "disarmed" -> "shield-off"
                "armed_home" -> "shield-home"
                "armed_away", "armed_night" -> "shield-lock"
                else -> "shield"
            }
            "siren" -> "bullhorn"
            "humidifier" -> "air-humidifier"
            "input_number", "number" -> "ray-vertex"
            "input_select", "select" -> "format-list-bulleted"
            "button", "input_button" -> "button-pointer"
            "update" -> "package-up"
            "zone" -> "map-marker-radius"
            else -> FALLBACK
        }
    }

    private fun binarySensor(deviceClass: String?, on: Boolean) = when (deviceClass) {
        "door" -> if (on) "door-open" else "door-closed"
        "garage_door" -> if (on) "garage-open" else "garage"
        "window" -> if (on) "window-open" else "window-closed"
        "motion" -> if (on) "motion-sensor" else "motion-sensor-off"
        "occupancy", "presence" -> if (on) "home" else "home-outline"
        "smoke" -> "smoke-detector"
        "moisture" -> if (on) "water-alert" else "water"
        "connectivity" -> if (on) "check-network-outline" else "close-network-outline"
        "plug", "power" -> if (on) "power-plug" else "power-plug-off"
        "vibration" -> "vibrate"
        "problem" -> "alert-circle"
        // on means unlocked for a lock binary sensor
        "lock" -> if (on) "lock-open" else "lock"
        else -> if (on) "checkbox-marked-circle" else "radiobox-blank"
    }

    private fun sensor(deviceClass: String?) = when (deviceClass) {
        "temperature" -> "thermometer"
        "humidity" -> "water-percent"
        "battery" -> "battery"
        "power" -> "flash"
        "energy" -> "lightning-bolt"
        "illuminance" -> "brightness-5"
        "pressure", "atmospheric_pressure" -> "gauge"
        "carbon_dioxide" -> "molecule-co2"
        "pm25", "pm10", "aqi" -> "air-filter"
        "voltage" -> "sine-wave"
        "current" -> "current-ac"
        "signal_strength" -> "wifi"
        "timestamp" -> "clock"
        "gas" -> "meter-gas"
        "water", "moisture" -> "water"
        "speed" -> "speedometer"
        "distance" -> "arrow-left-right"
        "weight" -> "weight"
        "duration" -> "progress-clock"
        "monetary" -> "cash"
        else -> "eye"
    }
}
