package com.chardidathing.litehub.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.util.Size
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import com.chardidathing.litehub.ui.tokens.Presets

// a video over everything, fitted to the screen and letterboxed. the surface goes to whoever's
// decoding through onSurface (null when it's gone), a tap anywhere is onTap
@SuppressLint("ViewConstructor")
class VideoFrame(context: Context, private val onSurface: (SurfaceHolder?) -> Unit, private val onTap: () -> Unit) : FrameLayout(context) {

    private val surface = SurfaceView(context)

    // 0 by 0 (or null) fills the screen until the real size turns up
    var videoSize: Size? = null
        set(value) {
            if (value == field) return
            field = value
            requestLayout()
        }

    init {
        setBackgroundColor(Presets.LETTERBOX)
        addView(surface)
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = onSurface(holder)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) = onSurface(null)
        })
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val w = right - left
        val h = bottom - top
        val v = videoSize?.takeIf { it.width > 0 && it.height > 0 }
        if (v == null) {
            surface.layout(0, 0, w, h)
            return
        }
        // the largest rect with the video's aspect that fits
        val scale = minOf(w / v.width.toFloat(), h / v.height.toFloat())
        val sw = (v.width * scale).toInt()
        val sh = (v.height * scale).toInt()
        val x = (w - sw) / 2
        val y = (h - sh) / 2
        surface.layout(x, y, x + sw, y + sh)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val v = videoSize?.takeIf { it.width > 0 && it.height > 0 } ?: return
        val scale = minOf(measuredWidth / v.width.toFloat(), measuredHeight / v.height.toFloat())
        surface.measure(
            MeasureSpec.makeMeasureSpec((v.width * scale).toInt(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec((v.height * scale).toInt(), MeasureSpec.EXACTLY),
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) onTap()
        return true
    }
}
