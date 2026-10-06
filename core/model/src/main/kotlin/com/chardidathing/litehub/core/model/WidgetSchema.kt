package com.chardidathing.litehub.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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

// domains limits an entity field to those ha domains, empty means any. min and max bound a
// number, options are a choice's values. showIf hides the field unless every key there holds
// that value (as text, so a toggle is "true" or "false")
@Serializable
data class SchemaField(
    val key: String,
    val label: String,
    val kind: FieldKind,
    val required: Boolean = false,
    val domains: List<String> = emptyList(),
    val default: JsonElement? = null,
    val min: Double? = null,
    val max: Double? = null,
    val options: List<Choice> = emptyList(),
    val showIf: Map<String, String> = emptyMap(),
)

fun SchemaField.shownWith(values: Map<String, JsonElement>): Boolean =
    showIf.all { (key, want) -> (values[key] as? JsonPrimitive)?.contentOrNull == want }

@Serializable
data class Choice(val value: String, val label: String)

// a group of the hub's own settings, read by both settings screens (on device and web). a section
// with items is a list (calendars, feeds), its value is an array of objects with those fields.
// actions are buttons that fill fields in from somewhere else (ha's home location)
@Serializable
data class SettingsSection(
    val id: String,
    val name: String,
    val fields: List<SchemaField> = emptyList(),
    val items: List<SchemaField>? = null,
    val itemName: String? = null,
    val actions: List<Choice> = emptyList(),
)

@Serializable
enum class FieldKind {
    @SerialName("text") TEXT,
    @SerialName("number") NUMBER,
    @SerialName("entity") ENTITY,
    @SerialName("calendars") CALENDARS,
    @SerialName("feeds") FEEDS,
    @SerialName("toggle") TOGGLE,
    @SerialName("choice") CHOICE,
    // "HH:mm"
    @SerialName("time") TIME,
    @SerialName("entities") ENTITIES,
    // never sent back out, left blank it keeps what's there
    @SerialName("secret") SECRET,
    // "#rrggbb", blank for automatic
    @SerialName("color") COLOR,
}
