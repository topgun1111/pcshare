package com.lanshare.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.lanshare.app.core.Core
import org.json.JSONArray
import java.io.File

/**
 * Entry point of the app (no WebView). Asks for the permissions, starts the background service, copies files that
 * arrive through the Android share sheet into "LANShare Shared", waits until the server is up and then opens the
 * native browser ([BrowserActivity]). The app has no web UI any more: everything after this screen is native.
 */
class LauncherActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var msg: TextView
    private lateinit var detail: TextView
    private var shared: List<String>? = null   // names (inside "LANShare Shared") of files received from the share sheet
    private var copying = false
    private var t0 = 0L
    private var gone = false
    private var active = false   // false while a system permission screen is on top: do not open the browser over it

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(0xFF1565C0.toInt()); setPadding(48, 48, 48, 48)
        }
        TextView(this).apply { text = "LANShare"; textSize = 26f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER }
            .also { col.addView(it) }
        msg = TextView(this).apply { text = "Starting\u2026"; textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, 24, 0, 0) }
        detail = TextView(this).apply { textSize = 11f; setTextColor(0xCCFFFFFF.toInt()); setPadding(0, 24, 0, 0) }
        col.addView(msg); col.addView(detail)
        setContentView(col)

        if (b == null) askPermissions()
        ContextCompat.startForegroundService(this, Intent(this, LanShareService::class.java))
        t0 = System.currentTimeMillis()
        handleShare(intent)
        ui.post(poll)
    }

    override fun onResume() { super.onResume(); active = true }
    override fun onPause() { active = false; super.onPause() }

    override fun onDestroy() { gone = true; ui.removeCallbacksAndMessages(null); super.onDestroy() }

    private val poll = object : Runnable {
        override fun run() {
            if (gone) return
            val err = Core.error
            when {
                Core.url != null && !copying && active -> forward()
                Core.url != null -> ui.postDelayed(this, 200)
                err != null && Core.url == null -> showError(err)
                System.currentTimeMillis() - t0 > 40_000 && Core.url == null -> showError("timeout")
                else -> ui.postDelayed(this, 200)
            }
        }
    }

    private fun showError(e: String) {
        msg.text = "Server did not start. Close and reopen the app."
        detail.text = e
    }

    private fun forward() {
        val i = Intent(this, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        shared?.let { if (it.isNotEmpty()) i.putExtra("share", JSONArray(it).toString()) }
        startActivity(i)
        finish()
    }

    /** Android share sheet -> copy into <storage>/LANShare Shared; the browser then offers the "Send to..." device list. */
    private fun handleShare(i: Intent?) {
        if (i == null || (i.action != Intent.ACTION_SEND && i.action != Intent.ACTION_SEND_MULTIPLE)) return
        @Suppress("DEPRECATION")
        val uris: List<Uri> = if (i.action == Intent.ACTION_SEND)
            listOfNotNull(i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        else i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
        if (uris.isEmpty()) return
        i.action = null   // consume once
        copying = true
        msg.text = "Receiving files\u2026"
        Thread {
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
                names.add(f.name)
            } catch (_: Exception) {}
            runOnUiThread {
                if (names.isEmpty()) Toast.makeText(this, "Could not read the shared file(s)", Toast.LENGTH_LONG).show()
                else shared = names
                copying = false
            }
        }.start()
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
}
