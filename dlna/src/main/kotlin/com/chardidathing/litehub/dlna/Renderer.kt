package com.chardidathing.litehub.dlna

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

enum class Service(val type: String, val id: String, val path: String) {
    AV_TRANSPORT("urn:schemas-upnp-org:service:AVTransport:1", "urn:upnp-org:serviceId:AVTransport", "AVTransport"),
    RENDERING_CONTROL("urn:schemas-upnp-org:service:RenderingControl:1", "urn:upnp-org:serviceId:RenderingControl", "RenderingControl"),
    CONNECTION_MANAGER("urn:schemas-upnp-org:service:ConnectionManager:1", "urn:upnp-org:serviceId:ConnectionManager", "ConnectionManager"),
}

enum class Transport(val upnp: String) {
    NO_MEDIA("NO_MEDIA_PRESENT"),
    STOPPED("STOPPED"),
    TRANSITIONING("TRANSITIONING"),
    PLAYING("PLAYING"),
    PAUSED("PAUSED_PLAYBACK"),
}

data class RendererState(val transport: Transport = Transport.NO_MEDIA, val uri: String = "", val metadata: String = "", val track: Track? = null, val failed: Boolean = false)

sealed class Outcome {
    data class Ok(val out: List<Pair<String, String>> = emptyList()) : Outcome()
    data class Fault(val code: Int, val description: String) : Outcome()
}

// one AVTransport instance (0) over one player. single track, no next/previous, no play modes,
// which is everything ha's dlna_dmr uses
class Renderer(private val player: Player, private val log: (String) -> Unit) {

    private val _state = MutableStateFlow(RendererState())
    val state: StateFlow<RendererState> = _state

    // anything evented changed, gena sends whatever differs from last time
    @Volatile var onChange: () -> Unit = {}

    @Synchronized
    fun handle(service: Service, action: String, args: Map<String, String>): Outcome {
        if (service != Service.CONNECTION_MANAGER && args["InstanceID"] != "0") return Outcome.Fault(INVALID_INSTANCE, "Invalid InstanceID")
        return when (service) {
            Service.AV_TRANSPORT -> transport(action, args)
            Service.RENDERING_CONTROL -> rendering(action, args)
            Service.CONNECTION_MANAGER -> connections(action, args)
        }
    }

    // the player's side, from whatever thread it calls back on
    @Synchronized
    fun onPlayer(event: PlayerEvent) {
        val s = _state.value
        when (event) {
            PlayerEvent.PLAYING -> if (s.transport == Transport.TRANSITIONING) set(s.copy(transport = Transport.PLAYING, failed = false))
            PlayerEvent.ENDED -> {
                player.stop()
                set(s.copy(transport = Transport.STOPPED))
            }
            PlayerEvent.FAILED -> {
                log("dlna couldn't play ${s.uri}")
                player.stop()
                set(s.copy(transport = Transport.STOPPED, failed = true))
            }
        }
    }

    // a tap on the hub itself, ha hears about it like any other stop
    @Synchronized
    fun stopHere() {
        if (_state.value.transport == Transport.NO_MEDIA) return
        player.stop()
        set(_state.value.copy(transport = Transport.STOPPED))
    }

    @Synchronized
    fun togglePause() {
        when (_state.value.transport) {
            Transport.PLAYING -> transport("Pause", mapOf())
            Transport.PAUSED, Transport.STOPPED -> transport("Play", mapOf())
            else -> Unit
        }
    }

    private fun transport(action: String, args: Map<String, String>): Outcome {
        val s = _state.value
        return when (action) {
            "SetAVTransportURI" -> {
                val uri = args["CurrentURI"].orEmpty().trim()
                if (!uri.startsWith("http://") && !uri.startsWith("https://")) return Outcome.Fault(RESOURCE_NOT_FOUND, "only http urls play here")
                val metadata = args["CurrentURIMetaData"].orEmpty()
                player.load(uri)
                set(RendererState(Transport.STOPPED, uri, metadata, Didl.parse(metadata)))
                Outcome.Ok()
            }
            "Play" -> when (s.transport) {
                Transport.NO_MEDIA -> notNow()
                // controllers repeat a play while the stream is still opening, that's fine
                Transport.TRANSITIONING, Transport.PLAYING -> Outcome.Ok()
                Transport.PAUSED -> {
                    player.play()
                    set(s.copy(transport = Transport.PLAYING))
                    Outcome.Ok()
                }
                Transport.STOPPED -> {
                    set(s.copy(transport = Transport.TRANSITIONING, failed = false))
                    player.play()
                    Outcome.Ok()
                }
            }
            "Pause" -> if (s.transport != Transport.PLAYING) notNow() else {
                player.pause()
                set(s.copy(transport = Transport.PAUSED))
                Outcome.Ok()
            }
            "Stop" -> {
                if (s.transport != Transport.NO_MEDIA && s.transport != Transport.STOPPED) {
                    player.stop()
                    set(s.copy(transport = Transport.STOPPED))
                }
                Outcome.Ok()
            }
            "Seek" -> {
                val unit = args["Unit"]
                if (unit != "REL_TIME" && unit != "ABS_TIME") return Outcome.Fault(SEEK_MODE, "Seek mode not supported")
                val ms = parseClock(args["Target"].orEmpty()) ?: return Outcome.Fault(ILLEGAL_SEEK, "Illegal seek target")
                if (s.transport == Transport.NO_MEDIA || s.transport == Transport.STOPPED) return notNow()
                player.seek(ms)
                Outcome.Ok()
            }
            "GetTransportInfo" -> Outcome.Ok(
                listOf("CurrentTransportState" to s.transport.upnp, "CurrentTransportStatus" to status(s), "CurrentSpeed" to "1"),
            )
            "GetPositionInfo" -> {
                val loaded = s.transport != Transport.NO_MEDIA
                val position = clock(if (playing(s)) player.positionMs() else 0)
                Outcome.Ok(
                    listOf(
                        "Track" to if (loaded) "1" else "0",
                        "TrackDuration" to clock(player.durationMs()),
                        "TrackMetaData" to s.metadata,
                        "TrackURI" to s.uri,
                        "RelTime" to position,
                        "AbsTime" to position,
                        "RelCount" to COUNT_UNKNOWN,
                        "AbsCount" to COUNT_UNKNOWN,
                    ),
                )
            }
            "GetMediaInfo" -> Outcome.Ok(
                listOf(
                    "NrTracks" to if (s.transport == Transport.NO_MEDIA) "0" else "1",
                    "MediaDuration" to clock(player.durationMs()),
                    "CurrentURI" to s.uri,
                    "CurrentURIMetaData" to s.metadata,
                    "NextURI" to "",
                    "NextURIMetaData" to "",
                    "PlayMedium" to if (s.transport == Transport.NO_MEDIA) "NONE" else "NETWORK",
                    "RecordMedium" to NOT_IMPLEMENTED,
                    "WriteStatus" to NOT_IMPLEMENTED,
                ),
            )
            "GetTransportSettings" -> Outcome.Ok(listOf("PlayMode" to "NORMAL", "RecQualityMode" to NOT_IMPLEMENTED))
            "GetCurrentTransportActions" -> Outcome.Ok(listOf("Actions" to actions(s)))
            "GetDeviceCapabilities" -> Outcome.Ok(listOf("PlayMedia" to "NETWORK", "RecMedia" to NOT_IMPLEMENTED, "RecQualityModes" to NOT_IMPLEMENTED))
            else -> Outcome.Fault(INVALID_ACTION, "Invalid Action")
        }
    }

    private fun rendering(action: String, args: Map<String, String>): Outcome {
        if (action.startsWith("Get") || action.startsWith("Set")) {
            if (args["Channel"] != "Master") return Outcome.Fault(INVALID_ARGS, "Invalid Args")
        }
        return when (action) {
            "GetVolume" -> Outcome.Ok(listOf("CurrentVolume" to player.volume.toString()))
            "SetVolume" -> {
                val v = args["DesiredVolume"]?.toIntOrNull()?.takeIf { it in 0..MAX_VOLUME } ?: return Outcome.Fault(INVALID_ARGS, "Invalid Args")
                player.volume = v
                onChange()
                Outcome.Ok()
            }
            "GetMute" -> Outcome.Ok(listOf("CurrentMute" to if (player.muted) "1" else "0"))
            "SetMute" -> {
                val m = when (args["DesiredMute"]?.lowercase(Locale.ROOT)) {
                    "1", "true", "yes" -> true
                    "0", "false", "no" -> false
                    else -> return Outcome.Fault(INVALID_ARGS, "Invalid Args")
                }
                player.muted = m
                onChange()
                Outcome.Ok()
            }
            else -> Outcome.Fault(INVALID_ACTION, "Invalid Action")
        }
    }

    private fun connections(action: String, args: Map<String, String>): Outcome = when (action) {
        "GetProtocolInfo" -> Outcome.Ok(listOf("Source" to "", "Sink" to SINK_PROTOCOLS))
        "GetCurrentConnectionIDs" -> Outcome.Ok(listOf("ConnectionIDs" to "0"))
        "GetCurrentConnectionInfo" -> if (args["ConnectionID"] != "0") Outcome.Fault(INVALID_CONNECTION, "Invalid connection reference") else Outcome.Ok(
            listOf(
                "RcsID" to "0",
                "AVTransportID" to "0",
                "ProtocolInfo" to "",
                "PeerConnectionManager" to "",
                "PeerConnectionID" to "-1",
                "Direction" to "Input",
                "Status" to "OK",
            ),
        )
        else -> Outcome.Fault(INVALID_ACTION, "Invalid Action")
    }

    // the evented variables per service. AVTransport and RenderingControl wrap theirs in LastChange
    @Synchronized
    fun evented(service: Service): List<Pair<String, String>> {
        val s = _state.value
        return when (service) {
            Service.AV_TRANSPORT -> listOf("LastChange" to lastChange(AVT_EVENT_NS, listOf(
                "TransportState" to s.transport.upnp,
                "TransportStatus" to status(s),
                "CurrentTransportActions" to actions(s),
                "TransportPlaySpeed" to "1",
                "CurrentPlayMode" to "NORMAL",
                "PlaybackStorageMedium" to if (s.transport == Transport.NO_MEDIA) "NONE" else "NETWORK",
                "NumberOfTracks" to if (s.transport == Transport.NO_MEDIA) "0" else "1",
                "CurrentTrack" to if (s.transport == Transport.NO_MEDIA) "0" else "1",
                "CurrentTrackDuration" to clock(player.durationMs()),
                "CurrentMediaDuration" to clock(player.durationMs()),
                "AVTransportURI" to s.uri,
                "AVTransportURIMetaData" to s.metadata,
                "CurrentTrackURI" to s.uri,
                "CurrentTrackMetaData" to s.metadata,
            ), channel = false))
            Service.RENDERING_CONTROL -> listOf("LastChange" to lastChange(RCS_EVENT_NS, listOf(
                "Volume" to player.volume.toString(),
                "Mute" to if (player.muted) "1" else "0",
            ), channel = true))
            Service.CONNECTION_MANAGER -> listOf("SourceProtocolInfo" to "", "SinkProtocolInfo" to SINK_PROTOCOLS, "CurrentConnectionIDs" to "0")
        }
    }

    private fun lastChange(ns: String, values: List<Pair<String, String>>, channel: Boolean): String = buildString {
        append("<Event xmlns=\"").append(ns).append("\"><InstanceID val=\"0\">")
        for ((name, value) in values) {
            append('<').append(name)
            if (channel) append(" channel=\"Master\"")
            append(" val=\"").append(Xml.escape(value)).append("\"/>")
        }
        append("</InstanceID></Event>")
    }

    private fun set(next: RendererState) {
        if (next == _state.value) return
        _state.value = next
        onChange()
    }

    private fun notNow() = Outcome.Fault(TRANSITION_NOT_AVAILABLE, "Transition not available")

    private fun playing(s: RendererState) = s.transport == Transport.PLAYING || s.transport == Transport.PAUSED

    private fun status(s: RendererState) = if (s.failed) "ERROR_OCCURRED" else "OK"

    private fun actions(s: RendererState) = when (s.transport) {
        Transport.NO_MEDIA -> ""
        Transport.STOPPED -> "Play"
        Transport.TRANSITIONING -> "Stop"
        Transport.PLAYING -> "Pause,Stop,Seek"
        Transport.PAUSED -> "Play,Stop,Seek"
    }

    companion object {
        const val INVALID_ACTION = 401
        const val INVALID_ARGS = 402
        const val TRANSITION_NOT_AVAILABLE = 701
        const val SEEK_MODE = 710
        const val ILLEGAL_SEEK = 711
        const val RESOURCE_NOT_FOUND = 716
        const val INVALID_INSTANCE = 718
        const val INVALID_CONNECTION = 706
        const val MAX_VOLUME = 100
        const val NOT_IMPLEMENTED = "NOT_IMPLEMENTED"
        const val COUNT_UNKNOWN = "2147483647"
        const val AVT_EVENT_NS = "urn:schemas-upnp-org:metadata-1-0/AVT/"
        const val RCS_EVENT_NS = "urn:schemas-upnp-org:metadata-1-0/RCS/"

        // what android's MediaPlayer reliably plays, plus a catch all for controllers that only
        // send a url when nothing else matches
        val SINK_PROTOCOLS = listOf(
            "audio/mpeg", "audio/mp4", "audio/x-m4a", "audio/aac", "audio/flac", "audio/x-flac", "audio/ogg", "audio/wav",
            "audio/x-wav", "video/mp4", "video/webm", "video/3gpp", "video/x-matroska", "application/vnd.apple.mpegurl",
            "application/x-mpegurl", "*",
        ).joinToString(",") { "http-get:*:$it:*" }

        private const val MS_PER_S = 1000L
        private const val S_PER_M = 60L
        private const val S_PER_H = 3600L

        // upnp durations, H+:MM:SS
        fun clock(ms: Long): String {
            val s = maxOf(ms, 0) / MS_PER_S
            return String.format(Locale.ROOT, "%d:%02d:%02d", s / S_PER_H, s % S_PER_H / S_PER_M, s % S_PER_M)
        }

        // H+:MM:SS with an optional .fraction, null when it isn't one
        fun parseClock(text: String): Long? {
            val parts = text.trim().split(':')
            if (parts.size != 3) return null
            val h = parts[0].toLongOrNull() ?: return null
            val m = parts[1].toLongOrNull() ?: return null
            val sec = parts[2].substringBefore('.').toLongOrNull() ?: return null
            val fraction = parts[2].substringAfter('.', "").takeWhile(Char::isDigit).padEnd(3, '0').take(3).toLong()
            if (h < 0 || m !in 0 until S_PER_M || sec !in 0 until S_PER_M) return null
            return ((h * S_PER_H + m * S_PER_M + sec) * MS_PER_S) + fraction
        }
    }
}
