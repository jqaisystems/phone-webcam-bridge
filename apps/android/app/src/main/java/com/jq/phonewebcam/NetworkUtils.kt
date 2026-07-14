package com.jq.phonewebcam

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

object NetworkUtils {
    fun broadcastAddresses(): List<InetAddress> {
        val result = mutableListOf<InetAddress>()
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList() ?: return result
        } catch (_: Exception) {
            return result
        }

        for (networkInterface in interfaces) {
            if (!networkInterface.isUp || networkInterface.isLoopback) {
                continue
            }
            for (interfaceAddress in networkInterface.interfaceAddresses) {
                interfaceAddress.broadcast?.let { result.add(it) }
            }
        }
        return result
    }

    fun localIpv4Address(): String? {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
        for (networkInterface in interfaces) {
            if (!networkInterface.isUp || networkInterface.isLoopback) {
                continue
            }

            val addresses = networkInterface.inetAddresses.toList()
            for (address in addresses) {
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    return address.hostAddress
                }
            }
        }
        return null
    }
}
