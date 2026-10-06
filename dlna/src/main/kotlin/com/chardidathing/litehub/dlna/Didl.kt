package com.chardidathing.litehub.dlna

// the bits of a DIDL-Lite item worth showing
data class Track(val title: String?, val artist: String?)

internal object Didl {

    fun parse(metadata: String): Track? {
        if (metadata.isBlank()) return null
        val root = Xml.parse(metadata) ?: return null
        val item = Xml.first(root, "item") ?: root
        fun text(local: String) = Xml.first(item, local)?.textContent?.trim()?.ifEmpty { null }
        return Track(title = text("title"), artist = text("artist") ?: text("creator"))
    }
}
