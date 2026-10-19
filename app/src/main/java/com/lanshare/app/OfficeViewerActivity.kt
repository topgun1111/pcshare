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

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        web = WebView(this)
        setContentView(web)
        web.settings.apply { javaScriptEnabled = false; setSupportZoom(true); builtInZoomControls = true; displayZoomControls = false
            allowFileAccess = false; allowContentAccess = false; useWideViewPort = true; loadWithOverviewMode = true }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url
                if (u.scheme == "lsoffice") { u.lastPathSegment?.toIntOrNull()?.let { showSheet(it) }; return true }
                return u.scheme != "about" && u.scheme != "data"   // block everything else (in-page #anchors are not navigations)
            }
        }
        val f = intent.getStringExtra("path")?.let { File(it) } ?: run {
            val u = intent.data ?: (intent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)) ?: return finish()
            val nm = (u.lastPathSegment ?: "file").substringAfterLast('/').replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val t = File(File(cacheDir, "office").apply { mkdirs() }, nm)
            try { contentResolver.openInputStream(u)?.use { i -> t.outputStream().use { i.copyTo(it) } } ?: return finish() } catch (e: Throwable) { return finish() }
            t
        }
        file = f; title = f.name
        dev = intent.getStringExtra("dev")?.takeIf { it.isNotEmpty() }; rpath = intent.getStringExtra("rpath")?.takeIf { it.isNotEmpty() }
        load(f)
    }

    private fun load(f: File) {
        Thread {
            val html = try { build(f) } catch (e: Throwable) {
                val enc = e.message.orEmpty().contains("password", true) || e.message.orEmpty().contains("zip", true) || e.message.orEmpty().contains("end header", true)
                "<body style=\"font:15px sans-serif;padding:16px\">" + (if (enc) "Cannot open: file is password protected or not a valid Office file." else "Cannot open: " + e.message) + "</body>"
            }
            runOnUiThread { if (!isDestroyed) { invalidateOptionsMenu(); web.loadDataWithBaseURL("about:blank", html, "text/html", "UTF-8", null) } }
        }.start()
    }

    private fun build(f: File): String {
        val o = OfficeOpen(f); od = o        // format detected by content (docx/xlsx/doc/xls/odt/ods)
        if (!o.isSheet) {
            val doc = o.readDoc()
            return DocxHtml.render(doc, title = f.name, image = { im ->
                val bytes = o.image(im.part)
                if (bytes == null || bytes.size > 3_000_000) null else {
                    val mime = when (im.part.substringAfterLast('.').lowercase()) { "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"; "svg" -> "image/svg+xml"; else -> null }
                    if (mime == null) null else "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                }
            })
        }
        tabs = o.sheets.filter { !it.hidden }.map { it.index to it.name }
        val first = (tabs.firstOrNull { it.first == curSheet } ?: tabs.firstOrNull())?.first ?: return "<body style=\"font:15px sans-serif;padding:16px\">No visible sheets.</body>"
        curSheet = first
        return XlsxHtml.render(o.readSheet(first), tabs, first)
    }

    private fun showSheet(i: Int) {
        val r = od ?: return
        curSheet = i
        Thread {
            val html = try { XlsxHtml.render(r.readSheet(i), tabs, i) } catch (e: Throwable) { "<body>" + e.message + "</body>" }
            runOnUiThread { if (!isDestroyed) web.loadDataWithBaseURL("about:blank", html, "text/html", "UTF-8", null) }
        }.start()
    }

    override fun onCreateOptionsMenu(m: Menu): Boolean {
        val sv = SearchView(this)
        sv.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String): Boolean { web.findAllAsync(q); return true }
            override fun onQueryTextChange(q: String): Boolean { if (q.isEmpty()) web.clearMatches() else web.findAllAsync(q); return true }
        })
        m.add(0, 1, 0, "Find").setActionView(sv).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)
        m.add(0, 2, 1, "Next").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        m.add(0, 3, 2, "Print / save as PDF").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        if (od?.isSheet == true) m.add(0, 4, 3, "Export sheet as CSV").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onPrepareOptionsMenu(m: Menu): Boolean {   // the viewer builds off-thread: show the CSV item once a sheet is loaded
        if (m.findItem(4) == null && od?.isSheet == true) m.add(0, 4, 3, "Export sheet as CSV").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        if (m.findItem(5) == null && od?.editable == true && dev != null && rpath != null) {   // docx / xlsx opened from the file list
            if (od?.isSheet == true) { m.add(0, 5, 4, "Edit cell...").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER); m.add(0, 6, 5, "Insert row...").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER) }
            else { m.add(0, 5, 4, "Find and replace...").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER); m.add(0, 6, 5, "Add paragraph...").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER) }
        }
        return super.onPrepareOptionsMenu(m)
    }

    override fun onOptionsItemSelected(i: MenuItem): Boolean = when (i.itemId) {
        2 -> { web.findNext(true); true }
        3 -> { printPage(); true }
        4 -> { exportCsv(); true }
        5 -> { if (od?.isSheet == true) askCell() else askReplace(); true }
        6 -> { if (od?.isSheet == true) askRow() else askParagraph(); true }
        else -> super.onOptionsItemSelected(i)
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
        android.app.AlertDialog.Builder(this).setTitle(title).setView(box).setNegativeButton("Cancel", null)
            .setPositiveButton("OK") { _, _ -> ok(ets.map { it.text.toString() }) }.show()
    }

    /** Runs [job] off the UI thread on the cache copy, uploads the result to the source, then re-reads the file into the viewer. */
    private fun mutate(job: (File) -> String) {
        val f = file ?: return
        Thread {
            var msg: String
            try {
                od?.close(); od = null
                msg = job(f)
                val d = dev; val rp = rpath
                if (d != null && rp != null) f.inputStream().use { com.lanshare.app.core.Jobs.ep(d).write(rp, it, f.length()) }
            } catch (e: Throwable) { msg = "Edit failed: " + (e.message ?: "error") }
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

    override fun onDestroy() { try { od?.close() } catch (_: Throwable) {}; web.destroy(); super.onDestroy() }
}
