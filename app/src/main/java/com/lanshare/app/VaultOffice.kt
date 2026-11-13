package com.lanshare.app

import android.app.Activity
import android.os.Bundle
import android.util.Base64
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.lanshare.app.core.office.DDoc
import com.lanshare.app.core.office.DImage
import com.lanshare.app.core.office.DocxHtml
import com.lanshare.app.core.office.OfficeOpen
import com.lanshare.app.core.office.XlsxHtml
import com.lanshare.app.core.vault.VaultSession
import java.io.File
import java.lang.ref.WeakReference
import java.util.UUID

/**
 * Lockdown vault mode part 4: READ-ONLY viewer for Word / Excel / ODF / PowerPoint items of the vault (extra "id" = vault node id, never a path).
 * The office readers need a real file (ZipFile / OLE), so the item is decrypted to ONE short-lived file in `cacheDir/vt/ofc-<random>/d.<ext>`
 * (generic name, app-private): deleted in onDestroy, on lock (VaultAppHooks wipes cacheDir/vt) and on process start. Nothing else is written:
 * no undo copies, no thumbnails, no recent-files entry, no log entry, no PC "exact pages" conversion, no export, no share, no network
 * (WebView JS off, http(s) blocked), FLAG_SECURE, closes when the vault locks. Not exported.
 */
class VaultOfficeActivity : Activity() {
    private lateinit var web: WebView
    private var dir: File? = null
    private var od: OfficeOpen? = null
    private var tabs: List<Pair<Int, String>> = emptyList()
    private var lock: (() -> Unit)? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        VaultMode.secure(this)
        val st = VaultSession.store?.takeIf { it.isOpen }
        val node = intent.getStringExtra("id")?.let { st?.get(it) }
        if (st == null || node == null || node.dir || node.size > MAX_BYTES) { finish(); return }
        lock = VaultMode.bindLock(this)
        web = WebView(this)
        web.setBackgroundColor(android.graphics.Color.WHITE)
        web.settings.apply { javaScriptEnabled = false; setSupportZoom(true); builtInZoomControls = true; displayZoomControls = false
            allowFileAccess = false; allowContentAccess = false; useWideViewPort = true; loadWithOverviewMode = true; blockNetworkLoads = true }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url
                if (u.scheme == "lsoffice" && u.host == "sheet") u.lastPathSegment?.toIntOrNull()?.let { showSheet(it) }
                return u.scheme != "about" && u.scheme != "data"
            }
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): android.webkit.WebResourceResponse? =
                if (r.url.scheme == "http" || r.url.scheme == "https")
                    android.webkit.WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0))) else null
        }
        setContentView(web)
        html("<body style=\"font:16px sans-serif;padding:24px;color:#555\">…</body>")
        val ext = node.name.substringAfterLast('.', "").lowercase().takeIf { it in EXT } ?: "bin"
        Thread {
            val page = try {
                val d = File(File(cacheDir, "vt"), "ofc-" + UUID.randomUUID().toString().replace("-", "")).apply { mkdirs() }; dir = d
                val f = File(d, "d.$ext")
                st.open(node.id).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                val o = OfficeOpen(f); od = o
                if (!o.isSheet) { val doc = o.readDoc(); DocxHtml.render(doc, title = "", fontBase = null, image = imgSrc(o)) }
                else {
                    tabs = o.sheets.filter { !it.hidden }.map { it.index to it.name }
                    val first = tabs.firstOrNull()?.first
                    if (first == null) msg("No visible sheets.") else XlsxHtml.render(o.readSheet(first), tabs, first, o.styles, null, null)
                }
            } catch (e: Throwable) {
                msg(if (e.message.orEmpty().contains("password", true)) vt("Password-protected files cannot be opened") else vt("Could not open this file"))
            }
            // the decrypted copy is no longer needed for a document (fully parsed); sheets read lazily, so a sheet file stays until the screen closes
            runOnUiThread { if (!isDestroyed) html(page) }
        }.also { it.isDaemon = true }.start()
    }

    private fun msg(t: String) = "<body style=\"font:15px sans-serif;padding:16px\">" + DocxHtml.esc(t) + "</body>"
    private fun html(h: String) = web.loadDataWithBaseURL("about:blank", h, "text/html", "UTF-8", null)

    private fun showSheet(i: Int) {
        val r = od ?: return
        Thread {
            val h = try { XlsxHtml.render(r.readSheet(i), tabs, i, r.styles, null, null) } catch (_: Throwable) { msg(vt("Could not open this file")) }
            runOnUiThread { if (!isDestroyed) html(h) }
        }.also { it.isDaemon = true }.start()
    }

    private fun imgSrc(o: OfficeOpen): (DImage) -> String? = { im ->
        val bytes = try { o.image(im.part) } catch (_: Throwable) { null }
        if (bytes == null || bytes.size > 3_000_000) null else {
            val mime = when (im.part.substringAfterLast('.').lowercase()) { "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"; else -> null }
            if (mime == null) null else "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
    }

    override fun onResume() { super.onResume(); if (!VaultSession.unlocked) finish() }
    override fun onDestroy() {
        lock?.let { VaultSession.removeOnLock(it) }; lock = null
        try { od?.close() } catch (_: Throwable) {}; od = null
        try { dir?.deleteRecursively() } catch (_: Throwable) {}
        try { if (::web.isInitialized) { web.loadUrl("about:blank"); web.destroy() } } catch (_: Throwable) {}
        super.onDestroy()
    }

    companion object {
        const val MAX_BYTES = 60L shl 20
        val EXT = setOf("docx", "docm", "dotx", "xlsx", "xlsm", "xltx", "odt", "ods", "doc", "xls", "pptx", "odp")
    }
}
