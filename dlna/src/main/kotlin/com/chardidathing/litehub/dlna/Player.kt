package com.chardidathing.litehub.dlna

// what the renderer drives. calls arrive on the server's threads, so implementations keep
// themselves safe for that, and report back through the renderer's onPlayer
interface Player {
    // prepare this, but don't start until play()
    fun load(uri: String)
    fun play()
    fun pause()
    // drop the stream, a play() after this starts it again from the top
    fun stop()
    fun seek(ms: Long)
    fun positionMs(): Long
    // 0 while it isn't known yet (still preparing, or a live stream)
    fun durationMs(): Long
    // 0 to 100
    var volume: Int
    var muted: Boolean
}

enum class PlayerEvent { PLAYING, ENDED, FAILED }
