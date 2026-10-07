package com.chardidathing.litehub.ui.components

import android.graphics.Canvas
import android.graphics.Paint
import com.chardidathing.litehub.ui.tokens.ResolvedTheme
import kotlin.math.ceil
import kotlin.math.min

// a colour dot and a name for each entry, wrapped onto as many lines as it takes. calendar
// widgets say which colour is which calendar with it
class ColorKey(private val theme: ResolvedTheme) {

    data class Entry(val name: String, val color: Int)

    private var entries: List<Entry> = emptyList()
    private val labels = ArrayList<TextBlock>()
    private var xs = FloatArray(0)
    private var ys = FloatArray(0)
    private var lineHeight = 0f
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    // nothing to show is no height at all
    var height = 0f
        private set

    fun set(next: List<Entry>, width: Int) {
        entries = next
        while (labels.size < next.size) labels += TextBlock(maxLines = 1)
        xs = FloatArray(next.size)
        ys = FloatArray(next.size)
        val style = theme.type.caption
        val radius = theme.spacing.xs
        val beforeLabel = radius * 2 + theme.spacing.xs
        lineHeight = labels.firstOrNull()?.lineHeight(style) ?: 0f
        var x = 0f
        var y = 0f
        next.forEachIndexed { i, e ->
            // a name too long for the whole width is cut short rather than pushed off the edge
            val labelWidth = min(labels[i].width(e.name, style), width - beforeLabel).coerceAtLeast(0f)
            val itemWidth = beforeLabel + labelWidth
            if (x > 0 && x + itemWidth > width) {
                x = 0f
                y += lineHeight + theme.spacing.xs
            }
            labels[i].set(e.name, style, theme.colors.onSurface, ceil(labelWidth).toInt())
            xs[i] = x
            ys[i] = y
            x += itemWidth + theme.spacing.m
        }
        height = if (next.isEmpty()) 0f else y + lineHeight
    }

    fun draw(canvas: Canvas, left: Float, top: Float) {
        val radius = theme.spacing.xs
        entries.forEachIndexed { i, e ->
            dot.color = e.color
            canvas.drawCircle(left + xs[i] + radius, top + ys[i] + lineHeight / 2, radius, dot)
            labels[i].draw(canvas, left + xs[i] + radius * 2 + theme.spacing.xs, top + ys[i])
        }
    }
}
