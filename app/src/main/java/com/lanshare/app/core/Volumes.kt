package com.lanshare.app.core

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.File

/**
 * Storage Analyzer, part 9b: the mounted storage volumes OTHER than the main one (SD card, USB stick), so the analyzer can scan them too.
 * The main volume is [Core.local]'s root and is not listed. Everything is best effort (a failure = empty list). NOT compiled / NOT device-tested.
 */
object Volumes {
    class Vol(val path: String, val name: String, val removable: Boolean)

    fun extra(ctx: Context): List<Vol> {
        val out = ArrayList<Vol>()
        val main = try { Core.local.root.canonicalPath } catch (_: Throwable) { try { Environment.getExternalStorageDirectory().canonicalPath } catch (_: Throwable) { "" } }
        try {
            val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            for (v in sm.storageVolumes) {
                if (v.isPrimary) continue
                val st = v.state
                if (st != Environment.MEDIA_MOUNTED && st != Environment.MEDIA_MOUNTED_READ_ONLY) continue
                val dir = dirOf(v) ?: continue
                if (!dir.isDirectory || dir.canonicalPath == main) continue
                val name = (try { v.getDescription(ctx) } catch (_: Throwable) { null }) ?: dir.name
                out.add(Vol(dir.path, name, v.isRemovable))
            }
        } catch (_: Throwable) {}
        if (out.isEmpty() && Build.VERSION.SDK_INT < 30) {
            // fallback when the hidden getPath() is refused: the app's own folder on a removable volume is <volume>/Android/data/<package>/files
            try {
                for (d in ctx.getExternalFilesDirs(null)) {
                    if (d == null) continue
                    val i = d.path.indexOf("/Android/data")
                    if (i <= 0) continue
                    val root = File(d.path.substring(0, i))
                    if (root.canonicalPath == main || out.any { it.path == root.path }) continue
                    out.add(Vol(root.path, root.name, true))
                }
            } catch (_: Throwable) {}
        }
        return out
    }

    private fun dirOf(v: StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= 30) return v.directory
        return try { File(v.javaClass.getMethod("getPath").invoke(v) as String) } catch (_: Throwable) { null }
    }
}
