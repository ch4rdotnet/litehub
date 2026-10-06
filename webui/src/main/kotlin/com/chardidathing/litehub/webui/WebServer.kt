package com.chardidathing.litehub.webui

import android.content.res.AssetManager
import com.chardidathing.litehub.core.config.Pin
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    private val sessions = HashSet<String>()
    private val random = SecureRandom()
    private var failures = 0
    private var lockedUntil = 0L

    private fun handle(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response = try {
        if (!onLan(session.remoteIpAddress)) text(NanoHTTPD.Response.Status.FORBIDDEN, "litehub only answers on the local network")
        else route(session)
    } catch (e: IOException) {
        text(NanoHTTPD.Response.Status.INTERNAL_ERROR, e.message ?: "something failed reading the request")
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
            get && path == "/api/config" -> json(access.config())
            s.method == NanoHTTPD.Method.PUT && path == "/api/config" -> saved(access.saveConfig(body(s)))
            get && path == "/api/sources" -> json(access.sources())
            s.method == NanoHTTPD.Method.PUT && path == "/api/sources" -> saved(access.saveSources(body(s)))
            get && path == "/api/settings" -> json(access.settings())
            s.method == NanoHTTPD.Method.PUT && path == "/api/settings" -> saved(access.saveSettings(body(s)))
            get && path == "/api/ha" -> json(access.ha())
            s.method == NanoHTTPD.Method.PUT && path == "/api/ha" -> {
                val params = runCatching { Json.parseToJsonElement(body(s)) }.getOrNull() as? JsonObject
                    ?: return text(NanoHTTPD.Response.Status.BAD_REQUEST, "expected a json object")
                val url = (params["url"] as? JsonPrimitive)?.content ?: return text(NanoHTTPD.Response.Status.BAD_REQUEST, "url is needed")
                val token = (params["token"] as? JsonPrimitive)?.content?.ifBlank { null }
                saved(access.saveHa(url, token))
            }
            get && path == "/api/entities" -> runBlocking { access.entities() }.fold(::json) { text(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE, it.message ?: "home assistant isn't reachable") }
            get && path == "/api/preview.png" -> access.preview()?.let {
                NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "image/png", ByteArrayInputStream(it), it.size.toLong()).apply { addHeader("Cache-Control", "no-store") }
            } ?: text(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE, "nothing on screen to show")
            else -> text(NanoHTTPD.Response.Status.NOT_FOUND, "nothing here")
        }
    }

    private fun login(s: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val stored = access.pinHash ?: return session(text(NanoHTTPD.Response.Status.OK, "no pin set"))
        val now = System.currentTimeMillis()
        if (now < lockedUntil) return text(NanoHTTPD.Response.Status.TOO_MANY_REQUESTS, "too many wrong pins, wait a minute")
        val pin = body(s).trim()
        if (!Pin.matches(pin, stored)) {
            if (++failures >= MAX_FAILURES) {
                failures = 0
                lockedUntil = now + LOCKOUT_MS
            }
            return text(NanoHTTPD.Response.Status.UNAUTHORIZED, "wrong pin")
        }
        failures = 0
        return session(text(NanoHTTPD.Response.Status.OK, "ok"))
    }

    private fun session(r: NanoHTTPD.Response): NanoHTTPD.Response {
        val token = ByteArray(TOKEN_BYTES).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        synchronized(sessions) { sessions += token }
        r.addHeader("Set-Cookie", "$COOKIE=$token; HttpOnly; SameSite=Strict; Path=/")
        return r
    }

    private fun authorised(s: NanoHTTPD.IHTTPSession): Boolean {
        if (access.pinHash == null) return true
        val token = s.cookies.read(COOKIE) ?: return false
        return synchronized(sessions) { token in sessions }
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
        const val MAX_FAILURES = 5
        const val LOCKOUT_MS = 60_000L
        const val MAX_BODY = 1 shl 20
    }
}
