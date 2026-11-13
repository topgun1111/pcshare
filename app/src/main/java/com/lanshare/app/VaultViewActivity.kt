package com.lanshare.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.ScaleGestureDetector
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.lanshare.app.core.vault.VaultSession
import java.lang.ref.WeakReference

/**
 * Lockdown (lockdown.md Part 3, viewers via VaultStream): opens ONE vault item (extra "id" = vault node id, never a path)
 * for viewing, decrypting in memory only (images, text). No temp file, no FileProvider, no share, text is not selectable
 * (nothing goes to the clipboard), FLAG_SECURE, closes itself when the vault locks. Everything else still goes through
 * VaultActivity's menu (export). Not exported; see AndroidManifest.
 */
class VaultViewActivity : Activity() {
    private var bmp: Bitmap? = null
    private var registeredLock: (() -> Unit)? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        val id = intent.getStringExtra("id")
        val st = VaultSession.store
        val node = if (id == null || st == null) null else st.get(id)
        if (node == null || node.dir) { finish(); return }
        title = node.name
        val me = WeakReference(this)
        registeredLock = { me.get()?.let { a -> a.runOnUiThread { a.finish() } } }
        VaultSession.onLock(registeredLock!!)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        val isText = !isImage(node.mime, node.name)
        Thread {
            try {
                val s = VaultSession.store ?: return@Thread
                if (isText) {
                    if (node.size > MAX_TEXT) throw IllegalStateException("too big")
                    val txt = s.open(node.id).use { String(it.readBytes(), Charsets.UTF_8) }
                    runOnUiThread { showText(root, txt) }
                } else {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    s.open(node.id).use { BitmapFactory.decodeStream(it, null, o) }
                    var sample = 1
                    while (o.outWidth / sample > MAX_PX || o.outHeight / sample > MAX_PX) sample *= 2
                    val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
                    val bm = s.open(node.id).use { BitmapFactory.decodeStream(it, null, o2) } ?: throw IllegalStateException("decode")
                    runOnUiThread { if (isFinishing) bm.recycle() else { bmp = bm; showImage(root, bm) } }
                }
            } catch (e: Throwable) {
                runOnUiThread { Toast.makeText(this, vt("Could not open this file"), Toast.LENGTH_SHORT).show(); finish() }
            }
        }.start()
    }

    private fun showText(root: FrameLayout, txt: String) {
        val tv = TextView(this).apply {
            text = txt; textSize = 14f; setTextColor(Color.WHITE); setPadding(32, 48, 32, 48)
            setTextIsSelectable(false); typeface = android.graphics.Typeface.MONOSPACE
        }
        root.addView(ScrollView(this).apply { addView(tv) })
    }

    private fun showImage(root: FrameLayout, bm: Bitmap) {
        val iv = ImageView(this).apply { setImageBitmap(bm); scaleType = ImageView.ScaleType.FIT_CENTER }
        var scale = 1f; var tx = 0f; var ty = 0f; var lx = 0f; var ly = 0f
        val sd = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                scale = (scale * d.scaleFactor).coerceIn(1f, 8f)
                if (scale == 1f) { tx = 0f; ty = 0f }
                iv.scaleX = scale; iv.scaleY = scale; iv.translationX = tx; iv.translationY = ty; return true
            }
        })
        iv.setOnTouchListener { _, e ->
            sd.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lx = e.x; ly = e.y }
                MotionEvent.ACTION_MOVE -> if (!sd.isInProgress && scale > 1f && e.pointerCount == 1) {
                    tx += e.x - lx; ty += e.y - ly; lx = e.x; ly = e.y
                    iv.translationX = tx; iv.translationY = ty
                }
            }
            true
        }
        root.addView(iv, FrameLayout.LayoutParams(-1, -1))
    }

    override fun onResume() { super.onResume(); if (!VaultSession.unlocked) finish() }
    override fun onDestroy() { registeredLock?.let { VaultSession.removeOnLock(it) }; registeredLock = null; bmp?.recycle(); bmp = null; super.onDestroy() }

    companion object {
        const val MAX_TEXT = 1_000_000L; const val MAX_PX = 2048
        /** mime may be empty for items added without one, so the extension decides too. */
        private val MEDIA_EXT = setOf("mp4", "m4v", "mkv", "webm", "3gp", "mov", "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac")
        fun isMedia(mime: String, name: String) =
            mime.startsWith("video/") || mime.startsWith("audio/") || name.substringAfterLast('.', "").lowercase() in MEDIA_EXT
        fun isPdf(mime: String, name: String) = mime == "application/pdf" || name.substringAfterLast('.', "").lowercase() == "pdf"
        fun isImage(mime: String, name: String) =
            (mime.startsWith("image/") && !mime.contains("svg")) || name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
    }
}
