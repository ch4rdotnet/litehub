package com.chardidathing.litehub.ui.widgets

import android.content.Context
import com.chardidathing.litehub.core.model.Placement
import com.chardidathing.litehub.ui.components.Icons
import com.chardidathing.litehub.ui.components.WidgetView
import com.chardidathing.litehub.ui.tokens.ResolvedTheme
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

object WidgetCatalog {

    private val json = Json

    // the entity a placement needs, so its state can be loaded before the first frame
    fun entityId(placement: Placement): String? = entityConfig(placement)?.entity
        ?: if (placement.type in WEATHER) decode(placement, WeatherConfig.serializer())?.entity else null

    fun isWeather(placement: Placement) = placement.type in WEATHER

    // a weather widget's ha entity, null means it wants open-meteo
    fun weatherEntity(placement: Placement): String? = decode(placement, WeatherConfig.serializer())?.entity

    private val WEATHER = setOf("weather", "hourly", "daily")

    fun create(context: Context, theme: ResolvedTheme, icons: Icons, legend: Legend, placement: Placement): WidgetView {
        val type = placement.type
        return when (type) {
            "placeholder" -> PlaceholderWidget(
                context, theme,
                title = placement.string("title") ?: "placeholder",
                detail = "${placement.w} by ${placement.h}",
            )
            "entity", "sensor" -> {
                val config = entityConfig(placement)
                    ?: return broken(context, theme, "$type config isn't valid", "it needs an entity")
                if (type == "entity") EntityTileWidget(context, theme, icons, config)
                else SensorWidget(context, theme, icons, config)
            }
            "agenda" -> decode(placement, AgendaConfig.serializer())?.let { AgendaWidget(context, theme, it, legend) }
                ?: broken(context, theme, "agenda config isn't valid", "see the agenda widget's fields")
            "month" -> decode(placement, MonthConfig.serializer())?.let { MonthWidget(context, theme, it, legend) }
                ?: broken(context, theme, "month config isn't valid", "see the month widget's fields")
            "headlines" -> decode(placement, HeadlinesConfig.serializer())?.let { HeadlinesWidget(context, theme, it, legend) }
                ?: broken(context, theme, "headlines config isn't valid", "see the headlines widget's fields")
            "weather", "hourly", "daily" -> decode(placement, WeatherConfig.serializer())?.let {
                when (type) {
                    "weather" -> WeatherNowWidget(context, theme, icons, it)
                    "hourly" -> HourlyWidget(context, theme, icons, it)
                    else -> DailyWidget(context, theme, icons, it)
                }
            } ?: broken(context, theme, "$type config isn't valid", "see the $type widget's fields")
            "todo" -> decode(placement, TodoConfig.serializer())?.let { TodoWidget(context, theme, icons, it) }
                ?: broken(context, theme, "list config isn't valid", "it needs a todo entity")
            "entities" -> decode(placement, EntitiesConfig.serializer())?.let { EntitiesWidget(context, theme, icons, it) }
                ?: broken(context, theme, "entities config isn't valid", "it takes a list of entity ids")
            "clock" -> decode(placement, ClockConfig.serializer())?.let { ClockWidget(context, theme, it) }
                ?: broken(context, theme, "clock config isn't valid", "it only takes whether to show the date")
            "photo" -> decode(placement, PhotoConfig.serializer())?.let { PhotoWidget(context, theme, it) }
                ?: broken(context, theme, "photo frame config isn't valid", "it only takes seconds per photo")
            "app" -> decode(placement, AppConfig.serializer())?.let { AppWidget(context, theme, it) }
                ?: broken(context, theme, "app config isn't valid", "it needs an app")
            "notifications" -> decode(placement, NotificationsConfig.serializer())?.let { NotificationsWidget(context, theme, it) }
                ?: broken(context, theme, "notifications config isn't valid", "it only takes a title")
            // shown as a failure, not dropped, so a typo in the config is visible on screen
            else -> broken(context, theme, "unknown widget", type)
        }
    }

    private fun entityConfig(placement: Placement): EntityConfig? {
        if (placement.type != "entity" && placement.type != "sensor") return null
        return decode(placement, EntityConfig.serializer())
    }

    private fun <T> decode(placement: Placement, serializer: KSerializer<T>): T? = try {
        json.decodeFromJsonElement(serializer, placement.config)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun broken(context: Context, theme: ResolvedTheme, title: String, detail: String) =
        PlaceholderWidget(context, theme, title = title, detail = detail, titleColor = theme.colors.error)

    private fun Placement.string(key: String) = (config[key] as? JsonPrimitive)?.contentOrNull
}
