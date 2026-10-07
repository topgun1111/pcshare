package com.lanshare.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Checks GitHub releases/latest on app start and offers the new APK. At most one request per [MIN_INTERVAL_MS]. */
object UpdateCheck {
    private const val API = "https://api.github.com/repos/topgun1111/pcshare/releases/latest"
    private const val PREFS = "update_check"
    private const val MIN_INTERVAL_MS = 6L * 60 * 60 * 1000

    /** [onMsg] (manual check only) gets a short status when no dialog is shown: up to date / failed. */
    fun run(a: Activity, force: Boolean = false, onMsg: ((String) -> Unit)? = null) {
        val sp = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - sp.getLong("last", 0L) < MIN_INTERVAL_MS) return
        Thread {
            try {
                val c = URL(API).openConnection() as HttpURLConnection
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.connectTimeout = 8000; c.readTimeout = 8000
                val j = try { JSONObject(c.inputStream.bufferedReader().readText()) } finally { c.disconnect() }
                sp.edit().putLong("last", now).apply()
                val tag = j.getString("tag_name")
                val cur = a.packageManager.getPackageInfo(a.packageName, 0).versionName ?: return@Thread
                if (!isNewer(tag, cur)) { onMsg?.invoke("Güncel: $cur"); return@Thread }
                if (!force && sp.getString("skip", null) == tag) return@Thread
                val assets = j.optJSONArray("assets")
                var url = j.getString("html_url")
                if (assets != null) for (i in 0 until assets.length()) {
                    val o = assets.getJSONObject(i)
                    if (o.optString("name").endsWith(".apk")) { url = o.getString("browser_download_url"); break }
                }
                val dl = url
                a.runOnUiThread {
                    if (a.isFinishing || a.isDestroyed) return@runOnUiThread
                    AlertDialog.Builder(a)
                        .setTitle("Yeni sürüm: $tag")
                        .setMessage("Mevcut sürüm: $cur\nGitHub'da yeni bir güncelleme var.")
                        .setPositiveButton("İndir") { _, _ ->
                            try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dl))) } catch (_: Exception) { }
                        }
                        .setNegativeButton("Sonra", null)
                        .setNeutralButton("Bu sürümü atla") { _, _ -> sp.edit().putString("skip", tag).apply() }
                        .show()
                }
            } catch (_: Throwable) { onMsg?.invoke("Kontrol edilemedi (internet / GitHub)") }   // automatic check stays silent
        }.start()
    }

    /** "v1.0.134" vs "1.0.133-abc1234": compares dotted numeric parts, ignores the "-sha" suffix. */
    fun isNewer(tag: String, cur: String): Boolean {
        fun parts(s: String) = s.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val x = parts(tag); val y = parts(cur)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }
}
