package com.chardidathing.litehub.dlna

internal object Soap {

    data class Call(val action: String, val args: Map<String, String>)

    // the action is the first element in the body, its children are the arguments
    fun parse(body: String): Call? {
        val envelope = Xml.parse(body) ?: return null
        val soapBody = Xml.first(envelope, "Body") ?: return null
        val action = Xml.children(soapBody).firstOrNull() ?: return null
        val args = Xml.children(action).associate { Xml.name(it) to it.textContent.orEmpty() }
        return Call(Xml.name(action), args)
    }

    fun response(service: Service, action: String, out: List<Pair<String, String>>): String = envelope(buildString {
        append("<u:").append(action).append("Response xmlns:u=\"").append(service.type).append("\">")
        for ((name, value) in out) append('<').append(name).append('>').append(Xml.escape(value)).append("</").append(name).append('>')
        append("</u:").append(action).append("Response>")
    })

    fun fault(code: Int, description: String): String = envelope(
        "<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>" +
            "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>$code</errorCode>" +
            "<errorDescription>${Xml.escape(description)}</errorDescription></UPnPError></detail></s:Fault>",
    )

    private fun envelope(body: String) = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
        "<s:Body>$body</s:Body></s:Envelope>"
}
