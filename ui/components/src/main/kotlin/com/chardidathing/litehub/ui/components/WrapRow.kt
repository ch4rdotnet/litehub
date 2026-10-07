package com.chardidathing.litehub.ui.components

import android.content.Context
import android.view.ViewGroup
import kotlin.math.roundToInt

// children left to right at their own size, onto the next line when they run out of room.
// for lists of chips (a light's effects) that can be any length
class WrapRow(context: Context, private val gap: Float) : ViewGroup(context) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val child = MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST)
        var x = 0f
        var y = 0f
        var line = 0f
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            if (v.visibility == GONE) continue
            v.measure(child, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0f && x + v.measuredWidth > width) {
                x = 0f
                y += line + gap
                line = 0f
            }
            x += v.measuredWidth + gap
            line = maxOf(line, v.measuredHeight.toFloat())
        }
        setMeasuredDimension(width, (y + line).roundToInt())
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        var x = 0f
        var y = 0f
        var line = 0f
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            if (v.visibility == GONE) continue
            if (x > 0f && x + v.measuredWidth > width) {
                x = 0f
                y += line + gap
                line = 0f
            }
            v.layout(x.roundToInt(), y.roundToInt(), x.roundToInt() + v.measuredWidth, y.roundToInt() + v.measuredHeight)
            x += v.measuredWidth + gap
            line = maxOf(line, v.measuredHeight.toFloat())
        }
    }
}
