package com.chardidathing.litehub.dlna

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// upnp eventing. subscribers get the evented variables straight away and again whenever they
// change. never call into the renderer while holding subs, the renderer calls changed() holding its own lock
internal class Gena(private val renderer: Renderer, http: OkHttpClient, private val log: (String) -> Unit) {

    private class Sub(val sid: String, val service: Service, val callback: HttpUrl, var expires: Long) {
        var seq = 0L
        var last: List<Pair<String, String>>? = null
        @Volatile var failing = false
    }

    private val client = http.newBuilder().callTimeout(NOTIFY_TIMEOUT_S, TimeUnit.SECONDS).build()
    private val subs = HashMap<String, Sub>()
    private val sender: ExecutorService = Executors.newSingleThreadExecutor()

    fun subscribe(service: Service, r: UpnpHttp.Request): UpnpHttp.Response {
        val timeout = timeout(r.header("TIMEOUT"))
        val renew = r.header("SID")
        if (renew != null) {
            if (r.header("CALLBACK") != null || r.header("NT") != null) return UpnpHttp.Response(BAD_REQUEST, "Bad Request")
            synchronized(subs) {
                val sub = subs[renew]?.takeIf { it.service == service && it.expires > System.currentTimeMillis() } ?: return UpnpHttp.Response(PRECONDITION_FAILED, "Precondition Failed")
                sub.expires = System.currentTimeMillis() + timeout * MS_PER_S
            }
            return accepted(renew, timeout, null)
        }
        if (r.header("NT") != "upnp:event") return UpnpHttp.Response(PRECONDITION_FAILED, "Precondition Failed")
        val callback = callback(r.header("CALLBACK")) ?: return UpnpHttp.Response(PRECONDITION_FAILED, "Precondition Failed")
        val sub = Sub("uuid:" + UUID.randomUUID(), service, callback, System.currentTimeMillis() + timeout * MS_PER_S)
        synchronized(subs) {
            purge()
            if (subs.size >= MAX_SUBSCRIBERS) return UpnpHttp.Response(SERVICE_UNAVAILABLE, "Service Unavailable")
            subs[sub.sid] = sub
        }
        log("dlna ${service.path} events going to ${callback.host}:${callback.port}")
        return accepted(sub.sid, timeout) { initial(sub) }
    }

    fun unsubscribe(r: UpnpHttp.Request): UpnpHttp.Response {
        val sid = r.header("SID") ?: return UpnpHttp.Response(PRECONDITION_FAILED, "Precondition Failed")
        val removed = synchronized(subs) { subs.remove(sid) }
        return if (removed == null) UpnpHttp.Response(PRECONDITION_FAILED, "Precondition Failed") else UpnpHttp.Response(OK, "OK")
    }

    // from the renderer, holding its lock
    fun changed() {
        val now = Service.values().associateWith(renderer::evented)
        synchronized(subs) {
            purge()
            for (sub in subs.values) {
                val last = sub.last ?: continue
                val next = now.getValue(sub.service)
                if (next != last) queue(sub, next)
            }
        }
    }

    fun close() {
        sender.shutdownNow()
        synchronized(subs) { subs.clear() }
    }

    private fun initial(sub: Sub) {
        val values = renderer.evented(sub.service)
        synchronized(subs) {
            if (subs[sub.sid] === sub) queue(sub, values)
        }
    }

    // under subs, so the single sender sees events in seq order
    private fun queue(sub: Sub, values: List<Pair<String, String>>) {
        sub.last = values
        val seq = sub.seq++
        val body = propertySet(values)
        val request = Request.Builder().url(sub.callback)
            .method("NOTIFY", body.toRequestBody(XML))
            .header("NT", "upnp:event")
            .header("NTS", "upnp:propchange")
            .header("SID", sub.sid)
            .header("SEQ", seq.toString())
            .build()
        runCatching {
            sender.execute {
                try {
                    client.newCall(request).execute().close()
                    sub.failing = false
                } catch (e: IOException) {
                    // the subscriber's gone or busy, it renews or resubscribes when it's back. said once, not per event
                    if (!sub.failing) log("dlna events to ${sub.callback.host} failed, ${e.message}")
                    sub.failing = true
                }
            }
        }
    }

    private fun accepted(sid: String, timeout: Long, after: (() -> Unit)?) =
        UpnpHttp.Response(OK, "OK", headers = listOf("SID" to sid, "TIMEOUT" to "Second-$timeout"), after = after)

    private fun purge() {
        val now = System.currentTimeMillis()
        subs.values.removeAll { it.expires <= now }
    }

    companion object {
        const val OK = 200
        const val BAD_REQUEST = 400
        const val PRECONDITION_FAILED = 412
        const val SERVICE_UNAVAILABLE = 503
        private const val MS_PER_S = 1000L
        private const val NOTIFY_TIMEOUT_S = 3L
        private const val MAX_SUBSCRIBERS = 32
        private const val DEFAULT_TIMEOUT_S = 1800L
        private const val MIN_TIMEOUT_S = 60L
        private const val MAX_TIMEOUT_S = 7200L
        private val XML = "text/xml; charset=\"utf-8\"".toMediaType()
        private val IPV4 = Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")

        fun timeout(header: String?): Long {
            val seconds = header?.removePrefix("Second-")?.toLongOrNull() ?: DEFAULT_TIMEOUT_S
            return seconds.coerceIn(MIN_TIMEOUT_S, MAX_TIMEOUT_S)
        }

        // "<http://a/b><http://c/d>", the first one that's a plain ip on the local network.
        // anything else would let the hub be pointed at hosts it has no business talking to
        fun callback(header: String?): HttpUrl? = header.orEmpty().split('<', '>')
            .mapNotNull { it.trim().toHttpUrlOrNull() }
            .firstOrNull { url ->
                url.scheme == "http" && IPV4.matches(url.host) && InetAddress.getByName(url.host).let { it.isSiteLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress }
            }

        fun propertySet(values: List<Pair<String, String>>): String = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?><e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">")
            for ((name, value) in values) {
                append("<e:property><").append(name).append('>').append(Xml.escape(value)).append("</").append(name).append("></e:property>")
            }
            append("</e:propertyset>")
        }
    }
}
