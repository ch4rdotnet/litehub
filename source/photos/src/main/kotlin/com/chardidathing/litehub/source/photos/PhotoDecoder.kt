package com.chardidathing.litehub.source.photos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import java.io.IOException
import kotlin.math.max

// decodes straight to about the panel's size and crops to fill it, in rgb565. a 12mp photo
// never exists in memory at full size, the frame holds two of these at most
object PhotoDecoder {

    fun decode(bytes: ByteArray, width: Int, height: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("that isn't an image")
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: throw IOException("couldn't decode that image")
        if (raw.width == width && raw.height == height) return raw
        // scale to cover the panel, centred, the edges that don't fit are cropped
        val scale = max(width / raw.width.toFloat(), height / raw.height.toFloat())
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        val m = Matrix().apply {
            setScale(scale, scale)
            postTranslate((width - raw.width * scale) / 2, (height - raw.height * scale) / 2)
        }
        Canvas(out).drawBitmap(raw, m, Paint(Paint.FILTER_BITMAP_FLAG))
        raw.recycle()
        return out
    }
}
