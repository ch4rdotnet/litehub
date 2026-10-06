package com.chardidathing.litehub.ui.widgets

import com.chardidathing.litehub.core.model.FieldKind
import com.chardidathing.litehub.core.model.SchemaField
import com.chardidathing.litehub.core.model.WidgetSchema
import kotlinx.serialization.json.JsonPrimitive

// every widget type's settings, in the order the add widget list shows them. the keys match
// the config classes the catalog decodes (EntityConfig, AgendaConfig and the rest)
object WidgetSchemas {

    val all = listOf(
        WidgetSchema(
            "entity", "entity tile", 1, 1,
            listOf(
                SchemaField("entity", "entity", FieldKind.ENTITY, required = true),
                SchemaField("name", "name", FieldKind.TEXT),
                SchemaField("icon", "icon (mdi:name)", FieldKind.TEXT),
            ),
        ),
        WidgetSchema(
            "sensor", "sensor value", 1, 1,
            listOf(
                SchemaField("entity", "entity", FieldKind.ENTITY, required = true, domains = listOf("sensor", "binary_sensor", "input_number", "number", "counter")),
                SchemaField("name", "name", FieldKind.TEXT),
                SchemaField("icon", "icon (mdi:name)", FieldKind.TEXT),
            ),
        ),
        WidgetSchema(
            "agenda", "agenda", 1, 2,
            listOf(
                SchemaField("title", "title", FieldKind.TEXT),
                SchemaField("calendars", "calendars (none picked shows all)", FieldKind.CALENDARS),
                SchemaField("days", "days ahead", FieldKind.NUMBER, default = JsonPrimitive(AgendaConfig().days)),
            ),
        ),
        WidgetSchema("month", "month", 2, 2, listOf(SchemaField("calendars", "calendars (none picked shows all)", FieldKind.CALENDARS))),
        WidgetSchema(
            "headlines", "headlines", 2, 1,
            listOf(
                SchemaField("title", "title", FieldKind.TEXT),
                SchemaField("feeds", "feeds (none picked shows all)", FieldKind.FEEDS),
            ),
        ),
        WidgetSchema("placeholder", "placeholder", 1, 1, listOf(SchemaField("title", "title", FieldKind.TEXT))),
    )

    fun of(type: String): WidgetSchema? = all.firstOrNull { it.type == type }
}
