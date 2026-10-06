package com.chardidathing.litehub.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.chardidathing.litehub.ui.tokens.ResolvedTheme

// a touch target sized square of one colour, ringed in the text colour while checked
@SuppressLint("ViewConstructor")
class SwatchView(context: Context, private val theme: ResolvedTheme, color: Int, private val onTap: () -> Unit) : View(context) {

    var checked = false
        set(value) {
            field = value
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = theme.colors.onBackground
        style = Paint.Style.STROKE
        strokeWidth = theme.spacing.xs
    }
    private val rect = RectF()

    init {
        isClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = theme.touchTarget.toInt()
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val inset = theme.spacing.xs
        rect.set(inset, inset, width - inset, height - inset)
        val r = theme.radii.medium
        canvas.drawRoundRect(rect, r, r, fill)
        if (checked) canvas.drawRoundRect(rect, r, r, ring)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) onTap()
        return true
    }
}
