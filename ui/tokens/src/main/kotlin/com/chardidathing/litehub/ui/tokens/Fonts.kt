package com.chardidathing.litehub.ui.tokens

import android.content.res.AssetManager
import android.graphics.Typeface
import kotlin.math.abs

// loads each bundled font file once, call off the main thread (it reads assets)
class Fonts(private val assets: AssetManager) {

    private val cache = HashMap<String, Typeface>()

    @Synchronized
    fun typeface(family: String, weight: Int): Typeface {
        val files = BUNDLED[family]
            ?: return Typeface.create(Typeface.SANS_SERIF, weight, false)
        val nearest = files.keys.minBy { abs(it - weight) }
        val path = files.getValue(nearest)
        return cache.getOrPut(path) { Typeface.createFromAsset(assets, path) }
    }

    companion object {
        const val LEXEND = "lexend"

        // anything not listed here falls through to the system sans
        private val BUNDLED = mapOf(
            LEXEND to mapOf(
                400 to "fonts/lexend/regular.ttf",
                500 to "fonts/lexend/medium.ttf",
                600 to "fonts/lexend/semibold.ttf",
            ),
        )
    }
}
