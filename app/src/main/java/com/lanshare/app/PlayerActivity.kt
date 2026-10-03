package com.lanshare.app

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Rational
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Dedicated video player (Media3 / ExoPlayer). The file browser hands it the videos of the open folder as URLs of the app's own
 * local HTTP server (/api/dl), which already supports Range requests, so local files, other devices and SMB shares all stream
 * the same way - nothing is downloaded first.
 *
 * Plays MKV / AVI / MOV / MP4 / WebM / TS ... with audio-track and subtitle selection, speed, playlist (next / previous),
 * resume position, Picture-in-Picture, double-tap seek (+-10 s), swipe for brightness (left) and volume (right).
 * Subtitle files next to a video (same name: .srt .vtt .ass .ssa) are picked up automatically; their text is converted to UTF-8
 * first (old Turkish files are windows-1254), so ğ ş ı İ ö ü ç show correctly.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : Activity() {
    companion object {
        /** JSON handed over by MainActivity.Bridge.play(): {start, items:[{name, url, key, subs:[{name, url}]}]} */
        @Volatile var pending: String? = null
        private const val PREFS = "ls_player"
        private const val MAX_ITEMS = 500
    }

    private class Sub(val name: String, val url: String)
    private class Item(val name: String, val url: String, val key: String, val subs: List<Sub>)

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var view: PlayerView
    private lateinit var top: LinearLayout
    private lateinit var titleTv: TextView
    private lateinit var hud: TextView
    private lateinit var errTv: TextView
    private lateinit var spin: ProgressBar
    private var player: ExoPlayer? = null
    private var items: List<Item> = emptyList()
    private var startIdx = 0
    private var gen = 0                  // bumped on every (re)start: a late subtitle-preparation thread is then ignored
    private var inPip = false
    private var userRotated = false
    private var resizeIdx = 0
    private var gMode = 0                // swipe gesture: 0 undecided, -1 ignored, 1 brightness, 2 volume
    private var gB0 = 0.5f
    private var gV0 = 0

    private val saver = object : Runnable {
        override fun run() { savePos(); ui.postDelayed(this, 5000) }
    }
    private val hideHud = Runnable { hud.visibility = View.GONE }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 28)
            window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        buildUi()
        begin()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        begin()   // another video was chosen while the player (or its PiP window) is still open
    }

    // ---------------------------------------------------------------- UI (built in code: no layout resources needed)
    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        view = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            useController = true
            controllerShowTimeoutMs = 3000
            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowSubtitleButton(true)
            setShowNextButton(true)
            setShowPreviousButton(true)
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { vis ->
                this@PlayerActivity.top.visibility = if (inPip) View.GONE else if (vis == View.VISIBLE) View.VISIBLE else View.GONE
            })
        }
        root.addView(view)

        fun tv(txt: String, sp: Float = 20f) = TextView(this).apply {
            text = txt; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            gravity = Gravity.CENTER; setPadding(dp(12), dp(8), dp(12), dp(8)); minWidth = dp(44)
        }
        top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xB3000000.toInt(), 0x00000000))
            setPadding(dp(8), dp(8), dp(8), dp(16))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
            visibility = View.GONE
        }
        val back = tv("\u2190", 24f).apply { setOnClickListener { finish() } }
        titleTv = tv("", 16f).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.START; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        val aspect = tv("Fit", 14f).apply {
            setOnClickListener {
                val modes = intArrayOf(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT, androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM, androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL)
                val names = arrayOf("Fit", "Zoom", "Stretch")
                resizeIdx = (resizeIdx + 1) % modes.size
                view.resizeMode = modes[resizeIdx]
                text = names[resizeIdx]
            }
        }
        val rot = tv("\u27F3", 22f).apply {
            setOnClickListener {
                userRotated = true
                requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }
        top.addView(back)
        top.addView(titleTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(aspect)
        top.addView(rot)
        if (Build.VERSION.SDK_INT >= 26) {
            top.addView(tv("\u29C9", 20f).apply { setOnClickListener { enterPip() } })
        }
        ViewCompat.setOnApplyWindowInsetsListener(top) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(dp(8) + b.left, dp(8) + b.top, dp(8) + b.right, dp(16))
            insets
        }
        root.addView(top)

        hud = TextView(this).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10)); visibility = View.GONE
            background = GradientDrawable().apply { setColor(0xAA000000.toInt()); cornerRadius = dp(20).toFloat() }
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        }
        root.addView(hud)

        errTv = TextView(this).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); gravity = Gravity.CENTER
            setPadding(dp(24), dp(16), dp(24), dp(16)); visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        }
        root.addView(errTv)

        spin = ProgressBar(this).apply {
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER)
        }
        root.addView(spin)

        setupGestures()
        setContentView(root)
    }

    private fun showHud(t: String) {
        hud.text = t; hud.visibility = View.VISIBLE
        ui.removeCallbacks(hideHud); ui.postDelayed(hideHud, 700)
    }

    /** Tap = show/hide controls, double-tap left/right = -10 s / +10 s, double-tap middle = pause, swipe up/down: left half brightness, right half volume. */
    private fun setupGestures() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (view.isControllerFullyVisible) view.hideController() else view.showController()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val p = player ?: return true
                val f = e.x / maxOf(view.width, 1)
                if (f < 0.33f) { p.seekTo(maxOf(0L, p.currentPosition - 10_000)); showHud("-10 s") }
                else if (f > 0.67f) { p.seekTo(p.currentPosition + 10_000); showHud("+10 s") }
                else if (p.isPlaying) p.pause() else p.play()
                return true
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                val s = e1 ?: return false
                val ady = Math.abs(e2.y - s.y)
                val adx = Math.abs(e2.x - s.x)
                if (gMode == 0) {
                    if (ady > dp(12) && ady > adx) {
                        gMode = if (s.x < view.width / 2f) 1 else 2
                        val cur = window.attributes.screenBrightness
                        gB0 = if (cur >= 0f) cur else try { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f } catch (_: Exception) { 0.5f }
                        gV0 = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    } else if (adx > dp(12)) gMode = -1
                }
                if (gMode <= 0) return false
                val d = (s.y - e2.y) / maxOf(view.height, 1) * 1.3f
                if (gMode == 1) {
                    val nb = (gB0 + d).coerceIn(0.02f, 1f)
                    window.attributes = window.attributes.apply { screenBrightness = nb }
                    showHud("Brightness " + Math.round(nb * 100) + "%")
                } else {
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val nv = Math.round(gV0 + d * max).coerceIn(0, max)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, nv, 0)
                    showHud("Volume $nv/$max")
                }
                return true
            }
        })
        view.setOnTouchListener { _, e ->
            gd.onTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) gMode = 0
            true
        }
    }

    // ---------------------------------------------------------------- start / playlist
    private fun parse(json: String): Pair<List<Item>, Int>? {
        val o = JSONObject(json)
        val arr = o.getJSONArray("items")
        val out = ArrayList<Item>()
        fun local(u: String) = u.startsWith("http://127.0.0.1") || u.startsWith("http://localhost")   // only the app's own server
        for (i in 0 until minOf(arr.length(), MAX_ITEMS)) {
            val it = arr.getJSONObject(i)
            val url = it.getString("url")
            if (!local(url)) continue
            val subs = ArrayList<Sub>()
            val sa = it.optJSONArray("subs")
            if (sa != null) for (j in 0 until minOf(sa.length(), 8)) {
                val s = sa.getJSONObject(j)
                if (local(s.getString("url"))) subs.add(Sub(s.optString("name"), s.getString("url")))
            }
            out.add(Item(it.optString("name"), url, it.optString("key", url), subs))
        }
        val st = o.optInt("start", 0).coerceIn(0, maxOf(out.size - 1, 0))
        return out to st
    }

    private fun begin() {
        val json = pending
        pending = null
        val parsed = try { if (json == null) null else parse(json) } catch (_: Exception) { null }
        if (parsed == null || parsed.first.isEmpty()) { finish(); return }
        releasePlayer()
        items = parsed.first
        startIdx = parsed.second
        val my = ++gen
        errTv.visibility = View.GONE
        if (items.none { it.subs.isNotEmpty() }) { start(emptyMap()); return }
        spin.visibility = View.VISIBLE
        val list = items
        Thread({
            val got = HashMap<String, Uri>()   // subtitle url -> local UTF-8 copy
            val end = System.currentTimeMillis() + 10_000
            File(cacheDir, "subs").deleteRecursively()
            outer@ for (item in list) {
                for (s in item.subs) {
                    if (System.currentTimeMillis() > end) break@outer
                    fetchSub(s, got.size)?.let { got[s.url] = it }
                }
            }
            ui.post { if (my == gen && !isFinishing) start(got) }
        }, "player-subs").also { it.isDaemon = true }.start()
    }

    private fun fetchSub(s: Sub, n: Int): Uri? = try {
        val c = URL(s.url).openConnection() as HttpURLConnection
        c.connectTimeout = 3000; c.readTimeout = 5000
        val raw = c.inputStream.use { it.readBytes() }
        if (raw.size > 3_000_000) null else {
            val dir = File(cacheDir, "subs").apply { mkdirs() }
            val f = File(dir, "s$n." + s.name.substringAfterLast('.', "srt").lowercase())
            f.writeText(decode(raw), Charsets.UTF_8)
            Uri.fromFile(f)
        }
    } catch (_: Exception) { null }

    /** UTF-8 (with or without BOM), UTF-16 with BOM, otherwise the Turkish Windows code page. */
    private fun decode(b: ByteArray): String {
        if (b.size >= 2 && ((b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) || (b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte())))
            return String(b, Charsets.UTF_16)
        val s = if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) 3 else 0
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b, s, b.size - s)).toString()
        } catch (_: CharacterCodingException) {
            String(b, s, b.size - s, Charset.forName("windows-1254"))
        }
    }

    private fun subMime(n: String) = when (n.substringAfterLast('.', "").lowercase()) {
        "vtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    private fun start(got: Map<String, Uri>) {
        spin.visibility = View.GONE
        val http = DefaultHttpDataSource.Factory().setConnectTimeoutMs(10_000).setReadTimeoutMs(30_000)
        val p = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        val mis = items.mapIndexed { i, item ->
            val subs = item.subs.mapIndexed { j, s ->
                MediaItem.SubtitleConfiguration.Builder(got[s.url] ?: Uri.parse(s.url))
                    .setMimeType(subMime(s.name)).setLabel(s.name)
                    .setSelectionFlags(if (j == 0) C.SELECTION_FLAG_DEFAULT else 0).build()
            }
            MediaItem.Builder().setUri(item.url).setMediaId(i.toString())
                .setMediaMetadata(MediaMetadata.Builder().setTitle(item.name).build())
                .setSubtitleConfigurations(subs).build()
        }
        p.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val i = p.currentMediaItemIndex
                titleTv.text = items.getOrNull(i)?.name ?: ""
                errTv.visibility = View.GONE
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                    val pos = savedPos(i)
                    if (pos > 0) p.seekTo(pos)
                }
                if (p.playbackState == Player.STATE_IDLE) p.prepare()   // next / previous after a playback error
            }
            override fun onPlayerError(error: PlaybackException) {
                errTv.text = "Cannot play this file\n(" + error.errorCodeName + ")\n\nUse next / previous or \u2190 to go back."
                errTv.visibility = View.VISIBLE
                view.showController()
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (!userRotated && !inPip && videoSize.width > 0)
                    requestedOrientation = if (videoSize.width >= videoSize.height) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                if (Build.VERSION.SDK_INT >= 26 && inPip) try { setPictureInPictureParams(pipParams()) } catch (_: Exception) {}
            }
        })
        val sp = savedPos(startIdx)
        p.setMediaItems(mis, startIdx, if (sp > 0) sp else C.TIME_UNSET)
        p.prepare()
        p.playWhenReady = true
        view.player = p
        player = p
        titleTv.text = items.getOrNull(startIdx)?.name ?: ""
        ui.postDelayed(saver, 5000)
        view.showController()
    }

    private fun releasePlayer() {
        ui.removeCallbacks(saver)
        savePos()
        view.player = null
        player?.release()
        player = null
    }

    // ---------------------------------------------------------------- resume position
    private fun savePos() {
        val p = player ?: return
        val item = items.getOrNull(p.currentMediaItemIndex) ?: return
        val d = p.duration
        if (d == C.TIME_UNSET || d < 20_000) return
        val pos = p.currentPosition
        val ed = getSharedPreferences(PREFS, MODE_PRIVATE).edit()
        if (p.playbackState == Player.STATE_ENDED || pos > d - 5_000) ed.remove("p:" + item.key) else ed.putLong("p:" + item.key, pos)
        ed.apply()
    }

    private fun savedPos(i: Int): Long {
        val k = items.getOrNull(i)?.key ?: return 0L
        val v = getSharedPreferences(PREFS, MODE_PRIVATE).getLong("p:$k", 0L)
        return if (v > 5_000) v else 0L
    }

    // ---------------------------------------------------------------- Picture-in-Picture, lifecycle
    private fun pipParams(): PictureInPictureParams {
        val v = player?.videoSize
        var r = if (v != null && v.width > 0 && v.height > 0) v.width * v.pixelWidthHeightRatio / v.height else 16f / 9f
        r = r.coerceIn(0.42f, 2.39f)
        return PictureInPictureParams.Builder().setAspectRatio(Rational((r * 1000).toInt(), 1000)).build()
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= 26) try { enterPictureInPictureMode(pipParams()) } catch (_: Exception) {}
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= 26 && player?.isPlaying == true && !isFinishing) enterPip()   // Home while playing -> floating window
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        view.useController = !inPip
        if (inPip) top.visibility = View.GONE else view.showController()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onPause() { super.onPause(); savePos() }

    override fun onStop() {
        super.onStop()
        savePos()
        player?.pause()
        if (inPip) finishAndRemoveTask()   // the floating window was closed
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        gen++
        releasePlayer()
        File(cacheDir, "subs").deleteRecursively()
        super.onDestroy()
    }
}
