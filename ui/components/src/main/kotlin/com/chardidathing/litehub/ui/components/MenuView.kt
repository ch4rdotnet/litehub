package com.chardidathing.litehub.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.chardidathing.litehub.ui.tokens.ResolvedTheme

// a full screen list of big tappable rows under a title. opaque, it covers whatever is behind
class MenuView(
    context: Context,
    private val theme: ResolvedTheme,
    private val title: String,
    private val detail: String?,
    private val items: List<String>,
    private val onPick: (index: Int) -> Unit,
) : View(context) {

    private val titleBlock = TextBlock(maxLines = 2)
    private val detailBlock = TextBlock(maxLines = 3)
    private val itemBlocks = items.map { TextBlock(maxLines = 1) }
    private val rows = items.map { RectF() }
    private val rowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.colors.surface }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.colors.primary }
    private var pressed = -1
    private var left = 0f
    private var titleTop = 0f
    private var detailTop = 0f

    init {
        setBackgroundColor(theme.colors.background)
        isClickable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val gap = theme.spacing.m
        // a column the width of a wide tile, centred, so rows aren't a whole screen wide
        val width = minOf(w - theme.spacing.xl * 2, theme.touchTarget * COLUMN_TARGETS)
        left = (w - width) / 2
        titleBlock.set(title, theme.type.h5, theme.colors.onBackground, width.toInt())
        detailBlock.set(detail.orEmpty(), theme.type.body2, theme.colors.onBackground, width.toInt())
        val headHeight = titleBlock.height + if (detail != null) theme.spacing.s + detailBlock.height else 0f
        val listHeight = items.size * theme.touchTarget + (items.size - 1) * gap
        titleTop = ((h - headHeight - theme.spacing.l - listHeight) / 2).coerceAtLeast(theme.spacing.xl)
        detailTop = titleTop + titleBlock.height + theme.spacing.s
        var y = titleTop + headHeight + theme.spacing.l
        items.forEachIndexed { i, item ->
            rows[i].set(left, y, left + width, y + theme.touchTarget)
            itemBlocks[i].set(item, theme.type.subtitle1, theme.colors.onSurface, (width - theme.spacing.m * 2).toInt())
            y += theme.touchTarget + gap
        }
    }

    override fun onDraw(canvas: Canvas) {
        titleBlock.draw(canvas, left, titleTop)
        if (detail != null) detailBlock.draw(canvas, left, detailTop)
        val r = theme.radii.medium
        rows.forEachIndexed { i, row ->
            canvas.drawRoundRect(row, r, r, if (i == pressed) pressedPaint else rowPaint)
            val text = itemBlocks[i]
            text.draw(canvas, row.left + theme.spacing.m, row.centerY() - text.height / 2f)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val hit = rows.indexOfFirst { it.contains(event.x, event.y) }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> setPressedRow(hit)
            MotionEvent.ACTION_MOVE -> if (hit != pressed) setPressedRow(-1)
            MotionEvent.ACTION_UP -> {
                val picked = pressed
                setPressedRow(-1)
                if (picked >= 0 && picked == hit) onPick(picked)
            }
            MotionEvent.ACTION_CANCEL -> setPressedRow(-1)
        }
        return true
    }

    private fun setPressedRow(i: Int) {
        if (i == pressed) return
        pressed = i
        invalidate()
    }

    private companion object {
        // rows are this many touch targets wide at most
        const val COLUMN_TARGETS = 8
    }
}
