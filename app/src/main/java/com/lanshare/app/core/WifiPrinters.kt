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
            val pdl: List<String>, val color: Boolean?, val duplex: Boolean?, val viaPeer: String? = null) {
        val uri: String get() = "ipp://$host:$port/$path"
        /** Id of the printer as the device that owns it knows it ("wifi:ip:port/path"); ids of remote printers are "rwifi:<peer id>|<that id>". */
        val origId: String get() = if (viaPeer != null) id.substringAfter('|') else id
        @Volatile var seen: Long = System.currentTimeMillis()
        @Volatile var formats: List<String> = pdl            // refined by status() from the printer itself
        @Volatile var sides: List<String> = emptyList()
        @Volatile var colorModes: List<String> = emptyList()
        @Volatile var mediaReady: List<String> = emptyList()      // paper actually loaded
        @Volatile var mediaDefault: List<String> = emptyList()
        @Volatile var mediaSupported: List<String> = emptyList()
    }

    private val map = ConcurrentHashMap<String, P>()
    private val remote = ConcurrentHashMap<String, P>()   // printers on other devices (reached through that device, see [remoteJson])
    private val WIFI_ID = Regex("^wifi:([0-9.]+):(\\d+)/(.*)$")
    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "wifi-resolve").also { it.isDaemon = true } }   // resolveService: one at a time
    @Volatile private var started = false
    @Volatile private var watching = false
    @Volatile private var listener: NsdManager.DiscoveryListener? = null
    private var nsd: NsdManager? = null

    /** Printers remembered from earlier scans: with the screen off Android stops delivering mDNS, but the printer itself still answers on its IPP port. */
    private fun loadSaved() {
        try {
            val a = Cfg.printers()
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isEmpty() || map.containsKey(id)) continue
                map[id] = P(id, o.optString("name"), o.optString("host"), o.optInt("port", 631), o.optString("path"), o.optString("model"), emptyList(),
                    if (o.has("color")) o.optBoolean("color") else null, if (o.has("duplex")) o.optBoolean("duplex") else null).also { it.seen = o.optLong("t", 0L) }
            }
        } catch (_: Exception) {}
    }

    private fun saveAll() {
        try {
            val a = JSONArray()
            for (p in map.values.sortedByDescending { it.seen }.take(20)) {
                val o = JSONObject().put("id", p.id).put("name", p.name).put("host", p.host).put("port", p.port).put("path", p.path).put("model", p.model).put("t", p.seen)
                p.color?.let { o.put("color", it) }; p.duplex?.let { o.put("duplex", it) }
                a.put(o)
            }
            Cfg.setPrinters(a)
        } catch (_: Exception) {}
    }

    /** Re-issue the mDNS queries (Android lets discovery go quiet in the background) - also called when the network changes. */
    @Synchronized fun restart() {
        val m = nsd; val l = listener
        if (m != null && l != null) try { m.stopServiceDiscovery(l) } catch (_: Throwable) {}
        started = false
        Core.appCtx?.let { start(it) }
    }

    private fun reachable(p: P): Boolean {
        val s = try { Net.newSocket(p.host) } catch (_: Exception) { return false }
        return try { s.connect(java.net.InetSocketAddress(p.host, p.port), 800); true } catch (_: Exception) { false } finally { try { s.close() } catch (_: Exception) {} }
    }

    @Synchronized
    fun start(ctx: Context) {
        if (!watching) {
            watching = true
            loadSaved()
            Thread({ while (true) { try { Thread.sleep(90_000); restart() } catch (_: Throwable) {} } }, "wifi-nsd-watch").also { it.isDaemon = true }.start()
        }
        if (started) return
        started = true
        try {
            val m = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
            nsd = m
            val l = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(type: String?, code: Int) { if (listener === this) started = false }
                override fun onStopDiscoveryFailed(type: String?, code: Int) {}
                override fun onDiscoveryStarted(type: String?) {}
                override fun onDiscoveryStopped(type: String?) { if (listener === this) started = false }
                override fun onServiceFound(info: NsdServiceInfo?) { if (info != null) pool.execute { resolve(m, info) } }
                override fun onServiceLost(info: NsdServiceInfo?) {}   // kept: a lost mDNS record (screen off, multicast filtered) does not mean the printer is gone - listJson checks reachability
            }
            listener = l
            m.discoverServices("_ipp._tcp", NsdManager.PROTOCOL_DNS_SD, l)
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
        val old = map[id]
        map[id] = P(id, s.serviceName ?: model.ifEmpty { ip }, ip, s.port, rp, model, pdl, flag("Color"), flag("Duplex"))
        if (old == null || old.name != (map[id]?.name ?: "") || System.currentTimeMillis() - old.seen > 3600_000) saveAll()
    }

    /** A printer this device itself discovered (what a remote device may ask it to forward IPP to). */
    fun known(id: String): P? { Core.appCtx?.let { start(it) }; return map[id] }

    fun isWifi(id: String) = id.startsWith("wifi:") || id.startsWith("rwifi:")

    /** Printers of the other LANShare devices (their /p/wifiprinters), as list entries with id "rwifi:<peer>|<printer id>" and the device name in "via". */
    fun remoteJson(): JSONArray {
        val out = java.util.Collections.synchronizedList(ArrayList<JSONObject>())
        val ths = Core.disc.list().filter { it.ok }.map { peer -> Thread {
            try {
                val r = Http.request(peer.ip, peer.port, "GET", "/p/wifiprinters", emptyMap(), 2500)
                try {
                    if (r.status == 200) {
                        val arr = JSONArray(String(r.readUpTo(1 shl 18), Charsets.UTF_8))
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            val oid = o.getString("id")
                            val m = WIFI_ID.find(oid) ?: continue
                            val id = "rwifi:${peer.id}|$oid"
                            val name = o.optString("name").ifEmpty { m.groupValues[1] }
                            remote[id] = P(id, name, m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3], o.optString("model"), emptyList(),
                                if (o.isNull("color")) null else o.optBoolean("color"), if (o.isNull("duplex")) null else o.optBoolean("duplex"), peer.id)
                            out.add(JSONObject().put("id", id).put("name", name).put("model", o.optString("model")).put("via", peer.name).put("viaId", peer.id)
                                .put("color", if (o.isNull("color")) JSONObject.NULL else o.optBoolean("color"))
                                .put("duplex", if (o.isNull("duplex")) JSONObject.NULL else o.optBoolean("duplex")))
                        }
                    }
                } finally { r.close() }
            } catch (_: Exception) {}   // offline, or an older LANShare without /p/wifiprinters
        }.also { it.isDaemon = true; it.start() } }
        ths.forEach { try { it.join(3000) } catch (_: InterruptedException) {} }
        return JSONArray(out.toList().sortedBy { it.optString("name").lowercase() })
    }

    fun get(id: String): P? {
        if (id.startsWith("rwifi:")) {
            remote[id]?.let { return it }
            val m = Regex("^rwifi:([0-9A-Za-z]+)\\|wifi:([0-9.]+):(\\d+)/(.*)$").find(id) ?: return null
            return P(id, m.groupValues[2], m.groupValues[2], m.groupValues[3].toInt(), m.groupValues[4], "", emptyList(), null, null, m.groupValues[1]).also { remote[id] = it }
        }
        return getLocal(id)
    }

    private fun getLocal(id: String): P? = map[id] ?: Regex("^wifi:([0-9.]+):(\\d+)/(.*)$").find(id)?.let {
        P(id, it.groupValues[1], it.groupValues[1], it.groupValues[2].toInt(), it.groupValues[3], "", emptyList(), null, null)
    }

    fun listJson(): JSONArray {
        Core.appCtx?.let { start(it) }
        val ps = map.values.toList()
        val up = ConcurrentHashMap.newKeySet<String>()
        val ths = ps.map { p -> Thread { if (reachable(p)) up.add(p.id) }.also { it.isDaemon = true; it.start() } }
        ths.forEach { try { it.join(1500) } catch (_: InterruptedException) {} }
        val a = JSONArray()
        ps.filter { it.id in up }.sortedBy { it.name.lowercase() }.forEach {
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
