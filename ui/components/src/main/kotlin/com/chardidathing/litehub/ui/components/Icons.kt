package com.chardidathing.litehub.ui.components

import android.content.res.AssetManager
import android.graphics.Path

// the bundled mdi subset (tools/mdi), 24 unit square paths. construct off the main thread
class Icons(assets: AssetManager) {

    private val data: Map<String, String> =
        assets.open("icons/mdi.txt").bufferedReader().useLines { lines ->
            lines.filter { '\t' in it }.associate { it.substringBefore('\t') to it.substringAfter('\t') }
        }
    private val parsed = HashMap<String, Path>()

    fun has(name: String) = name in data

    // shared instance, don't mutate it
    @Synchronized
    fun path(name: String): Path? = parsed[name] ?: data[name]?.let(::parse)?.also { parsed[name] = it }

    // gen.py only emits absolute M, L, C and Z
    private fun parse(d: String): Path {
        val t = d.split(' ')
        val path = Path()
        var i = 0
        fun f() = t[i++].toFloat()
        while (i < t.size) {
            when (t[i++]) {
                "M" -> path.moveTo(f(), f())
                "L" -> path.lineTo(f(), f())
                "C" -> path.cubicTo(f(), f(), f(), f(), f(), f())
                "Z" -> path.close()
            }
        }
        return path
    }

    companion object {
        const val UNITS = 24f
    }
}
