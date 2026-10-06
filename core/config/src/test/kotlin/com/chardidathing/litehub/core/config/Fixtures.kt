package com.chardidathing.litehub.core.config

import com.chardidathing.litehub.core.model.Theme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

object Fixtures {
    fun text(name: String): String =
        checkNotNull(javaClass.classLoader.getResource(name)) { "missing fixture $name" }.readText()

    val config: String get() = text("config.json")

    val base: Theme get() = Json.decodeFromString(Theme.serializer(), text("base-theme.json"))

    fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
}
