package com.chardidathing.litehub.ui.components

import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlin.math.roundToInt

// a page grid, children are placed by cell. no background, the window draws it
class PageView(context: Context, private val columns: Int, private val rows: Int, private val gutter: Float) :
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
            val left = (gutter + c.x * (cw + gutter)).roundToInt()
            val top = (gutter + c.y * (ch + gutter)).roundToInt()
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
        }
    }

    override fun checkLayoutParams(p: LayoutParams?) = p is Cell

    override fun shouldDelayChildPressedState() = false

    // gutters sit between cells and around the outside edge
    private fun cell(total: Int, count: Int) = (total - gutter * (count + 1)) / count

    private fun span(cell: Float, cells: Int) = cell * cells + gutter * (cells - 1)

    private fun exactly(size: Float) =
        MeasureSpec.makeMeasureSpec(size.roundToInt().coerceAtLeast(0), MeasureSpec.EXACTLY)
}
