package com.chardidathing.litehub.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.chardidathing.litehub.ui.tokens.ResolvedTheme

// what's playing, a title over an artist line with pause/play and stop beside it
@SuppressLint("ViewConstructor")
class NowPlayingView(context: Context, private val theme: ResolvedTheme, onToggle: () -> Unit, onStop: () -> Unit) : LinearLayout(context) {

    private val lines = Lines()
    private val toggle = ButtonView(context, theme, "pause", onToggle)
    private val stop = ButtonView(context, theme, "stop", onStop)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(lines, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val gap = theme.spacing.s.toInt()
        addView(toggle, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = gap })
        addView(stop, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = gap })
    }

    fun show(title: String, artist: String?, playing: Boolean) {
        toggle.label = if (playing) "pause" else "play"
        lines.set(title, artist)
    }

    private inner class Lines : View(context) {
        private val title = TextBlock(maxLines = 1)
        private val artist = TextBlock(maxLines = 1)
        private var text: Pair<String, String?> = "" to null

        fun set(t: String, a: String?) {
            if (text == t to a) return
            text = t to a
            requestLayout()
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            title.set(text.first, theme.type.subtitle1, theme.colors.onBackground, w)
            artist.set(text.second.orEmpty(), theme.type.body2, theme.colors.onBackground, w)
            val h = title.height + if (text.second != null) theme.spacing.xs + artist.height else 0f
            setMeasuredDimension(w, h.toInt())
        }

        override fun onDraw(canvas: Canvas) {
            title.draw(canvas, 0f, 0f)
            if (text.second != null) artist.draw(canvas, 0f, title.height + theme.spacing.xs)
        }
    }
}
