package com.chardidathing.litehub.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import com.chardidathing.litehub.ui.tokens.Easing
import com.chardidathing.litehub.ui.tokens.Presets
import com.chardidathing.litehub.ui.tokens.ResolvedTheme
import kotlin.math.abs

// a panel pulled down from the top of the screen over the dashboard. a tap below it, a swipe up
// on it, or close() sends it back up. whatever's inside is the caller's
@SuppressLint("ViewConstructor")
class ShadeView(context: Context, private val theme: ResolvedTheme, content: View, private val onClosed: () -> Unit) : FrameLayout(context) {

    private val scrim = View(context).apply {
        setBackgroundColor(Presets.SCRIM)
        alpha = 0f
    }
    private val panel = FrameLayout(context).apply {
        setBackgroundColor(theme.colors.background)
        val pad = theme.spacing.m.toInt()
        setPadding(pad, pad, pad, pad)
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downY = 0f
    private var closing = false

    init {
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        isClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // the panel takes the top part of the screen, the rest is there to tap away
        val h = MeasureSpec.getSize(heightMeasureSpec)
        panel.layoutParams.height = (h * PANEL_SHARE).toInt()
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun open() {
        post {
            panel.translationY = -panel.height.toFloat()
            scrim.alpha = 0f
            fade(1f)
            panel.animate().translationY(0f).setDuration(theme.slideMs.toLong()).setInterpolator(Easing.settle(0f)).start()
        }
    }

    fun close() {
        if (closing) return
        closing = true
        fade(0f)
        panel.animate().translationY(-panel.height.toFloat()).setDuration(theme.slideMs.toLong()).setInterpolator(Easing.settle(0f))
            .withEndAction(onClosed).start()
    }

    private fun fade(to: Float) {
        scrim.animate().alpha(to).setDuration(theme.slideMs.toLong()).start()
    }

    // a swipe upward anywhere on the panel closes it, taps still reach what's inside
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> downY = ev.y
            MotionEvent.ACTION_MOVE -> if (downY - ev.y > slop && abs(downY - ev.y) > slop) return true
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> downY = ev.y
            MotionEvent.ACTION_MOVE -> if (downY - ev.y > slop) close()
            // below the panel, a plain tap closes it
            MotionEvent.ACTION_UP -> if (ev.y > panel.bottom) close()
        }
        return true
    }

    private companion object {
        const val PANEL_SHARE = 0.6f
    }
}
