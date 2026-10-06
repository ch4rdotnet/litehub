package com.chardidathing.litehub.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.chardidathing.litehub.ui.tokens.Easing
import com.chardidathing.litehub.ui.tokens.ResolvedTheme

// a notification card for the top right corner, a title over the message. slides in from the
// right edge and back out, tap it to send it away early
class NoticeView(context: Context, private val theme: ResolvedTheme, private val title: String?, private val message: String, private val onDismiss: () -> Unit) :
    View(context) {

    private val card = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.colors.primary }
    private val titleBlock = TextBlock(maxLines = 1)
    private val messageBlock = TextBlock(maxLines = MAX_LINES)

    init {
        isClickable = true
    }

    // a third of the screen or a few touch targets, whichever is narrower
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec)
        val w = minOf(available / SCREEN_SHARE, (theme.touchTarget * WIDTH_TARGETS).toInt())
        layoutText(w)
        val inner = (if (title != null) titleBlock.height + theme.spacing.xs else 0f) + messageBlock.height
        setMeasuredDimension(w, (inner + theme.spacing.m * 2).toInt())
    }

    private fun layoutText(w: Int) {
        val textWidth = (w - theme.spacing.m * 2).toInt()
        titleBlock.set(title.orEmpty(), theme.type.subtitle1, theme.colors.onPrimary, textWidth)
        messageBlock.set(message, theme.type.body1, theme.colors.onPrimary, textWidth)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        card.set(0f, 0f, w.toFloat(), h.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        val r = theme.radii.medium
        canvas.drawRoundRect(card, r, r, paint)
        var y = theme.spacing.m
        if (title != null) {
            titleBlock.draw(canvas, theme.spacing.m, y)
            y += titleBlock.height + theme.spacing.xs
        }
        messageBlock.draw(canvas, theme.spacing.m, y)
    }

    fun slideIn() {
        // off the right edge first, measured width plus the margin it sits in from the edge. hidden
        // till then, the first frame can land before the post does
        visibility = INVISIBLE
        post {
            translationX = width + theme.spacing.m
            visibility = VISIBLE
            animate().translationX(0f).setDuration(theme.slideMs.toLong()).setInterpolator(Easing.settle(0f)).start()
        }
    }

    fun slideOut(done: () -> Unit) {
        animate().translationX(width + theme.spacing.m).setDuration(theme.slideMs.toLong()).setInterpolator(Easing.settle(0f))
            .withEndAction(done).start()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) onDismiss()
        return true
    }

    private companion object {
        const val MAX_LINES = 4
        const val SCREEN_SHARE = 3
        const val WIDTH_TARGETS = 7
    }
}
