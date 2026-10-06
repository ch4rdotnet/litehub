package com.chardidathing.litehub.source.ha

import com.chardidathing.litehub.core.model.EntityChoice
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// joins get_states with the entity, device and area registries, so the picker can show each
// entity's friendly name under its area. an entity's own area wins over its device's
internal object EntityCatalogue {

    fun build(states: JsonElement?, entities: JsonElement?, devices: JsonElement?, areas: JsonElement?): List<EntityChoice> {
        val areaNames = (areas as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .mapNotNull { a -> a.string("area_id")?.let { it to (a.string("name") ?: it) } }.toMap()
        val deviceArea = (devices as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .mapNotNull { d -> d.string("id")?.let { id -> d.string("area_id")?.let { id to it } } }.toMap()
        // list_for_display packs its rows with short keys, ei is the entity id, ai area, di device
        val registry = ((entities as? JsonObject)?.get("entities") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .mapNotNull { e -> e.string("ei")?.let { it to e } }.toMap()
        return (states as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { s ->
            val id = s.string("entity_id") ?: return@mapNotNull null
            val name = (s["attributes"] as? JsonObject)?.string("friendly_name") ?: id
            val row = registry[id]
            val areaId = row?.string("ai") ?: row?.string("di")?.let(deviceArea::get)
            EntityChoice(id, name, areaId?.let { areaNames[it] ?: it })
        }.sortedWith(compareBy({ it.area == null }, { it.area }, { it.name.lowercase() }))
    }

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
}
