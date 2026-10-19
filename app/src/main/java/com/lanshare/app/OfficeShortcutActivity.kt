package com.lanshare.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.lanshare.app.core.Core
import com.lanshare.app.core.Jobs
import com.lanshare.app.core.office.DocxWriter
import com.lanshare.app.core.office.XlsxWriter
import com.lanshare.app.core.vjoin
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Invisible launcher-shortcut target for office files (no screen of its own; theme Clear).
 *  - [ACT_NEW] + extra `kind` = docx | xlsx: creates an empty file with a free name in the local folder [NEW_DIR] and opens it in [OfficeViewerActivity].
 *  - [ACT_OPEN] + extras `dev`, `rpath`, `name`: copies that file (any device / share) into cache/office and opens it in the viewer.
 * Recent files are remembered by [Recent] (viewer calls it when a file was opened from the file list).
 */
class OfficeShortcutActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val i = intent
        try { TextSend.ensureRunning(this) } catch (_: Throwable) {}   // process was killed: bring Core back
        Thread {
            try {
                waitCore()
                val dev: String; val rpath: String
                if (i?.action == ACT_NEW) {
                    val kind = if (i.getStringExtra("kind") == "xlsx") "xlsx" else "docx"
                    dev = "local"; val ep = Jobs.ep(dev)
                    try { ep.mkdir(NEW_DIR) } catch (_: Throwable) {}
                    val taken = ep.names(NEW_DIR).map { it.lowercase() }.toSet()
                    val base = if (kind == "xlsx") "New workbook" else "New document"
                    var nm = "$base.$kind"; var n = 2
                    while (nm.lowercase() in taken) nm = "$base ($n).$kind".also { n++ }
                    val tmp = File(cacheDir, "new-$nm")
                    if (kind == "xlsx") XlsxWriter.createEmpty(tmp) else DocxWriter.createEmpty(tmp)
                    rpath = vjoin(NEW_DIR, nm)
                    try { tmp.inputStream().use { ep.write(rpath, it, tmp.length()) } } finally { tmp.delete() }
                } else if (i?.action == ACT_OPEN) {
                    dev = i.getStringExtra("dev").orEmpty(); rpath = i.getStringExtra("rpath").orEmpty()
                    if (dev.isEmpty() || rpath.isEmpty()) throw IllegalArgumentException("nothing to open")
                } else { runOnUiThread { finish() }; return@Thread }
                val nm = rpath.substringAfterLast('/').replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val d = File(cacheDir, "office").apply { mkdirs() }
                val f = File(d, nm)
                Jobs.ep(dev).open(rpath).use { src -> f.outputStream().use { o ->
                    var t = 0L; val buf = ByteArray(65536)
                    while (true) { val r = src.read(buf); if (r < 0) break; t += r; if (t > 60L shl 20) throw java.io.IOException("file is larger than 60 MB"); o.write(buf, 0, r) }
                } }
                runOnUiThread {
                    startActivity(Intent(this, OfficeViewerActivity::class.java).putExtra("path", f.absolutePath).putExtra("dev", dev).putExtra("rpath", rpath))
                    finish()
                }
            } catch (e: Throwable) {
                runOnUiThread { Toast.makeText(this, "Cannot open: " + (e.message ?: "error"), Toast.LENGTH_LONG).show(); finish() }
            }
        }.also { it.isDaemon = true }.start()
    }

    /** Core is started by LanShareService on its own thread: wait (max 6 s) until the local file system exists. */
    private fun waitCore() {
        for (n in 0 until 60) { try { Core.local; return } catch (_: Throwable) { Thread.sleep(100) } }
    }

    /** The last few office files opened from the file list ([dev], [path] virtual, [name]); newest first, max [MAX]. */
    object Recent {
        const val MAX = 3
        private const val PREF = "office_recent"
        class Item(val dev: String, val path: String, val name: String)

        @Synchronized fun list(c: Context): List<Item> = try {
            val a = JSONArray(c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("l", "[]"))
            (0 until a.length()).map { val o = a.getJSONObject(it); Item(o.getString("d"), o.getString("p"), o.getString("n")) }
        } catch (_: Throwable) { emptyList() }

        @Synchronized fun add(c: Context, dev: String, path: String, name: String) {
            try {
                val l = listOf(Item(dev, path, name)) + list(c).filter { !(it.dev == dev && it.path == path) }
                val a = JSONArray(); for (x in l.take(MAX)) a.put(JSONObject().put("d", x.dev).put("p", x.path).put("n", x.name))
                c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("l", a.toString()).apply()
            } catch (_: Throwable) {}
            val app = c.applicationContext
            Thread { Shortcuts.sync(app) }.also { it.isDaemon = true }.start()   // key changed: republish
        }
    }

    companion object {
        const val ACT_NEW = "com.lanshare.app.OFFICE_NEW"
        const val ACT_OPEN = "com.lanshare.app.OFFICE_OPEN"
        /** Local folder (virtual path, relative to phone storage) for files created from the launcher shortcuts. */
        const val NEW_DIR = "/Documents"
    }
}
