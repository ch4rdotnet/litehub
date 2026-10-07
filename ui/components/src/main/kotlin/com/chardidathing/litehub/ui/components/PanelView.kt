package com.chardidathing.litehub.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import com.chardidathing.litehub.ui.tokens.Presets
import com.chardidathing.litehub.ui.tokens.ResolvedTheme

// a card in the middle of the screen over a scrim, for controls that belong to one thing on the
// dashboard (a light). the dashboard stays visible behind, a tap outside the card closes it.
// whatever's inside is the caller's, it scrolls when it's taller than the screen
@SuppressLint("ViewConstructor")
class PanelView(context: Context, private val theme: ResolvedTheme, content: View, private val onClose: () -> Unit) : FrameLayout(context) {

    private val card = ScrollView(context).apply {
        background = GradientDrawable().apply {
            setColor(theme.colors.background)
            cornerRadius = theme.radii.medium
        }
        val pad = theme.spacing.l.toInt()
        setPadding(pad, pad, pad, pad)
        clipToPadding = false
        addView(content)
    }

    init {
        setBackgroundColor(Presets.SCRIM)
        isClickable = true
        addView(card)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val margin = (theme.spacing.xl * 2).toInt()
        val width = minOf(w - margin, (theme.touchTarget * WIDTH_TARGETS).toInt())
        card.layoutParams = LayoutParams(width, LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER)
        card.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h - margin, MeasureSpec.AT_MOST))
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun open() {
        if (theme.slideMs == 0) return
        alpha = 0f
        animate().alpha(1f).setDuration(theme.slideMs.toLong()).start()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // only reached outside the card, the card takes its own touches
        if (e.actionMasked == MotionEvent.ACTION_UP) onClose()
        return true
    }

    private companion object {
        // as wide as a menu, enough for a slider to have some travel
        const val WIDTH_TARGETS = 8
    }
}
