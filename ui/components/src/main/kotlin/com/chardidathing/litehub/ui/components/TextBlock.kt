package com.chardidathing.litehub.ui.components

import android.graphics.Canvas
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.chardidathing.litehub.ui.tokens.ResolvedTheme

// text measured once and kept until the text, style or width changes, so draw never allocates
class TextBlock(private val maxLines: Int = Int.MAX_VALUE) {

    private val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG)
    private var text: CharSequence = ""
    private var width = 0
    private var align = Layout.Alignment.ALIGN_NORMAL
    private var layout: StaticLayout? = null

    val height: Int get() = layout?.height ?: 0

    // first line's baseline from the top of the block, for lining up mixed sizes
    val baseline: Int get() = layout?.takeIf { it.lineCount > 0 }?.getLineBaseline(0) ?: 0

    // width of the first line as drawn, for placing things after a single line value
    val lineWidth: Float get() = layout?.takeIf { it.lineCount > 0 }?.getLineWidth(0) ?: 0f

    fun set(text: CharSequence, style: ResolvedTheme.Text, color: Int, width: Int, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL) {
        val changed = text != this.text || width != this.width || align != this.align ||
            style.size != paint.textSize || style.typeface != paint.typeface || color != paint.color
        if (!changed && layout != null) return
        this.text = text
        this.width = width
        this.align = align
        paint.textSize = style.size
        paint.typeface = style.typeface
        paint.color = color
        layout = if (width <= 0) null else StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(align)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setIncludePad(false)
            .build()
    }

    // a soft shadow behind the glyphs, for text that sits on photos
    fun shadow(radius: Float, color: Int) {
        paint.setShadowLayer(radius, 0f, 0f, color)
        layout = null
    }

    // single line height for a style, without laying anything out
    fun lineHeight(style: ResolvedTheme.Text): Float {
        paint.textSize = style.size
        paint.typeface = style.typeface
        return paint.fontMetrics.let { it.descent - it.ascent }
    }

    fun width(text: CharSequence, style: ResolvedTheme.Text): Float {
        paint.textSize = style.size
        paint.typeface = style.typeface
        return Layout.getDesiredWidth(text, paint)
    }

    fun draw(canvas: Canvas, x: Float, y: Float) {
        val l = layout ?: return
        canvas.save()
        canvas.translate(x, y)
        l.draw(canvas)
        canvas.restore()
    }
}
