package com.lanshare.app

import android.app.Activity
import android.os.Bundle
import android.util.Base64
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.SearchView
import android.widget.Toast
import com.lanshare.app.core.office.*
import java.io.File

/** Read-only viewer for .docx/.xlsx/.doc/.xls/.odt/.ods (Parts 3b + 6c; format by content). Intent extra "path" = LOCAL file (callers copy remote files to cache first). */
class OfficeViewerActivity : Activity() {
    private lateinit var web: WebView
    private var file: File? = null
    private var od: OfficeOpen? = null
    private var tabs: List<Pair<Int, String>> = emptyList()
    private var curSheet = -1
    private var dev: String? = null      // source device + path (set when opened from the file list): edits are uploaded back there
    private var rpath: String? = null
    private var pendingCsv: String? = null
    private var curDoc: DDoc? = null          // parsed Word-type document, kept so reading-mode changes re-render without re-parsing
    private val rprefs by lazy { getSharedPreferences("office_read", MODE_PRIVATE) }   // reading mode: on / px (text size) / theme
    private var readRow: android.widget.LinearLayout? = null
    private var readLbl: android.widget.TextView? = null
    private var pendingFrac = -1f             // scroll position (0..1) to restore after a reading-mode re-render
    private val undoStack = ArrayList<File>()   // Part 10a: copies of the cache file taken before each edit (any format); newest last, max 10

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        web = WebView(this)
        buildBar()
        web.settings.apply { javaScriptEnabled = false; setSupportZoom(true); builtInZoomControls = true; displayZoomControls = false
            allowFileAccess = false; allowContentAccess = false; useWideViewPort = true; loadWithOverviewMode = true }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url
                if (u.scheme == "lsoffice") { u.lastPathSegment?.toIntOrNull()?.let { showSheet(it) }; return true }
                return u.scheme != "about" && u.scheme != "data"   // block everything else (in-page #anchors are not navigations)
            }
            override fun onPageFinished(v: WebView, url: String?) {
                val fr = pendingFrac; if (fr < 0f) return
                pendingFrac = -1f
                v.postDelayed({ val h = v.contentHeight * resources.displayMetrics.density - v.height; if (h > 0) v.scrollTo(0, (fr * h).toInt()) }, 150)
            }
        }
        val f = intent.getStringExtra("path")?.let { File(it) } ?: run {
            val u = intent.data ?: (intent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)) ?: return finish()
            val nm = (u.lastPathSegment ?: "file").substringAfterLast('/').replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val t = File(File(cacheDir, "office").apply { mkdirs() }, nm)
            try { contentResolver.openInputStream(u)?.use { i -> t.outputStream().use { i.copyTo(it) } } ?: return finish() } catch (e: Throwable) { return finish() }
            t
        }
        file = f; title = f.name; bar?.findViewById<android.widget.TextView>(android.R.id.title)?.text = f.name
        dev = intent.getStringExtra("dev")?.takeIf { it.isNotEmpty() }; rpath = intent.getStringExtra("rpath")?.takeIf { it.isNotEmpty() }
        val rd = dev; val rp = rpath
        if (rd != null && rp != null) OfficeShortcutActivity.Recent.add(applicationContext, rd, rp, rp.substringAfterLast('/'))   // launcher "recent office files"
        val mode = if (intent.getBooleanExtra("simple", false)) "simple" else com.lanshare.app.core.OfficeExact.mode(this)
        if (mode != "simple" && com.lanshare.app.core.OfficeExact.eligible(f.name)) exactOrSimple(f, true) else load(f)
    }

    /**
     * Exact pages: the PC converts the file to PDF (cached by content) and the PDF viewer shows it; this screen then closes.
     * Any failure (no PC, old pcprint.py, no Office / LibreOffice there, timeout) falls back to the simple HTML view with ONE toast that says why.
     * [auto] = opened by the setting (a missing PC is the normal case then, so the toast is short); false = the user chose the menu item.
     */
    private fun exactOrSimple(f: File, auto: Boolean) {
        val oe = com.lanshare.app.core.OfficeExact
        fun status(t: String) = runOnUiThread { if (!isDestroyed) web.loadDataWithBaseURL("about:blank", "<body style=\"font:16px sans-serif;padding:24px;color:#555\">" + DocxHtml.esc(t) + "<br><small>Back = cancel</small></body>", "text/html", "UTF-8", null) }
        status("Preparing exact pages\u2026")
        Thread {
            var pdf: File? = null; var why: String? = null; var md5 = ""
            try {
                pdf = oe.cached(applicationContext, f) ?: oe.convert(applicationContext, f, auto) { status(it) }
                md5 = oe.md5(f)
            } catch (e: Throwable) { why = e.message ?: e.javaClass.simpleName }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                val p = pdf
                if (p != null) {
                    PdfViewerActivity.pending = org.json.JSONObject().put("name", f.nameWithoutExtension + ".pdf").put("file", p.absolutePath).put("size", p.length())
                        .put("key", "office|" + md5)
                        .put("office", org.json.JSONObject().put("path", f.absolutePath).put("dev", dev ?: "").put("rpath", rpath ?: "")).toString()
                    startActivity(android.content.Intent(this, PdfViewerActivity::class.java))
                    finish()
                } else {
                    Toast.makeText(this, (if (auto) "Simple view - " else "Exact pages not available - ") + (why ?: "unknown error"), Toast.LENGTH_LONG).show()
                    load(f)
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    // The theme has no ActionBar, so the options menu was never shown: own top bar (title, find, overflow) above the WebView.
    private var bar: android.widget.LinearLayout? = null
    private var palSig: String? = null
    private var findRow: android.widget.LinearLayout? = null

    private fun buildBar() {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val pal = AppPal.pal(this); palSig = AppPal.json(this)
        val light = androidx.core.graphics.ColorUtils.calculateLuminance(pal.bg) > 0.5
        window.statusBarColor = pal.bg; window.navigationBarColor = pal.bg
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply { isAppearanceLightStatusBars = light; isAppearanceLightNavigationBars = light }
        val root = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setBackgroundColor(pal.bg) }
        fun btn(txt: String, desc: String, click: (android.view.View) -> Unit) = android.widget.TextView(this).apply {
            text = txt; textSize = 22f; gravity = android.view.Gravity.CENTER; setTextColor(pal.fg); contentDescription = desc
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(48), dp(48)); setOnClickListener(click)
            val tv = android.util.TypedValue(); if (theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)) setBackgroundResource(tv.resourceId)
        }
        val top = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(4), 0); elevation = 4 * d; setBackgroundColor(pal.bg) }
        top.addView(btn("\u2190", "Back") { finish() })
        top.addView(android.widget.TextView(this).apply {
            id = android.R.id.title; text = title; textSize = 16f; setTextColor(pal.fg); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        top.addView(btn("\uD83D\uDD0D", "Find") { toggleFind() })
        top.addView(btn("\u22EE", "More") { v -> showMore(v) })
        bar = top
        val fr = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(4), 0); visibility = android.view.View.GONE; setBackgroundColor(pal.cont) }
        val et = android.widget.EditText(this).apply {
            hint = "Find in document"; setTextColor(pal.fg); setHintTextColor(pal.mut); setSingleLine(); imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            layoutParams = android.widget.LinearLayout.LayoutParams(0, dp(44), 1f)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(e: android.text.Editable?) { val q = e?.toString().orEmpty(); if (q.isEmpty()) web.clearMatches() else web.findAllAsync(q) }
                override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d2: Int) {}
                override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d2: Int) {}
            })
            setOnEditorActionListener { _, _, _ -> web.findNext(true); true }
        }
        fr.addView(et)
        fr.addView(btn("\u2191", "Previous") { web.findNext(false) })
        fr.addView(btn("\u2193", "Next") { web.findNext(true) })
        findRow = fr
        val rr = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER; visibility = android.view.View.GONE; setBackgroundColor(pal.cont) }
        rr.addView(btn("A\u2212", "Smaller text") { readStep(-DocxReading.STEP) })
        val lbl = android.widget.TextView(this).apply { textSize = 14f; setTextColor(pal.fg); gravity = android.view.Gravity.CENTER; layoutParams = android.widget.LinearLayout.LayoutParams(dp(56), dp(48)) }
        rr.addView(lbl); readLbl = lbl
        rr.addView(btn("A+", "Larger text") { readStep(DocxReading.STEP) })
        rr.addView(btn("\u25D0", "Reading colours: paper, sepia, dark") { readTheme() })
        readRow = rr
        root.addView(top, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        root.addView(fr, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        root.addView(rr, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        root.addView(web, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        com.lanshare.app.ops.JobOverlay.install(this)   // progress bubble for background jobs while this screen is open
    }

    override fun onResume() {
        super.onResume()
        if (AppPal.json(this) != palSig) recreate()
    }

    private fun toggleFind() {
        val r = findRow ?: return
        if (r.visibility == android.view.View.VISIBLE) { r.visibility = android.view.View.GONE; web.clearMatches(); (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(r.windowToken, 0) }
        else { r.visibility = android.view.View.VISIBLE; (r.getChildAt(0) as android.widget.EditText).requestFocus(); (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(r.getChildAt(0), 0) }
    }

    private fun showMore(anchor: android.view.View) {
        val pm = android.widget.PopupMenu(this, anchor)
        val m = pm.menu
        if (file?.let { com.lanshare.app.core.OfficeExact.eligible(it.name) } == true) m.add(0, 10, 0, "Show exact pages (PC)")
        m.add(0, 8, 0, "Print on PC")
        m.add(0, 3, 0, "Print / save as PDF (Android)")
        if (od?.isSheet == true) m.add(0, 4, 1, "Export sheet as CSV")
        else if (od != null) m.add(0, 9, 1, if (readingOn) "Reading mode: turn off" else "Reading mode")
        if (od?.editable == true && dev != null && rpath != null) {
            if (od?.isSheet == true) { m.add(0, 5, 2, "Edit cell..."); m.add(0, 6, 3, "Insert row...") }
            else { m.add(0, 5, 2, "Find and replace..."); m.add(0, 6, 3, "Add paragraph...") }
        }
        if (undoStack.isNotEmpty()) m.add(0, 7, 4, "Undo last edit")
        pm.setOnMenuItemClickListener { onOptionsItemSelected(it) }
        pm.show()
    }

    private fun load(f: File) {
        Thread {
            val html = try { build(f) } catch (e: Throwable) {
                val enc = e.message.orEmpty().contains("password", true) || e.message.orEmpty().contains("zip", true) || e.message.orEmpty().contains("end header", true)
                "<body style=\"font:15px sans-serif;padding:16px\">" + (if (enc) "Cannot open: file is password protected or not a valid Office file." else "Cannot open: " + e.message) + "</body>"
            }
            runOnUiThread { if (!isDestroyed) { updateReadRow(); web.loadDataWithBaseURL("about:blank", html, "text/html", "UTF-8", null) } }
        }.start()
    }

    // ---- Reading mode (Word-type documents): text reflows to the screen width, own text size and colours; settings are kept for every document ----
    private val readingOn get() = rprefs.getBoolean("on", false)
    private fun autoTheme() = if (androidx.core.graphics.ColorUtils.calculateLuminance(AppPal.pal(this).bg) > 0.5) DocxReading.PAPER else DocxReading.DARK
    private fun readingCfg(): DocxReading? =
        if (!readingOn || od?.isSheet == true) null
        else DocxReading(DocxReading.clampPx(rprefs.getInt("px", 18)), rprefs.getInt("theme", autoTheme()).coerceIn(0, 2))

    private fun updateReadRow() {
        val c = readingCfg()
        readRow?.visibility = if (c != null) android.view.View.VISIBLE else android.view.View.GONE
        readLbl?.text = c?.fontPx?.toString() ?: ""
        web.setBackgroundColor(if (c != null) android.graphics.Color.parseColor(c.bg) else android.graphics.Color.WHITE)
    }

    private fun readStep(d: Int) {
        val cur = DocxReading.clampPx(rprefs.getInt("px", 18))
        rprefs.edit().putInt("px", DocxReading.clampPx(cur + d)).apply(); rerender()
    }

    private fun readTheme() {
        val cur = rprefs.getInt("theme", autoTheme()).coerceIn(0, 2)
        rprefs.edit().putInt("theme", (cur + 1) % 3).apply(); rerender()
    }

    /** Re-renders the already parsed document with the current reading settings and keeps the reading position. */
    private fun rerender() {
        val o = od; val doc = curDoc
        if (o == null || doc == null || o.isSheet) return
        val h = web.contentHeight * resources.displayMetrics.density - web.height
        pendingFrac = if (h > 0) (web.scrollY / h).coerceIn(0f, 1f) else 0f
        updateReadRow()
        Thread {
            val html = try { renderDoc(o, doc) } catch (e: Throwable) { "<body style=\"font:15px sans-serif;padding:16px\">" + e.message + "</body>" }
            runOnUiThread { if (!isDestroyed) web.loadDataWithBaseURL("about:blank", html, "text/html", "UTF-8", null) }
        }.start()
    }

    private fun renderDoc(o: OfficeOpen, doc: DDoc): String = DocxHtml.render(doc, title = file?.name ?: "", rd = readingCfg(), image = { im ->
        val bytes = try { o.image(im.part) } catch (_: Throwable) { null }
        if (bytes == null || bytes.size > 3_000_000) null else {
            val mime = when (im.part.substringAfterLast('.').lowercase()) { "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"; "svg" -> "image/svg+xml"; else -> null }
            if (mime == null) null else "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
    })

    private fun build(f: File): String {
        val o = OfficeOpen(f); od = o        // format detected by content (docx/xlsx/doc/xls/odt/ods)
        if (!o.isSheet) {
            val doc = o.readDoc(); curDoc = doc
            return renderDoc(o, doc)
        }
        curDoc = null
        tabs = o.sheets.filter { !it.hidden }.map { it.index to it.name }
        val first = (tabs.firstOrNull { it.first == curSheet } ?: tabs.firstOrNull())?.first ?: return "<body style=\"font:15px sans-serif;padding:16px\">No visible sheets.</body>"
        curSheet = first
        return XlsxHtml.render(o.readSheet(first), tabs, first, o.styles)
    }

    private fun showSheet(i: Int) {
        val r = od ?: return
        curSheet = i
        Thread {
            val html = try { XlsxHtml.render(r.readSheet(i), tabs, i, r.styles) } catch (e: Throwable) { "<body>" + e.message + "</body>" }
            runOnUiThread { if (!isDestroyed) web.loadDataWithBaseURL("about:blank", html, "text/html", "UTF-8", null) }
        }.start()
    }

    override fun onOptionsItemSelected(i: MenuItem): Boolean = when (i.itemId) {
        3 -> { printPage(); true }
        8 -> { printOnPc(); true }
        10 -> { file?.let { exactOrSimple(it, false) }; true }
        4 -> { exportCsv(); true }
        5 -> { if (od?.isSheet == true) askCell() else askReplace(); true }
        6 -> { if (od?.isSheet == true) askRow() else askParagraph(); true }
        7 -> { undoEdit(); true }
        9 -> { rprefs.edit().putBoolean("on", !readingOn).apply(); rerender(); true }
        else -> super.onOptionsItemSelected(i)
    }

    /** Sends the document itself (pcprint.py prints docx/xlsx/doc/xls/odt/ods natively) to LANShare's PC print dialog: copy into "LANShare Shared", then the same hand-off PcPrintService uses. */
    private fun printOnPc() {
        val f = file ?: return
        Thread {
            try {
                val dir = java.io.File(android.os.Environment.getExternalStorageDirectory(), "LANShare Shared")
                if (!dir.isDirectory && !dir.mkdirs()) throw java.io.IOException("No storage access - allow \"All files access\" for LANShare")
                val ext = f.name.substringAfterLast('.', "")
                val base = f.name.removeSuffix(if (ext.isEmpty()) "" else ".$ext")
                var t = java.io.File(dir, f.name); var k = 1
                while (t.exists()) { t = java.io.File(dir, "$base ($k)" + (if (ext.isEmpty()) "" else ".$ext")); k++ }
                f.copyTo(t)
                runOnUiThread {
                    startActivity(android.content.Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_PRINT_SHARED)
                        .putExtra("names", arrayOf(t.name)).putExtra("nid", 0)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP))
                }
            } catch (e: Throwable) { runOnUiThread { Toast.makeText(this, "Print on PC failed: " + e.message, Toast.LENGTH_LONG).show() } }
        }.start()
    }

    /** System print dialog (also offers "Save as PDF" and the app's own print service). */
    private fun printPage() {
        try {
            val nm = file?.name ?: "document"
            (getSystemService(PRINT_SERVICE) as android.print.PrintManager).print(nm, web.createPrintDocumentAdapter(nm), android.print.PrintAttributes.Builder().build())
        } catch (e: Throwable) { Toast.makeText(this, "Print failed: " + e.message, Toast.LENGTH_LONG).show() }
    }

    // ---- Part 7: editing (docx via DocxEditor, xlsx via XlsxEditor on the cache copy; every edit is uploaded back to the source right away) ----
    private fun dlg(title: String, hints: List<String>, init: List<String> = emptyList(), ok: (List<String>) -> Unit) {
        val box = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(48, 16, 48, 0) }
        val ets = hints.mapIndexed { n, h -> android.widget.EditText(this).apply { hint = h; setText(init.getOrElse(n) { "" }); box.addView(this) } }
        AppPal.dlg(this).setTitle(title).setView(box).setNegativeButton("Cancel", null)
            .setPositiveButton("OK") { _, _ -> ok(ets.map { it.text.toString() }) }.show()
    }

    /** Runs [job] off the UI thread on the cache copy, uploads the result to the source, then re-reads the file into the viewer. */
    private fun mutate(job: (File) -> String) {
        val f = file ?: return
        Thread {
            var msg: String
            try {
                od?.close(); od = null
                val snap = File(File(cacheDir, "office-undo").apply { mkdirs() }, f.name + "." + System.nanoTime() + ".u~")
                f.copyTo(snap, overwrite = true)
                msg = try { job(f) } catch (e: Throwable) { snap.delete(); throw e }
                if (f.length() == snap.length() && f.readBytes().contentEquals(snap.readBytes())) snap.delete()   // nothing changed (e.g. "Nothing found"): no undo step
                else { undoStack.add(snap); while (undoStack.size > 10) undoStack.removeAt(0).delete() }
                val d = dev; val rp = rpath
                if (d != null && rp != null) f.inputStream().use { com.lanshare.app.core.Jobs.ep(d).write(rp, it, f.length()) }
            } catch (e: Throwable) { msg = "Edit failed: " + (e.message ?: "error") }
            runOnUiThread { if (!isDestroyed) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); load(f) } }
        }.start()
    }

    /** Puts the cache copy back to the state before the last edit, uploads it to the source and reloads. */
    private fun undoEdit() {
        val f = file ?: return
        val snap = undoStack.removeLastOrNull() ?: return
        Thread {
            var msg: String
            try {
                od?.close(); od = null
                snap.copyTo(f, overwrite = true); snap.delete()
                val d = dev; val rp = rpath
                if (d != null && rp != null) f.inputStream().use { com.lanshare.app.core.Jobs.ep(d).write(rp, it, f.length()) }
                msg = "Edit undone"
            } catch (e: Throwable) { msg = "Undo failed: " + (e.message ?: "error") }
            runOnUiThread { if (!isDestroyed) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); load(f) } }
        }.start()
    }

    private fun askCell() = dlg("Edit cell", listOf("Cell (e.g. B3)", "Value or =formula")) { v ->
        val rc = XRef.parse(v[0].trim()); if (rc == null) { Toast.makeText(this, "Bad cell reference", Toast.LENGTH_SHORT).show(); return@dlg }
        val sh = curSheet
        mutate { f -> XlsxEditor.setCell(f, sh, rc[1], rc[0], v[1]); "Saved " + v[0].trim().uppercase() }
    }

    private fun askRow() = dlg("Insert row", listOf("Insert before row number")) { v ->
        val at = (v[0].trim().toIntOrNull() ?: 0) - 1; if (at < 0) { Toast.makeText(this, "Bad row number", Toast.LENGTH_SHORT).show(); return@dlg }
        val sh = curSheet
        mutate { f -> XlsxEditor.insertRows(f, sh, at, 1); "Row inserted" }
    }

    private fun askReplace() = dlg("Find and replace", listOf("Find", "Replace with")) { v ->
        if (v[0].isEmpty()) return@dlg
        mutate { f -> val n = DocxEditor.replaceAll(f, v[0], v[1], matchCase = false); if (n == 0) "Nothing found" else "Replaced $n" }
    }

    private fun askParagraph() = dlg("Add paragraph at end", listOf("Text")) { v ->
        if (v[0].isEmpty()) return@dlg
        mutate { f -> DocxEditor.insertParagraph(f, DocxEditor.paragraphCount(f), v[0]); "Paragraph added" }
    }

    private fun exportCsv() {
        val r = od ?: return; val idx = curSheet; if (idx < 0) return
        Thread {
            try {
                val sh = r.readSheet(idx, 100_000, 1000, 2_000_000)
                pendingCsv = OfficeExport.sheetToCsv(sh)
                val nm = OfficeExport.csvName(file?.name ?: "sheet", sh.info.name)
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    startActivityForResult(android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("text/csv").putExtra(android.content.Intent.EXTRA_TITLE, nm), 77)
                }
            } catch (e: Throwable) { runOnUiThread { Toast.makeText(this, "Export failed: " + e.message, Toast.LENGTH_LONG).show() } }
        }.start()
    }

    override fun onActivityResult(req: Int, res: Int, data: android.content.Intent?) {
        super.onActivityResult(req, res, data)
        if (req != 77) return
        val csv = pendingCsv; pendingCsv = null
        val u = data?.data
        if (res != RESULT_OK || u == null || csv == null) return
        try { contentResolver.openOutputStream(u)?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }; Toast.makeText(this, "CSV saved", Toast.LENGTH_SHORT).show() }
        catch (e: Throwable) { Toast.makeText(this, "Save failed: " + e.message, Toast.LENGTH_LONG).show() }
    }

    override fun onDestroy() {
        undoStack.forEach { it.delete() }; undoStack.clear()
        file?.let { try { DocxEditor.clearUndo(it) } catch (_: Throwable) {} }   // the editor's own .undoN~ copies next to the cache file
        try { od?.close() } catch (_: Throwable) {}
        web.destroy(); super.onDestroy()
    }
}
