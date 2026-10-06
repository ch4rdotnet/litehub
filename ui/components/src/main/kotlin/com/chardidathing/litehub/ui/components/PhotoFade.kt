package com.chardidathing.litehub.ui.components

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

// a photo fading in over the last one, for any view that shows photos. never more than two held,
// it owns the bitmaps it's given and recycles each once it's faded out
class PhotoFade(private val view: View, private val durationMs: Int) {

    private var current: Bitmap? = null
    private var next: Bitmap? = null
    private var fade = 1f
    private var fading: ValueAnimator? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    val empty get() = current == null && next == null

    fun show(photo: Bitmap) {
        fading?.end()
        if (current == null || durationMs == 0) {
            current?.recycle()
            current = photo
            view.invalidate()
            return
        }
        next = photo
        fade = 0f
        // one hardware layer for the fade, gone again when it's done
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        fading = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs.toLong()
            addUpdateListener {
                fade = it.animatedFraction
                view.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    current?.recycle()
                    current = next
                    next = null
                    fade = 1f
                    view.setLayerType(View.LAYER_TYPE_NONE, null)
                    view.invalidate()
                }
            })
            start()
        }
    }

    fun draw(canvas: Canvas, x: Float, y: Float) {
        current?.let {
            paint.alpha = OPAQUE
            canvas.drawBitmap(it, x, y, paint)
        }
        next?.let {
            paint.alpha = (fade * OPAQUE).toInt()
            canvas.drawBitmap(it, x, y, paint)
        }
    }

    // when the view leaves the screen
    fun release() {
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
