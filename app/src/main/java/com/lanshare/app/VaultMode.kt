package com.lanshare.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.WindowManager
import com.lanshare.app.core.vault.VaultNode
import com.lanshare.app.core.vault.VaultReader
import com.lanshare.app.core.vault.VaultSession
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/**
 * Lockdown: the app's OWN photo viewer / editor and video player run on vault items ("vault mode"). Items travel as
 * `lsvault://<nodeId>` (never a path) in the same JSON the viewers already take, plus `"vault": true`. In vault mode the viewers
 * decrypt in memory (VaultReader / VaultDataSource), set FLAG_SECURE, close when the vault locks, and switch off everything that
 * would copy plaintext out (share, print space, PiP, disk text cache, HTTP details, rotate-save).
 */
object VaultMode {
    const val SCHEME = "lsvault://"
    /** Set by a viewer / editor after it changed the vault (delete, edit-save); VaultActivity re-lists on resume. */
    @Volatile var dirty = false

    fun ext(n: String) = n.substringAfterLast('.', "").lowercase()
    private val AUDIO = setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac")
    private val SUBS = setOf("srt", "vtt", "ass", "ssa")
    fun isAudio(mime: String, name: String) = mime.startsWith("audio/") || ext(name) in AUDIO
    fun isVideo(mime: String, name: String) = VaultViewActivity.isMedia(mime, name) && !isAudio(mime, name)
    fun idOf(url: String) = url.removePrefix(SCHEME)

    fun mime(name: String): String? = when (ext(name)) {
        "mp4", "m4v" -> "video/mp4"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"; "3gp" -> "video/3gpp"; "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "aac" -> "audio/aac"; "ogg", "opus" -> "audio/ogg"; "wav" -> "audio/wav"; "flac" -> "audio/flac"
        else -> null
    }

    private fun item(n: VaultNode) = JSONObject().put("name", n.name).put("url", SCHEME + n.id).put("key", "lsvault:" + n.id)
        .put("size", n.size).put("mtime", n.mtime / 1000)

    fun imagesJson(list: List<VaultNode>, start: Int): String {
        val a = JSONArray(); list.forEach { a.put(item(it)) }
        return JSONObject().put("vault", true).put("start", start.coerceAtLeast(0)).put("items", a).toString()
    }

    /** [all] = every node of the folder (to find subtitle files with the same name start), [videos] = the playlist. */
    fun videosJson(all: List<VaultNode>, videos: List<VaultNode>, start: Int): String {
        val a = JSONArray()
        for (v in videos) {
            val o = item(v); val stem = v.name.substringBeforeLast('.', v.name).lowercase()
            val subs = JSONArray()
            all.filter { !it.dir && ext(it.name) in SUBS && it.name.lowercase().startsWith(stem) }.take(8)
                .forEach { subs.put(JSONObject().put("name", it.name).put("url", SCHEME + it.id)) }
            if (subs.length() > 0) o.put("subs", subs)
            a.put(o)
        }
        return JSONObject().put("vault", true).put("start", start.coerceAtLeast(0)).put("items", a).toString()
    }

    private val TEXT_EXT = setOf("txt", "md", "json", "csv", "log", "xml", "ini", "yml", "yaml")

    /**
     * File manager vault mode (part 3): a tap on [name] inside virtual vault folder [dir]. [order] = the folder's file names in the order the
     * file manager shows them (swipe / playlist order). Returns "ok" (+ the Intent to start: the app's own photo viewer / video player in vault
     * mode, VaultPlayActivity, VaultPdfActivity, VaultViewActivity), "locked", "missing" or "other" (no vault viewer for this type: part 4).
     */
    fun route(ctx: android.content.Context, dir: String, name: String, order: List<String>): Pair<String, android.content.Intent?> {
        val kids = try { com.lanshare.app.core.VaultFs().childrenOf(dir) } catch (_: Throwable) { return "locked" to null }
        val files = kids.filter { !it.dir }
        val n = files.firstOrNull { it.name == name } ?: return "missing" to null
        fun seq(pred: (VaultNode) -> Boolean): List<VaultNode> {
            val by = files.associateBy { it.name }
            val l = order.mapNotNull { by[it] }.filter(pred).distinct()
            return if (l.any { it.id == n.id }) l else files.filter(pred)
        }
        fun go(c: Class<*>) = android.content.Intent(ctx, c)
        return when {
            VaultViewActivity.isImage(n.mime, n.name) -> {
                val l = seq { VaultViewActivity.isImage(it.mime, it.name) }
                ImageViewerActivity.pending = imagesJson(l, l.indexOfFirst { it.id == n.id }); "ok" to go(ImageViewerActivity::class.java)
            }
            isVideo(n.mime, n.name) -> {
                val l = seq { isVideo(it.mime, it.name) }
                PlayerActivity.pending = videosJson(files, l, l.indexOfFirst { it.id == n.id }); "ok" to go(PlayerActivity::class.java)
            }
            isAudio(n.mime, n.name) -> "ok" to go(VaultPlayActivity::class.java).putExtra("id", n.id)
            android.os.Build.VERSION.SDK_INT >= 26 && VaultViewActivity.isPdf(n.mime, n.name) -> "ok" to go(VaultPdfActivity::class.java).putExtra("id", n.id)
            (n.mime.startsWith("text/") || ext(n.name) in TEXT_EXT) && n.size <= VaultViewActivity.MAX_TEXT -> "ok" to go(VaultTextActivity::class.java).putExtra("id", n.id)   // editable, saves back into the vault
            ext(n.name) in VaultOfficeActivity.EXT && n.size <= VaultOfficeActivity.MAX_BYTES -> "ok" to go(VaultOfficeActivity::class.java).putExtra("id", n.id)   // read-only
            else -> "other" to null
        }
    }

    fun secure(a: Activity) = a.window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

    /** Finishes [a] when the vault locks. The caller passes the result to [VaultSession.removeOnLock] in onDestroy. */
    fun bindLock(a: Activity): () -> Unit {
        val me = WeakReference(a)
        val l: () -> Unit = { me.get()?.let { x -> x.runOnUiThread { x.finish() } } }
        VaultSession.onLock(l); return l
    }
}

/** In-memory thumbnails for the vault gallery (pictures: downsampled decode, videos: first frame). Wiped when the vault locks. Nothing touches the disk. */
object VaultThumbs {
    private val cache = object : LruCache<String, Bitmap>(24 shl 20) { override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount }
    private val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "vault-thumb").also { it.isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var hooked = false

    private fun hook() { if (!hooked) { hooked = true; VaultSession.onLock { cache.evictAll() } } }
    fun get(id: String): Bitmap? = cache.get(id)

    /** Blocking variant for the file manager's own thumbnail pool (vault mode). RAM only; the caller's memory cache is dropped on lock (NativeList.dropVaultThumbs). */
    fun make(n: VaultNode, px: Int): Bitmap? {
        hook()
        if (!VaultSession.unlocked) return null
        val bm = try { if (VaultMode.isVideo(n.mime, n.name)) video(n.id, px) else image(n.id, px) } catch (_: Throwable) { null }
        if (!VaultSession.unlocked) { bm?.recycle(); return null }
        return bm
    }

    /** [done] runs on the main thread (null = no thumbnail). Dropped silently when the vault locked meanwhile. */
    fun load(n: VaultNode, px: Int, done: (Bitmap?) -> Unit) {
        hook()
        pool.execute {
            val bm = try { if (VaultMode.isVideo(n.mime, n.name)) video(n.id, px) else image(n.id, px) } catch (_: Throwable) { null }
            main.post { if (!VaultSession.unlocked) { bm?.recycle(); return@post }; if (bm != null) cache.put(n.id, bm); done(bm) }
        }
    }

    private fun image(id: String, px: Int): Bitmap? {
        val st = VaultSession.store ?: return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        st.open(id).use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= px && o.outHeight / (s * 2) >= px) s *= 2
        val bm = st.open(id).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) } ?: return null
        val ori = try { st.open(id).use { androidx.exifinterface.media.ExifInterface(it).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1) } } catch (_: Exception) { 1 }
        val deg = when (ori) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
        if (deg == 0f) return bm
        val r = Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, android.graphics.Matrix().apply { setRotate(deg) }, true)
        if (r !== bm) bm.recycle(); return r
    }

    private fun video(id: String, px: Int): Bitmap? {
        val st = VaultSession.store ?: return null
        val rd: VaultReader = st.open(id)
        val src = object : MediaDataSource() {
            @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                if (position >= rd.length) return -1
                rd.seek(position); var got = 0
                while (got < size) { val c = rd.read(buffer, offset + got, size - got); if (c < 0) break; got += c }
                return if (got == 0) -1 else got
            }
            override fun getSize(): Long = rd.length
            override fun close() { try { rd.close() } catch (_: Throwable) {} }
        }
        val m = MediaMetadataRetriever()
        try {
            m.setDataSource(src)
            val f = m.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: m.getFrameAtTime(0) ?: return null
            val k = px.toFloat() / maxOf(f.width, f.height)
            if (k >= 1f) return f
            val r = Bitmap.createScaledBitmap(f, maxOf(1, (f.width * k).toInt()), maxOf(1, (f.height * k).toInt()), true)
            if (r !== f) f.recycle(); return r
        } finally { try { m.release() } catch (_: Throwable) {}; src.close() }
    }
}
