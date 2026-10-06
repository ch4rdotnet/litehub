package com.chardidathing.litehub.ui.widgets

import android.content.Context
import com.chardidathing.litehub.core.model.Placement
import com.chardidathing.litehub.ui.components.WidgetView
import com.chardidathing.litehub.ui.tokens.ResolvedTheme
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

object WidgetCatalog {

    fun create(context: Context, theme: ResolvedTheme, placement: Placement): WidgetView =
        when (placement.type) {
            "placeholder" -> PlaceholderWidget(
                context, theme,
                title = placement.string("title") ?: "placeholder",
                detail = "${placement.w} by ${placement.h}",
            )
            // shown as a failure, not dropped, so a typo in the config is visible on screen
            else -> PlaceholderWidget(
                context, theme,
                title = "unknown widget",
                detail = placement.type,
                titleColor = theme.colors.error,
            )
        }

    private fun Placement.string(key: String) = (config[key] as? JsonPrimitive)?.contentOrNull
}
