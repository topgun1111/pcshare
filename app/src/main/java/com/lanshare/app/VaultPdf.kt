package com.lanshare.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lanshare.app.core.vault.VaultReader
import com.lanshare.app.core.vault.VaultSession
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/**
 * Lockdown (lockdown.md, viewers via VaultStream): PDF straight from the vault. Extra "id" = vault node id (never a path).
 * [PdfRenderer] reads through a proxy file descriptor backed by a [VaultReader]: no temp file, no page cache on disk, no share /
 * print / FileProvider. The normal PdfViewerActivity is NOT used on purpose (it keeps a decrypted page-image cache on disk).
 * Needs API 26 (openProxyFileDescriptor); VaultActivity keeps the export menu below that. Not exported, FLAG_SECURE, closes on lock.
 * Password-protected PDFs are refused by PdfRenderer (SecurityException) -> "Could not open this file".
 */
@RequiresApi(26)
class VaultPdfActivity : Activity() {
    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var ioThread: HandlerThread? = null
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "vault-pdf").also { it.isDaemon = true } }   // PdfRenderer: one open page at a time
    private var registeredLock: (() -> Unit)? = null
    @Volatile private var dead = false

    /** Seekable fd over the decrypted stream. Every callback runs on [ioThread] only (VaultReader is not thread-safe). */
    private class Cb(val r: VaultReader) : ProxyFileDescriptorCallback() {
        override fun onGetSize(): Long = r.length
        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
            try {
                if (offset >= r.length) return 0
                r.seek(offset)
                var got = 0
                while (got < size) { val c = r.read(data, got, size - got); if (c < 0) break; got += c }
                return got
            } catch (e: Throwable) { throw ErrnoException("read", OsConstants.EIO) }
        }
        override fun onRelease() { try { r.close() } catch (_: Throwable) {} }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        val st = VaultSession.store
        val node = intent.getStringExtra("id")?.let { st?.get(it) }
        if (st == null || node == null || node.dir) { finish(); return }
        title = node.name
        val me = WeakReference(this)
        registeredLock = { me.get()?.let { a -> a.runOnUiThread { a.finish() } } }
        VaultSession.onLock(registeredLock!!)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.DKGRAY) }
        setContentView(root)
        try {
            val t = HandlerThread("vault-pdf-io").also { it.start() }; ioThread = t
            val sm = getSystemService(StorageManager::class.java)
            val p = sm.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, Cb(st.open(node.id)), Handler(t.looper)); pfd = p
            exec.execute {
                try {
                    val r = PdfRenderer(p)
                    if (dead) { r.close(); return@execute }
                    renderer = r
                    runOnUiThread { if (!dead) show(root, r) }
                } catch (e: Throwable) { runOnUiThread { fail() } }
            }
        } catch (e: Throwable) { fail() }
    }

    private fun fail() {
        if (isFinishing) return
        Toast.makeText(this, vt("Could not open this file"), Toast.LENGTH_SHORT).show(); finish()
    }

    private fun show(root: FrameLayout, r: PdfRenderer) {
        val w = minOf(resources.displayMetrics.widthPixels, 1600)
        val rv = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@VaultPdfActivity); setItemViewCacheSize(1); clipToPadding = false; setPadding(0, 8, 0, 8) }
        val n = r.pageCount
        rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = n
            override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
                val iv = ImageView(p.context).apply {
                    setBackgroundColor(Color.WHITE); scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = true
                    layoutParams = RecyclerView.LayoutParams(-1, (w * 1.414f).toInt()).also { it.bottomMargin = 8 }
                }
                return object : RecyclerView.ViewHolder(iv) {}
            }
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                val iv = h.itemView as ImageView; iv.tag = pos; iv.setImageBitmap(null)
                exec.execute {
                    if (dead) return@execute
                    val bm = try {
                        val rr = renderer ?: return@execute
                        rr.openPage(pos).use { pg ->
                            val hh = (w.toFloat() * pg.height / pg.width).toInt().coerceIn(1, 4000)
                            Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.WHITE); pg.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                        }
                    } catch (e: Throwable) { null }
                    runOnUiThread {
                        if (!dead && bm != null && iv.tag == pos) {
                            iv.layoutParams = iv.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
                            iv.setImageBitmap(bm)
                        } else bm?.recycle()
                    }
                }
            }
            override fun onViewRecycled(h: RecyclerView.ViewHolder) { (h.itemView as ImageView).apply { tag = -1; setImageBitmap(null) } }
        }
        root.addView(rv, FrameLayout.LayoutParams(-1, -1))
    }

    override fun onResume() { super.onResume(); if (!VaultSession.unlocked) finish() }

    override fun onDestroy() {
        dead = true
        registeredLock?.let { VaultSession.removeOnLock(it) }; registeredLock = null
        val r = renderer; val p = pfd; val t = ioThread
        renderer = null; pfd = null; ioThread = null
        exec.execute { try { r?.close() } catch (_: Throwable) {}; try { p?.close() } catch (_: Throwable) {}; t?.quitSafely() }   // after the last render, same thread
        exec.shutdown()
        super.onDestroy()
    }
}
