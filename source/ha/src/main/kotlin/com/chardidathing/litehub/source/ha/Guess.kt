package com.chardidathing.litehub.source.ha

import com.chardidathing.litehub.core.model.Entity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

// what an entity should look like once a service call lands, drawn until ha sends the real
// thing. only what the call makes obvious, null when there's nothing safe to guess
internal object Guess {

    private const val MAX_BRIGHTNESS = 255
    private const val PERCENT = 100.0

    fun after(entity: Entity, service: String, data: JsonObject?): Entity? = when (entity.domain) {
        "light" -> light(entity, service, data)
        else -> null
    }

    private fun light(e: Entity, service: String, data: JsonObject?): Entity? {
        if (service == "turn_off") return e.copy(state = "off")
        if (service != "turn_on") return null
        val attributes = e.attributes.toMutableMap()
        (data?.get("brightness_pct") as? JsonPrimitive)?.doubleOrNull?.let {
            attributes["brightness"] = JsonPrimitive(Math.round(it / PERCENT * MAX_BRIGHTNESS))
        }
        data?.get("color_temp_kelvin")?.let {
            attributes["color_temp_kelvin"] = it
            attributes["color_mode"] = JsonPrimitive("color_temp")
        }
        data?.get("hs_color")?.let {
            attributes["hs_color"] = it
            attributes["color_mode"] = JsonPrimitive("hs")
        }
        data?.get("effect")?.let { attributes["effect"] = it }
        return e.copy(state = "on", attributes = JsonObject(attributes))
    }
}
