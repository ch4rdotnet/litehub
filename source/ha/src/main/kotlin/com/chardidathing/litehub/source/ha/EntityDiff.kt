package com.chardidathing.litehub.source.ha

import com.chardidathing.litehub.core.model.Entity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

// applies a subscribe_entities event. ha sends it compressed, "a" is added in full,
// "c" is changed with "+" for new values and "-" for dropped attributes, "r" is removed
internal object EntityDiff {

    class Result(val added: Set<String>, val changed: Set<String>, val removed: Set<String>)

    fun apply(entities: MutableMap<String, Entity>, body: JsonObject): Result {
        val added = HashSet<String>()
        val changed = HashSet<String>()
        val removed = HashSet<String>()

        (body["a"] as? JsonObject)?.forEach { (id, value) ->
            val s = value as? JsonObject ?: return@forEach
            entities[id] = Entity(
                id = id,
                state = s.string("s").orEmpty(),
                attributes = s["a"] as? JsonObject ?: JsonObject(emptyMap()),
                lastChanged = s.double("lc") ?: 0.0,
            )
            added += id
        }

        (body["c"] as? JsonObject)?.forEach { (id, value) ->
            val diff = value as? JsonObject ?: return@forEach
            val current = entities[id] ?: return@forEach
            val plus = diff["+"] as? JsonObject
            val minus = (diff["-"] as? JsonObject)?.get("a") as? JsonArray
            val attributes = current.attributes.toMutableMap()
            (plus?.get("a") as? JsonObject)?.let { attributes.putAll(it) }
            minus?.forEach { key -> (key as? JsonPrimitive)?.contentOrNull?.let(attributes::remove) }
            entities[id] = current.copy(
                state = plus?.string("s") ?: current.state,
                attributes = JsonObject(attributes),
                lastChanged = plus?.double("lc") ?: current.lastChanged,
            )
            changed += id
        }

        (body["r"] as? JsonArray)?.forEach { value ->
            val id = (value as? JsonPrimitive)?.contentOrNull ?: return@forEach
            if (entities.remove(id) != null) removed += id
        }

        return Result(added, changed, removed)
    }

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.double(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull
}
