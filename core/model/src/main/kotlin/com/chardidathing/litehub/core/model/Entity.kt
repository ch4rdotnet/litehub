package com.chardidathing.litehub.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// one home assistant entity as last seen, lastChanged is epoch seconds like ha sends it
@Serializable
data class Entity(
    val id: String,
    val state: String,
    val attributes: JsonObject = JsonObject(emptyMap()),
    val lastChanged: Double = 0.0,
) {
    val domain: String get() = id.substringBefore('.')

    fun attribute(key: String): String? = (attributes[key] as? JsonPrimitive)?.contentOrNull
}

// what a widget gets to draw for one entity, failures are never folded into an empty state
sealed interface EntitySnapshot {
    data object Connecting : EntitySnapshot

    data class Live(val entity: Entity) : EntitySnapshot

    // cached state while the connection is down, reason says why
    data class Stale(val entity: Entity, val reason: String) : EntitySnapshot

    data object NotFound : EntitySnapshot

    data class Failed(val reason: String) : EntitySnapshot
}
