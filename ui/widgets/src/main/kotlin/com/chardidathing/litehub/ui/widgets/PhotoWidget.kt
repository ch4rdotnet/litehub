package com.chardidathing.litehub.ui.widgets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import com.chardidathing.litehub.ui.components.PhotoFade
import com.chardidathing.litehub.ui.components.TextBlock
import com.chardidathing.litehub.ui.components.WidgetView
import com.chardidathing.litehub.ui.tokens.ResolvedTheme
import kotlinx.serialization.Serializable

// seconds null uses the screensaver's own
@Serializable
data class PhotoConfig(val seconds: Int? = null)

// the screensaver's photos in a tile, edge to edge with the tile's corners, one fading into the
// next. photos are decoded at the tile's size, so a 1 by 1 costs a small bitmap, not a full screen one
class PhotoWidget(context: Context, theme: ResolvedTheme, val config: PhotoConfig) : WidgetView(context, theme) {

    private val photos = PhotoFade(this, theme.crossfadeMs)
    private val note = TextBlock(maxLines = MAX_NOTE_LINES)
    private val corners = Path()
    private var message: String? = "loading photos"
    private var failed = false

    // takes ownership of the bitmap, like the screensaver
    fun show(photo: Bitmap) {
        message = null
        failed = false
        photos.show(photo)
    }

    // a plain note where photos would be, not a failure (a preview that doesn't load any)
    fun note(text: String) {
        message = text
        failed = false
        onContentChanged()
        invalidate()
    }

    // a failure shows over the last photo only when there isn't one yet
    fun fail(reason: String) {
        message = reason
        failed = true
        onContentChanged()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        corners.reset()
        val r = theme.radii.medium
        corners.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), r, r, Path.Direction.CW)
    }

    override fun onContentChanged() {
        val color = if (failed) theme.colors.error else theme.colors.onSurface
        note.set(message.orEmpty(), theme.type.body2, color, content.width().toInt())
    }

    override fun drawContent(canvas: Canvas) {
        if (photos.empty) {
            note.draw(canvas, content.left, content.top)
            return
        }
        canvas.save()
        canvas.clipPath(corners)
        photos.draw(canvas, 0f, 0f)
        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        photos.release()
    }

    private companion object {
        const val MAX_NOTE_LINES = 4
    }
}
