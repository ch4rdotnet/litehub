package com.chardidathing.litehub.dlna

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

class ServerTest {

    private val player = FakePlayer()
    private val renderer = Renderer(player) {}
    private val client = OkHttpClient()
    private val listener = MockWebServer()
    private var port = 0
    private val server = DlnaServer(renderer, client, "test hub", "abc", "1") {}

    @Before
    fun up() {
        port = ServerSocket(0).use { it.localPort }
        // loopback, ssdp may or may not manage to join there and the rest doesn't care
        server.start(InetAddress.getByName("127.0.0.1") as Inet4Address, port).getOrThrow()
        listener.start()
    }

    @After
    fun down() {
        server.stop()
        listener.close()
    }

    private fun url(path: String) = "http://127.0.0.1:$port$path"

    private fun soap(service: Service, action: String, args: String): Pair<Int, String> {
        val body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" +
            "<u:$action xmlns:u=\"${service.type}\"><InstanceID>0</InstanceID>$args</u:$action></s:Body></s:Envelope>"
        val request = Request.Builder().url(url("/ctl/${service.path}"))
            .header("SOAPACTION", "\"${service.type}#$action\"")
            .post(body.toRequestBody("text/xml".toMediaType())).build()
        return client.newCall(request).execute().use { it.code to it.body.string() }
    }

    @Test
    fun `description and scpds are served`() {
        val description = client.newCall(Request.Builder().url(url("/description.xml")).build()).execute().use { it.body.string() }
        assertTrue(description.contains("<friendlyName>test hub</friendlyName>"))
        assertTrue(description.contains("<UDN>uuid:abc</UDN>"))
        val scpd = client.newCall(Request.Builder().url(url("/AVTransport.xml")).build()).execute().use { it.body.string() }
        assertTrue(scpd.contains("<name>SetAVTransportURI</name>"))
    }

    @Test
    fun `soap control drives the player`() {
        val meta = "&lt;DIDL-Lite xmlns:dc=&quot;http://purl.org/dc/elements/1.1/&quot;&gt;&lt;item&gt;&lt;dc:title&gt;Hi&lt;/dc:title&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;"
        val (code, _) = soap(Service.AV_TRANSPORT, "SetAVTransportURI", "<CurrentURI>http://10.0.0.2/a.mp3</CurrentURI><CurrentURIMetaData>$meta</CurrentURIMetaData>")
        assertEquals(200, code)
        assertEquals("Hi", renderer.state.value.track?.title)
        val (_, info) = soap(Service.AV_TRANSPORT, "GetTransportInfo", "")
        assertTrue(info, info.contains("<CurrentTransportState>STOPPED</CurrentTransportState>"))
        val (faultCode, fault) = soap(Service.AV_TRANSPORT, "Explode", "")
        assertEquals(500, faultCode)
        assertTrue(fault.contains("<errorCode>401</errorCode>"))
    }

    @Test
    fun `subscribers get the state then each change`() {
        listener.enqueue(MockResponse.Builder().build())
        listener.enqueue(MockResponse.Builder().build())
        val callback = "<http://127.0.0.1:${listener.port}/events>"
        val subscribe = Request.Builder().url(url("/evt/AVTransport"))
            .method("SUBSCRIBE", null)
            .header("CALLBACK", callback).header("NT", "upnp:event").header("TIMEOUT", "Second-300").build()
        val sid = client.newCall(subscribe).execute().use {
            assertEquals(200, it.code)
            assertEquals("Second-300", it.header("TIMEOUT"))
            it.header("SID")!!
        }
        val first = listener.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("NOTIFY", first.method)
        assertEquals("0", first.headers["SEQ"])
        assertEquals(sid, first.headers["SID"])
        assertTrue(first.body!!.utf8().contains("NO_MEDIA_PRESENT"))

        soap(Service.AV_TRANSPORT, "SetAVTransportURI", "<CurrentURI>http://10.0.0.2/a.mp3</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>")
        val second = listener.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("1", second.headers["SEQ"])
        assertTrue(second.body!!.utf8().contains("STOPPED"))
    }

    @Test
    fun `callbacks off the lan are refused`() {
        assertNull(Gena.callback("<http://8.8.8.8/x>"))
        assertNull(Gena.callback("<http://example.com/x>"))
        assertEquals("10.0.0.5", Gena.callback("<http://10.0.0.5:8000/ev>")?.host)
    }

    @Test
    fun `ssdp answers searches for us only`() {
        val ssdp = Ssdp("http://10.0.0.9:1/description.xml", "abc", null)
        val search = { st: String -> "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: $st\r\n\r\n" }
        assertEquals(1, ssdp.replies(search(Ssdp.DEVICE_TYPE)).size)
        assertEquals(ssdp.targets.size, ssdp.replies(search("ssdp:all")).size)
        assertTrue(ssdp.replies(search("urn:schemas-upnp-org:device:MediaServer:1")).isEmpty())
        assertTrue(ssdp.replies(search("upnp:rootdevice")).single().contains("USN: uuid:abc::upnp:rootdevice"))
        assertTrue(InetAddress.getByName("239.255.255.250") == Ssdp.GROUP)
    }

    private fun post(path: String, body: String, headers: Map<String, String>): Int {
        val b = Request.Builder().url(url(path)).post(body.toRequestBody("text/plain".toMediaType()))
        headers.forEach { (k, v) -> b.header(k, v) }
        return client.newCall(b.build()).execute().use { it.code }
    }

    @Test
    fun `a plain web form post can't drive playback`() {
        val play = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" +
            "<u:Play xmlns:u=\"${Service.AV_TRANSPORT.type}\"><InstanceID>0</InstanceID><Speed>1</Speed></u:Play></s:Body></s:Envelope>"
        // no SOAPACTION and a form's content type, what a cross site page can send without asking
        assertEquals(500, post("/ctl/AVTransport", play, emptyMap()))
        // a page that does manage the headers still carries its Origin
        assertEquals(403, post("/ctl/AVTransport", play, mapOf("Origin" to "http://evil.example", "SOAPACTION" to "x")))
        // and a rebinding page shows up under its own domain
        assertEquals(403, post("/ctl/AVTransport", play, mapOf("Host" to "evil.example:49152")))
        assertTrue(player.calls.isEmpty())
    }

    @Test
    fun `nesting deep enough to overflow the parser is refused before it`() {
        val bomb = "<a>".repeat(80_000)
        assertTrue(Xml.deeperThan(bomb, 64))
        assertNull(Xml.parse(bomb))
        assertTrue(!Xml.deeperThan("<s:Envelope><s:Body><u:Play><InstanceID>0</InstanceID><x/></u:Play></s:Body></s:Envelope>", 64))
    }

    @Test
    fun `a url with a line break in it is refused`() {
        val (code, body) = soap(Service.AV_TRANSPORT, "SetAVTransportURI", "<CurrentURI>http://10.0.0.2/a.mp3&#10;12:00:00 fake line</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>")
        assertEquals(500, code)
        assertTrue(body.contains("<errorCode>402</errorCode>"))
    }

    @Test
    fun `hosts a browser on the home network would use`() {
        for (ok in listOf(null, "10.201.0.254:8080", "localhost:8080", "litehub", "litehub.local", "hub.lan:49152", "[fe80::1]:8080")) assertTrue(ok.toString(), com.chardidathing.litehub.core.model.LanHost.accepts(ok))
        for (bad in listOf("evil.example", "evil.example:8080", "10.0.0.1.nip.io", "")) assertTrue(bad, !com.chardidathing.litehub.core.model.LanHost.accepts(bad))
    }
}
