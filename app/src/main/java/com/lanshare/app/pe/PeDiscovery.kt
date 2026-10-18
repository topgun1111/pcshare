package com.lanshare.app.pe

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import org.json.JSONObject

/**
 * Answers the PC app's UDP broadcast "PHONEEXPLORER_DISCOVER" on port 41234 with a small JSON
 * (device name, FTP port, FTP user). The PIN is never broadcast.
 */
class PeDiscovery(private val deviceName: String, private val ftpPort: Int, private val user: String) {
    @Volatile private var running = false
    private var socket: DatagramSocket? = null

    fun start() {
        if (running) return
        running = true
        Thread {
            try {
                val s = DatagramSocket(null)
                s.reuseAddress = true
                s.bind(InetSocketAddress(PORT))
                socket = s
                val buf = ByteArray(256)
                while (running) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val msg = String(p.data, 0, p.length, Charsets.UTF_8).trim()
                    if (msg == "PHONEEXPLORER_DISCOVER") {
                        val json = JSONObject()
                            .put("app", "PhoneExplorer")
                            .put("name", deviceName)
                            .put("port", ftpPort)
                            .put("user", user)
                            .toString()
                            .toByteArray(Charsets.UTF_8)
                        s.send(DatagramPacket(json, json.size, p.address, p.port))
                    }
                }
            } catch (_: Exception) {
            }
        }.also { it.isDaemon = true }.start()
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
    }

    companion object {
        const val PORT = 41234
    }
}
