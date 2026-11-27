package com.lanshare.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.lanshare.app.core.ApkDelta
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** Checks GitHub releases on app start and offers the new APK ONLY if it is newer than the installed version (same or older = nothing is shown). A manual check (force=true) skips the 6 h limit and the "skipped version" memory, only offers newer versions, and when up to date still shows the full change log (no download button). The dialog always lists every release newest first, the newest in a green tinted card. */
object UpdateCheck {
    private const val API = "https://api.github.com/repos/topgun1111/pcshare/releases?per_page=30"   // list, not /latest: we pick the highest version ourselves
    private const val PREFS = "update_check"
    private val downloading = AtomicBoolean(false)
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
                val txt = try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
                val arr = JSONArray(txt)
                val lite = isLite(a)   // this install's edition; a release only counts when it carries THIS edition's APK (never the other one)
                var best: JSONObject? = null
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.optBoolean("draft") || !hasApk(o, lite)) continue
                    val bt = best?.getString("tag_name")
                    if (bt == null || isNewer(o.getString("tag_name"), bt)) best = o
                }
                val j = best ?: throw IOException("Release yok")
                sp.edit().putLong("last", now).apply()
                val tag = j.getString("tag_name")
                val cur = a.packageManager.getPackageInfo(a.packageName, 0).versionName ?: return@Thread
                val all = ArrayList<JSONObject>()   // EVERY release of this edition, newest first: the dialog always shows the full change log
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.optBoolean("draft") || !hasApk(o, lite)) continue
                    all.add(o)
                }
                all.sortWith { x, y -> if (isNewer(x.optString("tag_name"), y.optString("tag_name"))) -1 else if (isNewer(y.optString("tag_name"), x.optString("tag_name"))) 1 else 0 }
                if (!isNewer(tag, cur)) {   // same or older release: automatic check stays silent; a manual check shows the change log with no download button
                    if (!force) return@Thread
                    a.runOnUiThread {
                        if (a.isFinishing || a.isDestroyed) return@runOnUiThread
                        AppPal.dlg(a).kind("info")
                            .setTitle("Güncel: $cur")
                            .setView(changeView(a, cur, all))
                            .setPositiveButton("Kapat", null)
                            .show()
                    }
                    return@Thread
                }
                if (!force && sp.getString("skip", null) == tag) return@Thread
                val assets = j.optJSONArray("assets")
                val apk = pickApk(assets, lite) ?: throw IOException("APK yok")   // never falls back to the release page (its first listed file is the lite APK)
                val dl = apk.getString("browser_download_url"); val dlSize = apk.optLong("size", 0L)
                a.runOnUiThread {
                    if (a.isFinishing || a.isDestroyed) return@runOnUiThread
                    AppPal.dlg(a).kind("info")
                        .setTitle("Yeni sürüm: $tag")
                        .setView(changeView(a, cur, all))
                        .setPositiveButton("İndir") { _, _ ->
                            download(a, dl, dlSize, tag, apk, assets, lite)
                        }
                        .setNegativeButton("Sonra", null)
                        .setNeutralButton("Bu sürümü atla") { _, _ ->
                            sp.edit().putString("skip", tag).apply()
                            Lm.snack(a, "$tag atlandı (Ayarlar > Check for updates ile yine de indirilebilir)", Toast.LENGTH_LONG)
                        }
                        .show()
                }
            } catch (_: Throwable) { onMsg?.invoke("Kontrol edilemedi (internet / GitHub)") }   // automatic check stays silent
        }.start()
    }

    /** Every release carries two APKs: "...-lite.apk" and the full one. Each edition only ever downloads its own file. */
    private fun isLite(c: Context) = BuildConfig.LITE || c.packageName.endsWith(".lite")

    private fun pickApk(oa: JSONArray?, lite: Boolean): JSONObject? {
        if (oa == null) return null
        for (k in 0 until oa.length()) {
            val o = oa.getJSONObject(k); val n = o.optString("name")
            if (n.endsWith(".apk") && n.endsWith("-lite.apk") == lite && o.optString("browser_download_url").isNotEmpty()) return o
        }
        return null
    }

    private fun hasApk(o: JSONObject, lite: Boolean) = pickApk(o.optJSONArray("assets"), lite) != null

    /** Scrollable change log: current version, then EVERY release newest first. The newest one (the last update) sits in a green tinted card; the installed one is marked. */
    private fun changeView(a: Activity, cur: String, rels: List<JSONObject>): android.view.View {
        val p = AppPal.pal(a); val night = AppPal.night(a)
        val d = a.resources.displayMetrics.density
        val pad = (20 * d).toInt()
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(a).apply {
            setTextColor(p.mut); LumenTypography.fit(this, 14f)
            text = "Mevcut sürüm: $cur"
            setPadding(0, 0, 0, (8 * d).toInt())
        })
        val lmA = Lumen.of(a)
        val greenFill = androidx.core.graphics.ColorUtils.setAlphaComponent(lmA.success, if (night) 0x33 else 0x26)
        val greenLine = lmA.success
        for ((i, o) in rels.withIndex()) {
            val tag = o.optString("tag_name")
            val latest = i == 0
            val installed = !isNewer(tag, cur) && !isNewer(cur, tag)
            val card = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                val q = (12 * d).toInt(); setPadding(q, q, q, q)
                if (latest) background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(greenFill); setStroke((1.5f * d).toInt(), greenLine); cornerRadius = 8 * d
                }
            }
            card.addView(TextView(a).apply {
                setTextColor(if (latest && androidx.core.graphics.ColorUtils.calculateContrast(greenLine or 0xFF000000.toInt(), androidx.core.graphics.ColorUtils.compositeColors(greenFill, p.bg)) >= 3.0) greenLine else p.fg); LumenTypography.fit(this, 15f); setTypeface(typeface, Typeface.BOLD)
                val date = o.optString("published_at").take(10)
                text = buildString {
                    append(tag)
                    if (latest) append("  ·  Son güncelleme")
                    if (installed) append("  ·  Kurulu")
                    if (date.isNotEmpty()) append("\n").append(date)
                }
            })
            card.addView(TextView(a).apply { setTextColor(p.fg); LumenTypography.fit(this, 14f); text = notesText(a, o) })
            box.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (8 * d).toInt() })
        }
        if (rels.isEmpty()) box.addView(TextView(a).apply { setTextColor(p.mut); text = "(not listed)" })
        return ScrollView(a).apply { setPadding(pad, pad / 2, pad, 0); addView(box) }
    }

    /** One release body (CHANGELOG.md "### Added / Removed / Changed / Fixed" lists) as plain styled text: green Added, red Removed, accent for the rest, "•" bullets. */
    private fun notesText(a: Activity, o: JSONObject): CharSequence {
        val p = AppPal.pal(a); val night = AppPal.night(a)
        val lmN = Lumen.of(a)
        fun rd(x: Int) = if (androidx.core.graphics.ColorUtils.calculateContrast(x or 0xFF000000.toInt(), p.bg or 0xFF000000.toInt()) >= 3.0) x else p.fg   // readable on the dialog window, else page ink
        val ok = rd(lmN.success)
        val bad = rd(lmN.error)
        val sb = SpannableStringBuilder()
        fun add(t: String, color: Int?, bold: Boolean) {
            val st = sb.length; sb.append(t)
            if (bold) sb.setSpan(StyleSpan(Typeface.BOLD), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (color != null) sb.setSpan(ForegroundColorSpan(color), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val body = (if (o.isNull("body")) "" else o.optString("body", "")).replace("\r", "").take(4000)
        var any = false
        for (raw in body.lines()) {
            val l = raw.trim().replace("**", "").replace("`", "")
            if (l.isEmpty() || l.startsWith("# ") || l.startsWith("## ")) continue
            if (l.startsWith("###")) {
                val h = l.trimStart('#').trim()
                add((if (any) "\n" else "") + h + "\n", when { h.startsWith("Added", true) -> ok; h.startsWith("Removed", true) -> bad; else -> rd(p.ac) }, true)
            } else if (l.startsWith("- ") || l.startsWith("* ")) add("\u2022 " + l.drop(2).trim() + "\n", null, false)
            else add(l + "\n", null, false)
            any = true
        }
        if (!any) add("(not listed)\n", p.mut, false)
        return sb.trimEnd()
    }

    private fun openInBrowser(a: Activity, url: String) {
        try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { }
    }

    private fun mb(n: Long) = String.format("%.1f MB", n / 1048576.0)

    /** Download an exact-base delta when worthwhile; keep the existing APK installer as the only install path. */
    private fun download(a: Activity, url: String, expected: Long, tag: String, apk: JSONObject, assets: JSONArray?, lite: Boolean) {
        if (!downloading.compareAndSet(false, true)) {
            Lm.snack(a, "Güncelleme zaten indiriliyor", Toast.LENGTH_SHORT)
            return
        }
        val cancel = AtomicBoolean(false)
        val pad = (20 * a.resources.displayMetrics.density).toInt()
        val tv = TextView(a).apply { text = "Güncelleme hazırlanıyor…" }
        val pb = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; isIndeterminate = true }
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0); addView(tv); addView(pb) }
        val dlg = AppPal.dlg(a).setTitle("Güncelleme $tag").setView(box).setCancelable(false)
            .setNegativeButton("İptal") { _, _ -> cancel.set(true) }.create()
        dlg.show()
        Thread {
            val dir = File(a.cacheDir, "open").apply { mkdirs() }
            val out = File(dir, "update.apk")
            val rebuilt = File(dir, "update-rebuild.apk")
            var err: String? = null
            var usedDelta = false
            val digest = apk.optString("digest").removePrefix("sha256:").takeIf { it.matches(Regex("[0-9a-f]{64}")) }
            fun status(message: String) = a.runOnUiThread { tv.text = message; pb.isIndeterminate = true }
            fun fetch(assetUrl: String, size: Long, label: String): File {
                // URL uniquely identifies the immutable release asset; partials cannot mix editions or releases.
                val key = java.security.MessageDigest.getInstance("SHA-256").digest(assetUrl.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
                val f = File(dir, "pcshare-update-$key.part")
                fetchUpdate(assetUrl, size, f, cancel) { got, total ->
                    a.runOnUiThread {
                        pb.isIndeterminate = total <= 0
                        if (total > 0) pb.progress = (got * 100 / total).coerceIn(0, 100).toInt()
                        tv.text = if (total > 0) "$label: ${mb(got)} / ${mb(total)}" else "$label: ${mb(got)}"
                    }
                }
                return f
            }
            try {
                out.delete(); rebuilt.delete()
                // Retain recent partials for a retry, but avoid accumulating old releases indefinitely.
                dir.listFiles()?.filter { it.name.startsWith("pcshare-update-") && it.name.endsWith(".part") &&
                    System.currentTimeMillis() - it.lastModified() > 7L * 24 * 60 * 60 * 1000 }?.forEach { it.delete() }
                try {
                    val base = File(a.applicationInfo.sourceDir)
                    val baseHash = ApkDelta.sha256(base) { cancel.get() }
                    val edition = if (lite) "lite" else "full"
                    val pattern = Regex("LANShare-delta-$baseHash-([0-9a-f]{64})-$edition\\.patch")
                    var chosen: JSONObject? = null
                    var targetHash = ""
                    if (assets != null && expected > 0) for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val match = pattern.matchEntire(asset.optString("name")) ?: continue
                        val hash = match.groupValues[1]
                        val size = asset.optLong("size")
                        if (size <= 0 || size >= expected || (digest != null && digest != hash) ||
                            asset.optString("browser_download_url").isEmpty()) continue
                        if (chosen == null || size < chosen.optLong("size")) { chosen = asset; targetHash = hash }
                    }
                    val selected = chosen
                    if (selected != null) {
                        val patch = fetch(selected.getString("browser_download_url"), selected.getLong("size"), "Parça güncelleme")
                        try {
                            status("APK yeniden oluşturuluyor…")
                            ApkDelta.apply(base, patch, rebuilt, baseHash, targetHash, expected) { cancel.get() }
                            verifyUpdate(a, rebuilt)
                            if (!rebuilt.renameTo(out)) throw IOException("Dosya kaydedilemedi")
                            usedDelta = true
                        } finally { patch.delete() }
                    }
                } catch (e: Exception) {
                    if (cancel.get()) throw e
                    rebuilt.delete()
                    com.lanshare.app.core.ErrorLog.exception("Delta update fallback", e)
                    status("Tam APK indiriliyor…")
                }
                if (!usedDelta) {
                    val part = fetch(url, expected, "Tam APK")
                    try {
                        status("APK doğrulanıyor…")
                        if (digest != null && ApkDelta.sha256(part) { cancel.get() } != digest) throw IOException("APK checksum mismatch")
                        if (cancel.get()) throw IOException("cancel")
                        if (!part.renameTo(out)) throw IOException("Dosya kaydedilemedi")
                        verifyUpdate(a, out)
                    } catch (e: Exception) { part.delete(); throw e }
                }
                if (cancel.get()) throw IOException("cancel")
            } catch (e: Exception) {
                if (!cancel.get()) com.lanshare.app.core.ErrorLog.exception("Update download", e)
                err = if (cancel.get()) "cancel" else (e.message ?: e.javaClass.simpleName)
                out.delete()
            } finally { rebuilt.delete(); downloading.set(false) }
            a.runOnUiThread {
                try { dlg.dismiss() } catch (_: Throwable) { }
                if (a.isFinishing || a.isDestroyed) return@runOnUiThread
                val e = err
                when {
                    e == null -> offerInstall(a, out, tag, usedDelta)
                    e == "cancel" -> { }
                    else -> AppPal.dlg(a).kind("warn").setTitle("İndirme başarısız").setMessage(e)
                        .setPositiveButton("Tarayıcıda aç") { _, _ -> openInBrowser(a, url) }
                        .setNegativeButton("Kapat", null).show()
                }
            }
        }.start()
    }

    /** Retry resumes the same release asset if the server supports ranges; a 200 response restarts cleanly. */
    private fun fetchUpdate(url: String, expected: Long, part: File, cancel: AtomicBoolean, progress: (Long, Long) -> Unit) {
        if (cancel.get()) throw IOException("cancel")
        if (expected <= 0 || part.length() > expected) part.delete()
        var got = part.length()
        if (expected > 0 && got == expected) { progress(got, expected); return }
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("Accept-Encoding", "identity")
        if (got > 0) c.setRequestProperty("Range", "bytes=$got-")
        try {
            val code = c.responseCode
            if (code == 206 && got > 0) {
                val match = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(c.getHeaderField("Content-Range") ?: "")
                if (match == null || match.groupValues[1].toLongOrNull() != got ||
                    match.groupValues[2].toLongOrNull() != expected - 1 || match.groupValues[3].toLongOrNull() != expected) {
                    part.delete(); throw IOException("Invalid download range")
                }
            } else if (code == 200) { got = 0; part.delete() }
            else { if (code == 416) part.delete(); throw IOException("HTTP $code") }
            val total = if (expected > 0) expected else c.contentLengthLong
            if (total > 512L * 1024 * 1024) throw IOException("APK too large")
            var lastUi = 0L
            c.inputStream.use { ins ->
                java.io.FileOutputStream(part, got > 0).use { os ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancel.get()) throw IOException("cancel")
                        val n = ins.read(buf); if (n < 0) break
                        if (got + n > 512L * 1024 * 1024 || (total > 0 && got + n > total)) {
                            throw IOException("Download exceeds expected size")
                        }
                        os.write(buf, 0, n); got += n
                        val t = System.currentTimeMillis()
                        if (t - lastUi > 200) { lastUi = t; progress(got, total) }
                    }
                }
            }
            if (got <= 0 || (total > 0 && got != total)) throw IOException("Eksik indirme: ${mb(got)} / ${mb(total)}")
            progress(got, total)
        } finally { c.disconnect() }
    }

    @Suppress("DEPRECATION")
    private fun verifyUpdate(a: Activity, file: File) {
        val pm = a.packageManager
        val current = pm.getPackageInfo(a.packageName, PackageManager.GET_SIGNATURES)
        val candidate = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)
            ?: throw IOException("Geçersiz APK")
        val installedSigners = current.signatures?.map { it.toCharsString() }?.toSet()
        val nextSigners = candidate.signatures?.map { it.toCharsString() }?.toSet()
        if (candidate.packageName != a.packageName || candidate.versionCode <= current.versionCode ||
            installedSigners.isNullOrEmpty() || installedSigners != nextSigners) throw IOException("APK kimliği, sürümü veya imzası uyuşmuyor")
    }

    private fun offerInstall(a: Activity, f: File, tag: String, usedDelta: Boolean = false) {
        AppPal.dlg(a).kind("ok").setTitle("İndirme tamamlandı")
            .setMessage("$tag ${if (usedDelta) "parça güncelleme ile hazırlandı" else "indirildi"} (${mb(f.length())}). Kurulumu başlatmak için Kur'a bas.")
            .setPositiveButton("Kur") { _, _ -> install(a, f, tag) }
            .setNegativeButton("Kapat", null).show()
    }

    private fun install(a: Activity, f: File, tag: String) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
                Lm.snack(a, "Bu uygulamaya 'bilinmeyen kaynaklardan yükleme' izni ver, sonra geri dönüp Kur'a bas", Toast.LENGTH_LONG)
                a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
                offerInstall(a, f, tag)   // still on screen when the user comes back
                return
            }
            val uri = com.lanshare.app.core.vault.VaultIntents.uri(a, "${a.packageName}.fileprovider", f)
            a.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Throwable) {
            com.lanshare.app.core.ErrorLog.exception("Update installation", e)
            Lm.snack(a, "Kurulum başlatılamadı: ${e.message}", Toast.LENGTH_LONG)
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
