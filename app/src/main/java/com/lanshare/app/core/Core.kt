package com.lanshare.app.core

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.util.Log
import java.io.File
import java.io.IOException

/** Replacement for android_main.py + lanshare.main(): owns config, local filesystem, HTTP server and discovery. */
object Core {
    private const val TAG = "LANShare"
    @Volatile var url: String? = null
    @Volatile var error: String? = null
    /** Application context (WebView-based HTML/SVG -> PDF conversion for printing needs one). */
    @Volatile var appCtx: Context? = null
    lateinit var local: LocalFs
    /** Scratch space for files extracted from archives (cleared at every start, size-capped while running). */
    @Volatile var cacheDir: File = File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp", "lsarc")
    lateinit var disc: Discovery
    lateinit var page: ByteArray
    private var started = false
    private var server: MiniHttp? = null
    private val held = ArrayList<Any>()   // wake/wifi/multicast locks - kept referenced for the life of the process

    fun discOrNull(): Discovery? = if (::disc.isInitialized) disc else null

    /** False when Android 11+ "All files access" is not granted (apps then only see their own/media files). Re-checked live. */
    val storageOk: Boolean?
        get() = try { if (Build.VERSION.SDK_INT < 30) true else Environment.isExternalStorageManager() } catch (_: Exception) { null }

    /** Non-blocking: sets up everything and returns. Failures end up in [error] (the activity shows them). */
    @Synchronized
    fun start(ctx: Context, rootPath: String) {
        if (started) return
        started = true
        try {
            val app = ctx.applicationContext
            appCtx = app
            Cfg.load(File(app.filesDir, "lanshare.json"), phoneModel(), "")
            holdAwake(app)
            try { Partials.init(app.filesDir); Thread({ Partials.purge() }, "partials-purge").also { it.isDaemon = true }.start() } catch (_: Throwable) {}   // interrupted copies: reload the registry, drop what is older than a week
            cacheDir = File(app.cacheDir, "arc").also { it.mkdirs() }
            ArcStore.cleanOnStart()
            try { File(app.cacheDir, "pv").deleteRecursively() } catch (_: Exception) {}
            page = app.assets.open("ui.html").use { it.readBytes() }
            local = LocalFs(rootPath)
            try { ClipEngine.tryLoad(app) } catch (_: Throwable) {}   // image search: engine ready at once when the model files are already downloaded
            try { ImgSearch.startAutoWatch(app) } catch (_: Throwable) {}   // new pictures are picked up by an automatic incremental scan
            Thread({ Bin.autoPurge() }, "bin-purge").also { it.isDaemon = true }.start()
            Thread({   // "LANShare Shared": cleaned at start and then every day while the app / service is alive
                while (true) {
                    cleanShared()
                    try { Thread.sleep(6 * 3_600_000L) } catch (_: InterruptedException) { return@Thread }
                }
            }, "shared-clean").also { it.isDaemon = true }.start()
            if (Cfg.rootEnabled()) Thread({ try { RootShell.check() } catch (_: Throwable) {} }, "root-check").also { it.isDaemon = true }.start()   // switched on earlier: ask su again (the manager remembers the grant)
            var srv: MiniHttp? = null
            var port = BASE_PORT
            // The page's localStorage (sort / view per folder, theme ...) belongs to the origin http://127.0.0.1:<port>: a different port = empty storage.
            // So the port used last time is tried first (and retried briefly: the previous process may still be closing after an update / restart).
            val sp = app.getSharedPreferences("core_port", Context.MODE_PRIVATE)
            val last = sp.getInt("last", 0).takeIf { it in BASE_PORT until BASE_PORT + 20 }
            if (last != null) {
                for (i in 0 until 12) {
                    val m = MiniHttp(last) { ex -> Routes.handle(ex) }
                    try { srv = m.bind(); port = last; break } catch (_: IOException) { m.stop() }
                    try { Thread.sleep(250) } catch (_: InterruptedException) {}
                }
            }
            if (srv == null) for (p in BASE_PORT until BASE_PORT + 20) {
                val m = MiniHttp(p) { ex -> Routes.handle(ex) }
                try { srv = m.bind(); port = p; break } catch (_: IOException) { m.stop() }
            }
            if (srv != null) try { sp.edit().putInt("last", port).apply() } catch (_: Throwable) {}
            if (srv == null) throw IOException("No free port found")
            disc = Discovery(port)
            server = srv
            srv.start()
            disc.start()
            try { WifiPrinters.start(app) } catch (_: Throwable) {}   // finds printers on the Wi-Fi (shown as "Wi-Fi" in the Print picker)
            url = "http://127.0.0.1:$port"
            Log.i(TAG, "running on $port, sharing ${local.root}, device ${Cfg.name}")
        } catch (e: Throwable) {
            error = e.stackTraceToString().takeLast(1500)
            Log.e(TAG, "startup failed", e)
            started = false
        }
    }

    /** Files in <storage>/LANShare Shared (shared-in, opened archives, printed PDFs) are deleted once they are older than [SHARED_KEEP_MS] (one day). */
    const val SHARED_KEEP_MS = 24 * 3_600_000L
    fun cleanShared() {
        try {
            val dir = File(Environment.getExternalStorageDirectory(), "LANShare Shared")
            val cut = System.currentTimeMillis() - SHARED_KEEP_MS
            dir.listFiles()?.forEach { if (it.isFile && it.lastModified() < cut) it.delete() }
        } catch (_: Throwable) { }
    }

    /** Wi-Fi / hotspot changed: refresh own IPs and sweep the subnet. */
    fun rescan() { try { discOrNull()?.scanNow() } catch (_: Exception) {}; try { WifiPrinters.refresh() } catch (_: Throwable) {} }

    fun stop() { server?.stop(); server = null }

    private fun getprop(k: String): String = try {
        val p = ProcessBuilder("getprop", k).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
    } catch (_: Exception) { "" }

    /** Phone model (e.g. "Pixel 7") used as the default device name. */
    private fun phoneModel(): String {
        for (k in listOf("ro.product.marketname", "ro.product.model", "ro.product.device")) {
            val v = getprop(k)
            if (v.isNotEmpty()) return v.take(40)
        }
        return Build.MODEL.orEmpty().trim().take(40)
    }

    /** CPU + Wi-Fi + multicast locks: keeps discovery and transfers alive with the screen off. Best effort. */
    @Suppress("DEPRECATION")
    private fun holdAwake(ctx: Context) {
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            held.add(pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LANShare:cpu").also { it.setReferenceCounted(false); it.acquire() })
        } catch (_: Exception) {}
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            held.add(wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "LANShare:wifi").also { it.setReferenceCounted(false); it.acquire() })
            held.add(wm.createMulticastLock("LANShare:mc").also { it.setReferenceCounted(false); it.acquire() })
        } catch (_: Exception) {}
    }
}
