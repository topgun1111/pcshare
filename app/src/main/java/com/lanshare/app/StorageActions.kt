package com.lanshare.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import com.lanshare.app.core.Core
import com.lanshare.app.core.SNode
import com.lanshare.app.core.errText
import com.lanshare.app.core.vdir
import com.lanshare.app.core.vjoin
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Storage Analyzer, part 8a: the non-destructive actions on a node of the scan tree (open, share, show in the file manager).
 * Delete (via Bin) and patching the tree afterwards live in StorageActivity (parts 8b + 8c) and StorageScan.removeFile.
 *
 * A node path is relative to the scan root; for a folder scan the screen's own virtual path has to be put in front
 * ([virt]), then [Core.local] turns it into the real file. Everything here runs on the main thread and only starts intents.
 */
object StorageActions {
    /** Virtual path (as the file manager uses it) of [n] inside a scan that was started at [scanPath]. The "small files" node gives its folder. */
    fun virt(scanPath: String, n: SNode): String = vjoin(scanPath, n.path())

    /** Virtual path of the folder the file manager should show for [n]: the node itself for a folder / "small files", the parent folder for a file. */
    fun folderOf(scanPath: String, n: SNode): String {
        val v = virt(scanPath, n)
        return if (n.dir || n.agg) v else vdir(v)
    }

    /** Real file of [n], or null (with a toast) when it cannot be resolved or is gone since the scan. */
    fun realOrToast(a: Activity, scanPath: String, n: SNode, base: File? = null): File? {
        // part 9b: [base] = root of another volume (SD card): the node path is simply below it
        val f = try { if (base != null) File(base, n.path().trimStart('/')) else Core.local.real(virt(scanPath, n)) } catch (e: Throwable) { toast(a, "Cannot find it: " + errText(e)); return null }
        if (!f.exists()) { toast(a, "It no longer exists (deleted or moved since the scan)"); return null }
        return f
    }

    private val IMG = setOf("jpg", "jpeg", "png", "webp", "bmp", "heic", "heif")
    private val VID = setOf("mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v", "mpg", "mpeg", "flv", "ogv", "m2ts", "mts")
    private val OFFICE = setOf("doc", "docx", "odt", "xls", "xlsx", "ods", "pptx", "odp")
    private val ED by lazy { TEXT_EXT + setOf("txt", "md", "log", "jsonc", "twig", "tpl", "text", "jsonl", "ndjson", "ass", "ssa", "diff", "patch",
        "swift", "dart", "lua", "pl", "r", "scala", "vue", "svelte", "graphql", "proto", "rst", "ini") }

    /**
     * Open with LANShare's own viewers when it supports the format (video player, picture viewer, PDF reader, Office viewer, text editor),
     * exactly like a tap in the file manager. [v] = the file's virtual path (null for another volume, which the file manager cannot address).
     * Returns false when the file is not a supported kind (the caller then hands it to another app).
     */
    private fun openNative(a: Activity, f: File, v: String?, line: Int = 0): Boolean {
        if (v == null) return false
        val ext = f.extension.lowercase()
        val origin = Core.url
        fun enc(x: String) = java.net.URLEncoder.encode(x, "UTF-8").replace("+", "%20")
        fun url(vp: String) = origin + "/api/dl?dev=local&path=" + enc(vp)
        try {
            when {
                ext in TEXT_EXT || ext in ED -> {
                    if (ext == "svg" || f.length() > 8L shl 20) return false
                    a.startActivity(TextEditorActivity.intent(a, JSONObject().put("dev", "local").put("path", v).put("line", line).toString())); return true
                }
                ext in OFFICE -> {
                    if (f.length() > 60L shl 20) return false
                    val d = File(a.cacheDir, "office").apply { mkdirs() }
                    val c = File(d, f.name)
                    Thread {
                        try {
                            f.copyTo(c, overwrite = true)
                            a.runOnUiThread { a.startActivity(Intent(a, OfficeViewerActivity::class.java).putExtra("path", c.absolutePath).putExtra("dev", "local").putExtra("rpath", v)) }
                        } catch (e: Throwable) { a.runOnUiThread { toast(a, "Cannot open: " + errText(e)) } }
                    }.start()
                    return true
                }
                origin == null -> return false
                ext == "pdf" -> {
                    PdfViewerActivity.pending = JSONObject().put("name", f.name).put("url", url(v)).put("size", f.length())
                        .put("key", "local|" + v + "|" + f.length()).toString()
                    a.startActivity(Intent(a, PdfViewerActivity::class.java)); return true
                }
                ext in IMG -> {
                    if (ext in setOf("heic", "heif") && android.os.Build.VERSION.SDK_INT < 28) return false
                    val dir = vdir(v)
                    val sib = (f.parentFile?.listFiles { x -> x.isFile && x.extension.lowercase() in IMG } ?: arrayOf(f)).sortedBy { it.name.lowercase() }
                    val items = JSONArray()
                    for (x in sib) items.put(JSONObject().put("name", x.name).put("url", url(vjoin(dir, x.name))).put("size", x.length()).put("mtime", x.lastModified()))
                    ImageViewerActivity.pending = JSONObject().put("start", Math.max(0, sib.indexOfFirst { it.name == f.name })).put("items", items).toString()
                    a.startActivity(Intent(a, ImageViewerActivity::class.java)); return true
                }
                ext in VID -> {
                    val dir = vdir(v)
                    val sib = (f.parentFile?.listFiles { x -> x.isFile && x.extension.lowercase() in VID } ?: arrayOf(f)).sortedBy { it.name.lowercase() }
                    val items = JSONArray()
                    for (x in sib) items.put(JSONObject().put("name", x.name).put("url", url(vjoin(dir, x.name)))
                        .put("key", "local|" + vjoin(dir, x.name) + "|" + x.length()).put("subs", JSONArray()).put("mtime", x.lastModified()).put("size", x.length()))
                    PlayerActivity.pending = JSONObject().put("start", Math.max(0, sib.indexOfFirst { it.name == f.name })).put("items", items).toString()
                    a.startActivity(Intent(a, PlayerActivity::class.java)); return true
                }
            }
        } catch (_: Throwable) { }
        return false
    }

    /** Open a file with an installed app (source / text files go to a text editor, like in the file manager); [pick] = always ask which app. */
    fun open(a: Activity, f: File, pick: Boolean = false, virtPath: String? = null, line: Int = 0) {
        if (!f.isFile) { toast(a, "Only files can be opened"); return }
        if (!pick && openNative(a, f, virtPath, line)) return
        val ext = f.extension.lowercase()
        val mime = if (ext in TEXT_EXT || f.name.startsWith(".")) "text/plain"
                   else MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        val uri = try { FileProvider.getUriForFile(a, a.packageName + ".fileprovider", f) }
                  catch (_: IllegalArgumentException) { toast(a, "Cannot open this file from here"); return }
        fun go(m: String) {
            val v = Intent(Intent.ACTION_VIEW).setDataAndType(uri, m).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            a.startActivity(if (pick) Intent.createChooser(v, "Open with").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) else v)
        }
        try { go(mime) }
        catch (e: ActivityNotFoundException) {
            try { if (mime != "*/*") go("*/*") else throw e }
            catch (_: Exception) { toast(a, "No app can open ." + ext) }
        } catch (e: Exception) { toast(a, "Cannot open: " + errText(e)) }
    }

    /** System share sheet for one file (no copy: the FileProvider serves it straight from the storage). */
    fun share(a: Activity, f: File) {
        if (!f.isFile) { toast(a, "Only files can be shared"); return }
        try {
            val uri = FileProvider.getUriForFile(a, a.packageName + ".fileprovider", f)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
            val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri("", uri)
            a.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: IllegalArgumentException) { toast(a, "Cannot share files outside the main storage from here")
        } catch (e: Exception) { toast(a, "Cannot share: " + errText(e)) }
    }

    /** Bring the file manager (MainActivity) to the front and open folder [vfolder] (virtual path) in it. The analyzer screen on top of it is closed. */
    fun reveal(a: Activity, vfolder: String) {
        try {
            a.startActivity(Intent(a, MainActivity::class.java).setAction(MainActivity.ACTION_SHOW_PATH).putExtra(MainActivity.X_SHOW_PATH, vfolder)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (e: Exception) { toast(a, "Cannot open the file manager: " + errText(e)) }
    }

    private fun toast(a: Activity, m: String) = Toast.makeText(a, m, Toast.LENGTH_LONG).show()

    // same list as MainActivity.TEXT_EXT (kept separate: that one is private to the file manager)
    private val TEXT_EXT = setOf("php", "phtml", "js", "mjs", "ts", "tsx", "jsx", "css", "scss", "json", "xml", "yml", "yaml", "toml",
        "ini", "cfg", "conf", "env", "md", "log", "sql", "py", "kt", "kts", "java", "c", "h", "cpp", "hpp", "cs", "go", "rs", "rb",
        "sh", "bat", "ps1", "gradle", "properties", "htaccess", "gitignore", "csv", "tsv", "srt", "vtt", "tex", "txt", "html", "htm", "svg")
}
