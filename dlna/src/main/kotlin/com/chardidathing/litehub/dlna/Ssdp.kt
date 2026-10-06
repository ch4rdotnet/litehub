package com.chardidathing.litehub.dlna

import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.random.Random

// ssdp discovery. answers searches and announces itself every few minutes, so ha's ssdp
// listener finds the hub without being told where it is
internal class Ssdp(private val location: String, private val uuid: String, private val iface: NetworkInterface?) {

    // changes every start. control points that saw us before (ha) take a new one as a reboot
    // and subscribe again, otherwise they sit on subscriptions that died with the process
    private val bootId = System.currentTimeMillis() / MS_PER_S

    private var socket: MulticastSocket? = null
    private var timer: ScheduledExecutorService? = null

    // nt to usn, the root device, the device itself, its type and each service
    val targets: List<Pair<String, String>> = buildList {
        add("upnp:rootdevice" to "uuid:$uuid::upnp:rootdevice")
        add("uuid:$uuid" to "uuid:$uuid")
        add(DEVICE_TYPE to "uuid:$uuid::$DEVICE_TYPE")
        for (s in Service.values()) add(s.type to "uuid:$uuid::${s.type}")
    }

    fun start() {
        val s = MulticastSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(PORT))
            timeToLive = TTL
            if (iface != null) networkInterface = iface
            joinGroup(InetSocketAddress(GROUP, PORT), iface)
        }
        socket = s
        Thread({ listen(s) }, "dlna-ssdp").apply { isDaemon = true }.start()
        timer = Executors.newSingleThreadScheduledExecutor().apply {
            scheduleWithFixedDelay({ announce("ssdp:alive") }, 0, ANNOUNCE_S, TimeUnit.SECONDS)
        }
    }

    fun stop() {
        timer?.shutdownNow()
        timer = null
        announce("ssdp:byebye")
        socket?.let { s ->
            runCatching { s.leaveGroup(InetSocketAddress(GROUP, PORT), iface) }
            s.close()
        }
        socket = null
    }

    private fun listen(s: MulticastSocket) {
        val buffer = ByteArray(BUFFER)
        while (!s.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                s.receive(packet)
            } catch (e: IOException) {
                break
            }
            val text = String(packet.data, 0, packet.length, Charsets.ISO_8859_1)
            val replies = replies(text)
            if (replies.isEmpty()) continue
            val to = packet.socketAddress
            // spread over a little of mx so a room full of devices doesn't answer at once
            val delayMs = Random.nextLong(REPLY_SPREAD_MS)
            timer?.schedule({ replies.forEach { send(it, to) } }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    // the responses an M-SEARCH wants, none for anything that isn't one aimed at us
    fun replies(request: String): List<String> {
        val lines = request.split("\r\n", "\n")
        if (!lines.first().startsWith("M-SEARCH", ignoreCase = true)) return emptyList()
        val headers = lines.drop(1).mapNotNull { l ->
            val colon = l.indexOf(':')
            if (colon > 0) l.substring(0, colon).trim().uppercase(Locale.ROOT) to l.substring(colon + 1).trim() else null
        }.toMap()
        if (headers["MAN"]?.trim('"') != "ssdp:discover") return emptyList()
        val st = headers["ST"] ?: return emptyList()
        val matching = if (st == "ssdp:all") targets else targets.filter { it.first == st }
        return matching.map { (nt, usn) ->
            "HTTP/1.1 200 OK\r\n" +
                "CACHE-CONTROL: max-age=$MAX_AGE_S\r\n" +
                "DATE: ${DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC))}\r\n" +
                "EXT:\r\n" +
                "LOCATION: $location\r\n" +
                "SERVER: ${UpnpHttp.SERVER}\r\n" +
                "ST: $nt\r\n" +
                "USN: $usn\r\n" +
                "BOOTID.UPNP.ORG: $bootId\r\n\r\n"
        }
    }

    private fun announce(nts: String) {
        for ((nt, usn) in targets) {
            val message = "NOTIFY * HTTP/1.1\r\n" +
                "HOST: ${GROUP.hostAddress}:$PORT\r\n" +
                "CACHE-CONTROL: max-age=$MAX_AGE_S\r\n" +
                "LOCATION: $location\r\n" +
                "NT: $nt\r\n" +
                "NTS: $nts\r\n" +
                "SERVER: ${UpnpHttp.SERVER}\r\n" +
                "USN: $usn\r\n" +
                "BOOTID.UPNP.ORG: $bootId\r\n\r\n"
            send(message, InetSocketAddress(GROUP, PORT))
        }
    }

    private fun send(message: String, to: SocketAddress) {
        val s = socket ?: return
        val bytes = message.toByteArray(Charsets.ISO_8859_1)
        try {
            s.send(DatagramPacket(bytes, bytes.size, to))
        } catch (e: IOException) {
            // wifi dropped for a moment, the next announce goes out anyway
        }
    }

    companion object {
        const val DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"
        val GROUP: InetAddress = InetAddress.getByAddress(byteArrayOf(239.toByte(), 255.toByte(), 255.toByte(), 250.toByte()))
        private const val PORT = 1900
        private const val TTL = 2
        private const val BUFFER = 2048
        private const val MAX_AGE_S = 1800
        // a third of max-age, a couple of missed announcements still don't expire us
        private const val ANNOUNCE_S = 600L
        private const val REPLY_SPREAD_MS = 500L
        private const val MS_PER_S = 1000L
    }
}
