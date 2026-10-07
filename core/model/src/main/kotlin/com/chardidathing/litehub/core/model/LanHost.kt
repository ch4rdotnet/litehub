package com.chardidathing.litehub.core.model

// whether a request's Host header names the hub the way something on the home network would:
// an ip, localhost, a bare name, or a home network name. a dns rebinding page arrives under its
// own public domain, which this turns away. no Host at all isn't a browser, so it's let through
object LanHost {

    private val IPV4 = Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")
    private val HOME_SUFFIXES = listOf(".local", ".lan", ".home", ".home.arpa", ".internal", ".localdomain")

    fun accepts(host: String?): Boolean {
        val h = host?.trim()?.lowercase() ?: return true
        if (h.startsWith("[")) return true
        val name = h.substringBefore(':')
        return name.isNotEmpty() && (IPV4.matches(name) || name == "localhost" || '.' !in name || HOME_SUFFIXES.any(name::endsWith))
    }

    // the host and port an Origin header names, to compare with Host
    fun originHost(origin: String): String = origin.trim().substringAfter("://").substringBefore('/').lowercase()
}
