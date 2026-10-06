package com.chardidathing.litehub

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Size
import android.view.SurfaceHolder
import com.chardidathing.litehub.dlna.Player
import com.chardidathing.litehub.dlna.PlayerEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import kotlin.math.roundToInt

// the dlna renderer's player over MediaPlayer. the renderer calls in from its server threads and
// MediaPlayer calls back on main, so everything touching it is synchronized. events go out
// through a post, never inline, the renderer may be holding its own lock when it calls us
class MediaPlayback(private val context: Context) : Player {

    var events: (PlayerEvent) -> Unit = {}

    // the picture's size while what's playing has one (0 by 0 until it's known), the activity
    // puts a surface up for it
    private val _video = MutableStateFlow<Size?>(null)
    val video: StateFlow<Size?> = _video

    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private var mp: MediaPlayer? = null
    private var uri: String? = null
    private var prepared = false
    private var wanted = false
    private var hasVideo = false
    private var display: SurfaceHolder? = null

    @Synchronized
    override fun load(uri: String) {
        release()
        wanted = false
        this.uri = uri
        open(uri)
    }

    @Synchronized
    override fun play() {
        wanted = true
        val p = mp
        if (p == null) uri?.let(::open) else startIfReady(p)
    }

    @Synchronized
    override fun pause() {
        wanted = false
        if (prepared) mp?.pause()
    }

    @Synchronized
    override fun stop() {
        wanted = false
        release()
    }

    @Synchronized
    override fun seek(ms: Long) {
        if (prepared) mp?.seekTo(ms.toInt())
    }

    @Synchronized
    override fun positionMs(): Long = if (prepared) mp?.currentPosition?.toLong() ?: 0 else 0

    @Synchronized
    override fun durationMs(): Long {
        val d = if (prepared) mp?.duration?.toLong() ?: 0 else 0
        // streams with no length (ha's tts proxy) come back as hundreds of hours, call it unknown
        return if (d in 0..MAX_DURATION_MS) d else 0
    }

    override var volume: Int
        get() = (audio.getStreamVolume(AudioManager.STREAM_MUSIC) * PERCENT / max()).roundToInt()
        set(value) = audio.setStreamVolume(AudioManager.STREAM_MUSIC, (value * max() / PERCENT).roundToInt(), 0)

    override var muted: Boolean
        get() = audio.isStreamMute(AudioManager.STREAM_MUSIC)
        set(value) = audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (value) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)

    @Synchronized
    fun attach(holder: SurfaceHolder?) {
        display = holder
        val p = mp ?: return
        p.setDisplay(holder)
        if (holder != null) startIfReady(p)
        else if (hasVideo) {
            // the picture went away under it (the hub left the foreground), that's the end of it
            release()
            send(PlayerEvent.ENDED)
        }
    }

    private fun max() = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat()

    private fun open(uri: String) {
        val p = MediaPlayer()
        p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
        // audio carries on with the screen blanked, the cpu has to stay up for it
        p.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
        p.setOnPreparedListener(::prepared)
        // a released player can still have callbacks queued, only the current one counts
        p.setOnCompletionListener { if (current(it)) send(PlayerEvent.ENDED) }
        p.setOnErrorListener { mp, what, extra ->
            if (current(mp)) {
                AppLog.add("dlna player error $what/$extra")
                send(PlayerEvent.FAILED)
            }
            true
        }
        p.setOnVideoSizeChangedListener { mp, w, h -> if (current(mp) && w > 0 && h > 0) _video.value = Size(w, h) }
        display?.let(p::setDisplay)
        mp = p
        prepared = false
        hasVideo = false
        try {
            p.setDataSource(uri)
            p.prepareAsync()
        } catch (e: IOException) {
            failed(e)
        } catch (e: IllegalArgumentException) {
            failed(e)
        } catch (e: IllegalStateException) {
            failed(e)
        }
    }

    @Synchronized
    private fun current(p: MediaPlayer) = p === mp

    // the tracks are known now. a video waits for the activity's surface before it starts,
    // rockchip's player takes the whole media server down if it starts one without
    @Synchronized
    private fun prepared(p: MediaPlayer) {
        if (p !== mp) return
        prepared = true
        hasVideo = p.trackInfo.any { it.trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_VIDEO }
        // 0 by 0 is a picture that's coming but not sized yet, the size change listener fills it in
        if (hasVideo) _video.value = Size(p.videoWidth, p.videoHeight)
        startIfReady(p)
    }

    private fun startIfReady(p: MediaPlayer) {
        if (!wanted || !prepared || (hasVideo && display == null) || p.isPlaying) return
        p.start()
        send(PlayerEvent.PLAYING)
    }

    private fun failed(e: Exception) {
        AppLog.add("dlna couldn't open the stream, ${e.message}")
        release()
        send(PlayerEvent.FAILED)
    }

    private fun release() {
        mp?.release()
        mp = null
        prepared = false
        hasVideo = false
        _video.value = null
    }

    private fun send(event: PlayerEvent) {
        main.post { events(event) }
    }

    private companion object {
        const val PERCENT = 100f
        const val MAX_DURATION_MS = 24L * 60 * 60 * 1000
    }
}
