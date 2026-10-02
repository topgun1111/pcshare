package com.lanshare.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.webkit.*
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.chaquo.python.Python
import android.provider.OpenableColumns
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
        /** Tap on a file: fetch it to cache, then hand it to an app that can open it. */
        @JavascriptInterface fun open(url: String) { saveToDownloads(url, null, true) }
    }
    private var pendingShare: List<String>? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            addJavascriptInterface(Bridge(), "LSAndroid")
            webViewClient = object : WebViewClient() {
                // window.open('/api/dl?...') and <a download> navigations -> save to Downloads
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    val u = r.url.toString()
                    if (u.contains("/api/dl")) { saveToDownloads(u, null); return true }
                    return false
                }
                override fun onPageFinished(v: WebView, u: String) {
                    if (u.startsWith("http://127.0.0.1") || u.startsWith("http://localhost")) { pageReady = true; runShare() }
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
        askPermissions()
        ContextCompat.startForegroundService(this, Intent(this, LanShareService::class.java))
        loadWhenReady()
        handleShare(intent)
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); handleShare(i) }

    /** Android share sheet -> copy into <storage>/LANShare Shared so it can be selected and Sent from the UI. */
    private fun handleShare(i: Intent?) {
        if (i == null || (i.action != Intent.ACTION_SEND && i.action != Intent.ACTION_SEND_MULTIPLE)) return
        @Suppress("DEPRECATION")
        val uris: List<Uri> = if (i.action == Intent.ACTION_SEND)
            listOfNotNull(i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        else i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
        if (uris.isEmpty()) return
        i.action = null   // consume once
        Thread {
            var n = 0
            val names = ArrayList<String>()
            val dir = File(Environment.getExternalStorageDirectory(), "LANShare Shared").apply { mkdirs() }
            for (u in uris) try {
                var name = "shared_" + System.currentTimeMillis()
                contentResolver.query(u, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }
                        ?.let { name = c.getString(it) ?: name }
                }
                var f = File(dir, name.replace("/", "_")); var k = 1
                while (f.exists()) { f = File(dir, "${f.nameWithoutExtension} ($k)${if (f.extension.isEmpty()) "" else "." + f.extension}"); k++ }
                contentResolver.openInputStream(u)?.use { ins -> f.outputStream().use { ins.copyTo(it) } }
                n++; names.add(f.name)
            } catch (_: Exception) {}
            runOnUiThread {
                if (n > 0) { pendingShare = names; runShare() }
                else Toast.makeText(this, "Could not read the shared file(s)", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    /** Open the UI's "Send to..." device picker for the files just shared in (waits until the page is loaded). */
    private fun runShare() {
        val names = pendingShare ?: return
        if (!pageReady) return
        pendingShare = null
        val arr = org.json.JSONArray(names).toString()
        web.postDelayed({
            web.evaluateJavascript(
                "(async()=>{try{const p=await pickDevice();if(!p)return;" +
                "const r=await api('POST','/api/send',{dev:'local',paths:" + arr +
                ".map(n=>'/LANShare Shared/'+n),to:p.id});track(r.job)}catch(e){toast('\\u26a0 '+e.message,5000)}})()", null)
        }, 1200)
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
    private fun dlUi(id: Int, name: String, done: Long, total: Long, speed: Double, st: String, msg: String = "") =
        runOnUiThread {
            web.evaluateJavascript("window.lsDl&&lsDl($id,${JSONObject.quote(name)},$done,$total,$speed,'$st',${JSONObject.quote(msg)})", null)
        }

    /** Stream a LANShare URL into /Download using the server's own filename, reporting progress. */
    private fun saveToDownloads(url: String, name0: String?, openAfter: Boolean = false) {
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
                    runOnUiThread { openWithApp(t) }
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
    private fun openWithApp(f: File) {
        val ext = f.extension.lowercase()
        val mime = if (ext in TEXT_EXT || f.name.startsWith(".")) "text/plain"
                   else MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        fun go(m: String) = startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, m)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        try { go(mime) }
        catch (e: ActivityNotFoundException) {
            try { if (mime != "*/*") go("*/*") else throw e }
            catch (e2: Exception) { Toast.makeText(this, "No app can open .${ext}. Long-press the file to download it instead.", Toast.LENGTH_LONG).show() }
        } catch (e: Exception) { Toast.makeText(this, "Cannot open: ${e.message}", Toast.LENGTH_LONG).show() }
    }

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
                    if (Python.isStarted()) {
                        val m = Python.getInstance().getModule("android_main")
                        url = m.callAttr("get_url")?.toString()
                        err = m.callAttr("get_error")?.toString()
                    }
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
