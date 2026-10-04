package com.lanshare.app.core

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Printers on the same Wi-Fi: found with mDNS / DNS-SD ("_ipp._tcp", what AirPrint / Mopria / IPP-Everywhere printers announce) and
 * printed to directly over IPP (see [Ipp], [WifiPrint]) - no PC needed. Ids look like "wifi:192.168.1.50:631/ipp/print".
 */
object WifiPrinters {
    class P(val id: String, val name: String, val host: String, val port: Int, val path: String, val model: String,
            val pdl: List<String>, val color: Boolean?, val duplex: Boolean?) {
        val uri: String get() = "ipp://$host:$port/$path"
        @Volatile var formats: List<String> = pdl            // refined by status() from the printer itself
        @Volatile var sides: List<String> = emptyList()
        @Volatile var colorModes: List<String> = emptyList()
        @Volatile var mediaReady: List<String> = emptyList()      // paper actually loaded
        @Volatile var mediaDefault: List<String> = emptyList()
        @Volatile var mediaSupported: List<String> = emptyList()
    }

    private val map = ConcurrentHashMap<String, P>()
    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "wifi-resolve").also { it.isDaemon = true } }   // resolveService: one at a time
    @Volatile private var started = false
    private var nsd: NsdManager? = null

    @Synchronized
    fun start(ctx: Context) {
        if (started) return
        started = true
        try {
            val m = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
            nsd = m
            m.discoverServices("_ipp._tcp", NsdManager.PROTOCOL_DNS_SD, object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(type: String?, code: Int) { started = false }
                override fun onStopDiscoveryFailed(type: String?, code: Int) {}
                override fun onDiscoveryStarted(type: String?) {}
                override fun onDiscoveryStopped(type: String?) { started = false }
                override fun onServiceFound(info: NsdServiceInfo?) { if (info != null) pool.execute { resolve(m, info) } }
                override fun onServiceLost(info: NsdServiceInfo?) {
                    val n = info?.serviceName ?: return
                    map.values.filter { it.name == n }.forEach { map.remove(it.id) }
                }
            })
        } catch (_: Throwable) { started = false }
    }

    @Suppress("DEPRECATION")
    private fun resolve(m: NsdManager, i: NsdServiceInfo) {
        val latch = CountDownLatch(1)
        try {
            m.resolveService(i, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo?, code: Int) { latch.countDown() }
                override fun onServiceResolved(info: NsdServiceInfo?) { try { if (info != null) add(info) } finally { latch.countDown() } }
            })
            latch.await(6, TimeUnit.SECONDS)
        } catch (_: Throwable) {}
    }

    private fun add(s: NsdServiceInfo) {
        val host = s.host as? Inet4Address ?: return      // the rest of the app talks IPv4 only
        val ip = host.hostAddress ?: return
        fun txt(k: String): String = s.attributes?.get(k)?.let { String(it, Charsets.UTF_8) }.orEmpty()
        val rp = txt("rp").trim('/').ifEmpty { "ipp/print" }
        val model = txt("ty").ifEmpty { txt("product").trim('(', ')') }
        val pdl = txt("pdl").split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val flag = { k: String -> txt(k).takeIf { it.isNotEmpty() }?.equals("T", true) }
        val id = "wifi:$ip:${s.port}/$rp"
        map[id] = P(id, s.serviceName ?: model.ifEmpty { ip }, ip, s.port, rp, model, pdl, flag("Color"), flag("Duplex"))
    }

    fun get(id: String): P? = map[id] ?: Regex("^wifi:([0-9.]+):(\\d+)/(.*)$").find(id)?.let {
        P(id, it.groupValues[1], it.groupValues[1], it.groupValues[2].toInt(), it.groupValues[3], "", emptyList(), null, null)
    }

    fun listJson(): JSONArray {
        Core.appCtx?.let { start(it) }
        val a = JSONArray()
        map.values.sortedBy { it.name.lowercase() }.forEach {
            a.put(JSONObject().put("id", it.id).put("name", it.name).put("model", it.model)
                .put("color", it.color ?: JSONObject.NULL).put("duplex", it.duplex ?: JSONObject.NULL))
        }
        return a
    }

    /** Same shape as the PC print service's /ping answer (ok, printer, problem), so the existing UI can show it. */
    fun status(id: String): JSONObject {
        val p = get(id) ?: return JSONObject().put("ok", false).put("why", "printer not found")
        return try {
            val r = Ipp.printerAttrs(p)
            if (!r.ok) throw IOException(Ipp.statusText(r.status))
            val fm = r.strs("document-format-supported").map { it.lowercase() }
            if (fm.isNotEmpty()) p.formats = fm
            p.sides = r.strs("sides-supported")
            p.colorModes = r.strs("print-color-mode-supported")
            p.mediaReady = r.strs("media-ready"); p.mediaDefault = r.strs("media-default"); p.mediaSupported = r.strs("media-supported")
            val reasons = r.strs("printer-state-reasons").filter { it != "none" }
            val problems = ArrayList<String>()
            if (r.int("printer-state") == 5) problems.add("stopped")
            if (r.bool("printer-is-accepting-jobs") == false) problems.add("not accepting jobs")
            reasons.filter { it.endsWith("-error") }.forEach { problems.add(it.removeSuffix("-error").replace('-', ' ')) }
            JSONObject().put("ok", true).put("wifi", true)
                .put("printer", p.name)
                .put("problem", if (problems.isEmpty()) JSONObject.NULL else problems.distinct().joinToString(", "))
                .put("printers", JSONArray())
                .put("color", if (p.colorModes.isNotEmpty()) p.colorModes.contains("color") else (p.color ?: JSONObject.NULL))
                .put("duplex", if (p.sides.isNotEmpty()) p.sides.any { it.startsWith("two-sided") } else (p.duplex ?: JSONObject.NULL))
        } catch (e: Exception) { JSONObject().put("ok", false).put("why", errText(e)) }
    }
}
