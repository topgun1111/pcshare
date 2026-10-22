package com.lanshare.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.widget.Toast
import com.lanshare.app.core.ClipEngine
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Watches the download links of the models that are NOT in the APK (PaddleOCR, CLIP). A link counts as dead when the server answers
 * 4xx (404 gone, 403 ...), except 408 / 429. Dead links are kept in preferences and shown as a warning screen ([showIfAny]) in the
 * main screen and the viewers until fixed, so it is noticed even when the models are already installed on this phone:
 * [checkAll] sends a HEAD request to every link once a day (Wi-Fi only). Failed real downloads report here too.
 * To fix a link: PaddleModels.BASE / FILES (PaddleOcr.kt) or ClipEngine.BASE / FILES (core/ClipEngine.kt).
 */
object ModelLinks {
    private const val PREF = "model_links"
    private const val DAY = 24 * 60 * 60_000L
    @Volatile private var checking = false

    private fun p(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    fun isDead(code: Int) = code in 400..499 && code != 408 && code != 429

    private fun list(c: Context): JSONArray = try { JSONArray(p(c).getString("dead", "[]")) } catch (_: Exception) { JSONArray() }
    private fun save(c: Context, a: JSONArray) { p(c).edit().putString("dead", a.toString()).apply() }

    @Synchronized fun report(c: Context, label: String, url: String, code: Int) {
        val old = list(c); val out = JSONArray()
        for (i in 0 until old.length()) if (old.getJSONObject(i).optString("url") != url) out.put(old.getJSONObject(i))
        out.put(JSONObject().put("label", label).put("url", url).put("code", code).put("at", System.currentTimeMillis()))
        save(c, out)
    }

    @Synchronized fun ok(c: Context, url: String) {
        val old = list(c); val out = JSONArray()
        for (i in 0 until old.length()) if (old.getJSONObject(i).optString("url") != url) out.put(old.getJSONObject(i))
        if (out.length() != old.length()) save(c, out)
    }

    fun pending(c: Context): List<JSONObject> = list(c).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    /** Daily background check of every model link (HEAD). Needs Wi-Fi / unmetered; does nothing when run less than a day ago. */
    fun checkAll(c: Context) {
        if (checking || System.currentTimeMillis() - p(c).getLong("last", 0L) < DAY) return
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null || cm.isActiveNetworkMetered) return
        checking = true
        val app = c.applicationContext
        Thread({
            try {
                var reached = false
                for ((label, url) in PaddleModels.links() + ClipEngine.links()) {
                    val code = try {
                        (URL(url).openConnection() as HttpURLConnection).run {
                            requestMethod = "HEAD"; connectTimeout = 15000; readTimeout = 15000
                            setRequestProperty("User-Agent", "LANShare-Android")
                            try { responseCode } finally { disconnect() }
                        }
                    } catch (_: Exception) { -1 }
                    if (code in 200..399) { reached = true; ok(app, url) }
                    else if (isDead(code)) { reached = true; report(app, label, url, code) }
                }
                if (reached) p(app).edit().putLong("last", System.currentTimeMillis()).apply()   // no network at all: try again next start
            } finally { checking = false }
        }, "model-links").also { it.isDaemon = true; it.start() }
    }

    /** The warning screen. Call from onResume; shows nothing when all links are fine. */
    fun showIfAny(a: Activity) {
        val dead = pending(a)
        if (dead.isEmpty() || a.isFinishing || a.isDestroyed) return
        val fmt = SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault())
        val text = dead.joinToString("\n\n") { o ->
            "${o.optString("label")}\nHTTP ${o.optInt("code")} · ${fmt.format(Date(o.optLong("at")))}\n${o.optString("url")}"
        }
        AppPal.dlg(a)
            .setTitle("Model download link is dead")
            .setMessage("A model the app downloads (it is not in the APK) can no longer be fetched. Phones that already have the model keep working; new installs and reinstalls will not get it. Update the link in the code:\n\n$text")
            .setPositiveButton("Copy details") { _, _ ->
                (a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("dead model links", text))
                Toast.makeText(a, "Copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Dismiss") { _, _ -> dead.forEach { ok(a, it.optString("url")) } }   // comes back at the next daily check if still dead
            .setCancelable(true).show()
    }
}
