package com.chardidathing.litehub.ui.widgets

import com.chardidathing.litehub.core.model.FieldKind
import com.chardidathing.litehub.core.model.MIN_PHOTO_SECONDS
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
            description = "one entity's icon, name and state, tap to toggle it",
        ),
        WidgetSchema(
            "sensor", "sensor value", 1, 1,
            listOf(
                SchemaField("entity", "entity", FieldKind.ENTITY, required = true, domains = listOf("sensor", "binary_sensor", "input_number", "number", "counter")),
                SchemaField("name", "name", FieldKind.TEXT),
                SchemaField("icon", "icon (mdi:name)", FieldKind.TEXT),
            ),
            description = "a sensor's value, big and readable from across the room",
        ),
        WidgetSchema(
            "agenda", "agenda", 1, 2,
            listOf(
                SchemaField("title", "title", FieldKind.TEXT),
                SchemaField("calendars", "calendars (none picked shows all)", FieldKind.CALENDARS),
                SchemaField("days", "days ahead", FieldKind.NUMBER, default = JsonPrimitive(AgendaConfig().days)),
            ),
            description = "the next few days of events from your calendars",
        ),
        WidgetSchema("month", "month", 2, 2, listOf(SchemaField("calendars", "calendars (none picked shows all)", FieldKind.CALENDARS)), description = "a month grid with dots on the days that have events"),
        WidgetSchema(
            "headlines", "headlines", 2, 1,
            listOf(
                SchemaField("title", "title", FieldKind.TEXT),
                SchemaField("feeds", "feeds (none picked shows all)", FieldKind.FEEDS),
            ),
            description = "the latest headlines from your rss and atom feeds",
        ),
        WidgetSchema("weather", "weather now", 1, 1, weather(), description = "the temperature and conditions right now"),
        WidgetSchema("hourly", "hourly forecast", 2, 1, weather() + SchemaField("hours", "hours", FieldKind.NUMBER, default = JsonPrimitive(WeatherConfig().hours)), description = "the next few hours of weather"),
        WidgetSchema("daily", "daily forecast", 1, 2, weather() + SchemaField("days", "days", FieldKind.NUMBER, default = JsonPrimitive(WeatherConfig().days)), description = "the week ahead, highs and lows"),
        WidgetSchema(
            "todo", "list", 1, 2,
            listOf(
                SchemaField("entity", "todo list", FieldKind.ENTITY, required = true, domains = listOf("todo")),
                SchemaField("title", "title", FieldKind.TEXT),
                SchemaField("quick", "quick add chips, comma separated", FieldKind.TEXT),
            ),
            description = "a home assistant list, tick things off or add to it",
        ),
        WidgetSchema(
            "entities", "entities", 2, 1,
            listOf(
                SchemaField("title", "title", FieldKind.TEXT),
                SchemaField("entities", "entities", FieldKind.ENTITIES, required = true),
            ),
            description = "lots of entities in one tile, each with its state, tap one to toggle it",
        ),
        WidgetSchema(
            "clock", "clock", 1, 1,
            listOf(SchemaField("date", "show the date", FieldKind.TOGGLE, default = JsonPrimitive(ClockConfig().date))),
            description = "the time, as big as the tile allows, with the date under it",
        ),
        WidgetSchema(
            "photo", "photo frame", 1, 1,
            listOf(SchemaField("seconds", "seconds per photo (blank uses the screensaver's)", FieldKind.NUMBER, min = MIN_PHOTO_SECONDS.toDouble())),
            description = "the screensaver's photos in a tile, one fading into the next",
        ),
        WidgetSchema("notifications", "notifications", 1, 2, listOf(SchemaField("title", "title", FieldKind.TEXT)), description = "what home assistant has sent, tap one to clear it"),
        WidgetSchema("placeholder", "placeholder", 1, 1, listOf(SchemaField("title", "title", FieldKind.TEXT)), description = "an empty tile to hold a space"),
    )

    fun of(type: String): WidgetSchema? = all.firstOrNull { it.type == type }

    private fun weather() = listOf(SchemaField("entity", "weather entity (none uses open-meteo)", FieldKind.ENTITY, domains = listOf("weather")))
}
