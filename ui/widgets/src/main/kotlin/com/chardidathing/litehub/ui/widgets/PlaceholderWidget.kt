package com.chardidathing.litehub.ui.widgets

import android.content.Context
import android.graphics.Canvas
import com.chardidathing.litehub.ui.components.TextBlock
import com.chardidathing.litehub.ui.components.WidgetView
import com.chardidathing.litehub.ui.tokens.ResolvedTheme

// a titled tile with no data behind it, stands in for widgets that don't exist yet
class PlaceholderWidget(
    context: Context,
    theme: ResolvedTheme,
    private val title: String,
    private val detail: String,
    private val titleColor: Int = theme.colors.onSurface,
) : WidgetView(context, theme) {

    private val titleBlock = TextBlock(maxLines = 2)
    private val detailBlock = TextBlock(maxLines = 1)

    override fun onContentChanged() {
        val w = content.width().toInt()
        titleBlock.set(title, theme.type.h6, titleColor, w)
        detailBlock.set(detail, theme.type.caption, theme.colors.onSurface, w)
    }

    override fun drawContent(canvas: Canvas) {
        titleBlock.draw(canvas, content.left, content.top)
        detailBlock.draw(canvas, content.left, content.top + titleBlock.height + theme.spacing.xs)
    }
}
