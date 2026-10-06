package com.chardidathing.litehub.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.chardidathing.litehub.ui.tokens.ResolvedTheme

// base for every dashboard widget, draws the surface tile and hands subclasses the inner rect
abstract class WidgetView(context: Context, protected val theme: ResolvedTheme) : View(context) {

    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.colors.surface }
    private val tile = RectF()
    protected val content = RectF()

    // rebuild cached text and paints here, never in drawContent
    protected abstract fun onContentChanged()

    protected abstract fun drawContent(canvas: Canvas)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        tile.set(0f, 0f, w.toFloat(), h.toFloat())
        val inset = theme.spacing.m
        content.set(inset, inset, w - inset, h - inset)
        onContentChanged()
    }

    override fun onDraw(canvas: Canvas) {
        val r = theme.radii.medium
        canvas.drawRoundRect(tile, r, r, tilePaint)
        drawContent(canvas)
    }
}
