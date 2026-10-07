package com.chardidathing.litehub.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import com.chardidathing.litehub.ui.tokens.ResolvedTheme

// a touch target tall slider, value runs 0 to 1. a plain one fills with primary up to the
// thumb, one given colours draws them as a gradient along the track (hue, warmth). onMove is
// every step of a drag, onSet only the value it was let go at, so ha gets one call per drag
@SuppressLint("ViewConstructor")
class SliderView(
    context: Context,
    private val theme: ResolvedTheme,
    private val onMove: (Float) -> Unit,
    private val onSet: (Float) -> Unit,
) : View(context) {

    // ignored while a finger is on it, ha catching up mid drag would yank the thumb back
    var value: Float
        get() = position
        set(v) {
            if (dragging) return
            position = v.coerceIn(0f, 1f)
            invalidate()
        }

    private var position = 0f

    var colors: IntArray? = null
        set(v) {
            field = v
            shade()
            invalidate()
        }

    private var dragging = false
    private val track = RectF()
    private val filled = RectF()
    // sits on the background like buttons do, surface coloured
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.colors.surface }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.colors.primary }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.colors.onSurface }
    private val thumbRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.colors.background
        style = Paint.Style.STROKE
        strokeWidth = theme.spacing.xs
    }

    init {
        isClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), theme.touchTarget.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // the thumb's radius in from each end, so it reaches 0 and 1 without leaving the view
        val inset = thumb()
        val half = theme.spacing.l / 2
        track.set(inset, h / 2f - half, w - inset, h / 2f + half)
        shade()
    }

    private fun thumb() = theme.spacing.m

    private fun shade() {
        val c = colors
        trackPaint.shader = if (c == null || track.width() <= 0f) null else LinearGradient(track.left, 0f, track.right, 0f, c, null, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val r = track.height() / 2
        canvas.drawRoundRect(track, r, r, trackPaint)
        val x = track.left + track.width() * value
        if (colors == null) {
            // the round end stops under the thumb, at 0 it's hidden there entirely
            filled.set(track.left, track.top, x + r, track.bottom)
            canvas.drawRoundRect(filled, r, r, fillPaint)
        }
        canvas.drawCircle(x, track.centerY(), thumb(), thumbPaint)
        canvas.drawCircle(x, track.centerY(), thumb(), thumbRing)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // a scrolling parent would take a sideways drag that starts a little crooked
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                follow(e.x)
            }
            MotionEvent.ACTION_MOVE -> follow(e.x)
            MotionEvent.ACTION_UP -> {
                follow(e.x)
                dragging = false
                onSet(position)
            }
            MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    private fun follow(x: Float) {
        val v = ((x - track.left) / track.width()).coerceIn(0f, 1f)
        if (v == position) return
        position = v
        invalidate()
        onMove(v)
    }
}
