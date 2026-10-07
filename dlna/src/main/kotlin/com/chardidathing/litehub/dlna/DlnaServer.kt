package com.chardidathing.litehub.dlna

import com.chardidathing.litehub.core.model.LanHost
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface

// the hub as a upnp MediaRenderer. ssdp for discovery, a small http server for the description,
// soap control and gena eventing, and a renderer over the app's player
class DlnaServer(
    private val renderer: Renderer,
    private val http: OkHttpClient,
    private val name: String,
    private val uuid: String,
    private val version: String,
    private val log: (String) -> Unit,
) {

    private var server: UpnpHttp? = null
    private var ssdp: Ssdp? = null
    private var gena: Gena? = null

    // address is the lan ipv4 the hub answers on, its interface is the one ssdp joins
    fun start(address: Inet4Address, port: Int): Result<Unit> = try {
        val g = Gena(renderer, http, log)
        renderer.onChange = g::changed
        val s = UpnpHttp(port, ::handle)
        s.start()
        val discovery = Ssdp("http://${address.hostAddress}:$port$DESCRIPTION", uuid, NetworkInterface.getByInetAddress(address))
        gena = g
        server = s
        try {
            discovery.start()
            ssdp = discovery
        } catch (e: IOException) {
            // still reachable by url when multicast is blocked, ha's manual setup takes that
            log("dlna discovery couldn't start, ${e.message}")
        }
        Result.success(Unit)
    } catch (e: IOException) {
        Result.failure(e)
    }

    fun stop() {
        ssdp?.stop()
        server?.stop()
        gena?.close()
        renderer.onChange = {}
        ssdp = null
        server = null
        gena = null
    }

    private fun handle(r: UpnpHttp.Request): UpnpHttp.Response {
        val local = r.remote.isSiteLocalAddress || r.remote.isLoopbackAddress || r.remote.isLinkLocalAddress
        if (!local) return UpnpHttp.Response(FORBIDDEN, "Forbidden")
        // upnp control points never send an Origin, a web page always does. a rebinding page
        // shows up under its own domain in Host
        if (r.header("Origin") != null || !LanHost.accepts(r.header("Host"))) return UpnpHttp.Response(FORBIDDEN, "Forbidden")
        if (r.method == "GET" && r.path == DESCRIPTION) return UpnpHttp.Response(OK, "OK", description(), XML)
        for (service in Service.values()) {
            when (r.path) {
                "/${service.path}.xml" -> if (r.method == "GET") return UpnpHttp.Response(OK, "OK", scpd(service), XML)
                "$CONTROL/${service.path}" -> if (r.method == "POST") return control(service, r)
                "$EVENTS/${service.path}" -> when (r.method) {
                    "SUBSCRIBE" -> return gena?.subscribe(service, r) ?: unavailable()
                    "UNSUBSCRIBE" -> return gena?.unsubscribe(r) ?: unavailable()
                }
            }
        }
        return UpnpHttp.Response(NOT_FOUND, "Not Found")
    }

    private fun control(service: Service, r: UpnpHttp.Request): UpnpHttp.Response {
        // both are what a real soap call carries, and neither can come from a plain web form, so
        // a page can't post control requests without a preflight this server never answers
        if (r.header("Content-Type")?.contains("xml", ignoreCase = true) != true) return fault(Renderer.INVALID_ACTION, "Invalid Action")
        val header = r.header("SOAPACTION")?.trim('"') ?: return fault(Renderer.INVALID_ACTION, "Invalid Action")
        val call = Soap.parse(r.body) ?: return fault(Renderer.INVALID_ACTION, "Invalid Action")
        // SOAPACTION is "type#Action", it has to agree with the body
        if (header != "${service.type}#${call.action}") return fault(Renderer.INVALID_ACTION, "Invalid Action")
        return when (val outcome = renderer.handle(service, call.action, call.args)) {
            is Outcome.Ok -> UpnpHttp.Response(OK, "OK", Soap.response(service, call.action, outcome.out), XML, listOf("EXT" to ""))
            is Outcome.Fault -> fault(outcome.code, outcome.description)
        }
    }

    private fun fault(code: Int, description: String) = UpnpHttp.Response(SERVER_ERROR, "Internal Server Error", Soap.fault(code, description), XML)

    private fun unavailable() = UpnpHttp.Response(SERVICE_UNAVAILABLE, "Service Unavailable")

    private fun scpd(service: Service): String =
        javaClass.getResourceAsStream("/dlna/${service.path}.xml")?.bufferedReader()?.use { it.readText() }.orEmpty()

    private fun description(): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<root xmlns=\"urn:schemas-upnp-org:device-1-0\" xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">")
        append("<specVersion><major>1</major><minor>0</minor></specVersion><device>")
        append("<deviceType>${Ssdp.DEVICE_TYPE}</deviceType>")
        append("<friendlyName>${Xml.escape(name)}</friendlyName>")
        append("<manufacturer>litehub</manufacturer><modelName>litehub</modelName>")
        append("<modelNumber>${Xml.escape(version)}</modelNumber>")
        append("<UDN>uuid:$uuid</UDN><dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC><serviceList>")
        for (s in Service.values()) {
            append("<service><serviceType>${s.type}</serviceType><serviceId>${s.id}</serviceId>")
            append("<SCPDURL>/${s.path}.xml</SCPDURL><controlURL>$CONTROL/${s.path}</controlURL><eventSubURL>$EVENTS/${s.path}</eventSubURL></service>")
        }
        append("</serviceList></device></root>")
    }

    private companion object {
        const val DESCRIPTION = "/description.xml"
        const val CONTROL = "/ctl"
        const val EVENTS = "/evt"
        const val XML = "text/xml; charset=\"utf-8\""
        const val OK = 200
        const val FORBIDDEN = 403
        const val NOT_FOUND = 404
        const val SERVER_ERROR = 500
        const val SERVICE_UNAVAILABLE = 503
    }
}
