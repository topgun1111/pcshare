package com.lanshare.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.webkit.*
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.chaquo.python.Python
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private val ui = Handler(Looper.getMainLooper())
    private var loaded = false
    private var hadAllFiles = false
    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { finishAndRemoveTask() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        web = WebView(this)
        setContentView(web)
        // Edge-to-edge: pad the WebView by system bars / keyboard (WebView does not feed bar insets to CSS env()).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(web) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
        val dark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bg = if (dark) 0xFF111215.toInt() else 0xFFF4F5F7.toInt() // matches the page's --bg
        window.decorView.setBackgroundColor(bg)
        web.setBackgroundColor(bg)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        ContextCompat.registerReceiver(
            this, stopReceiver, IntentFilter(ServerService.ACTION_STOPPED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.setSupportMultipleWindows(true)
        web.webViewClient = WebViewClient()
        web.webChromeClient = object : WebChromeClient() {
            // UI uses prompt()/confirm() (pairing PIN, rename, delete, new folder)
            override fun onJsPrompt(v: WebView, url: String, msg: String, def: String?, r: JsPromptResult): Boolean {
                val et = EditText(this@MainActivity).apply { setText(def ?: "") }
                AlertDialog.Builder(this@MainActivity).setMessage(msg).setView(et)
                    .setPositiveButton(android.R.string.ok) { _, _ -> r.confirm(et.text.toString()) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> r.cancel() }
                    .setOnCancelListener { r.cancel() }.show()
                return true
            }
            override fun onJsConfirm(v: WebView, url: String, msg: String, r: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity).setMessage(msg)
                    .setPositiveButton(android.R.string.ok) { _, _ -> r.confirm() }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> r.cancel() }
                    .setOnCancelListener { r.cancel() }.show()
                return true
            }
            override fun onJsAlert(v: WebView, url: String, msg: String, r: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity).setMessage(msg)
                    .setPositiveButton(android.R.string.ok) { _, _ -> r.confirm() }
                    .setOnCancelListener { r.confirm() }.show()
                return true
            }
            // UI calls window.open('/api/dl?...') to view/download a file
            override fun onCreateWindow(v: WebView, dialog: Boolean, gesture: Boolean, msg: android.os.Message): Boolean {
                val tmp = WebView(this@MainActivity)
                tmp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(w: WebView, req: WebResourceRequest): Boolean {
                        download(req.url.toString(), null, null); w.destroy(); return true
                    }
                }
                (msg.obj as WebView.WebViewTransport).webView = tmp
                msg.sendToTarget()
                return true
            }
        }
        web.setDownloadListener { url, _, cd, mime, _ -> download(url, cd, mime) }

        needPermissions()
        startServer()
    }

    private fun toast(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    /** Streams a local-server /api/dl URL into Downloads (MediaStore on Q+), then opens viewable types. */
    private fun download(url: String, cd: String?, mime: String?) {
        val u = try { URL(url) } catch (_: Exception) { return }
        if (u.host != "127.0.0.1" && u.host != "localhost") return // only our own embedded server
        Thread {
            var conn: HttpURLConnection? = null
            try {
                conn = (u.openConnection() as HttpURLConnection).apply { connectTimeout = 10000; readTimeout = 60000 }
                if (conn.responseCode !in 200..299) { toast("Download failed (${conn.responseCode})"); return@Thread }
                val type = (conn.contentType ?: mime ?: "application/octet-stream").substringBefore(';').trim()
                val name = fileName(conn.getHeaderField("Content-Disposition") ?: cd, u)
                val uri = save(conn, name, type) ?: return@Thread
                toast("Saved to Downloads: $name")
                if (viewable(type)) view(uri, type)
            } catch (e: Exception) {
                toast("Download failed: ${e.message}")
            } finally { conn?.disconnect() }
        }.start()
    }

    private fun viewable(t: String) =
        t.startsWith("image/") || t.startsWith("video/") || t.startsWith("audio/") || t == "application/pdf" || t.startsWith("text/")

    private fun fileName(cd: String?, u: URL): String {
        cd?.let {
            Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(it)?.let { m ->
                return URLDecoder.decode(m.groupValues[1].replace("+", "%2B"), "UTF-8").replace(Regex("[\\\\/]"), "_")
            }
            Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(it)?.let { m ->
                return m.groupValues[1].replace(Regex("[\\\\/]"), "_")
            }
        }
        val q = u.query?.split("&")?.firstOrNull { it.startsWith("path=") }?.substring(5)
        return URLDecoder.decode(q ?: "file", "UTF-8").substringAfterLast('/').ifBlank { "file" }
    }

    private fun save(conn: HttpURLConnection, name: String, type: String): Uri? {
        if (Build.VERSION.SDK_INT >= 29) {
            val rv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, type)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, rv) ?: run { toast("Cannot create file"); return null }
            try {
                contentResolver.openOutputStream(uri)!!.use { o -> conn.inputStream.use { it.copyTo(o, 1 shl 16) } }
            } catch (e: Exception) { contentResolver.delete(uri, null, null); throw e }
            contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            return uri
        }
        // Android 7-9: public Downloads dir (WRITE_EXTERNAL_STORAGE requested at startup)
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
        var f = File(dir, name); var i = 1
        while (f.exists()) { f = File(dir, "${name.substringBeforeLast('.', name)} ($i)${name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }}"); i++ }
        f.outputStream().use { o -> conn.inputStream.use { it.copyTo(o, 1 shl 16) } }
        sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(f)))
        return androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
    }

    private fun view(uri: Uri, type: String) = runOnUiThread {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { /* no viewer installed: file is already in Downloads */ }
    }

    private fun needPermissions() {
        hadAllFiles = hasAllFiles()
        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.NEARBY_WIFI_DEVICES), 1)
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 2)
        }
    }

    private fun hasAllFiles() = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true

    private fun startServer() {
        ContextCompat.startForegroundService(this, Intent(this, ServerService::class.java))
        poll()
    }

    private fun poll() {
        if (loaded) return
        val p = ServerService.port
        if (p > 0) { loaded = true; web.loadUrl("http://127.0.0.1:$p/"); return }
        ServerService.error?.let { web.loadData("<pre>$it</pre>", "text/html", "utf-8"); return }
        ui.postDelayed({ poll() }, 250)
    }

    /** After All-files-access is granted, restart the Python server so the root listing is populated. */
    override fun onResume() {
        super.onResume()
        if (!hadAllFiles && hasAllFiles() && ServerService.port > 0) {
            hadAllFiles = true
            Thread {
                try {
                    val bridge = Python.getInstance().getModule("bridge")
                    bridge.callAttr("stop")
                    val p = bridge.callAttr("start", ServerService.rootDir(), ServerService.cfgPath(this), Build.MODEL ?: "Android",
                        ServerService.linkSpec(this)).toInt()
                    ServerService.port = p
                    ui.post { web.loadUrl("http://127.0.0.1:$p/") }
                } catch (e: Throwable) { toast("Restart failed: $e") }
            }.start()
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(stopReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    @Deprecated("back")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else moveTaskToBack(true) }
}
