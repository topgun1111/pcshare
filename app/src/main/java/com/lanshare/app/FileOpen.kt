package com.lanshare.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import com.lanshare.app.core.Core
import com.lanshare.app.core.Jobs
import com.lanshare.app.core.errText
import com.lanshare.app.core.mimeFor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What a tap on a file does in FilesActivity. Videos / pictures / PDFs go to the dedicated viewers with the same payloads as
 * NativeList.openNative (= ui.html nativePlay / nativeImg / nativePdf); everything else is copied to cache/open and handed to an
 * installed app (same FileProvider folder MainActivity uses).
 * NOT compiled / NOT device-tested.
 */
object FileOpen {
    private val NOIMG = Regex("\\.(svg|gif)$", RegexOption.IGNORE_CASE)
    private val HEIC = Regex("\\.(heic|heif)$", RegexOption.IGNORE_CASE)
    private val SUBX = Regex("\\.(srt|vtt|ass|ssa)$", RegexOption.IGNORE_CASE)

    private fun encU(x: String): String = java.net.URLEncoder.encode(x, "UTF-8").replace("+", "%20").replace("%21", "!").replace("%27", "'")
        .replace("%28", "(").replace("%29", ")").replace("%7E", "~")   // = encodeURIComponent

    /** true = a viewer was started. false = not a viewer type (or inside an archive): use [external]. */
    fun viewer(act: Activity, dev: String, path: String, rows: List<NlRow>, r: NlRow): Boolean {
        try {
            val origin = Core.url ?: return false
            if (r.dir || path.contains('!')) return false
            fun url(n: String) = origin + "/api/dl?dev=" + encU(dev) + "&path=" + encU((if (path == "/") "" else path) + "/" + n)
            fun key(n: String, sz: Long) = dev + "|" + path + "/" + n + "|" + sz
            val files = rows.filter { !it.dir }
            when (r.k) {
                "vid" -> {
                    val v = files.filter { it.k == "vid" }
                    val subs = files.filter { SUBX.containsMatchIn(it.nm) }
                    val base = { n: String -> n.replace(Regex("\\.[^.]+$"), "").lowercase() }
                    val items = JSONArray()
                    for (m in v) {
                        val b = base(m.nm); val sj = JSONArray()
                        for (x in subs) { val c = base(x.nm); if (c == b || c.startsWith("$b.")) sj.put(JSONObject().put("name", x.nm).put("url", url(x.nm))) }
                        items.put(JSONObject().put("name", m.nm).put("url", url(m.nm)).put("key", key(m.nm, m.size)).put("subs", sj))
                    }
                    PlayerActivity.pending = JSONObject().put("start", Math.max(0, v.indexOfFirst { it.nm == r.nm })).put("items", items).toString()
                    act.startActivity(Intent(act, PlayerActivity::class.java))
                }
                "img" -> {
                    val heicOk = Build.VERSION.SDK_INT >= 28
                    val ok = { x: NlRow -> x.k == "img" && !NOIMG.containsMatchIn(x.nm) && (heicOk || !HEIC.containsMatchIn(x.nm)) }
                    if (!ok(r)) return false
                    val v = files.filter(ok)
                    val items = JSONArray()
                    for (m in v) items.put(JSONObject().put("name", m.nm).put("url", url(m.nm)).put("size", m.size))
                    ImageViewerActivity.pending = JSONObject().put("start", Math.max(0, v.indexOfFirst { it.nm == r.nm })).put("items", items).toString()
                    act.startActivity(Intent(act, ImageViewerActivity::class.java))
                }
                "pdf" -> {
                    PdfViewerActivity.pending = JSONObject().put("name", r.nm).put("url", url(r.nm)).put("size", r.size).put("key", key(r.nm, r.size)).toString()
                    act.startActivity(Intent(act, PdfViewerActivity::class.java))
                }
                else -> return false
            }
            return true
        } catch (_: Throwable) { return false }
    }

    /** Copy [vpath] of [dev] into cache/open and open it with another app ([chooser] = always show the app picker). */
    fun external(act: Activity, dev: String, vpath: String, name: String, chooser: Boolean = false) {
        Toast.makeText(act, "Opening $name\u2026", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val dir = File(act.cacheDir, "open").also { it.mkdirs() }
                dir.listFiles()?.forEach { try { it.delete() } catch (_: Throwable) {} }
                val f = File(dir, name.replace('/', '_'))
                Jobs.ep(dev).open(vpath).use { src ->
                    f.outputStream().use { o ->
                        val buf = ByteArray(256 * 1024)
                        while (true) { val n = src.read(buf, 0, buf.size); if (n < 0) break; o.write(buf, 0, n) }
                    }
                }
                val uri = FileProvider.getUriForFile(act, act.packageName + ".fileprovider", f)
                val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeFor(name)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                act.runOnUiThread {
                    try { act.startActivity(if (chooser) Intent.createChooser(i, name) else i) }
                    catch (_: ActivityNotFoundException) { Toast.makeText(act, "No app can open this file", Toast.LENGTH_LONG).show() }
                }
            } catch (e: Throwable) {
                act.runOnUiThread { Toast.makeText(act, "Cannot open: " + errText(e), Toast.LENGTH_LONG).show() }
            }
        }.also { it.isDaemon = true }.start()
    }
}
