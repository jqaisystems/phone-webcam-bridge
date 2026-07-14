package com.jq.phonewebcam

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import kotlin.concurrent.thread

class DiscoveryBeacon(
    context: Context,
    private val phonePort: Int,
    private val discoveryPort: Int,
    private val pinProvider: () -> String
) {
    private val appContext = context.applicationContext
    private val deviceId = AppIdentity.deviceId(appContext)

    @Volatile
    private var running = false
    private var worker: Thread? = null

    fun start() {
        if (running) {
            return
        }

        running = true
        worker = thread(name = "phone-webcam-discovery", isDaemon = true) {
            while (running) {
                try {
                    DatagramSocket().use { socket ->
                        socket.broadcast = true
                        while (running) {
                            broadcastOnce(socket)
                            Thread.sleep(2000)
                        }
                    }
                } catch (_: InterruptedException) {
                    running = false
                } catch (_: Exception) {
                    // The network changed or the socket broke: wait, then
                    // recreate the socket and keep announcing on the new network.
                    try {
                        Thread.sleep(2000)
                    } catch (_: InterruptedException) {
                        running = false
                    }
                }
            }
        }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }

    private fun broadcastOnce(socket: DatagramSocket) {
        val ip = NetworkUtils.localIpv4Address()
        val payload = JSONObject()
            .put("type", "jq-phone-webcam")
            .put("version", 1)
            .put("id", deviceId)
            .put("deviceName", Build.MODEL ?: "Android Phone")
            .put("port", phonePort)
            .put("baseUrl", ip?.let { "http://$it:$phonePort" } ?: JSONObject.NULL)
            .put("pinRequired", pinProvider().isNotBlank())
            .put("packageName", appContext.packageName)
            .toString()
            .toByteArray(Charsets.UTF_8)

        for (target in broadcastTargets()) {
            try {
                socket.send(DatagramPacket(payload, payload.size, target, discoveryPort))
            } catch (_: Exception) {
                // Some interfaces reject broadcast; keep trying the others.
            }
        }
    }

    private fun broadcastTargets(): List<InetAddress> {
        val targets = mutableListOf<InetAddress>()
        try {
            targets.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) {
        }

        // Subnet-directed broadcasts (e.g. 192.168.1.255) reach the PC on
        // networks where routers drop the global 255.255.255.255 address.
        try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { networkInterface ->
                if (!networkInterface.isUp || networkInterface.isLoopback) {
                    return@forEach
                }
                networkInterface.interfaceAddresses.forEach { interfaceAddress ->
                    interfaceAddress.broadcast?.let { targets.add(it) }
                }
            }
        } catch (_: Exception) {
        }

        return targets.distinct()
    }
}
