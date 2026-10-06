package com.chardidathing.litehub.ui.widgets

import com.chardidathing.litehub.core.model.Entity
import kotlin.math.roundToInt

// how an entity's state reads on a tile, shared by every widget that shows one
object EntityStates {

    private val ACTIVE = setOf("on", "open", "opening", "unlocked", "playing", "home", "heat", "cool", "heat_cool", "auto")
    private const val MAX_BRIGHTNESS = 255f
    private const val PERCENT = 100f

    // on, open, home and the like, drawn in the primary colour
    fun isActive(entity: Entity) = entity.state in ACTIVE

    // ha states are already lowercase words, lights also get their brightness and sensors their unit
    fun describe(entity: Entity): String {
        val brightness = entity.attribute("brightness")?.toFloatOrNull()
        if (entity.domain == "light" && entity.state == "on" && brightness != null) return "on, ${(brightness / MAX_BRIGHTNESS * PERCENT).roundToInt()}%"
        val unit = entity.attribute("unit_of_measurement")
        return if (unit != null) "${entity.state} $unit" else entity.state
    }
}
