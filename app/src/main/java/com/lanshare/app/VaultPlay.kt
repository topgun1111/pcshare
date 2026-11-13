package com.lanshare.app

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.lanshare.app.core.vault.VaultReader
import com.lanshare.app.core.vault.VaultSession
import java.io.IOException
import java.lang.ref.WeakReference

/**
 * Lockdown (lockdown.md, viewers via VaultStream): ExoPlayer DataSource over a vault item. URI = `lsvault://<nodeId>`.
 * Every open() makes its own [VaultReader] (random access, GCM tag checked per 64 KiB chunk), so seeking works and
 * NOTHING is written to disk (no cacheDir/vt file, no FileProvider, no MediaStore). Locked vault -> IOException.
 */
@UnstableApi
class VaultDataSource : BaseDataSource(false) {
    private var r: VaultReader? = null
    private var uri: Uri? = null
    private var left = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        val id = dataSpec.uri.host ?: throw IOException("bad vault uri")
        transferInitializing(dataSpec)
        val st = VaultSession.store?.takeIf { it.isOpen } ?: throw IOException("vault locked")
        val rd = st.open(id)
        try {
            if (dataSpec.position > rd.length) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            rd.seek(dataSpec.position)
            val avail = rd.length - dataSpec.position
            left = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, avail) else avail
        } catch (e: Throwable) { rd.close(); throw e }
        r = rd; uri = dataSpec.uri; opened = true
        transferStarted(dataSpec)
        return left
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (left == 0L) return C.RESULT_END_OF_INPUT
        val rd = r ?: throw IOException("closed")
        val n = try { rd.read(buffer, offset, minOf(left, length.toLong()).toInt()) } catch (e: IOException) { throw e } catch (e: Exception) { throw IOException(e) }
        if (n < 0) return C.RESULT_END_OF_INPUT
        left -= n; bytesTransferred(n); return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try { r?.close() } finally {
            r = null; uri = null
            if (opened) { opened = false; transferEnded() }
        }
    }
}

/**
 * Plays audio / video straight out of the vault. Extra "id" = vault node id (never a path). Plays the media siblings of the
 * same vault folder as a playlist. Not exported, FLAG_SECURE, pauses when the screen leaves, closes itself when the vault locks.
 */
@UnstableApi
class VaultPlayActivity : Activity() {
    private var player: ExoPlayer? = null
    private var registeredLock: (() -> Unit)? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        val st = VaultSession.store
        val node = intent.getStringExtra("id")?.let { st?.get(it) }
        if (st == null || node == null || node.dir) { finish(); return }
        val me = WeakReference(this)
        registeredLock = { me.get()?.let { a -> a.runOnUiThread { a.finish() } } }
        VaultSession.onLock(registeredLock!!)

        val sib = st.list(node.parent).filter { !it.dir && VaultViewActivity.isMedia(it.mime, it.name) }
        val items = sib.map { n ->
            MediaItem.Builder().setUri(Uri.parse("lsvault://" + n.id)).setMediaId(n.id)
                .setMimeType(mimeOf(n.mime, n.name)).build()
        }
        val start = sib.indexOfFirst { it.id == node.id }.coerceAtLeast(0)

        val factory = DataSource.Factory { VaultDataSource() }
        val p = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(factory)).setHandleAudioBecomingNoisy(true).build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                Toast.makeText(this@VaultPlayActivity, vt("Could not play this file"), Toast.LENGTH_SHORT).show(); finish()
            }
        })
        player = p
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(PlayerView(this).apply { this.player = p; setShowNextButton(items.size > 1); setShowPreviousButton(items.size > 1) }, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        p.setMediaItems(items, start, C.TIME_UNSET); p.prepare(); p.playWhenReady = true
    }

    override fun onResume() { super.onResume(); if (!VaultSession.unlocked) finish() }
    override fun onStop() { player?.pause(); super.onStop() }       // no background playback: audio would outlive the screen the vault guards
    override fun onDestroy() {
        registeredLock?.let { VaultSession.removeOnLock(it) }; registeredLock = null
        player?.release(); player = null
        super.onDestroy()
    }

    private fun mimeOf(mime: String, name: String): String? {
        if (mime.startsWith("video/") || mime.startsWith("audio/")) return mime
        return when (name.substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v" -> "video/mp4"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"; "3gp" -> "video/3gpp"; "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "aac" -> "audio/aac"; "ogg", "opus" -> "audio/ogg"; "wav" -> "audio/wav"; "flac" -> "audio/flac"
            else -> null
        }
    }
}
