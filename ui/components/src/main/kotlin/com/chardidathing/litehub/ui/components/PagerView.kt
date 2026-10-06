package com.chardidathing.litehub.ui.components

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import com.chardidathing.litehub.ui.tokens.Easing
import com.chardidathing.litehub.ui.tokens.ResolvedTheme
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

// pages side by side, swiped horizontally. a swipe only moves the scroll offset, so pages
// replay their recorded drawing instead of redrawing. dots in the bottom gutter when there's
// more than one page
class PagerView(context: Context, private val theme: ResolvedTheme, pages: List<View>) : ViewGroup(context) {

    // the page a swipe came to rest on. posted after the settling frame, never called from
    // inside a touch event, so whatever it sets off can't cost the swipe a frame
    var onSettled: ((page: Int) -> Unit)? = null

    var current = 0
        private set

    private val config = ViewConfiguration.get(context)
    private var settling: ValueAnimator? = null
    private var velocity: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var dragging = false

    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.colors.onBackground }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.colors.onBackground
        style = Paint.Style.STROKE
        strokeWidth = theme.spacing.xs / 2
    }

    init {
        pages.forEach(::addView)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val ws = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        val hs = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(ws, hs)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        for (i in 0 until childCount) getChildAt(i).layout(i * w, 0, (i + 1) * w, b - t)
        if (changed) scrollTo(current * w, 0)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (childCount < 2) return false
        gesture(ev)
        return dragging
    }

    // reached directly when the touch started on something that isn't clickable
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (childCount < 2) return false
        gesture(ev)
        return true
    }

    private fun gesture(ev: MotionEvent) {
        track(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastX = ev.x
                // catching a page mid settle takes the drag over straight away
                dragging = settling != null
                settling?.cancel()
                settling = null
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(ev.x - downX)
                if (!dragging && dx > config.scaledTouchSlop && dx > abs(ev.y - downY)) {
                    dragging = true
                    lastX = ev.x
                }
                if (dragging) {
                    val max = (childCount - 1) * width
                    scrollTo((scrollX + (lastX - ev.x)).roundToInt().coerceIn(0, max), 0)
                    lastX = ev.x
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val v = velocity?.run {
                    computeCurrentVelocity(1000)
                    xVelocity
                } ?: 0f
                velocity?.recycle()
                velocity = null
                if (!dragging) return
                dragging = false
                val target = when {
                    v < -config.scaledMinimumFlingVelocity -> current + 1
                    v > config.scaledMinimumFlingVelocity -> current - 1
                    else -> (scrollX.toFloat() / width).roundToInt()
                }
                settle(target.coerceIn(0, childCount - 1), v)
            }
        }
    }

    private fun track(ev: MotionEvent) {
        (velocity ?: VelocityTracker.obtain().also { velocity = it }).addMovement(ev)
    }

    // velocity is the finger's at release in px/s, negative moving left
    private fun settle(target: Int, velocity: Float) {
        current = target
        val from = scrollX
        val dx = target * width - from
        if (theme.pageSettleMs == 0 || dx == 0) {
            scrollTo(target * width, 0)
            settled()
            return
        }
        // a finger still heading for the target hands its speed on, one that stopped or turned
        // back starts the settle from rest
        val speed = if (sign(-velocity) == sign(dx.toFloat())) abs(velocity) / 1000f else 0f
        val ms = if (speed > 0f) {
            (Easing.MAX_START_SLOPE * abs(dx) / speed).toLong().coerceAtMost(theme.pageSettleMs.toLong())
        } else {
            theme.pageSettleMs.toLong()
        }
        settling = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            interpolator = Easing.settle(speed * ms / abs(dx))
            addUpdateListener { scrollTo(from + (dx * it.animatedFraction).roundToInt(), 0) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    settling = null
                    settled()
                }
            })
            start()
        }
    }

    private fun settled() {
        val page = current
        post { onSettled?.invoke(page) }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (childCount < 2) return
        val d = theme.spacing.s
        val r = d / 2
        val total = childCount * d + (childCount - 1) * d
        var x = scrollX + (width - total) / 2 + r
        // centred in the bottom gutter, the grid leaves spacing.m free there
        val y = height - theme.spacing.m / 2
        for (i in 0 until childCount) {
            if (i == current) canvas.drawCircle(x, y, r, dotFill) else canvas.drawCircle(x, y, r - dotRing.strokeWidth / 2, dotRing)
            x += d * 2
        }
    }

    override fun shouldDelayChildPressedState() = true
}
