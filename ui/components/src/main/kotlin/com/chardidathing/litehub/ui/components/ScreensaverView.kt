package com.chardidathing.litehub.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import com.chardidathing.litehub.ui.tokens.ResolvedTheme
import kotlin.random.Random

// the screensaver, a clock and date over photos (or over black without any). a new photo fades
// in over the last one, never more than two held. the clock moves a little every minute so
// nothing sits in one place long enough to burn in
class ScreensaverView(context: Context, private val theme: ResolvedTheme) : View(context) {

    private var current: Bitmap? = null
    private var next: Bitmap? = null
    private var fade = 1f
    private var fading: ValueAnimator? = null
    private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val time = TextBlock(maxLines = 1)
    private val date = TextBlock(maxLines = 1)
    private var timeText = ""
    private var dateText = ""
    private var driftX = 0f
    private var driftY = 0f

    init {
        setBackgroundColor(theme.screenOff)
        isClickable = true
        // the clock has to read on a bright photo as well as on black
        time.shadow(theme.spacing.xs, theme.screenOff)
        date.shadow(theme.spacing.xs, theme.screenOff)
    }

    // takes ownership of the bitmap, the one it replaces is recycled once it's faded out
    fun showPhoto(photo: Bitmap) {
        fading?.end()
        if (current == null || theme.crossfadeMs == 0) {
            current?.recycle()
            current = photo
            invalidate()
            return
        }
        next = photo
        fade = 0f
        // one hardware layer for the fade, gone again when it's done
        setLayerType(LAYER_TYPE_HARDWARE, null)
        fading = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = theme.crossfadeMs.toLong()
            addUpdateListener {
                fade = it.animatedFraction
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    current?.recycle()
                    current = next
                    next = null
                    fade = 1f
                    setLayerType(LAYER_TYPE_NONE, null)
                    invalidate()
                }
            })
            start()
        }
    }

    fun showTime(timeText: String, dateText: String) {
        if (timeText == this.timeText && dateText == this.dateText) return
        this.timeText = timeText
        this.dateText = dateText
        val range = theme.spacing.xl
        driftX = Random.nextFloat() * range
        driftY = Random.nextFloat() * range
        layoutText()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutText()

    private fun layoutText() {
        val w = width - (theme.spacing.xl * 2).toInt()
        time.set(timeText, theme.type.h1, theme.colors.onBackground, w)
        date.set(dateText, theme.type.h5, theme.colors.onBackground, w)
    }

    override fun onDraw(canvas: Canvas) {
        current?.let {
            photoPaint.alpha = OPAQUE
            canvas.drawBitmap(it, 0f, 0f, photoPaint)
        }
        next?.let {
            photoPaint.alpha = (fade * OPAQUE).toInt()
            canvas.drawBitmap(it, 0f, 0f, photoPaint)
        }
        val x = theme.spacing.xl + driftX
        val bottom = height - theme.spacing.xl - driftY
        date.draw(canvas, x, bottom - date.height)
        time.draw(canvas, x, bottom - date.height - time.height)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        fading?.cancel()
        current?.recycle()
        next?.recycle()
        current = null
        next = null
    }

    private companion object {
        const val OPAQUE = 255
    }
}
