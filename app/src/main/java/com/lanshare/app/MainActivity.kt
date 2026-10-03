package com.lanshare.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.webkit.*
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.*
import android.text.TextUtils
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.lanshare.app.core.Core
import java.io.File
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var chooser: ValueCallback<Array<Uri>>? = null
    private var pageReady = false
    private val dlSeq = AtomicInteger()
    private val dlCancel = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    inner class Bridge {
        @JavascriptInterface fun cancel(id: Int) { dlCancel.add(id) }
        /** Copy/paste/send job started in the UI: mirror its progress in the notification shade. */
        @JavascriptInterface fun watch(origin: String, job: String) { watchJob(origin, job) }
        /** Tap on a file: fetch it to cache, then hand it to an app that can open it. */
        @JavascriptInterface fun open(url: String) { saveToDownloads(url, null, true) }
        /** Same, but always shows the system "Open with" app chooser. */
        @JavascriptInterface fun openWith(url: String) { saveToDownloads(url, null, true, true) }
        /** Video tapped: open the dedicated player (Media3) with the folder's videos as a playlist. [json] = {start, items:[{name,url,key,subs}]} */
        @JavascriptInterface fun play(json: String) {
            runOnUiThread { PlayerActivity.pending = json; startActivity(Intent(this@MainActivity, PlayerActivity::class.java)) }
        }
        /** Picture tapped: open the dedicated image viewer with the folder's pictures. [json] = {start, items:[{name,url,size}]} */
        @JavascriptInterface fun viewImages(json: String) {
            runOnUiThread { ImageViewerActivity.pending = json; startActivity(Intent(this@MainActivity, ImageViewerActivity::class.java)) }
        }
        /** PDF tapped: open the dedicated PDF reader (PdfRenderer). [json] = {name, url, size, key} */
        @JavascriptInterface fun viewPdf(json: String) {
            runOnUiThread { PdfViewerActivity.pending = json; startActivity(Intent(this@MainActivity, PdfViewerActivity::class.java)) }
        }
        /** Open the fully native file browser on device [dev] ("local", a peer id or "smb:..") at [path]. */
        @JavascriptInterface fun nativeBrowser(path: String, dev: String) {
            runOnUiThread { startActivity(Intent(this@MainActivity, BrowserActivity::class.java).putExtra("path", path).putExtra("dev", dev)) }
        }
        /** Android version, so the UI knows whether HEIC pictures can be decoded natively (API 28+). */
        @JavascriptInterface fun sdk(): Int = Build.VERSION.SDK_INT
        /** Print started from the native browser: {dev, path, names}. Empty when the web UI was opened normally. */
        @JavascriptInterface fun printRequest(): String = this@MainActivity.intent?.getStringExtra("print") ?: ""
        /** Print dialog cancelled: nothing left to do here, go back to the native browser. */
        @JavascriptInterface fun closeHost() { runOnUiThread { finish() } }
        /** Print a file on this phone through the Android print system (pdf, images, text). */
        @JavascriptInterface fun printHere(url: String, name: String) {
            phonePrint.start(JSONObject().put("items", org.json.JSONArray().put(JSONObject().put("url", url).put("name", name))).toString())
        }
        /** All selected files as ONE print job. [json] = {items:[{name, url}]} */
        @JavascriptInterface fun printHereMany(json: String) { phonePrint.start(json) }
    }
    private val phonePrint by lazy { PhonePrint(this) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.textZoom = 100   // ignore the system font-size slider so every device renders the same sizes
            addJavascriptInterface(Bridge(), "LSAndroid")
            webViewClient = object : WebViewClient() {
                // window.open('/api/dl?...') and <a download> navigations -> save to Downloads
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    val u = r.url.toString()
                    if (u.contains("/api/dl")) { saveToDownloads(u, null); return true }
                    return false
                }
                override fun onPageFinished(v: WebView, u: String) {
                    if (u.startsWith("http://127.0.0.1") || u.startsWith("http://localhost")) { pageReady = true }
                }
                // WebView renderer crashed / was killed by Android: rebuild the screen
                override fun onRenderProcessGone(v: WebView, d: RenderProcessGoneDetail): Boolean {
                    runOnUiThread { recreate() }; return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>,
                                               p: FileChooserParams): Boolean {
                    chooser?.onReceiveValue(null); chooser = cb
                    val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    return try { startActivityForResult(i, 10); true }
                    catch (e: Exception) { chooser = null; false }
                }
            }
            setDownloadListener { url, _, cd, _, _ ->
                saveToDownloads(url, URLUtil.guessFileName(url, cd, null))
            }
        }
        setContentView(web)
        ContextCompat.startForegroundService(this, Intent(this, LanShareService::class.java))
        loadWhenReady()
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) web.evaluateJavascript("window.lsDlSweep&&lsDlSweep()", null)
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        if (i.hasExtra("print")) { setIntent(i); pageReady = false; Core.url?.let { web.loadUrl(it) }; return }
    }

    override fun onActivityResult(req: Int, res: Int, d: Intent?) {
        if (req != 10) return super.onActivityResult(req, res, d)
        val out: Array<Uri>? = if (res != RESULT_OK || d == null) null else {
            val c = d.clipData
            if (c != null) Array(c.itemCount) { c.getItemAt(it).uri } else d.data?.let { arrayOf(it) }
        }
        chooser?.onReceiveValue(out); chooser = null
    }

    /** Push download progress to the page: state = run | done | err | cancel */
    private fun dlUi(id: Int, name: String, done: Long, total: Long, speed: Double, st: String, msg: String = "") {
        val nid = 1000 + id
        when {
            st == "cancel" || (st == "done" && msg.isNotEmpty()) -> getSystemService(NotificationManager::class.java).cancel(nid)
            st == "done" -> notifyProgress(nid, name, "Saved to Downloads \u00b7 " + sz(done), 0, 0, "done")
            st == "err" -> notifyProgress(nid, name, "Download failed: $msg", 0, 0, "err")
            else -> notifyProgress(nid, name, progressText(done, total, speed), done, total, "run")
        }
        runOnUiThread {
            web.evaluateJavascript("window.lsDl&&lsDl($id,${JSONObject.quote(name)},$done,$total,$speed,'$st',${JSONObject.quote(msg)})", null)
        }
    }

    private fun sz(n: Long) = android.text.format.Formatter.formatFileSize(this, n)
    private fun progressText(done: Long, total: Long, speed: Double): String {
        val sp = if (speed > 0) " \u00b7 ${sz(speed.toLong())}/s" else ""
        return if (total > 0) "${(done * 100 / total).coerceIn(0, 100)}% \u00b7 ${sz(done)} / ${sz(total)}$sp" else "${sz(done)}$sp"
    }

    /** One progress notification per operation (determinate bar; indeterminate if the size is unknown). */
    private fun notifyProgress(nid: Int, title: String, text: String, done: Long, total: Long, st: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26)
                nm.createNotificationChannel(NotificationChannel("lanshare_transfers", "Transfers", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val b = NotificationCompat.Builder(this, "lanshare_transfers")
                .setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(text)
                .setContentIntent(open).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_PROGRESS)
            if (st == "run") b.setOngoing(true).setProgress(100, if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0, total <= 0)
            else b.setOngoing(false).setAutoCancel(true).setProgress(0, 0, false)
            nm.notify(nid, b.build())
        } catch (_: Exception) {}
    }

    private fun watchJob(origin: String, job: String) {
        val nid = 5000 + (job.hashCode() and 0xFFFF)
        Thread {
            var lastD = 0L; var lastT = System.nanoTime(); var speed = 0.0
            try {
                while (true) {
                    val body = URL("$origin/api/job?id=$job").openConnection()
                        .apply { connectTimeout = 5_000; readTimeout = 10_000 }
                        .getInputStream().bufferedReader().use { it.readText() }
                    val j = JSONObject(body)
                    val label = j.optString("label", "Working")
                    val done = j.optLong("done"); val total = j.optLong("total", 1).coerceAtLeast(1)
                    when (j.optString("state")) {
                        "error" -> { notifyProgress(nid, label, "Failed: " + (if (j.isNull("error")) "" else j.optString("error")), 0, 0, "err"); break }
                        "done" -> { notifyProgress(nid, label, "Done", 0, 0, "done"); break }
                        "cancel" -> { notifyProgress(nid, label, "Cancelled", 0, 0, "cancel"); break }
                    }
                    val now = System.nanoTime()
                    if (now > lastT) {
                        val inst = (done - lastD) * 1e9 / (now - lastT)
                        speed = if (speed == 0.0) inst else speed * 0.6 + inst * 0.4
                        lastT = now; lastD = done
                    }
                    val text = if (j.optBoolean("bytes", true)) progressText(done, total, speed) else "$done / $total items"
                    notifyProgress(nid, label, text, done, total, "run")
                    Thread.sleep(700)
                }
            } catch (_: Exception) {
                getSystemService(NotificationManager::class.java).cancel(nid)
            }
        }.start()
    }

    /** Stream a LANShare URL into /Download using the server's own filename, reporting progress. */
    private fun saveToDownloads(url: String, name0: String?, openAfter: Boolean = false, pick: Boolean = false) {
        val id = dlSeq.incrementAndGet()
        Thread {
            var name = name0 ?: "download"
            var f: File? = null
            try {
                dlUi(id, name, 0, 0, 0.0, "run")
                val c = URL(url).openConnection().apply { connectTimeout = 15_000; readTimeout = 60_000 }
                c.getHeaderField("Content-Disposition")?.let {
                    Regex("filename\\*=UTF-8''([^;]+)").find(it)?.let { m ->
                        name = URLDecoder.decode(m.groupValues[1], "UTF-8") }
                }
                val total = c.contentLengthLong.coerceAtLeast(0)
                val dir = if (openAfter) File(cacheDir, "open").apply { deleteRecursively(); mkdirs() }
                          else Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
                var t = File(dir, name.replace("/", "_"))
                var n = 1
                while (t.exists()) { t = File(dir, "${t.nameWithoutExtension} ($n)${if (t.extension.isEmpty()) "" else "." + t.extension}"); n++ }
                f = t
                var done = 0L; var speed = 0.0
                var lastT = System.nanoTime(); var lastD = 0L
                dlUi(id, t.name, 0, total, 0.0, "run")
                c.getInputStream().use { i -> t.outputStream().use { o ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        if (dlCancel.remove(id)) throw InterruptedException()
                        val r = i.read(buf); if (r < 0) break
                        o.write(buf, 0, r); done += r
                        val now = System.nanoTime()
                        if (now - lastT >= 500_000_000L) {
                            val inst = (done - lastD) * 1e9 / (now - lastT)
                            speed = if (speed == 0.0) inst else speed * 0.6 + inst * 0.4 // smoothed
                            lastT = now; lastD = done
                            dlUi(id, t.name, done, total, speed, "run")
                        }
                    }
                } }
                if (openAfter) {
                    dlUi(id, t.name, done, done, speed, "done", "Opening\u2026")
                    runOnUiThread { openWithApp(t, pick) }
                } else dlUi(id, t.name, done, done, speed, "done")
            } catch (e: InterruptedException) {
                f?.delete(); dlUi(id, name, 0, 0, 0.0, "cancel")
            } catch (e: Exception) {
                f?.delete(); dlUi(id, name, 0, 0, 0.0, "err", e.message ?: e.javaClass.simpleName)
            }
        }.start()
    }

    private val TEXT_EXT = setOf("php", "phtml", "js", "mjs", "ts", "tsx", "jsx", "css", "scss", "json", "xml", "yml", "yaml", "toml",
        "ini", "cfg", "conf", "env", "md", "log", "sql", "py", "kt", "kts", "java", "c", "h", "cpp", "hpp", "cs", "go", "rs", "rb",
        "sh", "bat", "ps1", "gradle", "properties", "htaccess", "gitignore", "csv", "tsv", "srt", "vtt", "tex", "txt", "html", "htm", "svg")

    /** Open a fetched file with an installed app; source/text files go to a text editor. */
    private fun openWithApp(f: File, pick: Boolean = false) {
        val ext = f.extension.lowercase()
        val mime = if (ext in TEXT_EXT || f.name.startsWith(".")) "text/plain"
                   else MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        fun go(m: String) {
            val v = Intent(Intent.ACTION_VIEW).setDataAndType(uri, m).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(if (pick) Intent.createChooser(v, "Open with").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) else v)
        }
        try { go(mime) }
        catch (e: ActivityNotFoundException) {
            try { if (mime != "*/*") go("*/*") else throw e }
            catch (e2: Exception) { Toast.makeText(this, "No app can open .${ext}. Long-press the file to download it instead.", Toast.LENGTH_LONG).show() }
        } catch (e: Exception) { Toast.makeText(this, "Cannot open: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    // ---- print on this phone (Android print framework: Wi-Fi/Mopria/vendor plugins/Save as PDF) ----
    private fun toastUi(t: String) = runOnUiThread { Toast.makeText(this, t, Toast.LENGTH_LONG).show() }

    @SuppressLint("BatteryLife")
    private fun askPermissions() {
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager())
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        else if (Build.VERSION.SDK_INT < 30)
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 2)
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName))
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    private fun page(msg: String, extra: String = "") =
        "<html><body style='background:#1565C0;color:#fff;font-family:sans-serif;text-align:center;padding:30% 8% 0'>" +
        "<h2>LANShare</h2><p>$msg</p><pre style='text-align:left;white-space:pre-wrap;font-size:11px'>$extra</pre></body></html>"

    private fun loadWhenReady() {
        web.loadDataWithBaseURL(null, page("Starting\u2026"), "text/html", "utf-8", null)
        Thread {
            var url: String? = null
            var err: String? = null
            val t0 = System.currentTimeMillis()
            while (url == null && err == null && System.currentTimeMillis() - t0 < 40_000) {
                Thread.sleep(300)
                try {
                    url = Core.url
                    err = Core.error
                } catch (_: Exception) {}
            }
            runOnUiThread {
                if (url != null) web.loadUrl(url)
                else web.loadDataWithBaseURL(null,
                    page("Server did not start. Close and reopen the app.", err ?: "timeout"), "text/html", "utf-8", null)
            }
        }.start()
    }

    @Deprecated("ok")
    override fun onBackPressed() {
        // Never web.goBack(): history still holds the "Starting..." page. Let the UI close its own
        // overlays / selection first; otherwise leave the app (the server keeps running in the service).
        if (!pageReady) { finish(); return }
        web.evaluateJavascript("(window.lsBack?lsBack():false)") { if (it != "true") finish() }
    }
}
