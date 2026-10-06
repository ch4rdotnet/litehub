package com.chardidathing.litehub.ui.widgets

import android.content.Context
import android.graphics.Canvas
import com.chardidathing.litehub.core.model.Entity
import com.chardidathing.litehub.ui.components.IconBlock
import com.chardidathing.litehub.ui.components.Icons
import com.chardidathing.litehub.ui.components.TextBlock
import com.chardidathing.litehub.ui.tokens.ResolvedTheme
import kotlin.math.roundToInt

// icon, name and state for any entity, tapping toggles it when the binder allows
class EntityTileWidget(context: Context, theme: ResolvedTheme, icons: Icons, config: EntityConfig) :
    EntityWidget(context, theme, icons, config) {

    private val icon = IconBlock()
    private val nameBlock = TextBlock(maxLines = 2)
    private val stateBlock = TextBlock(maxLines = 2)

    override fun onContentChanged() {
        val w = content.width().toInt()
        val active = entity?.let(::isActive) == true
        icon.set(icons.path(iconName), theme.iconSize, if (active) theme.colors.primary else theme.colors.onSurface)
        nameBlock.set(name, theme.type.h6, theme.colors.onSurface, w)
        val problem = problem
        val stateColor = if (problem != null && problemIsError) theme.colors.error else theme.colors.onSurface
        stateBlock.set(problem ?: entity?.let(::describe).orEmpty(), theme.type.body2, stateColor, w)
    }

    override fun drawContent(canvas: Canvas) {
        icon.draw(canvas, content.left, content.top)
        val nameTop = content.top + theme.iconSize + theme.spacing.s
        nameBlock.draw(canvas, content.left, nameTop)
        stateBlock.draw(canvas, content.left, nameTop + nameBlock.height + theme.spacing.xs)
    }

    private fun isActive(entity: Entity) =
        entity.state in ACTIVE_STATES

    // ha states are already lowercase words, lights also get their brightness
    private fun describe(entity: Entity): String {
        val brightness = entity.attribute("brightness")?.toFloatOrNull()
        return if (entity.domain == "light" && entity.state == "on" && brightness != null) {
            "on, ${(brightness / 255f * 100f).roundToInt()}%"
        } else {
            entity.state
        }
    }

    private companion object {
        val ACTIVE_STATES = setOf("on", "open", "opening", "unlocked", "playing", "home", "heat", "cool", "heat_cool", "auto")
    }
}
