package com.chardidathing.litehub.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.chardidathing.litehub.ui.tokens.Presets
import com.chardidathing.litehub.ui.tokens.ResolvedTheme
import kotlin.random.Random

// the screensaver, a clock and date over photos (or over black without any). a new photo fades
// in over the last one, never more than two held. the clock moves a little every minute so
// nothing sits in one place long enough to burn in
class ScreensaverView(context: Context, private val theme: ResolvedTheme) : View(context) {

    private val photos = PhotoFade(this, theme.crossfadeMs)
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
    fun showPhoto(photo: Bitmap) = photos.show(photo)

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
        time.set(timeText, theme.type.h1, Presets.OVER_PHOTO, w)
        date.set(dateText, theme.type.h5, Presets.OVER_PHOTO, w)
    }

    override fun onDraw(canvas: Canvas) {
        photos.draw(canvas, 0f, 0f)
        val x = theme.spacing.xl + driftX
        val bottom = height - theme.spacing.xl - driftY
        date.draw(canvas, x, bottom - date.height)
        time.draw(canvas, x, bottom - date.height - time.height)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        photos.release()
    }
}
