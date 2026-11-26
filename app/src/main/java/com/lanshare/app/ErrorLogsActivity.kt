package com.lanshare.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.lanshare.app.core.ErrorLog
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Local-only settings screen. The WebView can open it but cannot read the log or export it itself. */
class ErrorLogsActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "error-logs-screen").also { it.isDaemon = true } }
    private lateinit var body: TextView
    private lateinit var info: TextView
    private lateinit var scroll: ScrollView
    private lateinit var pal: AppPal.P
    private var busy = false

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    override fun attachBaseContext(b: Context) { super.attachBaseContext(b); UiScale.attach(this, b) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Fnt.init(applicationContext)
        pal = AppPal.pal(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(pal.bg) }
        LumenNativeTheme.edgeToEdge(this, root)
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(12), dp(8)) }
        header.addView(button("Back") { finish() })
        header.addView(TextView(this).apply {
            text = "Error Logs"; NlA11y.heading(this); LumenTypography.fit(this, 20f); typeface = Fnt.semi()
            setTextColor(pal.fg); setPadding(dp(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header)
        info = TextView(this).apply {
            text = "Automatic diagnostics are stored privately on this device. Newest entries first. Export includes all retained logs."
            setTextColor(pal.mut); typeface = Fnt.reg(); LumenTypography.fit(this, 13f)
            setPadding(dp(16), 0, dp(16), dp(10))
        }
        root.addView(info)
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(8), dp(8)) }
        bar.addView(button("Refresh") { refresh() })
        bar.addView(button("Export to storage") { chooseExport() })
        root.addView(bar)
        scroll = ScrollView(this).apply { isFillViewport = true }
        body = TextView(this).apply {
            typeface = Typeface.MONOSPACE; LumenTypography.fit(this, 12f)
            setTextColor(pal.fg); setTextIsSelectable(true)
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        scroll.addView(body, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        refresh()
    }

    private fun button(title: String, click: () -> Unit) = android.widget.Button(this).apply {
        text = title; isAllCaps = false; setTextColor(pal.fg); LumenTypography.fit(this, 13f)
        minHeight = dp(44); setOnClickListener { click() }
    }

    private fun refresh() {
        if (busy) return
        body.text = "Reading logs…"
        worker.execute {
            val text = try { ErrorLog.recent().ifBlank { "No errors recorded yet." } }
            catch (e: Throwable) { ErrorLog.exception("Error Logs viewer", e); "Unable to read diagnostic logs." }
            runOnUiThread {
                if (!isDestroyed && !isFinishing) { body.text = text; scroll.scrollTo(0, 0) }
            }
        }
    }

    private fun chooseExport() {
        if (busy) return
        AppPal.dlg(this).setTitle("Export error logs?")
            .setMessage("The file may include filenames and technical error details. Review it before sharing. Nothing is uploaded automatically.")
            .setPositiveButton("Choose location") { _, _ ->
                try {
                    val date = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                    val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("text/plain").putExtra(Intent.EXTRA_TITLE, "PCShare-error-logs-$date.txt")
                    startActivityForResult(i, EXPORT)
                } catch (e: Exception) { ErrorLog.exception("Error Logs export picker", e); toast("Cannot open the file picker") }
            }.setNegativeButton("Cancel", null).show()
    }

    override fun onActivityResult(req: Int, result: Int, data: Intent?) {
        super.onActivityResult(req, result, data)
        if (req != EXPORT || result != RESULT_OK) return
        val uri = data?.data ?: return
        if (busy) return
        busy = true
        worker.execute {
            val message = try {
                (contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open destination")).use { ErrorLog.export(it) }
                "Error logs exported"
            } catch (e: Throwable) {
                ErrorLog.exception("Error Logs export", e)
                "Export failed: " + (e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread {
                busy = false
                if (!isDestroyed && !isFinishing) toast(message)
            }
        }
    }

    private fun toast(s: String) { Lm.snack(this, s, Toast.LENGTH_LONG) }

    override fun onDestroy() { worker.shutdown(); super.onDestroy() }

    companion object { private const val EXPORT = 450 }
}
