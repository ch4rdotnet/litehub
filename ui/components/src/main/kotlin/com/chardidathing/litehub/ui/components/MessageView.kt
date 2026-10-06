package com.chardidathing.litehub.ui.components

import android.content.Context
import android.graphics.Canvas
import android.text.Layout
import android.view.View
import com.chardidathing.litehub.ui.tokens.ResolvedTheme

// full screen title and detail, for failures that leave nothing else to draw
class MessageView(context: Context, private val theme: ResolvedTheme, private val title: String, private val detail: String) :
    View(context) {

    private val titleBlock = TextBlock()
    private val detailBlock = TextBlock()
    private var top = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val inset = theme.spacing.xl
        val textWidth = (w - inset * 2).toInt()
        val center = Layout.Alignment.ALIGN_CENTER
        titleBlock.set(title, theme.type.h5, theme.colors.onBackground, textWidth, center)
        detailBlock.set(detail, theme.type.body1, theme.colors.onBackground, textWidth, center)
        top = (h - titleBlock.height - theme.spacing.s - detailBlock.height) / 2f
    }

    override fun onDraw(canvas: Canvas) {
        val inset = theme.spacing.xl
        titleBlock.draw(canvas, inset, top)
        detailBlock.draw(canvas, inset, top + titleBlock.height + theme.spacing.s)
    }
}
