package com.chardidathing.litehub.ui.components

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path

// an icon scaled once into a square of the given size, so draw never allocates
class IconBlock {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scaled = Path()
    private val matrix = Matrix()
    private var source: Path? = null
    private var size = 0f

    fun set(path: Path?, size: Float, color: Int) {
        paint.color = color
        if (path === source && size == this.size) return
        source = path
        this.size = size
        scaled.reset()
        if (path != null) {
            matrix.setScale(size / Icons.UNITS, size / Icons.UNITS)
            path.transform(matrix, scaled)
        }
    }

    // a soft shadow behind the shape, for icons that sit on photos
    fun shadow(radius: Float, color: Int) = paint.setShadowLayer(radius, 0f, 0f, color)

    fun draw(canvas: Canvas, x: Float, y: Float) {
        if (source == null) return
        canvas.save()
        canvas.translate(x, y)
        canvas.drawPath(scaled, paint)
        canvas.restore()
    }
}
