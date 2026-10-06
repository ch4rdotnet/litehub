package com.chardidathing.litehub.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakePlayer : Player {
    val calls = mutableListOf<String>()
    var position = 0L
    var duration = 0L
    override fun load(uri: String) { calls += "load $uri" }
    override fun play() { calls += "play" }
    override fun pause() { calls += "pause" }
    override fun stop() { calls += "stop" }
    override fun seek(ms: Long) { calls += "seek $ms" }
    override fun positionMs() = position
    override fun durationMs() = duration
    override var volume = 40
    override var muted = false
}

class RendererTest {

    private val player = FakePlayer()
    private val renderer = Renderer(player) {}
    private val zero = mapOf("InstanceID" to "0")

    private fun avt(action: String, args: Map<String, String> = emptyMap()) = renderer.handle(Service.AV_TRANSPORT, action, zero + args)

    private fun load(uri: String = "http://10.0.0.2/a.mp3", meta: String = "") =
        avt("SetAVTransportURI", mapOf("CurrentURI" to uri, "CurrentURIMetaData" to meta))

    @Test
    fun `play goes through transitioning until the player starts`() {
        load()
        assertEquals(Transport.STOPPED, renderer.state.value.transport)
        avt("Play", mapOf("Speed" to "1"))
        assertEquals(Transport.TRANSITIONING, renderer.state.value.transport)
        renderer.onPlayer(PlayerEvent.PLAYING)
        assertEquals(Transport.PLAYING, renderer.state.value.transport)
        assertEquals(listOf("load http://10.0.0.2/a.mp3", "play"), player.calls)
    }

    @Test
    fun `pause and resume`() {
        load()
        avt("Play")
        renderer.onPlayer(PlayerEvent.PLAYING)
        avt("Pause")
        assertEquals(Transport.PAUSED, renderer.state.value.transport)
        avt("Play")
        assertEquals(Transport.PLAYING, renderer.state.value.transport)
    }

    @Test
    fun `nothing loaded refuses play`() {
        val out = avt("Play")
        assertEquals(Renderer.TRANSITION_NOT_AVAILABLE, (out as Outcome.Fault).code)
    }

    @Test
    fun `only http urls are accepted`() {
        val out = load("file:///sdcard/secret")
        assertEquals(Renderer.RESOURCE_NOT_FOUND, (out as Outcome.Fault).code)
        assertTrue(player.calls.isEmpty())
    }

    @Test
    fun `wrong instance is refused`() {
        val out = renderer.handle(Service.AV_TRANSPORT, "Play", mapOf("InstanceID" to "3"))
        assertEquals(Renderer.INVALID_INSTANCE, (out as Outcome.Fault).code)
    }

    @Test
    fun `failure stops with an error status`() {
        load()
        avt("Play")
        renderer.onPlayer(PlayerEvent.FAILED)
        val info = (avt("GetTransportInfo") as Outcome.Ok).out.toMap()
        assertEquals("STOPPED", info["CurrentTransportState"])
        assertEquals("ERROR_OCCURRED", info["CurrentTransportStatus"])
    }

    @Test
    fun `position and metadata come back`() {
        val meta = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="1" parentID="0" restricted="1"><dc:title>Song</dc:title><upnp:artist>Band</upnp:artist><upnp:class>object.item.audioItem.musicTrack</upnp:class></item></DIDL-Lite>"""
        load(meta = meta)
        avt("Play")
        renderer.onPlayer(PlayerEvent.PLAYING)
        player.position = 83_500
        player.duration = 3_725_000
        val info = (avt("GetPositionInfo") as Outcome.Ok).out.toMap()
        assertEquals("0:01:23", info["RelTime"])
        assertEquals("1:02:05", info["TrackDuration"])
        assertEquals(meta, info["TrackMetaData"])
        assertEquals(Track("Song", "Band"), renderer.state.value.track)
    }

    @Test
    fun `seek parses upnp times`() {
        load()
        avt("Play")
        renderer.onPlayer(PlayerEvent.PLAYING)
        avt("Seek", mapOf("Unit" to "REL_TIME", "Target" to "0:01:30.250"))
        assertEquals("seek 90250", player.calls.last())
        val bad = avt("Seek", mapOf("Unit" to "TRACK_NR", "Target" to "1"))
        assertEquals(Renderer.SEEK_MODE, (bad as Outcome.Fault).code)
    }

    @Test
    fun `volume and mute`() {
        val rc = mapOf("InstanceID" to "0", "Channel" to "Master")
        renderer.handle(Service.RENDERING_CONTROL, "SetVolume", rc + ("DesiredVolume" to "65"))
        assertEquals(65, player.volume)
        renderer.handle(Service.RENDERING_CONTROL, "SetMute", rc + ("DesiredMute" to "1"))
        assertTrue(player.muted)
        val bad = renderer.handle(Service.RENDERING_CONTROL, "SetVolume", rc + ("DesiredVolume" to "101"))
        assertEquals(Renderer.INVALID_ARGS, (bad as Outcome.Fault).code)
    }

    @Test
    fun `last change carries the transport state`() {
        load()
        val change = renderer.evented(Service.AV_TRANSPORT).toMap().getValue("LastChange")
        assertTrue(change.contains("<TransportState val=\"STOPPED\"/>"))
        assertTrue(change.contains("<CurrentTransportActions val=\"Play\"/>"))
    }

    @Test
    fun `clock round trips`() {
        assertEquals("0:00:00", Renderer.clock(0))
        assertEquals(3_725_000L, Renderer.parseClock("1:02:05"))
        assertNull(Renderer.parseClock("1:99:05"))
        assertNull(Renderer.parseClock("NOT_IMPLEMENTED"))
    }
}
