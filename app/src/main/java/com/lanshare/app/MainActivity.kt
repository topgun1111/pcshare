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
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.lanshare.app.core.Core
import com.lanshare.app.core.InProc
import java.io.ByteArrayInputStream
import android.provider.OpenableColumns
import java.io.File
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

class MainActivity : Activity() {
    companion object { const val ACTION_PRINT_SHARED = "com.lanshare.app.PRINT_SHARED" }
    private lateinit var web: WebView
    private lateinit var nl: NativeList
    private lateinit var chrome: NlChrome
    private lateinit var drawer: NlDrawer
    private lateinit var splash: NlSplash
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
        /** Android version, so the UI knows whether HEIC pictures can be decoded natively (API 28+). */
        @JavascriptInterface fun sdk(): Int = Build.VERSION.SDK_INT
        // ---- native file list (NativeList.kt): ui.html only sends data; see HANDOVER_NATIVE_LIST.md ----
        @JavascriptInterface fun nlAvail(): Boolean = NativeList.ENABLED
        /** Page theme changed: paint the status / navigation bars like the top bar and pick dark or light icons. [color] = "#rrggbb". */
        @JavascriptInterface fun bars(color: String, light: Boolean) {
            runOnUiThread {
                try {
                    val c = Color.parseColor(color)
                    window.statusBarColor = c; window.navigationBarColor = c
                    val ic = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
                    ic.isAppearanceLightStatusBars = light; ic.isAppearanceLightNavigationBars = light
                } catch (_: Throwable) { }
            }
        }
        @JavascriptInterface fun nlPalette(json: String) { runOnUiThread { nl.setPalette(json) } }
        /** key = "dev|path", items = JSON rows, sel = JSON array of selected row indexes. Parsed here (bridge thread), applied on the UI thread. */
        @JavascriptInterface fun nlItems(key: String, items: String, sel: String) {
            splash.noteItems(key, items)
            try { val rows = NativeList.parseRows(items, key.substringBefore('|')); val s = NativeList.parseSel(sel); val sig = NlModel.sig(rows)
                runOnUiThread { nl.setItems(key, rows, s, sig) } } catch (_: Throwable) { }
        }
        @JavascriptInterface fun nlCfg(c: String) { runOnUiThread { nl.setCfg(c) } }
        @JavascriptInterface fun nlPatch(json: String) { runOnUiThread { try { nl.patch(json) } catch (_: Throwable) { } } }
        @JavascriptInterface fun nlSel(sel: String) { try { val s = NativeList.parseSel(sel); runOnUiThread { nl.setSel(s) } } catch (_: Throwable) { } }
        @JavascriptInterface fun nlLayout(json: String) { runOnUiThread { try { nl.layout(json) } catch (_: Throwable) { }; splash.dismiss() } }
        @JavascriptInterface fun nlHide() { runOnUiThread { nl.hide(); splash.dismiss() } }
        /** Path bar + tool row content (NlHead.kt): JSON from ui.html nlHeadPush(). */
        @JavascriptInterface fun nlHead(json: String) { runOnUiThread { try { nl.setHead(json) } catch (_: Throwable) { } } }
        /** First load() of the page finished (whatever the outcome): the native start picture is no longer needed. */
        @JavascriptInterface fun nlBoot() { runOnUiThread { splash.dismiss() } }
        @JavascriptInterface fun nlDone() { runOnUiThread { nl.done() } }
        /** Native search box (NlSearch.kt): ui.html nlSrPush() geometry / state, the query the next nlItems was filtered by, focus request. */
        @JavascriptInterface fun nlSearch(json: String) { runOnUiThread { try { nl.setSearch(json) } catch (_: Throwable) { } } }
        @JavascriptInterface fun nlQ(q: String) { runOnUiThread { nl.noteQ(q) } }
        @JavascriptInterface fun nlSrFocus() { runOnUiThread { nl.focusSearch() } }
        /** Native Sort / View sheets (NlSheets.kt): JSON = current state from ui.html. Choices come back through nlPref(). */
        @JavascriptInterface fun nlSort(json: String) { runOnUiThread { try { nl.sheets.showSort(json) } catch (_: Throwable) { } } }
        @JavascriptInterface fun nlView(json: String) { runOnUiThread { try { nl.sheets.showView(json) } catch (_: Throwable) { } } }
        @JavascriptInterface fun nlSheets(): Boolean = NativeList.SHEETS
        @JavascriptInterface fun nlSearchOk(): Boolean = NativeList.SEARCH
        /** Native app chrome (NlChrome.kt): top bar, selection bar, FAB, dock, snackbar, download cards. JSON from ui.html nlChPush(); "0" = hide all. */
        @JavascriptInterface fun nlChromeOk(): Boolean = NlChrome.ENABLED
        @JavascriptInterface fun nlChrome(json: String) { runOnUiThread { try { chrome.set(json) } catch (_: Throwable) { } } }
        /** Native side drawer (NlDrawer.kt): "0" = closed, "h" = hidden behind a page dialog, else the JSON model from ui.html nlDrPush(). */
        @JavascriptInterface fun nlDrawerOk(): Boolean = NlDrawer.ENABLED
        @JavascriptInterface fun nlDrawer(json: String) { runOnUiThread { try { drawer.set(json) } catch (_: Throwable) { } } }
        /** Print a file on this phone through the Android print system (pdf, images, text). */
        @JavascriptInterface fun printHere(url: String, name: String) {
            phonePrint.start(JSONObject().put("items", org.json.JSONArray().put(JSONObject().put("url", url).put("name", name))).toString())
        }
        /** All selected files as ONE print job. [json] = {items:[{name, url}]} */
        @JavascriptInterface fun printHereMany(json: String) { phonePrint.start(json) }
    }
    private var pendingShare: List<String>? = null
    private var pendingPrint: List<String>? = null
    private val phonePrint by lazy { PhonePrint(this) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Fnt.init(applicationContext)
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
                // quick local GETs (folder listing, free space, job progress ...) are answered in-process: no localhost TCP round trip
                override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                    return try {
                        val u = r.url
                        val x = InProc.serve(r.method, u.host, u.port, u.encodedPath ?: "", u.encodedQuery) ?: return null
                        WebResourceResponse(x.ctype, "utf-8", x.code, x.reason, x.headers, x.body())
                    } catch (_: Throwable) { null }   // anything unexpected: the request simply goes the normal HTTP way
                }
                override fun onPageFinished(v: WebView, u: String) {
                    if (u.startsWith("http://127.0.0.1") || u.startsWith("http://localhost")) { pageReady = true; runShare(); runPrint() }
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
        nl = NativeList(this, web)
        chrome = NlChrome(this) { ev, x -> web.evaluateJavascript("window.nlCh&&nlCh(" + JSONObject.quote(ev) + "," + JSONObject.quote(x) + ")", null) }
        drawer = NlDrawer(this) { k, i, j, v -> web.evaluateJavascript("window.nlDr&&nlDr(" + JSONObject.quote(k) + "," + i + "," + j + "," + v + ")", null) }
        val root = FrameLayout(this).apply {
            addView(web, FrameLayout.LayoutParams(-1, -1))
            addView(nl.overlay, FrameLayout.LayoutParams(-1, -1))   // native file list: above the page, invisible until ui.html sends a layout
            addView(nl.search, FrameLayout.LayoutParams(1, 1))      // native search box: its own view (the overlay forwards touches outside the list to the page); GONE until the page opens its search row
            addView(chrome.layer, FrameLayout.LayoutParams(-1, -1)) // native app chrome (bars, FAB, dock, snackbar): no background, touches outside its buttons fall through
            addView(drawer.panel, drawer.layoutParams())            // native side drawer: topmost, GONE until the page opens its drawer; swallows touches inside its own width only
        }
        setContentView(root)
        splash = NlSplash(this, nl, web, root)
        if (b == null) splash.start()      // cold start: draw the last screen natively while the page loads
        askPermissions()
        ContextCompat.startForegroundService(this, Intent(this, LanShareService::class.java))
        loadWhenReady()
        handleShare(intent)
    }

    override fun onPause() { super.onPause(); if (::splash.isInitialized) try { splash.save() } catch (_: Throwable) { } }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) web.evaluateJavascript("window.lsDlSweep&&lsDlSweep()", null)
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); handleShare(i) }

    /** Android share sheet -> copy into <storage>/LANShare Shared so it can be selected and Sent from the UI. */
    private fun handleShare(i: Intent?) {
        if (i != null && i.action == ACTION_PRINT_SHARED) {   // from PcPrintService: a document printed from another app
            val n = i.getStringArrayExtra("names")?.toList().orEmpty()
            getSystemService(NotificationManager::class.java).cancel(i.getIntExtra("nid", 0))
            i.action = null
            if (n.isNotEmpty()) { pendingPrint = n; runPrint() }
            return
        }
        if (i == null || (i.action != Intent.ACTION_SEND && i.action != Intent.ACTION_SEND_MULTIPLE)) return
        @Suppress("DEPRECATION")
        val uris: List<Uri> = if (i.action == Intent.ACTION_SEND)
            listOfNotNull(i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        else i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
        if (uris.isEmpty()) return
        val toPrint = i.component?.className?.endsWith("SendShareAlias") != true   // plain "LANShare" share entry = print; only the "LANShare Send" entry sends
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
                if (n > 0) { if (toPrint) { pendingPrint = names; runPrint() } else { pendingShare = names; runShare() } }
                else Toast.makeText(this, "Could not read the shared file(s)", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    /** Open LANShare's print dialog (PC picker + layout options + preview) for files printed from another app (waits until the page is loaded). */
    private fun runPrint() {
        val names = pendingPrint ?: return
        if (!pageReady) return
        pendingPrint = null
        val arr = org.json.JSONArray(names).toString()
        web.postDelayed({ web.evaluateJavascript("window.lsPrintShared&&lsPrintShared($arr)", null) }, 1200)
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
                ".map(n=>'/LANShare Shared/'+n),to:p.id});watchJob(r.job);track(r.job)}catch(e){toast('\\u26a0 '+e.message,5000)}})()", null)
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
                try {
                    url = Core.url
                    err = Core.error
                } catch (_: Exception) {}
                if (url == null && err == null) Thread.sleep(25)      // was 300 ms (+300 ms before the first check): up to 0.6 s of pure waiting
            }
            runOnUiThread {
                if (url != null) { web.loadUrl(url); web.postDelayed({ splash.dismiss() }, 8000) }   // watchdog: never leave the start picture up
                else { splash.dismiss(); web.loadDataWithBaseURL(null,
                    page("Server did not start. Close and reopen the app.", err ?: "timeout"), "text/html", "utf-8", null) }
            }
        }.start()
    }

    @Deprecated("ok")
    override fun onBackPressed() {
        // Never web.goBack(): history still holds the "Starting..." page. Let the UI close its own
        // overlays / selection first; otherwise leave the app (the server keeps running in the service).
        if (!pageReady) { finish(); return }
        if (::nl.isInitialized && nl.backUp()) return   // plain folder, nothing open: up one level at once (rows from the native cache)
        web.evaluateJavascript("(window.lsBack?lsBack():false)") { if (it != "true") finish() }
    }
}
