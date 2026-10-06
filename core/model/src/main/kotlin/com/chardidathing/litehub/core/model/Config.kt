package com.chardidathing.litehub.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class Config(
    val version: Int,
    val activeDashboard: String,
    val dashboards: List<Dashboard>,
    // user themes, each one is partial and laid over the theme it extends
    val themes: List<JsonObject> = emptyList(),
)

@Serializable
data class Dashboard(
    val id: String,
    val name: String,
    val theme: ThemeSelection,
    val pages: List<Page>,
)

// compact pages draw with the theme one step smaller (spacing and type), for finer grids
@Serializable
data class Page(
    val id: String,
    val columns: Int,
    val rows: Int,
    val widgets: List<Placement>,
    val density: Density = Density.COMFORTABLE,
)

@Serializable
enum class Density {
    @SerialName("comfortable") COMFORTABLE,
    @SerialName("compact") COMPACT,
}

// x, y, w, h are in grid cells, config is whatever the widget type's schema says
@Serializable
data class Placement(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val type: String,
    val config: JsonObject = JsonObject(emptyMap()),
)
