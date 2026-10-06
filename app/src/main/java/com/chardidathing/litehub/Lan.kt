package com.chardidathing.litehub

import java.net.Inet4Address
import java.net.NetworkInterface

object Lan {

    // the address other machines on the network reach the hub on, null with no network
    fun address(): Inet4Address? = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress } as Inet4Address?
}
