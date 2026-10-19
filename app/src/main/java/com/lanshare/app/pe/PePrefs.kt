package com.lanshare.app.pe

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.random.Random

/** Settings of the Phone Explorer feature (FTP share of the phone storage for Windows Explorer). */
object PePrefs {
    const val USER = "phone"
    const val DEFAULT_PORT = 2121

    private fun sp(c: Context) = c.getSharedPreferences("lanshare_pe", Context.MODE_PRIVATE)

    fun pin(c: Context): String =
        sp(c).getString("pin", null) ?: Random.nextInt(100000, 1000000).toString().also { sp(c).edit().putString("pin", it).apply() }

    fun newPin(c: Context): String = Random.nextInt(100000, 1000000).toString().also { sp(c).edit().putString("pin", it).apply() }

    fun port(c: Context): Int = sp(c).getInt("port", DEFAULT_PORT)
    fun setPort(c: Context, p: Int) { sp(c).edit().putInt("port", p.coerceIn(1024, 65535)).apply() }

    fun readOnly(c: Context): Boolean = sp(c).getBoolean("readonly", false)
    fun setReadOnly(c: Context, v: Boolean) { sp(c).edit().putBoolean("readonly", v).apply() }

    /** FTPS (explicit TLS, AUTH TLS) offered next to plain FTP; applies after Stop + Start. */
    fun tls(c: Context): Boolean = sp(c).getBoolean("tls", false)
    fun setTls(c: Context, v: Boolean) { sp(c).edit().putBoolean("tls", v).apply() }
    /** Plain FTP logins / data connections are refused (Windows Explorer cannot connect then). Only counts while FTPS is on. */
    fun tlsRequired(c: Context): Boolean = sp(c).getBoolean("tls_req", false)
    fun setTlsRequired(c: Context, v: Boolean) { sp(c).edit().putBoolean("tls_req", v).apply() }

    fun autoStart(c: Context): Boolean = sp(c).getBoolean("autostart", false)
    fun setAutoStart(c: Context, v: Boolean) { sp(c).edit().putBoolean("autostart", v).apply() }

    /** Comma-separated folders relative to the storage root, e.g. "DCIM, Download". Empty = everything. */
    fun whitelistText(c: Context): String = sp(c).getString("whitelist", "") ?: ""
    fun setWhitelistText(c: Context, v: String) { sp(c).edit().putString("whitelist", v.trim()).apply() }
    fun whitelist(c: Context): List<String> =
        whitelistText(c).split(",", ";", "\n").map { it.trim().trim('/') }.filter { it.isNotEmpty() }

    /** Link the PC tool imports (tools/phone_explorer.py --pair LINK); also shown as a QR code. */
    fun pairingLink(c: Context, name: String): String =
        "phoneexplorer://$USER:${pin(c)}@${localIp() ?: "0.0.0.0"}:${port(c)}/?name=" + java.net.URLEncoder.encode(name, "UTF-8")

    /** Best guess of the phone's LAN IPv4 (prefers wlan, then hotspot interfaces). */
    fun localIp(): String? {
        val found = mutableListOf<Pair<String, String>>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) if (a is Inet4Address && !a.isLoopbackAddress) found.add(ni.name to a.hostAddress!!)
            }
        } catch (_: Exception) {}
        return (found.firstOrNull { it.first.startsWith("wlan") }
            ?: found.firstOrNull { it.first.startsWith("ap") || it.first.startsWith("swlan") }
            ?: found.firstOrNull())?.second
    }
}
