package com.chardidathing.litehub.ui.components

import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlin.math.roundToInt

// a page grid, children are placed by cell. no background, the window draws it. edge is the
// margin around the outside (the pager's dots sit in the bottom one), gap is between tiles
class PageView(context: Context, private val columns: Int, private val rows: Int, private val edge: Float, private val gap: Float) :
    ViewGroup(context) {

    private class Cell(val x: Int, val y: Int, val w: Int, val h: Int) : LayoutParams(MATCH_PARENT, MATCH_PARENT)

    fun addWidget(view: View, x: Int, y: Int, w: Int, h: Int) = addView(view, Cell(x, y, w, h))

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val cw = cell(w, columns)
        val ch = cell(h, rows)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val c = child.layoutParams as Cell
            child.measure(exactly(span(cw, c.w)), exactly(span(ch, c.h)))
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cw = cell(width, columns)
        val ch = cell(height, rows)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val c = child.layoutParams as Cell
            val left = (edge + c.x * (cw + gap)).roundToInt()
            val top = (edge + c.y * (ch + gap)).roundToInt()
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
        }
    }

    override fun checkLayoutParams(p: LayoutParams?) = p is Cell

    override fun shouldDelayChildPressedState() = false

    private fun cell(total: Int, count: Int) = (total - edge * 2 - gap * (count - 1)) / count

    private fun span(cell: Float, cells: Int) = cell * cells + gap * (cells - 1)

    private fun exactly(size: Float) =
        MeasureSpec.makeMeasureSpec(size.roundToInt().coerceAtLeast(0), MeasureSpec.EXACTLY)
}
