package com.lanshare.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

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
                var size = 0L
                var isApk = false
                if (assets != null) for (i in 0 until assets.length()) {
                    val o = assets.getJSONObject(i)
                    if (o.optString("name").endsWith(".apk")) { url = o.getString("browser_download_url"); size = o.optLong("size", 0L); isApk = true; break }
                }
                val dl = url; val dlSize = size; val dlApk = isApk
                a.runOnUiThread {
                    if (a.isFinishing || a.isDestroyed) return@runOnUiThread
                    AlertDialog.Builder(a)
                        .setTitle("Yeni sürüm: $tag")
                        .setMessage("Mevcut sürüm: $cur\nGitHub'da yeni bir güncelleme var.")
                        .setPositiveButton("İndir") { _, _ ->
                            if (dlApk) download(a, dl, dlSize, tag) else openInBrowser(a, dl)
                        }
                        .setNegativeButton("Sonra", null)
                        .setNeutralButton("Bu sürümü atla") { _, _ -> sp.edit().putString("skip", tag).apply() }
                        .show()
                }
            } catch (_: Throwable) { onMsg?.invoke("Kontrol edilemedi (internet / GitHub)") }   // automatic check stays silent
        }.start()
    }

    private fun openInBrowser(a: Activity, url: String) {
        try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { }
    }

    private fun mb(n: Long) = String.format("%.1f MB", n / 1048576.0)

    /** In-app download with progress: .part file -> size check -> rename, so "completed" only shows when every byte arrived. */
    private fun download(a: Activity, url: String, expected: Long, tag: String) {
        val cancel = AtomicBoolean(false)
        val pad = (20 * a.resources.displayMetrics.density).toInt()
        val tv = TextView(a).apply { text = "İndiriliyor…" }
        val pb = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; isIndeterminate = expected <= 0 }
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0); addView(tv); addView(pb) }
        val dlg = AlertDialog.Builder(a).setTitle("Güncelleme $tag").setView(box).setCancelable(false)
            .setNegativeButton("İptal") { _, _ -> cancel.set(true) }.create()
        dlg.show()
        Thread {
            val dir = File(a.cacheDir, "open").apply { mkdirs() }   // already exposed by the app's FileProvider
            val part = File(dir, "update.apk.part"); val out = File(dir, "update.apk")
            var err: String? = null
            try {
                part.delete(); out.delete()
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 30000
                try {
                    if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
                    val total = if (c.contentLengthLong > 0) c.contentLengthLong else expected
                    var got = 0L; var lastUi = 0L
                    c.inputStream.use { ins ->
                        part.outputStream().use { os ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                if (cancel.get()) throw IOException("cancel")
                                val n = ins.read(buf); if (n < 0) break
                                os.write(buf, 0, n); got += n
                                val t = System.currentTimeMillis()
                                if (t - lastUi > 200) {
                                    lastUi = t; val g = got
                                    a.runOnUiThread {
                                        if (total > 0) { pb.progress = (g * 100 / total).toInt(); tv.text = "${mb(g)} / ${mb(total)}" } else tv.text = mb(g)
                                    }
                                }
                            }
                            os.flush()
                        }
                    }
                    if (got <= 0L) throw IOException("Boş dosya")
                    if (total > 0 && got != total) throw IOException("Eksik indirme: ${mb(got)} / ${mb(total)}")
                } finally { c.disconnect() }
                if (!part.renameTo(out)) throw IOException("Dosya kaydedilemedi")
            } catch (e: Throwable) {
                err = if (cancel.get()) "cancel" else (e.message ?: e.javaClass.simpleName)
                part.delete()
            }
            a.runOnUiThread {
                try { dlg.dismiss() } catch (_: Throwable) { }
                if (a.isFinishing || a.isDestroyed) return@runOnUiThread
                val e = err
                when {
                    e == null -> offerInstall(a, out, tag)
                    e == "cancel" -> { }
                    else -> AlertDialog.Builder(a).setTitle("İndirme başarısız").setMessage(e)
                        .setPositiveButton("Tarayıcıda aç") { _, _ -> openInBrowser(a, url) }
                        .setNegativeButton("Kapat", null).show()
                }
            }
        }.start()
    }

    private fun offerInstall(a: Activity, f: File, tag: String) {
        AlertDialog.Builder(a).setTitle("İndirme tamamlandı")
            .setMessage("$tag indirildi (${mb(f.length())}). Kurulumu başlatmak için Kur'a bas.")
            .setPositiveButton("Kur") { _, _ -> install(a, f, tag) }
            .setNegativeButton("Kapat", null).show()
    }

    private fun install(a: Activity, f: File, tag: String) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(a, "Bu uygulamaya 'bilinmeyen kaynaklardan yükleme' izni ver, sonra geri dönüp Kur'a bas", Toast.LENGTH_LONG).show()
                a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
                offerInstall(a, f, tag)   // still on screen when the user comes back
                return
            }
            val uri = FileProvider.getUriForFile(a, "${a.packageName}.fileprovider", f)
            a.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Throwable) {
            Toast.makeText(a, "Kurulum başlatılamadı: ${e.message}", Toast.LENGTH_LONG).show()
        }
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
