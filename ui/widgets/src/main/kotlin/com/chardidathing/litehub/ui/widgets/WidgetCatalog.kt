package com.chardidathing.litehub.ui.widgets

import android.content.Context
import com.chardidathing.litehub.core.model.Placement
import com.chardidathing.litehub.ui.components.Icons
import com.chardidathing.litehub.ui.components.WidgetView
import com.chardidathing.litehub.ui.tokens.ResolvedTheme
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

object WidgetCatalog {

    private val json = Json

    // the entity a placement needs, so its state can be loaded before the first frame
    fun entityId(placement: Placement): String? = entityConfig(placement)?.entity

    fun create(context: Context, theme: ResolvedTheme, icons: Icons, placement: Placement): WidgetView {
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
            // shown as a failure, not dropped, so a typo in the config is visible on screen
            else -> broken(context, theme, "unknown widget", type)
        }
    }

    private fun entityConfig(placement: Placement): EntityConfig? {
        if (placement.type != "entity" && placement.type != "sensor") return null
        return try {
            json.decodeFromJsonElement(EntityConfig.serializer(), placement.config)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun broken(context: Context, theme: ResolvedTheme, title: String, detail: String) =
        PlaceholderWidget(context, theme, title = title, detail = detail, titleColor = theme.colors.error)

    private fun Placement.string(key: String) = (config[key] as? JsonPrimitive)?.contentOrNull
}
