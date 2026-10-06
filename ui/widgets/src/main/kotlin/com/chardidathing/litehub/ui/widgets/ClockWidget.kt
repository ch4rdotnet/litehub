package com.chardidathing.litehub.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.text.Layout
import com.chardidathing.litehub.ui.components.TextBlock
import com.chardidathing.litehub.ui.components.WidgetView
import com.chardidathing.litehub.ui.tokens.ResolvedTheme
import kotlinx.serialization.Serializable

@Serializable
data class ClockConfig(val date: Boolean = true)

// the time as big as the tile allows, the date under it. sized against the widest time the
// format can show, so the digits don't jump a size between minutes
class ClockWidget(context: Context, theme: ResolvedTheme, val config: ClockConfig) : WidgetView(context, theme) {

    private val time = TextBlock(maxLines = 1)
    private val date = TextBlock(maxLines = 1)
    private var moment: Moment? = null

    fun show(now: Moment) {
        if (now.nowMs / MINUTE_MS == moment?.nowMs?.div(MINUTE_MS)) return
        moment = now
        onContentChanged()
        invalidate()
    }

    override fun onContentChanged() {
        val m = moment ?: return
        val w = content.width()
        val h = content.height()
        val dateText = if (config.date) m.longDate() else ""
        date.set(dateText, theme.type.subtitle1, theme.colors.onSurface, w.toInt(), Layout.Alignment.ALIGN_CENTER)
        val room = h - if (config.date) date.height + theme.spacing.xs else 0f
        val widest = if (m.hour24) WIDEST_24 else WIDEST_12
        val sizes = with(theme.type) { listOf(h1, h2, h3, h4, h5, h6, subtitle1) }
        // the biggest step on the theme's type scale that fits, the smallest if none do
        val style = sizes.firstOrNull { s -> time.width(widest, s) <= w && time.lineHeight(s) <= room } ?: sizes.last()
        time.set(m.time(m.nowMs), style, theme.colors.onSurface, w.toInt(), Layout.Alignment.ALIGN_CENTER)
    }

    override fun drawContent(canvas: Canvas) {
        if (moment == null) return
        val total = time.height + if (config.date) theme.spacing.xs + date.height else 0f
        val top = content.top + (content.height() - total) / 2
        time.draw(canvas, content.left, top)
        if (config.date) date.draw(canvas, content.left, top + time.height + theme.spacing.xs)
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        // the widest each format gets, measured instead of the time itself
        const val WIDEST_12 = "12:59pm"
        const val WIDEST_24 = "23:59"
    }
}
