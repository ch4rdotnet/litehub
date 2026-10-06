package com.chardidathing.litehub.ui.widgets

import android.content.Context
import android.graphics.Canvas
import com.chardidathing.litehub.ui.components.IconBlock
import com.chardidathing.litehub.ui.components.Icons
import com.chardidathing.litehub.ui.components.TextBlock
import com.chardidathing.litehub.ui.tokens.ResolvedTheme

// one sensor's value large in the bottom left, with its unit beside it
class SensorWidget(context: Context, theme: ResolvedTheme, icons: Icons, config: EntityConfig) :
    EntityWidget(context, theme, icons, config) {

    private val icon = IconBlock()
    private val nameBlock = TextBlock(maxLines = 2)
    private val valueBlock = TextBlock(maxLines = 1)
    private val unitBlock = TextBlock(maxLines = 1)
    private val problemBlock = TextBlock(maxLines = 2)

    override fun onContentChanged() {
        val w = content.width().toInt()
        icon.set(icons.path(iconName), theme.iconSize, theme.colors.onSurface)
        nameBlock.set(name, theme.type.h6, theme.colors.onSurface, w)
        val problem = problem
        val color = if (problemIsError) theme.colors.error else theme.colors.onSurface
        problemBlock.set(problem.orEmpty(), theme.type.body2, color, w)
        val e = if (problem == null) entity else null
        valueBlock.set(e?.state.orEmpty(), theme.type.h3, theme.colors.onSurface, w)
        val unit = e?.attribute("unit_of_measurement").orEmpty()
        val rest = (w - valueBlock.lineWidth - theme.spacing.xs).toInt()
        unitBlock.set(unit, theme.type.h6, theme.colors.onSurface, rest)
    }

    override fun drawContent(canvas: Canvas) {
        icon.draw(canvas, content.left, content.top)
        val nameTop = content.top + theme.iconSize + theme.spacing.s
        nameBlock.draw(canvas, content.left, nameTop)
        if (problem != null) {
            problemBlock.draw(canvas, content.left, nameTop + nameBlock.height + theme.spacing.xs)
            return
        }
        val valueTop = content.bottom - valueBlock.height
        valueBlock.draw(canvas, content.left, valueTop)
        // bottoms line up closely enough to read as a shared baseline at these sizes
        unitBlock.draw(canvas, content.left + valueBlock.lineWidth + theme.spacing.xs, content.bottom - unitBlock.height)
    }
}
