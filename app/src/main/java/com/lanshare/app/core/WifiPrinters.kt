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
        @Volatile var formats: List<String> = pdl            // refined by status() from the printer itself
        @Volatile var sides: List<String> = emptyList()
        @Volatile var colorModes: List<String> = emptyList()
        @Volatile var mediaReady: List<String> = emptyList()      // paper actually loaded
        @Volatile var mediaDefault: List<String> = emptyList()
        @Volatile var mediaSupported: List<String> = emptyList()
        @Volatile var rasterDpi: List<Int> = emptyList()          // resolutions of image/pwg-raster the printer takes
        @Volatile var sheetBack: String = "normal"                // pwg-raster-document-sheet-back: normal / flipped / rotated / manual-tumble
    }

    private val map = ConcurrentHashMap<String, P>()
    private val remote = ConcurrentHashMap<String, P>()   // printers on other devices (reached through that device, see [remoteJson])
    private val WIFI_ID = Regex("^wifi:([0-9.]+):(\\d+)/(.*)$")
    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "wifi-resolve").also { it.isDaemon = true } }   // resolveService: one at a time
    @Volatile private var started = false
    private var nsd: NsdManager? = null
    private var listener: NsdManager.DiscoveryListener? = null
    @Volatile private var loaded = false
    @Volatile private var keeper = false
    @Volatile private var lastScan = 0L
    private val scanBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private val IPP_PATHS = listOf("ipp/print", "ipp", "")   // en yaygın IPP yolları (HP/Epson/Brother/Canon ...)

    @Synchronized
    fun start(ctx: Context) {
        if (!loaded) { loaded = true; loadCache() }
        startKeeper()
        if (started) return
        started = true
        try {
            val m = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
            nsd = m
            val l = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(type: String?, code: Int) { started = false }
                override fun onStopDiscoveryFailed(type: String?, code: Int) {}
                override fun onDiscoveryStarted(type: String?) {}
                override fun onDiscoveryStopped(type: String?) { started = false }
                override fun onServiceFound(info: NsdServiceInfo?) { if (info != null) pool.execute { resolve(m, info) } }
                // Not removed on purpose: with the screen off the Wi-Fi chip drops multicast and Android reports "lost" for printers that are still there.
                // Whether a printer is really gone is decided by reaching it (see [listJson]).
                override fun onServiceLost(info: NsdServiceInfo?) {}
            }
            listener = l
            m.discoverServices("_ipp._tcp", NsdManager.PROTOCOL_DNS_SD, l)
        } catch (_: Throwable) { started = false }
    }

    /** Stops and starts the mDNS search again (it silently dies / goes deaf when the phone sleeps or the network changes). */
    fun restart() {
        val ctx = Core.appCtx ?: return
        synchronized(this) {
            try { listener?.let { nsd?.stopServiceDiscovery(it) } } catch (_: Throwable) {}
            listener = null; started = false
        }
        Thread { try { Thread.sleep(700) } catch (_: InterruptedException) {}; try { start(ctx) } catch (_: Throwable) {} }
            .also { it.isDaemon = true }.start()
    }

    /** Screen back on / Wi-Fi changed: search again (mDNS) and look for printers directly (port 631), no multicast needed. */
    fun refresh() { restart(); scanAsync(true) }

    private fun startKeeper() {
        if (keeper) return
        keeper = true
        Thread {
            while (true) {
                try { Thread.sleep(90_000) } catch (_: InterruptedException) {}
                try { restart(); scanAsync(false) } catch (_: Throwable) {}
            }
        }.also { it.isDaemon = true; it.name = "wifi-keeper" }.start()
    }

    /** Looks for IPP printers (TCP 631) on every local address. Works while mDNS is deaf. Runs in the background. */
    fun scanAsync(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastScan < 120_000) return
        if (!scanBusy.compareAndSet(false, true)) return
        lastScan = now
        Thread {
            try { scan() } catch (_: Throwable) {} finally { scanBusy.set(false) }
        }.also { it.isDaemon = true; it.name = "wifi-scan" }.start()
    }

    private fun scan() {
        val d = Core.discOrNull() ?: return
        val hits = java.util.Collections.synchronizedList(ArrayList<String>())
        val ex = Executors.newFixedThreadPool(48) { r -> Thread(r).also { it.isDaemon = true } }
        try {
            ex.invokeAll(d.candidates().filter { h -> map.values.none { it.host == h } }.map { h -> java.util.concurrent.Callable<Unit> {
                val s = Net.newSocket(h)
                try { s.connect(java.net.InetSocketAddress(h, 631), 500); hits.add(h) } catch (_: Exception) {} finally { try { s.close() } catch (_: Exception) {} }
            } }, 25, TimeUnit.SECONDS)
        } finally { ex.shutdownNow() }
        var added = false
        for (h in hits.toList()) {
            if (h in d.ownIps) continue
            if (map.values.any { it.host == h }) continue      // mDNS zaten buldu
            // Yazıcıların IPP yolu markaya göre değişir; sırayla dene, ilk cevap veren kazanır
            for (path in IPP_PATHS) {
                val id = "wifi:$h:631/$path"
                val p = P(id, h, h, 631, path, "", emptyList(), null, null)
                try {
                    val r = Ipp.printerAttrs(p)
                    if (!r.ok) continue
                    val nm = r.strs("printer-name").firstOrNull().orEmpty().ifEmpty { h }
                    val md = r.strs("printer-make-and-model").firstOrNull().orEmpty()
                    map[id] = P(id, nm, h, 631, path, md, r.strs("document-format-supported").map { it.lowercase() }, null, null)
                    added = true
                    break
                } catch (_: Exception) {}
            }
        }
        if (added) saveCache()
    }

    private fun loadCache() {
        try {
            val a = Cfg.printerCache()
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val pd = o.optJSONArray("pdl")
                map[o.getString("id")] = P(o.getString("id"), o.optString("name"), o.getString("host"), o.getInt("port"), o.optString("path"),
                    o.optString("model"), if (pd == null) emptyList() else (0 until pd.length()).map { pd.optString(it) },
                    if (o.has("color")) o.optBoolean("color") else null, if (o.has("duplex")) o.optBoolean("duplex") else null)
            }
        } catch (_: Exception) {}
    }

    private fun saveCache() {
        try {
            val a = JSONArray()
            map.values.sortedBy { it.id }.take(32).forEach {
                val o = JSONObject().put("id", it.id).put("name", it.name).put("host", it.host).put("port", it.port).put("path", it.path)
                    .put("model", it.model).put("pdl", JSONArray(it.pdl))
                it.color?.let { c -> o.put("color", c) }; it.duplex?.let { d -> o.put("duplex", d) }
                a.put(o)
            }
            Cfg.setPrinterCache(a)
        } catch (_: Exception) {}
    }

    private fun reachable(p: P): Boolean {
        val s = Net.newSocket(p.host)
        return try { s.connect(java.net.InetSocketAddress(p.host, p.port), 900); true } catch (_: Exception) { false } finally { try { s.close() } catch (_: Exception) {} }
    }

    @Suppress("DEPRECATION")
    private fun resolve(m: NsdManager, i: NsdServiceInfo) {
        // Android bazen "already active" / zaman aşımı ile çözümlemeyi düşürür: 3 kez dene
        for (attempt in 0 until 3) {
            val latch = CountDownLatch(1)
            var okRes = false
            try {
                m.resolveService(i, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo?, code: Int) { latch.countDown() }
                    override fun onServiceResolved(info: NsdServiceInfo?) { try { if (info != null) { add(info); okRes = true } } finally { latch.countDown() } }
                })
                latch.await(6, TimeUnit.SECONDS)
            } catch (_: Throwable) {}
            if (okRes) return
            try { Thread.sleep(600) } catch (_: InterruptedException) { return }
        }
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
        map.values.filter { it.host == ip && it.port == s.port && it.id != id }.forEach { map.remove(it.id) }   // aynı yazıcının tarama kaydı
        map[id] = P(id, s.serviceName ?: model.ifEmpty { ip }, ip, s.port, rp, model, pdl, flag("Color"), flag("Duplex"))
        saveCache()
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

    /** Şimdi ara: mDNS'i yeniden başlatır ve ağı (port 631) senkron tarar, sonra listeyi döner (en çok ~15 sn). "Yazıcıları ara" düğmesi bunu çağırır. */
    fun searchNow(): JSONArray {
        Core.appCtx?.let { start(it) }
        restart()
        if (scanBusy.compareAndSet(false, true)) {
            lastScan = System.currentTimeMillis()
            try { scan() } catch (_: Throwable) {} finally { scanBusy.set(false) }
        } else {                                  // arka planda zaten tarama var: bitmesini bekle
            var w = 0
            while (scanBusy.get() && w < 15_000) { try { Thread.sleep(200) } catch (_: InterruptedException) { break }; w += 200 }
        }
        try { Thread.sleep(1200) } catch (_: InterruptedException) {}   // mDNS cevapları son anda gelebilir
        return listJson()
    }

    /** [wait] = hiç yazıcı bilinmiyorsa ilk mDNS/tarama sonucunu kısa süre bekle (ilk açılışta boş liste görünmesin). */
    fun listJson(wait: Boolean = false): JSONArray {
        Core.appCtx?.let { start(it) }
        if (map.isEmpty()) {
            scanAsync(true)
            if (wait) { var w = 0; while (map.isEmpty() && w < 2500) { try { Thread.sleep(150) } catch (_: InterruptedException) { break }; w += 150 } }
        }
        val a = JSONArray()
        // remembered printers are listed only while they answer (a printer that was switched off simply drops out and comes back later)
        val all = map.values.toList()
        val up = java.util.Collections.synchronizedSet(HashSet<String>())
        all.map { p -> Thread { if (reachable(p)) up.add(p.id) }.also { it.isDaemon = true; it.start() } }
            .forEach { try { it.join(1500) } catch (_: InterruptedException) {} }
        if (up.size < all.size) scanAsync(false)
        all.filter { it.id in up }.sortedBy { it.name.lowercase() }.forEach {
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
            p.rasterDpi = r.dpis("pwg-raster-document-resolution-supported")
            p.sheetBack = r.strs("pwg-raster-document-sheet-back").firstOrNull() ?: "normal"
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
