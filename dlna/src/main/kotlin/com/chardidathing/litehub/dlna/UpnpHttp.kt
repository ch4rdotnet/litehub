package com.chardidathing.litehub.dlna

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// a deliberately small http/1.1 server, one request per connection. nanohttpd can't be used
// here, it refuses SUBSCRIBE and UNSUBSCRIBE which upnp eventing needs
internal class UpnpHttp(private val port: Int, private val handler: (Request) -> Response) {

    class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String, val remote: InetAddress) {
        fun header(name: String) = headers[name.lowercase(Locale.ROOT)]
    }

    // after runs once the response has gone out, gena's first event has to follow the reply
    class Response(val status: Int, val reason: String, val body: String = "", val type: String? = null, val headers: List<Pair<String, String>> = emptyList(), val after: (() -> Unit)? = null)

    private var server: ServerSocket? = null
    private var pool: ExecutorService? = null

    fun start() {
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
        server = socket
        val workers = Executors.newFixedThreadPool(WORKERS)
        pool = workers
        Thread({
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    break
                }
                workers.execute { serve(client) }
            }
        }, "dlna-http").apply { isDaemon = true }.start()
    }

    fun stop() {
        runCatching { server?.close() }
        pool?.shutdownNow()
        server = null
        pool = null
    }

    private fun serve(client: Socket) = client.use { s ->
        try {
            s.soTimeout = TIMEOUT_MS
            val input = BufferedInputStream(s.getInputStream())
            val request = read(input, s.inetAddress)
            val response = if (request == null) Response(BAD_REQUEST, "Bad Request") else answer(request)
            write(s, response)
            response.after?.invoke()
        } catch (e: IOException) {
            // the other end went away mid request, nothing to answer
        }
    }

    // a request that blows up in a way nobody planned for gets a 500, the app carries on
    private fun answer(request: Request): Response = try {
        handler(request)
    } catch (e: StackOverflowError) {
        Response(SERVER_ERROR, "Internal Server Error")
    } catch (e: RuntimeException) {
        Response(SERVER_ERROR, "Internal Server Error")
    }

    private fun read(input: InputStream, remote: InetAddress): Request? {
        val head = line(input) ?: return null
        val parts = head.split(' ')
        if (parts.size != 3) return null
        val headers = HashMap<String, String>()
        var total = 0
        while (true) {
            val l = line(input) ?: return null
            if (l.isEmpty()) break
            total += l.length
            if (total > MAX_HEADERS) return null
            val colon = l.indexOf(':')
            if (colon > 0) headers[l.substring(0, colon).trim().lowercase(Locale.ROOT)] = l.substring(colon + 1).trim()
        }
        if (headers["transfer-encoding"] != null) return null
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length < 0 || length > MAX_BODY) return null
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) return null
            read += n
        }
        return Request(parts[0].uppercase(Locale.ROOT), parts[1].substringBefore('?'), headers, String(body, Charsets.UTF_8), remote)
    }

    private fun line(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) break
            if (out.size() > MAX_LINE) return null
            out.write(b)
        }
        return out.toString("ISO-8859-1").trimEnd('\r')
    }

    private fun write(s: Socket, r: Response) {
        val body = r.body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(r.status).append(' ').append(r.reason).append("\r\n")
            append("Server: ").append(SERVER).append("\r\n")
            append("Connection: close\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            r.type?.let { append("Content-Type: ").append(it).append("\r\n") }
            for ((k, v) in r.headers) append(k).append(": ").append(v).append("\r\n")
            append("\r\n")
        }
        val out = s.getOutputStream()
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    companion object {
        const val SERVER = "Android/1.0 UPnP/1.0 litehub/1.0"
        const val BAD_REQUEST = 400
        const val SERVER_ERROR = 500
        private const val WORKERS = 4
        private const val TIMEOUT_MS = 5_000
        private const val MAX_LINE = 8 * 1024
        private const val MAX_HEADERS = 16 * 1024
        private const val MAX_BODY = 256 * 1024
    }
}
