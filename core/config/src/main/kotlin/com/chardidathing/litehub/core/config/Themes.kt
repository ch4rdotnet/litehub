package com.chardidathing.litehub.core.config

import com.chardidathing.litehub.core.model.Theme
import com.chardidathing.litehub.core.model.ThemeMode
import com.chardidathing.litehub.core.model.ThemeSelection
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

class Themes(presets: List<Theme>, user: List<JsonObject>) {

    private val byId: Map<String, Theme>

    init {
        val resolved = LinkedHashMap<String, Theme>()
        presets.forEach { resolved[it.id] = it }
        if (user.size > MAX_THEMES) throw ConfigException("there are more than $MAX_THEMES themes")
        val pending = user.associateBy { it.string("id") ?: throw ConfigException("a theme is missing its id") }
        if (pending.size != user.size) throw ConfigException("two themes share an id")
        pending.keys.forEach { resolve(it, pending, resolved, mutableSetOf()) }
        byId = resolved
    }

    operator fun get(id: String): Theme = byId[id] ?: throw ConfigException("theme \"$id\" doesn't exist")

    fun select(selection: ThemeSelection, systemDark: Boolean): Theme {
        val dark = when (selection.mode) {
            ThemeMode.SYSTEM -> systemDark
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
        return get(if (dark) selection.dark else selection.light)
    }

    // user themes can extend presets or each other, in any order in the file
    private fun resolve(
        id: String,
        pending: Map<String, JsonObject>,
        resolved: MutableMap<String, Theme>,
        visiting: MutableSet<String>,
    ): Theme {
        resolved[id]?.let { return it }
        val overrides = pending.getValue(id)
        if (!visiting.add(id)) throw ConfigException("theme \"$id\" extends itself")
        // resolving recurses once per link, a chain this long is a config made to overflow it
        if (visiting.size > MAX_CHAIN) throw ConfigException("theme \"$id\" builds on more than $MAX_CHAIN other themes")
        val parentId = overrides.string("extends") ?: throw ConfigException("theme \"$id\" needs an extends")
        val parent = when {
            parentId in pending -> resolve(parentId, pending, resolved, visiting)
            else -> resolved[parentId] ?: throw ConfigException("theme \"$id\" extends \"$parentId\", which doesn't exist")
        }
        val base = ConfigCodec.json.encodeToJsonElement(Theme.serializer(), parent).jsonObject
        // name isn't inherited, an unnamed theme is called by its id
        val own = JsonObject(overrides - "extends" + ("name" to (overrides["name"] ?: JsonPrimitive(id))))
        val merged = merge(base, own)
        val theme = try {
            ConfigCodec.json.decodeFromJsonElement(Theme.serializer(), merged)
        } catch (e: SerializationException) {
            throw ConfigException("theme \"$id\" isn't valid, ${e.summary()}", e)
        } catch (e: IllegalArgumentException) {
            throw ConfigException("theme \"$id\" isn't valid, ${e.summary()}", e)
        }
        resolved[id] = theme
        return theme
    }

    private fun merge(base: JsonObject, overrides: JsonObject): JsonObject {
        val out = base.toMutableMap()
        for ((key, value) in overrides) {
            val existing = out[key]
            out[key] = if (existing is JsonObject && value is JsonObject) merge(existing, value) else value
        }
        return JsonObject(out)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private companion object {
        const val MAX_THEMES = 64
        const val MAX_CHAIN = 16
    }
}
