package com.chardidathing.litehub.webui

import android.content.res.AssetManager
import com.chardidathing.litehub.core.config.Pin
import com.chardidathing.litehub.core.model.LanHost
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress
import java.security.SecureRandom

// a tiny server for the browser editor and the status page. lan only, everything else is
// refused. the editor's api needs the device pin when there is one, the status page doesn't
class WebServer(port: Int, private val access: HubAccess, private val assets: AssetManager) {

    private val http = object : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response = handle(session)
    }

    fun start(): Result<Unit> = try {
        http.start(SOCKET_TIMEOUT_MS, true)
        Result.success(Unit)
    } catch (e: IOException) {
        Result.failure(e)
    }

    fun stop() = http.stop()

    // a session only works under the pin it was issued with, so setting or changing the pin
    // logs everyone out, and none outlives SESSION_MS
    private class Session(val pinHash: String, val issuedMs: Long)

    private val sessions = HashMap<String, Session>()
    private val random = SecureRandom()

    private fun handle(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response = try {
        when {
            !onLan(session.remoteIpAddress) -> text(NanoHTTPD.Response.Status.FORBIDDEN, "litehub only answers on the local network")
            !sameSite(session) -> text(NanoHTTPD.Response.Status.FORBIDDEN, "litehub only answers its own pages")
            else -> route(session)
        }
    } catch (e: IOException) {
        text(NanoHTTPD.Response.Status.INTERNAL_ERROR, e.message ?: "something failed reading the request")
    } catch (e: StackOverflowError) {
        // a request built to recurse forever gets an error, not a dead app
        text(NanoHTTPD.Response.Status.INTERNAL_ERROR, "that request was too deep to read")
    }

    // a dns rebinding page asks for the hub under its own domain, and another site's form posts
    // carry that site in Origin. the hub's own pages pass both
    private fun sameSite(s: NanoHTTPD.IHTTPSession): Boolean {
        val host = s.headers["host"]
        if (!LanHost.accepts(host)) return false
        if (s.method == NanoHTTPD.Method.GET || s.method == NanoHTTPD.Method.HEAD) return true
        val origin = s.headers["origin"] ?: return true
        return host != null && LanHost.originHost(origin) == host.trim().lowercase()
    }

    private fun route(s: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val path = s.uri.trimEnd('/').ifEmpty { "/" }
        val get = s.method == NanoHTTPD.Method.GET
        when {
            get && path == "/theme.css" -> return text(NanoHTTPD.Response.Status.OK, access.themeCss(), "text/css")
            get && path.startsWith("/fonts/") -> return asset("fonts/lexend/" + path.substringAfterLast('/'))
        }
        if (path == "/status" || path == "/api/status") {
            if (!access.statusEnabled) return text(NanoHTTPD.Response.Status.NOT_FOUND, "the status page is turned off on the hub")
            return if (path == "/status") asset("webui/status.html") else json(runBlocking { access.status() })
        }
        if (get && path == "/status.js") return asset("webui/status.js")
        if (!access.editorEnabled) {
            return text(NanoHTTPD.Response.Status.NOT_FOUND, "the web editor is turned off on the hub")
        }
        when {
            get && path == "/" -> return asset("webui/index.html")
            get && (path == "/app.js" || path == "/style.css") -> return asset("webui" + path)
            path == "/api/login" && s.method == NanoHTTPD.Method.POST -> return login(s)
        }
        if (!authorised(s)) return text(NanoHTTPD.Response.Status.UNAUTHORIZED, "pin needed")
        return when {
            get && path == "/api/schema" -> json(access.schemas())
            get && path == "/api/themes" -> json(access.presetThemes())
            get && path == "/api/config" -> json(access.config())
            s.method == NanoHTTPD.Method.PUT && path == "/api/config" -> saved(access.saveConfig(body(s)))
            get && path == "/api/sources" -> json(access.sources())
            get && path == "/api/settings" -> json(access.settings())
            s.method == NanoHTTPD.Method.PUT && path == "/api/settings" -> saved(access.saveSettings(body(s)))
            s.method == NanoHTTPD.Method.POST && path == "/api/settings/action" -> runBlocking { access.settingsAction(body(s).trim()) }
                .fold(::json) { text(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE, it.message ?: "that didn't work") }
            get && path == "/api/entities" -> runBlocking { access.entities() }.fold(::json) { text(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE, it.message ?: "home assistant isn't reachable") }
            s.method == NanoHTTPD.Method.POST && path == "/api/tile.png" -> runBlocking { access.tilePreview(body(s)) }?.let {
                NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "image/png", ByteArrayInputStream(it), it.size.toLong())
            } ?: text(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE, "nothing on screen to size a tile against")
            get && path == "/api/preview.png" -> access.preview()?.let {
                NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "image/png", ByteArrayInputStream(it), it.size.toLong()).apply { addHeader("Cache-Control", "no-store") }
            } ?: text(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE, "nothing on screen to show")
            else -> text(NanoHTTPD.Response.Status.NOT_FOUND, "nothing here")
        }
    }

    private fun login(s: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        // with no pin there's nothing to log in to, every request is let through already
        val stored = access.pinHash ?: return text(NanoHTTPD.Response.Status.OK, "no pin set")
        val throttle = access.pinThrottle
        if (!throttle.begin()) {
            return text(NanoHTTPD.Response.Status.TOO_MANY_REQUESTS, "too many wrong pins, try again in ${throttle.waitSeconds()} seconds")
        }
        val pin = body(s).trim()
        if (!Pin.matches(pin, stored)) return text(NanoHTTPD.Response.Status.UNAUTHORIZED, "wrong pin")
        throttle.succeeded()
        return session(text(NanoHTTPD.Response.Status.OK, "ok"), stored)
    }

    private fun session(r: NanoHTTPD.Response, pinHash: String): NanoHTTPD.Response {
        val token = ByteArray(TOKEN_BYTES).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        synchronized(sessions) { sessions[token] = Session(pinHash, System.currentTimeMillis()) }
        r.addHeader("Set-Cookie", "$COOKIE=$token; HttpOnly; SameSite=Strict; Path=/")
        return r
    }

    private fun authorised(s: NanoHTTPD.IHTTPSession): Boolean {
        val pin = access.pinHash ?: return true
        val token = s.cookies.read(COOKIE) ?: return false
        val now = System.currentTimeMillis()
        return synchronized(sessions) {
            sessions.values.removeAll { it.pinHash != pin || now - it.issuedMs > SESSION_MS }
            token in sessions
        }
    }

    // private ranges, loopback and link local, which is what "the lan" means for a home hub
    private fun onLan(address: String?): Boolean {
        val a = address?.let { runCatching { InetAddress.getByName(it) }.getOrNull() } ?: return false
        return a.isSiteLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress
    }

    private fun body(s: NanoHTTPD.IHTTPSession): String {
        val length = s.headers["content-length"]?.toIntOrNull() ?: 0
        if (length > MAX_BODY) throw IOException("that's too big for a config")
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = s.inputStream.read(bytes, read, length - read)
            if (n < 0) break
            read += n
        }
        return String(bytes, 0, read, Charsets.UTF_8)
    }

    private fun saved(result: Result<Unit>): NanoHTTPD.Response =
        result.fold({ text(NanoHTTPD.Response.Status.OK, "saved") }, { text(NanoHTTPD.Response.Status.BAD_REQUEST, it.message ?: "couldn't save") })

    private fun asset(path: String): NanoHTTPD.Response = try {
        val bytes = assets.open(path).use { it.readBytes() }
        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, mime(path), ByteArrayInputStream(bytes), bytes.size.toLong())
    } catch (e: IOException) {
        text(NanoHTTPD.Response.Status.NOT_FOUND, "nothing here")
    }

    private fun mime(path: String) = when (path.substringAfterLast('.')) {
        "html" -> "text/html; charset=utf-8"
        "js" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "ttf" -> "font/ttf"
        else -> "application/octet-stream"
    }

    private fun json(body: String) = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json; charset=utf-8", body)

    private fun text(status: NanoHTTPD.Response.Status, body: String, type: String = "text/plain; charset=utf-8") =
        NanoHTTPD.newFixedLengthResponse(status, type, body)

    private companion object {
        const val SOCKET_TIMEOUT_MS = 10_000
        const val COOKIE = "litehub_session"
        const val TOKEN_BYTES = 24
        // a week, then the pin is asked for again
        const val SESSION_MS = 7 * 24 * 60 * 60 * 1000L
        const val MAX_BODY = 1 shl 20
    }
}
