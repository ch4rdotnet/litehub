package com.chardidathing.litehub.core.model

// colours as "#rrggbb" text, for forms and css. always opaque
object Hex {

    fun of(argb: Argb): String = "#%06x".format(argb and RGB)

    // null when it isn't six hex digits (the # is optional)
    fun parse(text: String): Argb? {
        val t = text.trim().removePrefix("#")
        if (t.length != DIGITS) return null
        return t.toIntOrNull(BASE)?.let { it or OPAQUE }
    }

    private const val RGB = 0xFFFFFF
    private const val OPAQUE = 0xFF shl 24
    private const val BASE = 16
    private const val DIGITS = 6
}
