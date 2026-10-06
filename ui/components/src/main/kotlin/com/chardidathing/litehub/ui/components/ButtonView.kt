package com.chardidathing.litehub.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.view.MotionEvent
import android.view.View
import com.chardidathing.litehub.ui.tokens.ResolvedTheme

// a touch target sized button, surface coloured, primary while pressed or checked.
// checked is for toggle lists (which calendars a widget shows)
class ButtonView(context: Context, private val theme: ResolvedTheme, label: String, private val onTap: () -> Unit) : View(context) {

    var label: String = label
        set(value) {
            field = value
            layoutText()
            invalidate()
        }

    var checked = false
        set(value) {
            field = value
            layoutText()
            invalidate()
        }

    var centred = false

    private val rect = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextBlock(maxLines = 1)
    private var pressed = false

    init {
        isClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wanted = (text.width(label, theme.type.button) + theme.spacing.m * 2).toInt()
        val w = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(widthMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(wanted, MeasureSpec.getSize(widthMeasureSpec))
            else -> wanted
        }
        setMeasuredDimension(w, theme.touchTarget.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        layoutText()
    }

    private fun layoutText() {
        val on = pressed || checked
        val color = if (on) theme.colors.onPrimary else theme.colors.onSurface
        val align = if (centred) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL
        text.set(label, theme.type.button, color, (width - theme.spacing.m * 2).toInt(), align)
        paint.color = if (on) theme.colors.primary else theme.colors.surface
    }

    override fun onDraw(canvas: Canvas) {
        val r = theme.radii.medium
        canvas.drawRoundRect(rect, r, r, paint)
        text.draw(canvas, theme.spacing.m, (height - text.height) / 2f)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> setPressedState(true)
            MotionEvent.ACTION_MOVE -> if (!rect.contains(event.x, event.y)) setPressedState(false)
            MotionEvent.ACTION_UP -> {
                val was = pressed
                setPressedState(false)
                if (was) onTap()
            }
            MotionEvent.ACTION_CANCEL -> setPressedState(false)
        }
        return true
    }

    private fun setPressedState(on: Boolean) {
        if (on == pressed) return
        pressed = on
        layoutText()
        invalidate()
    }
}
