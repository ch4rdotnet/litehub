package com.chardidathing.litehub.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// how a widget type is configured, declared once and read by both editors (on device and web)
@Serializable
data class WidgetSchema(
    val type: String,
    val name: String,
    // size a new one is placed at, in cells
    val w: Int,
    val h: Int,
    val fields: List<SchemaField>,
)

// domains limits an entity field to those ha domains, empty means any
@Serializable
data class SchemaField(
    val key: String,
    val label: String,
    val kind: FieldKind,
    val required: Boolean = false,
    val domains: List<String> = emptyList(),
    val default: JsonElement? = null,
)

@Serializable
enum class FieldKind {
    @SerialName("text") TEXT,
    @SerialName("number") NUMBER,
    @SerialName("entity") ENTITY,
    @SerialName("calendars") CALENDARS,
    @SerialName("feeds") FEEDS,
}
