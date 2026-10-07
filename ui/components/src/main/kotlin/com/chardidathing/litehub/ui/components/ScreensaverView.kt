package com.chardidathing.litehub.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.text.Layout
import android.view.View
import com.chardidathing.litehub.ui.tokens.Presets
import com.chardidathing.litehub.ui.tokens.ResolvedTheme
import kotlin.random.Random

// the screensaver, a clock and date over photos (or over black without any), and the weather
// opposite the clock when it's wanted. a new photo fades in over the last one, never more than
// two held. both move a little every minute so nothing sits in one place long enough to burn in
class ScreensaverView(context: Context, private val theme: ResolvedTheme) : View(context) {

    // already worded. detail is the condition and maybe today's range, days is empty unless asked for
    data class Weather(val icon: Path?, val temperature: String, val detail: String, val days: List<Day> = emptyList())

    data class Day(val label: String, val icon: Path?, val range: String)

    private class DayBlock(val label: TextBlock = TextBlock(maxLines = 1), val icon: IconBlock = IconBlock(), val range: TextBlock = TextBlock(maxLines = 1))

    private var weather: Weather? = null
    private val weatherIcon = IconBlock()
    private val temperature = TextBlock(maxLines = 1)
    private val detail = TextBlock(maxLines = 1)
    private var days: List<DayBlock> = emptyList()
    private var dayWidth = 0f
    private var weatherWidth = 0
    // its own paint, measuring through a block that's laid out changes how it draws
    private val measure = TextBlock()

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
        weatherIcon.shadow(theme.spacing.xs, theme.screenOff)
        temperature.shadow(theme.spacing.xs, theme.screenOff)
        detail.shadow(theme.spacing.xs, theme.screenOff)
    }

    // null takes it away
    fun showWeather(next: Weather?) {
        if (next == weather) return
        weather = next
        layoutText()
        invalidate()
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
        layoutWeather(w / 2)
    }

    // right aligned in half the width, the clock has the other half
    private fun layoutWeather(w: Int) {
        val wx = weather ?: return
        weatherWidth = w
        val opposite = Layout.Alignment.ALIGN_OPPOSITE
        temperature.set(wx.temperature, theme.type.h2, Presets.OVER_PHOTO, w, opposite)
        weatherIcon.set(wx.icon, temperature.height.toFloat(), Presets.OVER_PHOTO)
        detail.set(wx.detail, theme.type.h5, Presets.OVER_PHOTO, w, opposite)
        val style = theme.type.body1
        dayWidth = wx.days.maxOfOrNull { maxOf(measure.width(it.label, style), measure.width(it.range, style), theme.iconSize) }
            ?.plus(theme.spacing.l) ?: 0f
        days = wx.days.map { d ->
            DayBlock().apply {
                // shadows first, setting one drops the text's layout
                label.shadow(theme.spacing.xs, theme.screenOff)
                range.shadow(theme.spacing.xs, theme.screenOff)
                icon.shadow(theme.spacing.xs, theme.screenOff)
                val center = Layout.Alignment.ALIGN_CENTER
                label.set(d.label, style, Presets.OVER_PHOTO, dayWidth.toInt(), center)
                icon.set(d.icon, theme.iconSize, Presets.OVER_PHOTO)
                range.set(d.range, style, Presets.OVER_PHOTO, dayWidth.toInt(), center)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        photos.draw(canvas, 0f, 0f)
        val x = theme.spacing.xl + driftX
        val bottom = height - theme.spacing.xl - driftY
        date.draw(canvas, x, bottom - date.height)
        time.draw(canvas, x, bottom - date.height - time.height)
        if (weather != null) drawWeather(canvas, width - theme.spacing.xl - driftX, bottom)
    }

    // from the bottom up, the days, then the detail line, then the icon and temperature
    private fun drawWeather(canvas: Canvas, right: Float, bottom: Float) {
        val half = weatherWidth.toFloat()
        var y = bottom
        if (days.isNotEmpty()) {
            val dayHeight = days.maxOf { it.label.height + theme.spacing.xs + theme.iconSize + theme.spacing.xs + it.range.height }
            y -= dayHeight
            days.forEachIndexed { i, d ->
                val x = right - (days.size - i) * dayWidth
                d.label.draw(canvas, x, y)
                val iconTop = y + d.label.height + theme.spacing.xs
                d.icon.draw(canvas, x + (dayWidth - theme.iconSize) / 2, iconTop)
                d.range.draw(canvas, x, iconTop + theme.iconSize + theme.spacing.xs)
            }
            y -= theme.spacing.m
        }
        y -= detail.height
        detail.draw(canvas, right - half, y)
        y -= temperature.height
        temperature.draw(canvas, right - half, y)
        weatherIcon.draw(canvas, right - temperature.lineWidth - theme.spacing.s - temperature.height, y)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        photos.release()
    }
}
