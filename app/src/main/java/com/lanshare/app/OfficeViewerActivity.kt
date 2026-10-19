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
import java.util.zip.ZipFile

/** Read-only viewer for .docx / .xlsx (Part 3b). Intent extra "path" = LOCAL file (callers copy remote files to cache first). */
class OfficeViewerActivity : Activity() {
    private lateinit var web: WebView
    private var file: File? = null
    private var xlsx: XlsxReader? = null
    private var tabs: List<Pair<Int, String>> = emptyList()

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
        val f = File(intent.getStringExtra("path") ?: return finish())
        file = f; title = f.name
        Thread {
            val html = try { build(f) } catch (e: Throwable) {
                val enc = e.message.orEmpty().contains("zip", true) || e.message.orEmpty().contains("end header", true)
                "<body style=\"font:15px sans-serif;padding:16px\">" + (if (enc) "Cannot open: file is password protected or not a valid Office file." else "Cannot open: " + e.message) + "</body>"
            }
            runOnUiThread { if (!isDestroyed) web.loadDataWithBaseURL("about:blank", html, "text/html", "UTF-8", null) }
        }.start()
    }

    private fun build(f: File): String {
        val ext = f.extension.lowercase()
        if (ext == "docx" || ext == "dotx" || ext == "docm") {
            val doc = DocxReader(f).use { it.read() }
            ZipFile(f).use { zip ->
                return DocxHtml.render(doc, title = f.name, image = { im ->
                    val e = zip.getEntry(im.part) ?: zip.entries().asSequence().firstOrNull { it.name.equals(im.part, true) }
                    if (e == null || e.size > 3_000_000) null else {
                        val mime = when (im.part.substringAfterLast('.').lowercase()) { "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"; "svg" -> "image/svg+xml"; else -> null }
                        if (mime == null) null else "data:$mime;base64," + Base64.encodeToString(zip.getInputStream(e).use { it.readBytes() }, Base64.NO_WRAP)
                    }
                })
            }
        }
        val r = XlsxReader(f); xlsx = r
        tabs = r.sheets.filter { !it.hidden }.map { it.index to it.name }
        val first = tabs.firstOrNull()?.first ?: return "<body style=\"font:15px sans-serif;padding:16px\">No visible sheets.</body>"
        return XlsxHtml.render(r.readSheet(first), tabs, first)
    }

    private fun showSheet(i: Int) {
        val r = xlsx ?: return
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
        return true
    }

    override fun onOptionsItemSelected(i: MenuItem): Boolean = if (i.itemId == 2) { web.findNext(true); true } else super.onOptionsItemSelected(i)

    override fun onDestroy() { try { xlsx?.close() } catch (_: Throwable) {}; web.destroy(); super.onDestroy() }
}
