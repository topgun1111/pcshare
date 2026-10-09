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
            cacheDir = File(app.cacheDir, "arc").also { it.mkdirs() }
            ArcStore.cleanOnStart()
            try { File(app.cacheDir, "pv").deleteRecursively() } catch (_: Exception) {}
            page = app.assets.open("ui.html").use { it.readBytes() }
            local = LocalFs(rootPath)
            try { ClipEngine.tryLoad(app) } catch (_: Throwable) {}   // image search: engine ready at once when the model files are already downloaded
            Thread({ Bin.autoPurge() }, "bin-purge").also { it.isDaemon = true }.start()
            var srv: MiniHttp? = null
            var port = BASE_PORT
            for (p in BASE_PORT until BASE_PORT + 20) {
                val m = MiniHttp(p) { ex -> Routes.handle(ex) }
                try { srv = m.bind(); port = p; break } catch (_: IOException) { m.stop() }
            }
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
