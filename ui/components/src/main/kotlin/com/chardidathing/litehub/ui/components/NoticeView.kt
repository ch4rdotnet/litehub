package com.chardidathing.litehub.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.chardidathing.litehub.ui.tokens.ResolvedTheme

// a notification banner across the top of the dashboard, a title over the message. tap to
// dismiss, whoever shows it also takes it away after a while
class NoticeView(context: Context, private val theme: ResolvedTheme, private val title: String?, private val message: String, private val onDismiss: () -> Unit) :
    View(context) {

    private val card = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.colors.primary }
    private val titleBlock = TextBlock(maxLines = 1)
    private val messageBlock = TextBlock(maxLines = MAX_LINES)

    init {
        isClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        layoutText(w)
        val inner = (if (title != null) titleBlock.height + theme.spacing.xs else 0f) + messageBlock.height
        setMeasuredDimension(w, (inner + theme.spacing.m * 2 + theme.spacing.m).toInt())
    }

    private fun layoutText(w: Int) {
        val textWidth = (w - theme.spacing.m * 4).toInt()
        titleBlock.set(title.orEmpty(), theme.type.subtitle1, theme.colors.onPrimary, textWidth)
        messageBlock.set(message, theme.type.body1, theme.colors.onPrimary, textWidth)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        card.set(theme.spacing.m, theme.spacing.m, w - theme.spacing.m, h.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        val r = theme.radii.medium
        canvas.drawRoundRect(card, r, r, paint)
        var y = card.top + theme.spacing.m
        if (title != null) {
            titleBlock.draw(canvas, card.left + theme.spacing.m, y)
            y += titleBlock.height + theme.spacing.xs
        }
        messageBlock.draw(canvas, card.left + theme.spacing.m, y)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) onDismiss()
        return true
    }

    private companion object {
        const val MAX_LINES = 4
    }
}
