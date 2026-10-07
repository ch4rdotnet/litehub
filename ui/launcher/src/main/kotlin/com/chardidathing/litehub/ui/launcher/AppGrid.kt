package com.chardidathing.litehub.ui.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.chardidathing.litehub.ui.components.TextBlock
import com.chardidathing.litehub.ui.tokens.ResolvedTheme
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

// apps in rows, icon over name, as tall as it needs to be (it sits in a scroll view). an icon
// that hasn't loaded yet is just left out, the name is enough to find it by
@SuppressLint("ViewConstructor")
class AppGrid(
    context: Context,
    private val theme: ResolvedTheme,
    private val onPick: (AppEntry) -> Unit,
    private val onHold: ((AppEntry) -> Unit)?,
) : View(context) {

    // px, square
    val iconSize = theme.touchTarget.toInt()

    private var apps: List<AppEntry> = emptyList()
    private val names = HashMap<String, TextBlock>()
    private val icons = HashMap<String, Bitmap>()
    private val cells = ArrayList<RectF>()
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.colors.surface }
    private val iconPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var pressed = -1
    private var held = false
    private var downX = 0f
    private var downY = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val measuring = TextBlock()
    private val hold = Runnable {
        val i = pressed
        if (i < 0) return@Runnable
        held = true
        press(-1)
        onHold?.invoke(apps[i])
    }

    fun show(list: List<AppEntry>) {
        apps = list
        press(-1)
        requestLayout()
        invalidate()
    }

    fun icon(key: String, bitmap: Bitmap) {
        icons[key] = bitmap
        invalidate()
    }

    private fun cellWidth() = theme.touchTarget * CELL_TARGETS

    private fun cellHeight() = theme.spacing.m * 2 + iconSize + theme.spacing.s + measuring.lineHeight(theme.type.body2) * NAME_LINES

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val columns = max(1, (w / cellWidth()).toInt())
        val rows = ceil(apps.size / columns.toFloat()).toInt()
        setMeasuredDimension(w, ceil(rows * cellHeight()).toInt())
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val w = right - left
        val columns = max(1, (w / cellWidth()).toInt())
        // the spare width goes into the cells, so the grid fills the row edge to edge
        val cw = w / columns.toFloat()
        val ch = cellHeight()
        cells.clear()
        apps.forEachIndexed { i, app ->
            val x = (i % columns) * cw
            val y = (i / columns) * ch
            cells += RectF(x, y, x + cw, y + ch)
            val name = names.getOrPut(app.key) { TextBlock(maxLines = NAME_LINES) }
            name.set(app.label, theme.type.body2, theme.colors.onBackground, (cw - theme.spacing.s * 2).toInt(), Layout.Alignment.ALIGN_CENTER)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val r = theme.radii.medium
        apps.forEachIndexed { i, app ->
            val cell = cells.getOrNull(i) ?: return
            if (i == pressed) canvas.drawRoundRect(cell, r, r, pressedPaint)
            val top = cell.top + theme.spacing.m
            icons[app.key]?.let { canvas.drawBitmap(it, cell.centerX() - iconSize / 2f, top, iconPaint) }
            names[app.key]?.draw(canvas, cell.left + theme.spacing.s, top + iconSize + theme.spacing.s)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                held = false
                press(cells.indexOfFirst { it.contains(e.x, e.y) })
                if (pressed >= 0 && onHold != null) postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (abs(e.x - downX) > slop || abs(e.y - downY) > slop) {
                removeCallbacks(hold)
                press(-1)
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(hold)
                val i = pressed
                press(-1)
                if (i >= 0 && !held) onPick(apps[i])
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(hold)
                press(-1)
            }
        }
        return true
    }

    private fun press(i: Int) {
        if (i == pressed) return
        pressed = i
        invalidate()
    }

    private companion object {
        // a cell is this many touch targets wide at least, room for a name under the icon
        const val CELL_TARGETS = 2
        const val NAME_LINES = 2
    }
}
