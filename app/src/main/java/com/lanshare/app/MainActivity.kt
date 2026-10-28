package com.lanshare.app

import android.content.Context
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
import android.view.MotionEvent
import android.view.View
import android.print.*
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.lanshare.app.core.Core
import com.lanshare.app.core.GAuth
import com.lanshare.app.core.Gdrive
import com.lanshare.app.core.InProc
import com.lanshare.app.core.PrintSpace
import com.lanshare.app.core.errText
import android.provider.OpenableColumns
import java.io.File
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

class MainActivity : Activity() {
    companion object {
        const val ACTION_PRINT_SHARED = "com.lanshare.app.PRINT_SHARED"
        const val ACTION_PRINT_FILES = "com.lanshare.app.PRINT_FILES"   // from the picture viewer's Print button: open the printer picker for these files
        const val X_DEV = "dev"
        const val X_PATHS = "paths"
        const val X_NAMES = "names"
        const val ACTION_SHOW_PATH = "com.lanshare.app.SHOW_PATH"   // from the Storage Analyzer: show this folder in the file manager
        const val X_SHOW_PATH = "path"
        const val X_SHOW_DEV = "show_dev"     // Storage Analyzer on another device: that device id ("" = this phone)
        const val X_SHOW_OPEN = "show_open"   // ... and the file name to open in that folder
    }
    private lateinit var web: WebView
    private lateinit var nl: NativeList
    private lateinit var chrome: NlChrome
    private lateinit var drawer: NlDrawer
    private lateinit var splash: NlSplash
    private var chooser: ValueCallback<Array<Uri>>? = null
    private var pageReady = false
    private var uiBooted = false   // the page's first screen is drawn (nlBoot / nlLayout / nlHide): a pending print dialog may open now
    private var showHome = false
    private val perfMarks get() = com.lanshare.app.core.PerfMarks.marks   // PERF-DEBUG (temporary): Settings -> Debug: performance
    private fun mark(k: String) { perfMarks.putIfAbsent(k, android.os.SystemClock.elapsedRealtime()) }   // PERF-DEBUG
    private val dlSeq = AtomicInteger()
    private val dlCancel = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    inner class Bridge {
        /** Start menu is shown on a fresh launch only: not after a rotation / recreate and not when started by a share / print / open-with intent. */
        @JavascriptInterface fun homeStart(): Boolean = showHome
        /** PERF-DEBUG (temporary): startup marks, ms since this process started. */
        @JavascriptInterface fun perfGet(): String { val base = android.os.Process.getStartElapsedRealtime(); val o = JSONObject(); perfMarks.forEach { (k, v) -> o.put(k, v - base) }; return JSONObject().put("marks", o).toString() }
        /** ui.html tells whether the swipe-right (Main menu <-> file manager) may act now, and where the header ends (CSS px; [vw] = page width in CSS px). */
        @JavascriptInterface fun edgeOk(ok: Boolean, top: Double, vw: Double) {
            swipeOk = ok
            swipeTop = if (vw > 0 && ::web.isInitialized) (top * web.width / vw).toFloat() else 0f
        }
        @JavascriptInterface fun cancel(id: Int) { dlCancel.add(id) }
        /** Main menu > Google Drive > Sign in / Reconnect: [email] = "" lets the user pick the account. The page hears the result through lsGdriveDone(ok, message). */
        @JavascriptInterface fun gdriveConnect(email: String) { gdStart(email) }
        /** Page keys the settings backup needs from localStorage (JSON array of names). */
        @JavascriptInterface fun settingsWebKeys(): String = org.json.JSONArray(com.lanshare.app.core.settings.SettingsSchema.WEB_KEYS).toString()
        /** Settings > Google Drive > Back up settings: [webJson] = {key: value|null} read from localStorage. The page hears the result through lsSettingsCloudDone(ok, message). */
        @JavascriptInterface fun settingsCloudBackup(accountId: String, webJson: String) {
            Thread({
                try { scJs(true, com.lanshare.app.core.settings.SettingsCloud.backup(applicationContext, accountId, webJson)) }
                catch (e: Throwable) { scJs(false, errText(e)) }
            }, "settings-cloud").also { it.isDaemon = true }.start()
        }
        /** Download + check the backup; the page gets lsSettingsCloudAsk(summary) (or lsSettingsCloudDone(false, why)) and then calls [settingsCloudApply] or [settingsCloudDrop]. */
        @JavascriptInterface fun settingsCloudRestore(accountId: String) {
            Thread({
                try {
                    when (val r = com.lanshare.app.core.settings.SettingsCloud.fetch(accountId)) {
                        is com.lanshare.app.core.settings.SettingsSchema.Result.Ok -> {
                            scStaged = r.parsed
                            val t = com.lanshare.app.core.settings.SettingsIo.describe(r.parsed)
                            runOnUiThread { try { web.evaluateJavascript("window.lsSettingsCloudAsk&&lsSettingsCloudAsk(" + JSONObject.quote(t) + ")", null) } catch (_: Throwable) {} }
                        }
                        is com.lanshare.app.core.settings.SettingsSchema.Result.Refused -> scJs(false, r.reasons.joinToString(" "))
                    }
                } catch (e: Throwable) { scJs(false, errText(e)) }
            }, "settings-cloud").also { it.isDaemon = true }.start()
        }
        /** The user confirmed: write the staged settings. Returns {count, web:{key:value|null}} for the page, or {error}. */
        @JavascriptInterface fun settingsCloudApply(): String {
            val p = scStaged ?: return JSONObject().put("error", "Nothing to restore").toString()
            scStaged = null
            return try {
                val a = com.lanshare.app.core.settings.SettingsIo.apply(applicationContext, p)
                JSONObject().put("count", a.count).put("web", a.web).toString()
            } catch (e: Throwable) { JSONObject().put("error", errText(e)).toString() }
        }
        @JavascriptInterface fun settingsCloudDrop() { scStaged = null }
        /** Settings > Export / import settings: [webJson] as in [settingsCloudBackup]; opens the system "save as" screen. The page hears the result through lsSettingsCloudDone(ok, message). */
        @JavascriptInterface fun settingsExport(webJson: String, secrets: Boolean) {
            try {
                setExpText = com.lanshare.app.core.settings.SettingsIo.export(applicationContext, webJson, secrets)
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE, com.lanshare.app.core.settings.SettingsCloud.FILE_NAME)
                runOnUiThread { try { startActivityForResult(i, reqSetExp) } catch (e: Throwable) { setExpText = null; scJs(false, errText(e)) } }
            } catch (e: Throwable) { scJs(false, errText(e)) }
        }
        /** Opens the system file picker; the chosen file is checked and the page gets lsSettingsCloudAsk(summary) (or lsSettingsCloudDone(false, why)), then calls [settingsCloudApply] or [settingsCloudDrop]. */
        @JavascriptInterface fun settingsImport() {
            runOnUiThread {
                try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), reqSetImp) }
                catch (e: Throwable) { scJs(false, errText(e)) }
            }
        }
        /** Root support: the per-run token the page keeps in a cookie so its requests count as "from the app's own screen" (see RootShell). Other apps cannot call this bridge. */
        @JavascriptInterface fun rootToken(): String = com.lanshare.app.core.RootShell.token
        @JavascriptInterface fun checkUpdate() = UpdateCheck.run(this@MainActivity, true) { m -> runOnUiThread { web.evaluateJavascript("toast(" + JSONObject.quote(m) + ")", null) } }
        /** Copy/paste/send job started in the UI: mirror its progress in the notification shade. */
        @JavascriptInterface fun watch(origin: String, job: String) { watchJob(origin, job) }
        /** Tap on a file: fetch it to cache, then hand it to an app that can open it. */
        @JavascriptInterface fun open(url: String) { saveToDownloads(url, null, true) }
        /** Part 8: "Open in Google Drive" - only Google's own https links (browser or the Drive app). */
        @JavascriptInterface fun openLink(url: String) {
            if (!(url.startsWith("https://drive.google.com/") || url.startsWith("https://docs.google.com/"))) return
            runOnUiThread { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)) } catch (_: Exception) {} }
        }
        /** Same, but always shows the system "Open with" app chooser. */
        @JavascriptInterface fun openWith(url: String) { saveToDownloads(url, null, true, true) }
        /** Share one or more LANShare files: fetched to cache/open, then the system share sheet. [json] = {items:[{name,url,size}]} */
        @JavascriptInterface fun shareFiles(json: String) { shareFilesImpl(json) }
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
        /** Word / Excel / ODF tapped: copy to cache (max 60 MB) and open the Office viewer. [json] = {name, url, size} */
        @JavascriptInterface fun viewOffice(json: String) {
            val o = JSONObject(json); val nm = o.optString("name", "file").replace(Regex("[\\\\/:*?\"<>|]"), "_")
            Thread {
                try {
                    val d = java.io.File(cacheDir, "office").apply { mkdirs(); listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 3600_000) it.delete() } }
                    val f = java.io.File(d, nm)
                    val c = java.net.URL(o.getString("url")).openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = 15000; c.readTimeout = 60000
                    c.inputStream.use { i -> f.outputStream().use { out -> var t = 0L; val b = ByteArray(65536); while (true) { val n = i.read(b); if (n < 0) break; t += n; if (t > 60L shl 20) throw java.io.IOException("too big"); out.write(b, 0, n) } } }
                    runOnUiThread { startActivity(Intent(this@MainActivity, OfficeViewerActivity::class.java).putExtra("path", f.absolutePath).putExtra("dev", o.optString("dev")).putExtra("rpath", o.optString("path"))) }
                } catch (e: Throwable) { runOnUiThread { android.widget.Toast.makeText(this@MainActivity, "Cannot open: " + (e.message ?: "error"), android.widget.Toast.LENGTH_LONG).show() } }
            }.start()
        }
        /** + menu -> "Word document" / "Excel workbook": [json] = {dev, dir, kind:"docx"|"xlsx"}. Creates an empty file with a free name in that folder. */
        @JavascriptInterface fun newOffice(json: String) {
            val o = JSONObject(json)
            Thread {
                try {
                    val kind = if (o.optString("kind") == "xlsx") "xlsx" else "docx"
                    val ep = com.lanshare.app.core.Jobs.ep(o.getString("dev")); val dir = o.optString("dir", "/")
                    val taken = ep.names(dir).map { it.lowercase() }.toSet()
                    val base = if (kind == "xlsx") "New workbook" else "New document"
                    var nm = "$base.$kind"; var n = 2
                    while (nm.lowercase() in taken) nm = "$base ($n).$kind".also { n++ }
                    val tmp = java.io.File(cacheDir, "new-$nm")
                    if (kind == "xlsx") com.lanshare.app.core.office.XlsxWriter.createEmpty(tmp) else com.lanshare.app.core.office.DocxWriter.createEmpty(tmp)
                    try { tmp.inputStream().use { ep.write(com.lanshare.app.core.vjoin(dir, nm), it, tmp.length()) } } finally { tmp.delete() }
                    runOnUiThread { try { web.evaluateJavascript("window.lsRefresh&&window.lsRefresh()", null) } catch (_: Throwable) {}; android.widget.Toast.makeText(this@MainActivity, "Created $nm", android.widget.Toast.LENGTH_SHORT).show() }
                } catch (e: Throwable) { runOnUiThread { android.widget.Toast.makeText(this@MainActivity, "Cannot create: " + (e.message ?: "error"), android.widget.Toast.LENGTH_LONG).show() } }
            }.start()
        }
        /** Text / code file tapped, or "New note": open the Keep-style editor. [json] = {dev, path} or {dev, dir, isNew:true} */
        @JavascriptInterface fun editText(json: String) {
            runOnUiThread { try { startActivity(TextEditorActivity.intent(this@MainActivity, json)) } catch (_: Throwable) { } }
        }
        /** Menu -> "Phone Explorer": state of the FTP share (phone storage in Windows Explorer). */
        @JavascriptInterface fun peState(): String {
            val c = applicationContext
            val o = JSONObject()
            o.put("run", com.lanshare.app.pe.PeService.isRunning)
            o.put("err", com.lanshare.app.pe.PeService.lastError ?: "")
            o.put("clients", com.lanshare.app.pe.PeService.clients)
            o.put("pin", com.lanshare.app.pe.PePrefs.pin(c))
            o.put("port", com.lanshare.app.pe.PePrefs.port(c))
            o.put("ip", com.lanshare.app.pe.PePrefs.localIp() ?: "")
            o.put("ro", com.lanshare.app.pe.PePrefs.readOnly(c))
            o.put("auto", com.lanshare.app.pe.PePrefs.autoStart(c))
            o.put("tls", com.lanshare.app.pe.PePrefs.tls(c))
            o.put("tlsreq", com.lanshare.app.pe.PePrefs.tlsRequired(c))
            o.put("fp", com.lanshare.app.pe.PeTls.fingerprint(filesDir))
            o.put("wl", com.lanshare.app.pe.PePrefs.whitelistText(c))
            o.put("user", com.lanshare.app.pe.PePrefs.USER)
            o.put("name", Build.MODEL ?: "Android")
            o.put("access", Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager())
            o.put("link", com.lanshare.app.pe.PePrefs.pairingLink(c, Build.MODEL ?: "Android"))
            return o.toString()
        }
        /** Starts the share; without all-files access it opens that Android setting instead and returns false. */
        @JavascriptInterface fun peStart(): Boolean {
            if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) { peGrant(); return false }
            return try { com.lanshare.app.pe.PeService.start(applicationContext); true } catch (_: Throwable) { false }
        }
        @JavascriptInterface fun peStop() { com.lanshare.app.pe.PeService.stop(applicationContext) }
        @JavascriptInterface fun peGrant() {
            runOnUiThread {
                try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
                catch (_: Throwable) { try { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (_: Throwable) { } }
            }
        }
        /** Saves settings {ro, auto, wl, port}; read-only / folders apply at once, a new port after Stop + Start. */
        @JavascriptInterface fun peSet(json: String) {
            val j = JSONObject(json); val c = applicationContext
            if (j.has("ro")) com.lanshare.app.pe.PePrefs.setReadOnly(c, j.getBoolean("ro"))
            if (j.has("auto")) com.lanshare.app.pe.PePrefs.setAutoStart(c, j.getBoolean("auto"))
            if (j.has("tls")) com.lanshare.app.pe.PePrefs.setTls(c, j.getBoolean("tls"))
            if (j.has("tlsreq")) com.lanshare.app.pe.PePrefs.setTlsRequired(c, j.getBoolean("tlsreq"))
            if (j.has("wl")) com.lanshare.app.pe.PePrefs.setWhitelistText(c, j.getString("wl"))
            if (j.has("port")) com.lanshare.app.pe.PePrefs.setPort(c, j.getInt("port"))
        }
        @JavascriptInterface fun peNewPin(): String = com.lanshare.app.pe.PePrefs.newPin(applicationContext)
        /** QR code of the pairing link as rows of 0 / 1 (the page draws it on a canvas). */
        @JavascriptInterface fun peQr(): String = try {
            val link = com.lanshare.app.pe.PePrefs.pairingLink(applicationContext, Build.MODEL ?: "Android")
            val m = com.google.zxing.qrcode.QRCodeWriter().encode(link, com.google.zxing.BarcodeFormat.QR_CODE, 1, 1,
                mapOf<com.google.zxing.EncodeHintType, Any>(com.google.zxing.EncodeHintType.MARGIN to 1))
            (0 until m.height).joinToString("\n") { y -> (0 until m.width).joinToString("") { x -> if (m.get(x, y)) "1" else "0" } }
        } catch (_: Throwable) { "" }
        /** Menu -> "Text inbox": the native send / receive text screen (InboxActivity). */
        @JavascriptInterface fun openInbox() {
            runOnUiThread { try { startActivity(Intent(this@MainActivity, InboxActivity::class.java)) } catch (_: Throwable) { } }
        }
        /** Settings -> Text sharing: {"def": default device id or "", "defName": its name or "", "devs": [{"id","name"} online devices]}. */
        /** Settings -> Print log / PC screen -> Print log: the native print log screen. [jids] = comma list of job ids ("" = all), [dev] = PC id for "Fetch PC log" ("" = none). */
        @JavascriptInterface fun openPrintLog(jids: String, dev: String) {
            runOnUiThread { try { PrintLogActivity.open(this@MainActivity, jids, dev) } catch (_: Throwable) { } }
        }
        /** Storage pill: opens the Storage Analyzer for this phone's folder [vpath] ("/" = whole main storage). */
        @JavascriptInterface fun openStorage(vpath: String) {
            runOnUiThread { try { startActivity(StorageActivity.intent(this@MainActivity, vpath)) } catch (_: Throwable) { } }
        }
        /** Path-bar / file manager search: the Universal Search screen (instant name search of this phone). [q] = text to start with. */
        @JavascriptInterface fun openUsearch(q: String) {
            runOnUiThread { try { startActivity(UsActivity.intent(this@MainActivity, q)) } catch (_: Throwable) { } }
        }
        /** File manager search button: Universal Search limited to the folder [vpath] (file manager path of this phone; "/" = whole storage, blank = whole phone). */
        @JavascriptInterface fun openUsearchIn(q: String, vpath: String) {
            runOnUiThread {
                try {
                    val v = vpath.trim()
                    val scope = if (v.isEmpty() || v == "/") "" else java.io.File(Core.local.root, v.trimStart('/')).let { if (it.isDirectory) it.path else "" }
                    startActivity(UsActivity.intent(this@MainActivity, q, "", scope))
                } catch (_: Throwable) { }
            }
        }
        /** True when Universal Search is switched on (kill switch UniversalSearch.ENABLED / Settings > Search index). */
        @JavascriptInterface fun usearchOk(): Boolean = try { com.lanshare.app.core.IndexManager.enabled() } catch (_: Throwable) { false }
        /** Storage pill on another device (LANShare phone / SMB / FTP / SFTP): read-only analysis of its folder [vpath]. */
        @JavascriptInterface fun openStorageDev(dev: String, name: String, vpath: String) {
            runOnUiThread { try { startActivity(StorageActivity.intentDev(this@MainActivity, dev, name, vpath)) } catch (_: Throwable) { } }
        }
        @JavascriptInterface fun txtDevs(): String {
            val o = JSONObject()
            val d = com.lanshare.app.core.Cfg.defTarget()
            o.put("def", d)
            o.put("defName", TextSend.defaultName() ?: "")
            val a = org.json.JSONArray()
            TextSend.online().forEach { a.put(JSONObject().put("id", it.id).put("name", it.name)) }
            o.put("devs", a)
            return o.toString()
        }
        /** Settings -> Text sharing: set the default device ("" = ask every time). */
        @JavascriptInterface fun txtDefSet(id: String) {
            com.lanshare.app.core.Cfg.setDefTarget(id)
            Thread { Shortcuts.sync(applicationContext) }.also { it.isDaemon = true }.start()
        }
        /** Android version, so the UI knows whether HEIC pictures can be decoded natively (API 28+). */
        @JavascriptInterface fun sdk(): Int = Build.VERSION.SDK_INT
        /** Settings -> display size: "interface%,text%". */
        /** Width of the WebView in dp: in split screen / multi-window it is narrower than the display (window.screen.width reports the whole display). */
        @JavascriptInterface fun winW(): Int = Math.round(web.width / resources.displayMetrics.density)
        /** Page preferences that must survive a change of the page's origin (port) or cleared web storage: per-folder sort / view (ls_g, ls_pf). */
        @JavascriptInterface fun prefGet(k: String): String = try { getSharedPreferences("ui_prefs", Context.MODE_PRIVATE).getString(k, "") ?: "" } catch (_: Throwable) { "" }
        @JavascriptInterface fun prefSet(k: String, v: String) { try { if (k.startsWith("ls_") && v.length < 1_500_000) getSharedPreferences("ui_prefs", Context.MODE_PRIVATE).edit().putString(k, v).apply() } catch (_: Throwable) { } }
        @JavascriptInterface fun uiGet(): String = UiScale.iface(this@MainActivity).toString() + "," + UiScale.text(this@MainActivity)
        @JavascriptInterface fun uiSet(i: Int, t: Int) { UiScale.save(this@MainActivity, i, t); runOnUiThread { recreate() } }
        /** Settings -> Font: current id and the list as "id|name" lines. */
        @JavascriptInterface fun fontGet(): String = UiScale.font(this@MainActivity).let { if (Fnt.valid(it)) it else "figtree" }
        @JavascriptInterface fun fontList(): String = Fnt.LIST.joinToString("\n") { it.first + "|" + it.second }
        /** Interface size + text size + font in one go (one recreate). */
        @JavascriptInterface fun uiSet2(i: Int, t: Int, font: String) {
            UiScale.save(this@MainActivity, i, t)
            if (Fnt.valid(font)) UiScale.saveFont(this@MainActivity, font)
            Fnt.reload(applicationContext); runOnUiThread { recreate() }
        }
        /** Settings -> Text recognition model: "mode,paddleInstalled" (mode 0 automatic, 1 PaddleOCR, 2 ML Kit; installed 0 / 1). */
        @JavascriptInterface fun ocrEngGet(): String = OcrPrefs.engine(this@MainActivity).toString() + "," + (if (PaddleModels.installed(this@MainActivity)) 1 else 0)
        @JavascriptInterface fun officeViewGet(): String = com.lanshare.app.core.OfficeExact.mode(this@MainActivity)
        @JavascriptInterface fun officeViewSet(m: String) { com.lanshare.app.core.OfficeExact.setMode(this@MainActivity, m) }
        @JavascriptInterface fun ocrEngSet(m: Int) { OcrPrefs.setEngine(this@MainActivity, m); if (m != OcrPrefs.ENG_MLKIT) PaddleModels.installAsync(applicationContext) }
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
        @JavascriptInterface fun nlPalette(json: String) { try { AppPal.save(applicationContext, json) } catch (_: Throwable) { }; runOnUiThread { nl.setPalette(json) } }
        /** key = "dev|path", items = JSON rows, sel = JSON array of selected row indexes. Parsed here (bridge thread), applied on the UI thread. */
        @JavascriptInterface fun nlItems(key: String, items: String, sel: String) {
            splash.noteItems(key, items)
            try { val rows = NativeList.parseRows(items, key.substringBefore('|')); val s = NativeList.parseSel(sel); val sig = NlModel.sig(rows)
                runOnUiThread { nl.setItems(key, rows, s, sig) } } catch (_: Throwable) { }
        }
        @JavascriptInterface fun nlCfg(c: String) { runOnUiThread { nl.setCfg(c) } }
        /** JSON array of the paths hidden with the Hide button (ui.html HGAL). */
        @JavascriptInterface fun nlHidden(json: String) {
            try { val a = org.json.JSONArray(json); val set = HashSet<String>(); for (k in 0 until a.length()) set.add(a.getString(k)); runOnUiThread { nl.setHidden(set) } } catch (_: Throwable) { } }
        /** JSON {"/path": [colour ARGB or 0, icon key or "", icon colour]} of this phone's folders with a custom colour / icon (ui.html FSTY). */
        @JavascriptInterface fun nlFStyle(json: String) {
            try {
                val o = org.json.JSONObject(json); val m = HashMap<String, NlModel.FS>()
                for (k in o.keys()) { val a = o.getJSONArray(k); m[k] = NlModel.FS(a.getLong(0).toInt(), a.getString(1).ifEmpty { null }, a.getLong(2).toInt()) }
                runOnUiThread { nl.setFStyle(m) }
            } catch (_: Throwable) { } }
        @JavascriptInterface fun nlPatch(json: String) { runOnUiThread { try { nl.patch(json) } catch (_: Throwable) { } } }
        @JavascriptInterface fun nlSel(sel: String) { try { val s = NativeList.parseSel(sel); runOnUiThread { nl.setSel(s) } } catch (_: Throwable) { } }
        @JavascriptInterface fun nlLayout(json: String) { mark("firstScreen"); Core.firstScreenDrawn(); runOnUiThread { try { nl.layout(json) } catch (_: Throwable) { }; splash.dismiss(); uiBooted = true; runPrint() } }
        @JavascriptInterface fun nlHide() { mark("firstScreen"); Core.firstScreenDrawn(); runOnUiThread { nl.hide(); splash.dismiss(); uiBooted = true; runPrint() } }
        /** Path bar + tool row content (NlHead.kt): JSON from ui.html nlHeadPush(). */
        @JavascriptInterface fun nlHead(json: String) { runOnUiThread { try { nl.setHead(json) } catch (_: Throwable) { } } }
        /** First load() of the page finished (whatever the outcome): the native start picture is no longer needed. */
        @JavascriptInterface fun nlBoot() { mark("firstScreen"); Core.firstScreenDrawn(); runOnUiThread { splash.dismiss(); uiBooted = true; runPrint() } }
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
    private var pendingPrintFiles: String? = null   // {dev, items:[{name, path}]} from the picture viewer
    private var pendingPrintPriv = false   // names are in the private print-in folder (from PcPrintService), not in LANShare Shared
    private val phonePrint by lazy { PhonePrint(this) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun attachBaseContext(b: Context) { super.attachBaseContext(b); UiScale.attach(this, b, false) }

    /** Fold / unfold: the activity is not recreated (configChanges), so tell the page to re-fit its layout width to the new screen. */
    override fun onConfigurationChanged(c: android.content.res.Configuration) {
        super.onConfigurationChanged(c)
        if (::web.isInitialized) web.post { web.evaluateJavascript("window.lsFit&&lsFit()", null) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        mark("onCreate")   // PERF-DEBUG
        // Start the local server right away on a background thread (it used to wait for the foreground service to come up). Core.start is idempotent: the service's later call returns at once.
        Thread { mark("core.threadStart"); Core.start(applicationContext, android.os.Environment.getExternalStorageDirectory().absolutePath) }.apply { isDaemon = true; start() }
        showHome = b == null && (intent == null || intent.action == null || intent.action == Intent.ACTION_MAIN)
        Core.onFtpChanged = { dev, path -> runOnUiThread { try { if (::web.isInitialized) web.evaluateJavascript("window.lsFtpChanged&&lsFtpChanged(" + JSONObject.quote(dev) + "," + JSONObject.quote(path) + ")", null) } catch (_: Throwable) {} } }   // FTP folder shown from the saved listing changed on the server
        Fnt.init(applicationContext)
        mark("fontInit")   // PERF-DEBUG
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            if (UiScale.iface(this@MainActivity) != 100) { settings.useWideViewPort = true; settings.loadWithOverviewMode = false }   // the page rewrites its viewport meta to scale itself (ui.html head)
            settings.textZoom = UiScale.text(this@MainActivity)   // 100 = ignore the system font-size slider so every device renders the same sizes; Settings -> Text size changes it
            addJavascriptInterface(Bridge(), "LSAndroid")
            addOnLayoutChangeListener { _, l, _, r, _, ol, _, or2, _ -> if (r - l != or2 - ol) post { evaluateJavascript("window.lsFit&&lsFit()", null) } }   // window resized (split screen, fold)
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
                        val up = u.encodedPath ?: ""
                        if (up.startsWith("/appfont/") && (u.host == "127.0.0.1" || u.host == "localhost")) {      // chosen app font for the page (Settings -> Font)
                            val id = up.substring(9).removeSuffix(".ttf")
                            if (Fnt.valid(id) && id != "figtree" && id != "system")
                                return WebResourceResponse("font/ttf", null, 200, "OK", mapOf("Cache-Control" to "max-age=86400", "Access-Control-Allow-Origin" to "*"), assets.open("fonts/ff_" + id + ".ttf"))
                        }
                        val x = InProc.serve(r.method, u.host, u.port, u.encodedPath ?: "", u.encodedQuery) ?: return null
                        WebResourceResponse(x.ctype, "utf-8", x.code, x.reason, x.headers, x.body())
                    } catch (_: Throwable) { null }   // anything unexpected: the request simply goes the normal HTTP way
                }
                override fun onPageFinished(v: WebView, u: String) {
                    if (u.startsWith("http://127.0.0.1") || u.startsWith("http://localhost")) { mark("pageFinished"); pageReady = true; runShare(); runClipShare(); runPrint(); runOpen(); runShowPath() }
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
        mark("webCreated")   // PERF-DEBUG
        nl = NativeList(this, web)
        chrome = NlChrome(this) { ev, x -> web.evaluateJavascript("window.nlCh&&nlCh(" + JSONObject.quote(ev) + "," + JSONObject.quote(x) + ")", null) }
        drawer = NlDrawer(this) { k, i, j, v -> web.evaluateJavascript("window.nlDr&&nlDr(" + JSONObject.quote(k) + "," + i + "," + j + "," + v + ")", null) }
        mark("nativeViews")   // PERF-DEBUG
        val root = FrameLayout(this).apply {
            addView(web, FrameLayout.LayoutParams(-1, -1))
            addView(nl.overlay, FrameLayout.LayoutParams(-1, -1))   // native file list: above the page, invisible until ui.html sends a layout
            addView(nl.search, FrameLayout.LayoutParams(1, 1))      // native search box: its own view (the overlay forwards touches outside the list to the page); GONE until the page opens its search row
            addView(chrome.layer, FrameLayout.LayoutParams(-1, -1)) // native app chrome (bars, FAB, dock, snackbar): no background, touches outside its buttons fall through
            addView(drawer.panel, drawer.layoutParams())            // native side drawer: topmost, GONE until the page opens its drawer; swallows touches inside its own width only
        }
        setContentView(root)
        mark("contentSet")   // PERF-DEBUG
        edgeInit(root)
        splash = NlSplash(this, nl, web, root)
        if (b == null) splash.start()      // cold start: draw the last screen natively while the page loads
        mark("splashStarted")   // PERF-DEBUG
        askPermissions()
        mark("permissionsAsked")   // PERF-DEBUG
        ContextCompat.startForegroundService(this, Intent(this, LanShareService::class.java))
        mark("loadStart")   // PERF-DEBUG
        loadWhenReady()
        cleanShared()
        handleShare(intent)
        if (b == null) UpdateCheck.run(this)   // cold start: look for a newer GitHub release
    }


    // ---- swipe = Main menu <-> file manager, from anywhere below the header (longer, mostly horizontal swipe). Not from the left screen edge (system Back gesture). ----
    @Volatile private var swipeOk = false     // set by ui.html (edgeOk): false while a dialog / viewer / selection / search / the side drawer is open
    @Volatile private var swipeTop = 0f       // px: the header (path bar, device chips) ends here; swipes that start inside it scroll those strips instead
    private var swOn = false                  // the current touch is being watched
    private var swDone = false                // the swipe was recognised: swallow the rest of this touch
    private var swEdge = false                // the touch started in the edge zone
    private var swTop = false                 // the touch started in the header
    private var swX = 0f; private var swY = 0f

    /** The left screen edge belongs to the phone's Back gesture: no gesture-exclusion strip is requested and swipes that start there are never ours. */
    private fun edgeInit(root: View) { }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val d = resources.displayMetrics.density
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swDone = false
                swEdge = ev.rawX <= 36f * d                                       // system Back-gesture zone: leave it alone
                swOn = swipeOk && ev.pointerCount == 1 && !swEdge
                swTop = ev.rawY < swipeTop
                swX = ev.rawX; swY = ev.rawY
            }
            MotionEvent.ACTION_POINTER_DOWN -> swOn = false                  // pinch / two fingers: not a swipe
            MotionEvent.ACTION_MOVE -> {
                if (swDone) return true
                if (swOn && ev.pointerCount == 1) {
                    val dx = ev.rawX - swX; val dy = ev.rawY - swY
                    val ok = swEdge || !swTop                                // the header scrolls horizontally itself, except right at the edge
                    val need = if (swEdge) 40f * d else 72f * d
                    val slope = if (swEdge) 0.8f else 0.5f
                    if (!ok || (Math.abs(dy) > 24f * d && Math.abs(dy) > Math.abs(dx))) swOn = false   // header or a vertical scroll: not ours
                    else if (Math.abs(dx) > need && Math.abs(dy) < Math.abs(dx) * slope) {   // swipe in either direction toggles file manager <-> Main menu
                        swOn = false; swDone = true
                        val c = MotionEvent.obtain(ev); c.action = MotionEvent.ACTION_CANCEL
                        super.dispatchTouchEvent(c); c.recycle()               // list / page stop their own gesture
                        if (::web.isInitialized) web.evaluateJavascript("window.lsEdgeOpen&&lsEdgeOpen()", null)
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val was = swDone
                swOn = false; swDone = false
                if (was) return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) tryClipSend() }

    override fun onPause() { super.onPause(); if (ClipPush.front === this) ClipPush.front = null; if (::splash.isInitialized) try { splash.save() } catch (_: Throwable) { } }

    override fun onResume() {
        super.onResume()
        ClipPush.front = this
        try { com.lanshare.app.core.IndexManager.onResume() } catch (_: Throwable) {}   // name index: look for changes other apps made
        ModelLinks.checkAll(applicationContext); ModelLinks.showIfAny(this)   // dead model download links: daily check + warning screen
        if (::drawer.isInitialized) drawer.resync()
        if (::chrome.isInitialized) chrome.resync()
        if (::web.isInitialized) web.evaluateJavascript("window.lsNlResync&&lsNlResync()", null)
        if (::web.isInitialized) web.evaluateJavascript("window.lsDlSweep&&lsDlSweep()", null)
        if (::web.isInitialized) web.evaluateJavascript("window.jbKick&&jbKick()", null)   // restart the job poll loop (it stops when idle): a scan / job started in another screen shows the bubble again
        if (::web.isInitialized && (TextEditorActivity.consumeChanged() or ImageViewerActivity.consumeChanged() or PlayerActivity.consumeChanged())) web.evaluateJavascript("window.lsRefresh&&lsRefresh()", null)   // the editor saved a file / the picture viewer deleted or edited one: refresh the list and an open album
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); handleShare(i) }

    /** Android share sheet -> copy into <storage>/LANShare Shared so it can be selected and Sent from the UI. */
    private fun handleShare(i: Intent?) {
        if (i != null && i.action == ACTION_PRINT_FILES) {   // picture viewer -> same printer picker as the file list (this phone, Wi-Fi printers, PCs, PDF)
            val paths = i.getStringArrayExtra(X_PATHS)?.toList().orEmpty()
            val names = i.getStringArrayExtra(X_NAMES)?.toList().orEmpty()
            val arr = org.json.JSONArray()
            paths.forEachIndexed { k, p -> arr.put(JSONObject().put("name", names.getOrNull(k) ?: p.substringAfterLast('/')).put("path", p)) }
            i.action = null
            if (arr.length() > 0) { pendingPrintFiles = JSONObject().put("dev", i.getStringExtra(X_DEV) ?: "local").put("items", arr).toString(); runPrint() }
            return
        }
        if (i != null && i.action == ACTION_PRINT_SHARED) {   // from PcPrintService: a document printed from another app
            val n = i.getStringArrayExtra("names")?.toList().orEmpty()
            getSystemService(NotificationManager::class.java).cancel(i.getIntExtra("nid", 0))
            pendingPrintPriv = i.getBooleanExtra("priv", false)
            i.action = null
            if (n.isNotEmpty()) { pendingPrint = n; runPrint() }
            return
        }
        if (i != null && i.action == ACTION_SHOW_PATH) {   // Storage Analyzer -> "Show in file manager"
            pendingShowPath = i.getStringExtra(X_SHOW_PATH)
            pendingShowDev = i.getStringExtra(X_SHOW_DEV) ?: ""
            pendingShowOpen = i.getStringExtra(X_SHOW_OPEN) ?: ""
            i.action = null   // consume once
            runShowPath()
            return
        }
        if (i != null && i.action == Intent.ACTION_VIEW && i.data != null) { handleView(i); return }   // "Open with" on an archive
        if (i == null || (i.action != Intent.ACTION_SEND && i.action != Intent.ACTION_SEND_MULTIPLE)) return
        @Suppress("DEPRECATION")
        val uris: List<Uri> = if (i.action == Intent.ACTION_SEND)
            listOfNotNull(i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        else i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
        val viaSend = i.component?.className?.endsWith("SendShareAlias") == true   // plain "LANShare" share entry = print; the "LANShare Send" entry sends the clipboard
        if (viaSend) {
            val shownText = i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            i.action = null   // consume once
            clipReq = ClipReq(shownText, uris)
            tryClipSend()
            return
        }
        if (i.component?.className?.endsWith("PrintSpaceShareAlias") == true) {   // "LANShare Print space" share entry (HANDOVER 9.7 part 3b)
            i.action = null   // consume once
            if (uris.isNotEmpty()) shareToPrintSpace(uris)
            return
        }
        if (uris.isEmpty()) return
        i.action = null   // consume once
        copySharedIn(uris, true)
    }

    private class ClipReq(val text: String?, val uris: List<Uri>)
    private var clipReq: ClipReq? = null
    private var pendingClipText: String? = null

    /** "LANShare Send": the clipboard (link / text) goes to the picked device. Android 10+ lets only the focused app read the clipboard, so this waits for window focus.
     *  Empty clipboard -> the text of the share, then (last resort) the shared files like before. */
    private fun tryClipSend() {
        val r = clipReq ?: return
        if (!hasWindowFocus()) { window.decorView.postDelayed({ tryClipSend() }, 600); return }
        clipReq = null
        val fromClip = try {
            (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip
                ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        } catch (_: Throwable) { null }
        val text = listOf(fromClip, r.text).firstOrNull { !it.isNullOrBlank() }
        when {
            text != null -> { pendingClipText = text; runClipShare() }
            r.uris.isNotEmpty() -> copySharedIn(r.uris, false)
            else -> Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_LONG).show()
        }
    }

    /** Opens the "Send to..." device picker for the clipboard text (waits until the page is loaded). */
    private fun runClipShare() {
        val t = pendingClipText ?: return
        if (!pageReady) return
        pendingClipText = null
        web.postDelayed({
            web.evaluateJavascript(
                "(async()=>{try{const p=await pickDevice(true,'Send clipboard to\\u2026');if(!p)return;" +
                "await api('POST','/api/sendclip',{to:p.id,text:" + JSONObject.quote(t) + "});toast('Sent to '+p.name)}catch(e){toast('\\u26a0 '+e.message,5000)}})()", null)
        }, 1200)
    }

    /** Share sheet > "LANShare Print space": every picture is streamed into the Print space (core/PrintSpace.kt); nothing is printed or sent. */
    private fun shareToPrintSpace(uris: List<Uri>) {
        Toast.makeText(this, "Adding to Print space\u2026", Toast.LENGTH_SHORT).show()
        Thread {
            var ok = 0
            var why: String? = null
            for (u in uris) {
                var name = ""
                try { contentResolver.query(u, null, null, null, null)?.use { c -> if (c.moveToFirst()) c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: "" } } } catch (_: Exception) {}
                if (name.isEmpty()) name = u.lastPathSegment?.substringAfterLast('/') ?: ""
                val r = try { PrintSpace.addStream(name, contentResolver.getType(u)) { contentResolver.openInputStream(u) } } catch (e: Exception) { errText(e).take(120) }
                if (r == null) ok++ else if (why == null) why = r
            }
            val msg = when {
                ok > 0 && why == null -> "$ok picture" + (if (ok == 1) "" else "s") + " added to Print space"
                ok > 0 -> "$ok added, others skipped: $why"
                else -> "Not added: " + (why ?: "nothing to add")
            }
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }.start()
    }

    private fun copySharedIn(uris: List<Uri>, toPrint: Boolean) {
        if (toPrint && !pageReady) Toast.makeText(this, "Preparing print\u2026", Toast.LENGTH_SHORT).show()   // cold start: something on screen while the page loads
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

    private var pendingOpen: String? = null

    /** Files copied into "LANShare Shared" (shared-in / opened archives) are deleted after one day (Core.cleanShared); runs at every start and then daily. */
    private fun cleanShared() { Thread { Core.cleanShared() }.start() }

    /** "Open with LANShare" on a zip / rar: browse it in the app's own file browser (path + "!"). Files already on the shared storage are opened in place, anything else is copied to "LANShare Shared" first. */
    private fun handleView(i: Intent) {
        val u = i.data ?: return
        i.action = null   // consume once
        Thread {
            var rel: String? = null
            try {
                val root = Environment.getExternalStorageDirectory().absolutePath
                var real: File? = null
                if (u.scheme == "file") real = u.path?.let { File(it) }
                else if (u.authority == "com.android.externalstorage.documents") {   // file managers: primary:Download/a.zip -> /storage/emulated/0/Download/a.zip
                    val id = android.provider.DocumentsContract.getDocumentId(u)
                    if (id.startsWith("primary:")) real = File(root, id.substring(8))
                }
                if (real != null && real.isFile && real.absolutePath.startsWith("$root/")) rel = real.absolutePath.substring(root.length)
                if (rel == null) {
                    var name = "opened_" + System.currentTimeMillis() + ".zip"; var size = -1L
                    contentResolver.query(u, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                            c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
                        }
                    }
                    val dir = File(Environment.getExternalStorageDirectory(), "LANShare Shared").apply { mkdirs() }
                    var f = File(dir, name.replace("/", "_")); var k = 1
                    if (f.isFile && size > 0 && f.length() == size) f.setLastModified(System.currentTimeMillis())   // reused: keep it for another day (Core.SHARED_KEEP_MS)
                    else {   // not copied yet
                        while (f.exists()) { f = File(dir, "${f.nameWithoutExtension} ($k)${if (f.extension.isEmpty()) "" else "." + f.extension}"); k++ }
                        contentResolver.openInputStream(u)?.use { ins -> f.outputStream().use { ins.copyTo(it) } }
                    }
                    if (f.isFile) rel = "/LANShare Shared/" + f.name
                }
            } catch (_: Exception) {}
            runOnUiThread {
                if (rel != null) { pendingOpen = rel; runOpen() }
                else Toast.makeText(this, "Could not open the archive", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private var pendingShowPath: String? = null
    private var pendingShowDev = ""
    private var pendingShowOpen = ""

    /** Opens folder [pendingShowPath] (virtual path on this device) in the file manager page; waits until the page is loaded. */
    private fun runShowPath() {
        val p = pendingShowPath ?: return
        if (!pageReady) return
        pendingShowPath = null
        val d = pendingShowDev; val o = pendingShowOpen
        pendingShowDev = ""; pendingShowOpen = ""
        if (d.isNotEmpty()) web.postDelayed({ web.evaluateJavascript("window.lsGoDev&&lsGoDev(" + JSONObject.quote(d) + "," + JSONObject.quote(p) + "," + JSONObject.quote(o) + ")", null) }, 300)
        else web.postDelayed({ web.evaluateJavascript("window.lsGoLocal&&lsGoLocal(" + JSONObject.quote(p) + ")", null) }, 300)
    }

    private fun runOpen() {
        val p = pendingOpen ?: return
        if (!pageReady) return
        pendingOpen = null
        web.postDelayed({ web.evaluateJavascript("window.lsOpenArc&&lsOpenArc(" + JSONObject.quote(p) + ")", null) }, 800)
    }

    /** Open LANShare's print dialog (PC picker + layout options + preview) for files printed from another app (waits until the page is loaded). */
    private fun runPrint() {
        if ((pendingPrint == null && pendingPrintFiles == null) || !pageReady) return
        if (uiBooted) firePrint()
        else web.postDelayed({ firePrint() }, 600)   // first screen not drawn yet: wait at most 0.6 s (nlBoot / nlLayout call runPrint again as soon as it is)
    }

    /** Opens the print dialog in the page; idempotent (the pending names are consumed), so several scheduled calls are harmless. */
    private fun firePrint() {
        pendingPrintFiles?.let { js ->
            pendingPrintFiles = null
            web.evaluateJavascript("(function f(n){if(window.lsPrintFiles)lsPrintFiles($js);else if(n<30)setTimeout(function(){f(n+1)},100)})(0)", null)
        }
        val names = pendingPrint ?: return
        pendingPrint = null
        val arr = org.json.JSONArray(names).toString()
        val priv = pendingPrintPriv; pendingPrintPriv = false
        web.evaluateJavascript("(function f(n){if(window.lsPrintShared)lsPrintShared($arr,$priv);else if(n<30)setTimeout(function(){f(n+1)},100)})(0)", null)
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

    // ---- Google Drive sign-in (HANDOVER chapter 17, Part 1): Play Services Authorization API; the consent screen needs an Activity
    private val reqGdrive = 11
    private fun gdJs(ok: Boolean, msg: String) = runOnUiThread { try { if (::web.isInitialized) web.evaluateJavascript("window.lsGdriveDone&&lsGdriveDone($ok," + JSONObject.quote(msg) + ")", null) } catch (_: Throwable) {} }
    private fun gdSave(token: String) {
        try { val c = Gdrive.connect(token); gdJs(true, c.optString("email")) } catch (e: Throwable) { gdJs(false, errText(e)) }
    }
    @Volatile private var scStaged: com.lanshare.app.core.settings.SettingsSchema.Parsed? = null
    private fun scJs(ok: Boolean, msg: String) = runOnUiThread { try { if (::web.isInitialized) web.evaluateJavascript("window.lsSettingsCloudDone&&lsSettingsCloudDone($ok," + JSONObject.quote(msg) + ")", null) } catch (_: Throwable) {} }
    private val reqGdPick = 12
    private val reqSetExp = 13
    private val reqSetImp = 14
    @Volatile private var setExpText: String? = null
    /** "Add account" (no email yet): ALWAYS show Google's account chooser first. Without it Play Services silently reuses the account that already granted Drive access. */
    private fun gdStart(email: String) {
        if (email.isEmpty()) {
            runOnUiThread {
                try {
                    val opts = com.google.android.gms.common.AccountPicker.AccountChooserOptions.Builder()
                        .setAllowableAccountsTypes(listOf("com.google")).setAlwaysShowAccountPicker(true).build()
                    startActivityForResult(com.google.android.gms.common.AccountPicker.newChooseAccountIntent(opts), reqGdPick)
                } catch (e: Throwable) { gdAuth("") }   // no chooser available: fall back to the old behaviour
            }
            return
        }
        gdAuth(email)
    }
    private fun gdAuth(email: String) {
        Thread({
            try {
                val s = GAuth.begin(email.ifEmpty { null })
                if (s.token != null) gdSave(s.token)
                else runOnUiThread { try { startIntentSenderForResult(s.consent!!.intentSender, reqGdrive, null, 0, 0, 0) } catch (e: Throwable) { gdJs(false, errText(e)) } }
            } catch (e: Throwable) { gdJs(false, errText(e)) }
        }, "gd-signin").also { it.isDaemon = true }.start()
    }

    override fun onActivityResult(req: Int, res: Int, d: Intent?) {
        if (req == reqGdPick) {
            val picked = if (res == RESULT_OK) d?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME) else null
            if (picked.isNullOrEmpty()) gdJs(false, "Sign-in was cancelled") else gdAuth(picked)
            return
        }
        if (req == reqGdrive) {
            if (res != RESULT_OK) gdJs(false, "Sign-in was cancelled")
            else Thread({ try { gdSave(GAuth.finish(d, applicationContext)) } catch (e: Throwable) { gdJs(false, errText(e)) } }, "gd-signin").also { it.isDaemon = true }.start()
            return
        }
        if (req == reqSetExp) {
            val text = setExpText; setExpText = null
            val uri = if (res == RESULT_OK) d?.data else null
            if (uri == null || text == null) { scJs(false, "Export was cancelled"); return }
            Thread({
                try {
                    contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    scJs(true, "Settings exported")
                } catch (e: Throwable) { scJs(false, errText(e)) }
            }, "settings-export").also { it.isDaemon = true }.start()
            return
        }
        if (req == reqSetImp) {
            val uri = if (res == RESULT_OK) d?.data else null
            if (uri == null) { scJs(false, "Import was cancelled"); return }
            Thread({
                try {
                    val max = com.lanshare.app.core.settings.SettingsSchema.MAX_FILE_CHARS * 4
                    val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    val text = if (bytes.size > max) "" else String(bytes, Charsets.UTF_8)
                    when (val r = com.lanshare.app.core.settings.SettingsSchema.parse(text)) {
                        is com.lanshare.app.core.settings.SettingsSchema.Result.Ok -> {
                            scStaged = r.parsed
                            val t = com.lanshare.app.core.settings.SettingsIo.describe(r.parsed)
                            runOnUiThread { try { web.evaluateJavascript("window.lsSettingsCloudAsk&&lsSettingsCloudAsk(" + JSONObject.quote(t) + ")", null) } catch (_: Throwable) {} }
                        }
                        is com.lanshare.app.core.settings.SettingsSchema.Result.Refused -> scJs(false, r.reasons.joinToString(" "))
                    }
                } catch (e: Throwable) { scJs(false, errText(e)) }
            }, "settings-import").also { it.isDaemon = true }.start()
            return
        }
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
    private fun notifyProgress(nid: Int, title: String, text: String, done: Long, total: Long, st: String, alert: Boolean = false) {   // alert: final result with sound / heads-up (app in the background)
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26)
                nm.createNotificationChannel(NotificationChannel("lanshare_transfers", "Transfers", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            if (alert && Build.VERSION.SDK_INT >= 26)
                nm.createNotificationChannel(NotificationChannel("lanshare_results", "Job results", NotificationManager.IMPORTANCE_HIGH))
            val b = NotificationCompat.Builder(this, if (alert) "lanshare_results" else "lanshare_transfers")
                .setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(text)
                .setContentIntent(open).setOnlyAlertOnce(!alert)
                .setCategory(if (alert) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_PROGRESS)
            if (alert) b.setStyle(NotificationCompat.BigTextStyle().bigText(text)).setPriority(NotificationCompat.PRIORITY_HIGH)
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
                    // the final result alerts (sound / heads-up) when the app is in the background and the job was a print, a long one (>= 10 s) or failed
                    val isPrint = label.startsWith("Print")
                    val loud = ClipPush.front == null && (isPrint || j.optLong("elapsed") >= 10 || j.optString("state") == "error")
                    when (j.optString("state")) {
                        "error" -> { notifyProgress(nid, if (isPrint) "Print failed" else "$label failed", (if (j.isNull("error")) "" else j.optString("error")).ifBlank { label }, 0, 0, "err", loud); break }
                        "done" -> { notifyProgress(nid, if (isPrint) "Print finished" else label, if (isPrint) label else "Done", 0, 0, "done", loud); break }
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
                val c = URL(com.lanshare.app.core.RootShell.sign(url)).openConnection().apply { connectTimeout = 15_000; readTimeout = 60_000 }
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

    /** Fetch the given files into cache/open (FileProvider path) and open the system share sheet with all of them. */
    private fun shareFilesImpl(json: String) {
        val items = try { JSONObject(json).getJSONArray("items") } catch (e: Exception) { return }
        if (items.length() == 0) return
        val id = dlSeq.incrementAndGet()
        Thread {
            val out = ArrayList<File>()
            val label = if (items.length() == 1) items.getJSONObject(0).optString("name", "file") else "${items.length()} files"
            try {
                val dir = File(cacheDir, "open").apply { deleteRecursively(); mkdirs() }
                var total = 0L; for (k in 0 until items.length()) total += items.getJSONObject(k).optLong("size", 0)
                var done = 0L; var speed = 0.0; var lastT = System.nanoTime(); var lastD = 0L
                dlUi(id, label, 0, total, 0.0, "run")
                for (k in 0 until items.length()) {
                    val ob = items.getJSONObject(k)
                    var t = File(dir, ob.optString("name", "file$k").replace("/", "_"))
                    var n = 1
                    while (t.exists()) { t = File(dir, "${t.nameWithoutExtension} ($n)${if (t.extension.isEmpty()) "" else "." + t.extension}"); n++ }
                    out.add(t)
                    val c = URL(com.lanshare.app.core.RootShell.sign(ob.getString("url"))).openConnection().apply { connectTimeout = 15_000; readTimeout = 60_000 }
                    c.getInputStream().use { i -> t.outputStream().use { o ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            if (dlCancel.remove(id)) throw InterruptedException()
                            val r = i.read(buf); if (r < 0) break
                            o.write(buf, 0, r); done += r
                            val now = System.nanoTime()
                            if (now - lastT >= 500_000_000L) {
                                val inst = (done - lastD) * 1e9 / (now - lastT)
                                speed = if (speed == 0.0) inst else speed * 0.6 + inst * 0.4
                                lastT = now; lastD = done
                                dlUi(id, label, done, maxOf(total, done), speed, "run")
                            }
                        }
                    } }
                }
                dlUi(id, label, done, done, speed, "done", "Sharing\u2026")
                runOnUiThread { shareFileList(out) }
            } catch (e: InterruptedException) {
                out.forEach { it.delete() }; dlUi(id, label, 0, 0, 0.0, "cancel")
            } catch (e: Exception) {
                out.forEach { it.delete() }; dlUi(id, label, 0, 0, 0.0, "err", e.message ?: e.javaClass.simpleName)
            }
        }.start()
    }

    private fun shareFileList(files: List<File>) {
        try {
            val uris = ArrayList<Uri>(files.map { FileProvider.getUriForFile(this, "$packageName.fileprovider", it) })
            val mimes = files.map { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.extension.lowercase()) ?: "*/*" }.distinct()
            val mime = when {
                mimes.size == 1 -> mimes[0]
                mimes.all { it.startsWith("image/") } -> "image/*"
                mimes.all { it.startsWith("video/") } -> "video/*"
                else -> "*/*"
            }
            val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
                       else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            send.setType(mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val cd = android.content.ClipData.newRawUri("", uris[0]); for (k in 1 until uris.size) cd.addItem(android.content.ClipData.Item(uris[k]))
            send.clipData = cd
            startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) { Toast.makeText(this, "Cannot share: ${e.message}", Toast.LENGTH_LONG).show() }
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
        "<html><body style='background:#E65100;color:#fff;font-family:sans-serif;text-align:center;padding:30% 8% 0'>" +
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
                mark("coreReady")   // PERF-DEBUG
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
