package com.lanshare.app

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Phone-only office reader (tier 2). Fetches the HTML from the app's own `/api/officehtml` (OfficeText, no PC needed)
 * and shows it in a WebView with JS off and pinch zoom. [pending] = {name, url} set by `LSAndroid.viewOffice`.
 * Not compiled / not device-tested.
 */
class OfficeViewerActivity : Activity() {
    companion object { @Volatile var pending: String? = null }

    private lateinit var web: WebView
    private lateinit var spin: ProgressBar
    private lateinit var msg: TextView
    @Volatile private var dead = false
    private lateinit var printBtn: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val j = try { JSONObject(pending ?: "{}") } catch (_: Exception) { JSONObject() }
        pending = null
        val url = j.optString("url")
        title = j.optString("name", "Document")
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        web = WebView(this).apply {
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportZoom(true); settings.builtInZoomControls = true; settings.displayZoomControls = false
            settings.useWideViewPort = true; settings.loadWithOverviewMode = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            setBackgroundColor(Color.TRANSPARENT)
            visibility = android.view.View.INVISIBLE
        }
        spin = ProgressBar(this)
        msg = TextView(this).apply { setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER; setPadding(48, 48, 48, 48); visibility = android.view.View.GONE }
        root.addView(web, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(spin, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(msg, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        printBtn = TextView(this).apply {
            text = "\u2399"; setTextColor(Color.WHITE); textSize = 22f; setPadding(36, 24, 36, 24)
            setBackgroundColor(0x99000000.toInt()); visibility = android.view.View.GONE
            setOnClickListener { printDoc() }
        }
        root.addView(printBtn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
        setContentView(root)
        if (url.isEmpty()) { fail("No file"); return }
        Thread {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 5000; c.readTimeout = 120000
                val html: String
                if (c.responseCode == 200) html = c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
                else {
                    val raw = try { c.errorStream?.use { String(it.readBytes(), Charsets.UTF_8) } } catch (_: Exception) { null }
                    val txt = try { JSONObject(raw ?: "").optString("error", "") } catch (_: Exception) { "" }
                    throw java.io.IOException(txt.ifEmpty { raw?.take(300) ?: ("HTTP " + c.responseCode) })
                }
                runOnUiThread {
                    if (dead) return@runOnUiThread
                    spin.visibility = android.view.View.GONE
                    web.visibility = android.view.View.VISIBLE
                    printBtn.visibility = android.view.View.VISIBLE
                    web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                }
            } catch (e: Throwable) { runOnUiThread { fail(e.message ?: e.toString()) } }
        }.start()
    }

    /** Android print dialog (simplified layout; the phone reader has no page layout of the original). */
    private fun printDoc() {
        try {
            val n = (title?.toString() ?: "Document")
            (getSystemService(PRINT_SERVICE) as PrintManager).print(n, web.createPrintDocumentAdapter(n), PrintAttributes.Builder().build())
        } catch (e: Throwable) { android.widget.Toast.makeText(this, "Print failed: " + (e.message ?: e.javaClass.simpleName), android.widget.Toast.LENGTH_LONG).show() }
    }

    private fun fail(t: String) {
        if (dead) return
        spin.visibility = android.view.View.GONE
        msg.text = t; msg.visibility = android.view.View.VISIBLE
    }

    override fun onDestroy() { dead = true; try { web.destroy() } catch (_: Throwable) {}; super.onDestroy() }
}
