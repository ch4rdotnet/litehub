package com.chardidathing.litehub.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.view.View
import com.chardidathing.litehub.ui.tokens.ResolvedTheme

// base for every dashboard widget, draws the surface tile and an optional badge in the top
// right corner (stale data and the like), and hands subclasses the inner rect
abstract class WidgetView(context: Context, protected val theme: ResolvedTheme) : View(context) {

    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.colors.surface }
    private val tile = RectF()
    protected val content = RectF()
    private val badgeBlock = TextBlock(maxLines = 1)
    private var badge: String? = null

    // rebuild cached text and paints here, never in drawContent
    protected abstract fun onContentChanged()

    protected abstract fun drawContent(canvas: Canvas)

    protected fun setBadge(text: String?) {
        if (text == badge) return
        badge = text
        layoutBadge()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        tile.set(0f, 0f, w.toFloat(), h.toFloat())
        val inset = theme.spacing.m
        content.set(inset, inset, w - inset, h - inset)
        layoutBadge()
        onContentChanged()
    }

    override fun onDraw(canvas: Canvas) {
        val r = theme.radii.medium
        canvas.drawRoundRect(tile, r, r, tilePaint)
        drawContent(canvas)
        if (badge != null) badgeBlock.draw(canvas, content.left, content.top)
    }

    private fun layoutBadge() {
        val text = badge ?: return
        badgeBlock.set(text, theme.type.caption, theme.colors.onSurface, content.width().toInt(), Layout.Alignment.ALIGN_OPPOSITE)
    }
}
