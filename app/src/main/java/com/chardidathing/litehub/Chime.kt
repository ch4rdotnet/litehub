package com.chardidathing.litehub

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

// the notification chime, loaded the first time it's needed
class Chime(private val context: Context) {

    private var pool: SoundPool? = null
    private var sound = 0
    private var ready = false
    private var pending = false

    fun play() {
        val p = pool ?: SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .build().also { created ->
                pool = created
                created.setOnLoadCompleteListener { sp, id, status ->
                    ready = status == 0
                    if (ready && pending) sp.play(id, 1f, 1f, 0, 0, 1f)
                    pending = false
                }
                sound = created.load(context, R.raw.chime, 1)
            }
        if (ready) p.play(sound, 1f, 1f, 0, 0, 1f) else pending = true
    }

    fun release() {
        pool?.release()
        pool = null
        ready = false
    }
}
