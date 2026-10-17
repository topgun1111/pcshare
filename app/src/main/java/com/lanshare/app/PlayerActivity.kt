package com.lanshare.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
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
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.lanshare.app.core.mimeFor
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Date
import java.util.Locale

/**
 * Dedicated video player (Media3 / ExoPlayer). The file browser hands it the videos of the open folder as URLs of the app's own
 * local HTTP server (/api/dl), which already supports Range requests, so local files, other devices and SMB shares all stream
 * the same way - nothing is downloaded first.
 *
 * Look (like the system gallery's video screen): the video fills the window under translucent bars; a header shows "3/17 . name",
 * the file's date and the video's size; at the bottom a frosted seek pill (current / total time + progress) with a round play-pause
 * button, and a row of round buttons: turn video left, screen rotation, share, delete, turn video right, more.
 *
 * The two "turn video" buttons rotate only the picture (a TextureView that is rotated and scaled to fit, any number of times, in both
 * directions) - the screen itself is never rotated by them. The screen-rotation button toggles portrait / landscape (long press = auto).
 *
 * Plays MKV / AVI / MOV / MP4 / WebM / TS ... with audio-track and subtitle selection (More menu), speed, playlist (previous / next),
 * resume position, Picture-in-Picture, double-tap seek (+-10 s), swipe for brightness (left) and volume (right).
 * Subtitle files next to a video (same name: .srt .vtt .ass .ssa) are picked up automatically; their text is converted to UTF-8
 * first (old Turkish files are windows-1254), so ğ ş ı İ ö ü ç show correctly.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : Activity() {
    companion object {
        /** JSON handed over by MainActivity.Bridge.play(): {start, items:[{name, url, key, subs:[{name, url}], mtime?, size?}]} */
        @Volatile var pending: String? = null
        private const val PREFS = "ls_player"
        private const val MAX_ITEMS = 500
        private const val HIDE_MS = 3500L
        /** Set after a video was deleted here: MainActivity refreshes the file list when it comes back to the front. */
        @Volatile private var changed = false
        fun consumeChanged(): Boolean { val c = changed; changed = false; return c }
    }

    private class Sub(val name: String, val url: String)
    private class Item(val name: String, val url: String, val key: String, val subs: List<Sub>, val mtime: Long, val size: Long)

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var stage: FrameLayout          // the video area (whole window, behind the bars)
    private lateinit var view: PlayerView
    private lateinit var touch: View                 // gesture layer above the video
    private lateinit var header: LinearLayout
    private lateinit var backBtn: RoundBtn
    private lateinit var titleTv: TextView
    private lateinit var dateTv: TextView
    private lateinit var resTv: TextView
    private lateinit var calIcon: IconView
    private lateinit var bottom: LinearLayout
    private lateinit var row1: LinearLayout
    private lateinit var row2: LinearLayout
    private lateinit var pill: SeekPill
    private lateinit var playBtn: RoundBtn
    private lateinit var prevBtn: RoundBtn
    private lateinit var nextBtn: RoundBtn
    private lateinit var rotLBtn: RoundBtn
    private lateinit var scrBtn: RoundBtn
    private lateinit var shareBtn: RoundBtn
    private lateinit var delBtn: RoundBtn
    private lateinit var rotRBtn: RoundBtn
    private lateinit var moreBtn: RoundBtn
    private lateinit var hud: TextView
    private lateinit var errTv: TextView
    private lateinit var spin: ProgressBar
    private var pal = PlayerPal(false)

    private var player: ExoPlayer? = null
    private var items: List<Item> = emptyList()
    private var startIdx = 0
    private var gen = 0                  // bumped on every (re)start: a late subtitle-preparation thread is then ignored
    private var inPip = false
    private var userRotated = false      // the screen-rotation button was used: stop following the video's shape
    private var fullscreen = false       // default: system bars visible; toggled in the More menu and remembered
    private var resizeIdx = 0
    private var gMode = 0                // swipe gesture: 0 undecided, -1 ignored, 1 brightness, 2 volume
    private var gB0 = 0.5f
    private var gV0 = 0
    private var insL = 0; private var insT = 0; private var insR = 0; private var insB = 0
    private var lastBs = -1
    private var controlsOn = false
    private var holds = 0                // >0 while a dialog is open or the seek pill is being dragged: controls stay
    private var rotTarget = 0            // video rotation chosen with the turn buttons, degrees clockwise (unbounded while turning, 0..359 at rest)
    private var rotAnim: ValueAnimator? = null
    private val mtimes = HashMap<String, Long>()    // item url -> modified time (seconds), from the list or /api/stat
    private val sizes = HashMap<String, Long>()
    private val metaAsked = HashSet<String>()

    private val saver = object : Runnable {
        override fun run() { savePos(); ui.postDelayed(this, 5000) }
    }
    private val tick = object : Runnable {
        override fun run() { if (controlsOn && !inPip) updateProgress(); ui.postDelayed(this, 250) }
    }
    private val hideHud = Runnable { hud.visibility = View.GONE }
    private val autoHide = Runnable { if (holds == 0 && player?.playWhenReady == true && !isFinishing) setControls(false) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun attachBaseContext(b: Context) = super.attachBaseContext(UiScale.wrap(b))

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 28)
            window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) { window.isStatusBarContrastEnforced = false; window.isNavigationBarContrastEnforced = false }
        fullscreen = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("fullscreen", false)
        buildUi()
        applyTheme()
        begin()
        ui.post(tick)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        begin()   // another video was chosen while the player (or its PiP window) is still open
    }

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        applyTheme()
        applyBars()
    }

    // ---------------------------------------------------------------- theme
    /** The app's own theme (light / dark) when the page has sent one, otherwise the system day / night mode. */
    private fun isLight(): Boolean {
        AppPal.load(this)?.let { return ColorUtils.calculateLuminance(it.bg) > 0.5 }
        return (Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES
    }

    private fun applyTheme() {
        pal = PlayerPal(isLight())
        root.setBackgroundColor(pal.bg)
        window.setBackgroundDrawable(ColorDrawable(pal.bg))
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.isAppearanceLightStatusBars = pal.light
        c.isAppearanceLightNavigationBars = pal.light
        header.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(pal.scrimTop, pal.scrimEnd))
        titleTv.setTextColor(pal.text); dateTv.setTextColor(pal.text); resTv.setTextColor(pal.text)
        calIcon.pal = pal; pill.pal = pal
        spin.indeterminateTintList = android.content.res.ColorStateList.valueOf(pal.fill)
        for (b in listOf(backBtn, playBtn, prevBtn, nextBtn, rotLBtn, scrBtn, shareBtn, delBtn, rotRBtn, moreBtn)) b.pal = pal
    }

    // ---------------------------------------------------------------- UI (built in code; only the PlayerView comes from a tiny layout, see player_texture_view.xml)
    private fun buildUi() {
        root = FrameLayout(this)
        stage = FrameLayout(this)
        root.addView(stage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view = layoutInflater.inflate(R.layout.player_texture_view, stage, false) as PlayerView
        view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        stage.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        stage.addOnLayoutChangeListener { _, l, t, r, bt, ol, ot, or2, ob ->
            if (r - l != or2 - ol || bt - t != ob - ot) applyVideoTransform(false)   // new window size (screen turned, split screen, PiP): refit the turned picture
        }

        touch = View(this)
        root.addView(touch, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        fun label(sp: Float, bold: Boolean) = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            if (bold) typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            includeFontPadding = false
        }

        // ---- header: back, "n/total . name", date + size
        header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        backBtn = RoundBtn(this, PIc.BACK).apply { contentDescription = "Back"; setOnClickListener { finish() } }
        header.addView(backBtn, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(10) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleTv = label(17f, true)
        texts.addView(titleTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val sub = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        calIcon = IconView(this, PIc.CAL)
        sub.addView(calIcon, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
        dateTv = label(14f, false)
        sub.addView(dateTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        resTv = label(14f, false)
        sub.addView(resTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
        texts.addView(sub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        header.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // ---- bottom: seek pill + play/pause (+ previous / next), then the row of round buttons
        bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        pill = SeekPill(this)
        playBtn = RoundBtn(this, PIc.PLAY).apply { contentDescription = "Play / pause"; setOnClickListener { togglePlay() } }
        prevBtn = RoundBtn(this, PIc.PREV).apply { contentDescription = "Previous video"; setOnClickListener { player?.seekToPreviousMediaItem() } }
        nextBtn = RoundBtn(this, PIc.NEXT).apply { contentDescription = "Next video"; setOnClickListener { player?.seekToNextMediaItem() } }
        row1.addView(pill); row1.addView(prevBtn); row1.addView(nextBtn); row1.addView(playBtn)
        row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        rotLBtn = RoundBtn(this, PIc.ROT_L).apply { contentDescription = "Turn video left"; setOnClickListener { rotateVideo(-90) } }
        scrBtn = RoundBtn(this, PIc.SCREEN).apply {
            contentDescription = "Screen rotation"
            setOnClickListener { toggleScreenOrientation() }
            setOnLongClickListener { screenAuto(); true }
        }
        shareBtn = RoundBtn(this, PIc.SHARE).apply { contentDescription = "Share"; setOnClickListener { shareCurrent() } }
        delBtn = RoundBtn(this, PIc.TRASH).apply { contentDescription = "Delete"; setOnClickListener { deleteCurrent() } }
        rotRBtn = RoundBtn(this, PIc.ROT_R).apply { contentDescription = "Turn video right"; setOnClickListener { rotateVideo(90) } }
        moreBtn = RoundBtn(this, PIc.MORE).apply { contentDescription = "More"; setOnClickListener { showMore() } }
        for (b in listOf(rotLBtn, scrBtn, shareBtn, delBtn, rotRBtn, moreBtn)) row2.addView(b)
        bottom.addView(row1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bottom.addView(row2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        pill.onSeek = { f, preview ->
            val d = player?.duration ?: C.TIME_UNSET
            if (!preview && d != C.TIME_UNSET && d > 0) player?.seekTo((f * d).toLong())
        }
        pill.onTouching = { on -> if (on) holds++ else { holds = maxOf(0, holds - 1); scheduleHide() } }

        // controls are shown from the start (the video is not there yet); they hide themselves while playing
        controlsOn = true

        hud = TextView(this).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10)); visibility = View.GONE
            background = GradientDrawable().apply { setColor(0xAA000000.toInt()); cornerRadius = dp(20).toFloat() }
        }
        root.addView(hud, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        errTv = TextView(this).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); gravity = Gravity.CENTER
            setPadding(dp(24), dp(16), dp(24), dp(16)); visibility = View.GONE
            background = GradientDrawable().apply { setColor(0xCC000000.toInt()); cornerRadius = dp(16).toFloat() }
        }
        root.addView(errTv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        spin = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(spin, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))

        // the video lies under the translucent bars; only the controls keep clear of them
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            insL = b.left; insT = b.top; insR = b.right; insB = b.bottom
            layoutBars()
            insets
        }
        root.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or2, _ -> if (r - l != or2 - ol) layoutBars() }

        setupGestures()
        setContentView(root)
        layoutBars()
        com.lanshare.app.ops.JobOverlay.install(this)   // progress bubble for background jobs (copy / zip / print ...) while this screen is open
    }

    /** Paddings from the system bars, and the size of the round buttons (48 dp, smaller when the row would not fit). */
    private fun layoutBars() {
        val m = dp(8); val gap = dp(8)
        header.setPadding(insL + dp(10), insT + dp(8), insR + dp(12), dp(22))
        bottom.setPadding(insL + m, 0, insR + m, insB + dp(10))
        val innerW = root.width - insL - insR - 2 * m
        val bs = if (innerW <= 0) dp(48) else minOf(dp(48), (innerW - 5 * gap) / 6).coerceAtLeast(dp(30))
        if (bs != lastBs) {
            lastBs = bs
            fun lp(w: Int, h: Int, start: Int) = LinearLayout.LayoutParams(w, h).apply { marginStart = start }
            pill.layoutParams = LinearLayout.LayoutParams(0, bs, 1f)
            for (b in listOf(prevBtn, nextBtn, playBtn)) b.layoutParams = lp(bs, bs, gap)
            for (b in listOf(rotLBtn, scrBtn, shareBtn, delBtn, rotRBtn, moreBtn)) b.layoutParams = lp(bs, bs, gap)
        }
        subtitlePadding()
    }

    /** Keeps subtitles above the control pill while the controls are shown (only when the picture is not turned). */
    private fun subtitlePadding() {
        val pad = if (controlsOn && Math.floorMod(rotTarget, 360) == 0 && !inPip) bottom.height else 0
        view.subtitleView?.setPadding(0, 0, 0, pad)
    }

    private fun applyBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (fullscreen && !inPip) c.hide(WindowInsetsCompat.Type.systemBars()) else c.show(WindowInsetsCompat.Type.systemBars())
        ViewCompat.requestApplyInsets(root)
    }

    private fun applyOrientation() {
        if (userRotated || inPip) return
        val v = player?.videoSize
        requestedOrientation = if (!fullscreen || v == null || v.width <= 0) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        else if (v.width >= v.height) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("fullscreen", on).apply()
        applyBars()
        applyOrientation()
    }

    private fun showHud(t: String) {
        hud.text = t; hud.visibility = View.VISIBLE
        ui.removeCallbacks(hideHud); ui.postDelayed(hideHud, 800)
    }

    // ---------------------------------------------------------------- controls show / hide
    private fun setControls(on: Boolean, animate: Boolean = true) {
        controlsOn = on
        for (v in listOf(header, bottom)) {
            v.animate().cancel()
            if (on) {
                v.visibility = View.VISIBLE
                if (animate) v.animate().alpha(1f).setDuration(150).start() else v.alpha = 1f
            } else if (animate) {
                v.animate().alpha(0f).setDuration(150).withEndAction { if (!controlsOn) v.visibility = View.GONE }.start()
            } else { v.alpha = 0f; v.visibility = View.GONE }
        }
        ui.removeCallbacks(autoHide)
        if (on) { updateProgress(); scheduleHide() }
        subtitlePadding()
    }

    private fun scheduleHide() {
        ui.removeCallbacks(autoHide)
        if (controlsOn && holds == 0 && player?.playWhenReady == true) ui.postDelayed(autoHide, HIDE_MS)
    }

    private fun togglePlay() {
        val p = player ?: return
        if (Util.shouldShowPlayButton(p)) Util.handlePlayButtonAction(p) else Util.handlePauseButtonAction(p)
        updatePlayIcon()
        scheduleHide()
    }

    private fun updatePlayIcon() {
        val p = player
        playBtn.iconKind = if (p == null || Util.shouldShowPlayButton(p)) PIc.PLAY else PIc.PAUSE
    }

    private fun updateProgress() {
        val p = player ?: return
        val d = p.duration
        pill.set(p.currentPosition, if (d == C.TIME_UNSET) 0L else d, p.bufferedPosition)
    }

    private fun updateNav() {
        val many = items.size > 1
        prevBtn.visibility = if (many) View.VISIBLE else View.GONE
        nextBtn.visibility = if (many) View.VISIBLE else View.GONE
        val p = player
        prevBtn.isEnabled = p?.hasPreviousMediaItem() == true
        nextBtn.isEnabled = p?.hasNextMediaItem() == true
    }

    // ---------------------------------------------------------------- header info
    private fun curIndex() = player?.currentMediaItemIndex ?: startIdx

    private fun updateInfo() {
        val i = curIndex()
        val it = items.getOrNull(i)
        titleTv.text = if (it == null) "" else "${i + 1}/${items.size} \u2022 ${it.name}"
        val v = player?.videoSize
        resTv.text = if (v != null && v.width > 0 && v.height > 0) "${v.width} \u00D7 ${v.height}" else ""
        val mt = if (it == null) 0L else (if (it.mtime > 0) it.mtime else mtimes[it.url] ?: 0L)
        if (it != null && mt <= 0L) fetchMeta(it)
        dateTv.text = if (mt > 0) dateText(mt) else ""
        calIcon.visibility = if (mt > 0) View.VISIBLE else View.INVISIBLE
    }

    /** "Jul 24, 2026 . 17:51" in the phone's language and 12 / 24 hour setting. */
    private fun dateText(sec: Long): String {
        val loc = Locale.getDefault()
        val datePat = android.text.format.DateFormat.getBestDateTimePattern(loc, "MMMdyyyy")
        val tp = if (android.text.format.DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm a"
        return java.text.SimpleDateFormat("$datePat '\u2022' $tp", loc).format(Date(sec * 1000))
    }

    /** The list did not say when the file was modified: ask the app's own server (GET /api/stat). */
    private fun fetchMeta(item: Item) {
        if (!metaAsked.add(item.url)) return
        Thread({
            try {
                val u = Uri.parse(item.url)
                val dev = u.getQueryParameter("dev") ?: "local"
                val path = u.getQueryParameter("path") ?: ""
                val url = item.url.substringBefore("/api/") + "/api/stat?dev=" + Uri.encode(dev) + "&path=" + Uri.encode(path)
                val c = URL(com.lanshare.app.core.RootShell.sign(url)).openConnection() as HttpURLConnection
                c.connectTimeout = 3000; c.readTimeout = 5000
                val o = JSONObject(c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8))
                val mt = o.optLong("mtime", 0L)
                if (mt > 0) mtimes[item.url] = if (mt > 100_000_000_000L) mt / 1000 else mt
                val sz = o.optLong("size", 0L)
                if (sz > 0) sizes[item.url] = sz
                ui.post { if (!isFinishing) updateInfo() }
            } catch (_: Exception) { }
        }, "player-meta").also { it.isDaemon = true }.start()
    }

    // ---------------------------------------------------------------- turning the VIDEO (not the screen)
    private fun rotateVideo(delta: Int) {
        rotTarget += delta
        applyVideoTransform(true)
        showHud(Math.floorMod(rotTarget, 360).toString() + "\u00B0")
        subtitlePadding()
        scheduleHide()
    }

    /**
     * The picture keeps its normal layout (the PlayerView fills the window and fits the video as usual); turning it is a plain
     * view rotation around the window centre plus a uniform scale, so that the turned picture fits the window again.
     */
    private fun applyVideoTransform(animate: Boolean) {
        rotAnim?.cancel(); rotAnim = null
        val r1 = rotTarget.toFloat()
        val s1 = fitScale(rotTarget)
        if (!animate || stage.width == 0 || !view.isLaidOut) { setXform(r1, s1); normaliseRot(); return }
        val r0 = view.rotation; val s0 = view.scaleX
        val an = ValueAnimator.ofFloat(0f, 1f)
        var cancelled = false
        an.duration = 240
        an.interpolator = DecelerateInterpolator()
        an.addUpdateListener { a -> val t = a.animatedFraction; setXform(r0 + (r1 - r0) * t, s0 + (s1 - s0) * t) }
        an.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationCancel(a: Animator) { cancelled = true }
            override fun onAnimationEnd(a: Animator) { if (!cancelled) normaliseRot() }
        })
        rotAnim = an
        an.start()
    }

    private fun setXform(rot: Float, s: Float) { view.rotation = rot; view.scaleX = s; view.scaleY = s }

    private fun normaliseRot() {
        rotTarget = Math.floorMod(rotTarget, 360)
        view.rotation = rotTarget.toFloat()
    }

    /** Scale that makes a picture turned by [rot] fit the window: 1 for 0 / 180 degrees, for 90 / 270 the picture's own fitted rectangle is swapped. */
    private fun fitScale(rot: Int): Float {
        if (Math.floorMod(rot / 90, 2) == 0) return 1f
        val w = stage.width.toFloat(); val h = stage.height.toFloat()
        if (w <= 0f || h <= 0f) return 1f
        var fw = w; var fh = h                     // rectangle the picture occupies before turning
        val v = player?.videoSize
        if (resizeIdx == 0 && v != null && v.width > 0 && v.height > 0) {
            val a = v.width * v.pixelWidthHeightRatio / v.height
            fw = minOf(w, h * a); fh = fw / a
        }
        return minOf(w / fh, h / fw)
    }

    // ---------------------------------------------------------------- turning the SCREEN
    /** Tap: portrait <-> landscape. Works from the real window shape (the resources' orientation can be stale when the interface size is scaled). */
    private fun toggleScreenOrientation() {
        val land = root.width > root.height
        userRotated = true
        requestedOrientation = if (land) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        showHud(if (land) "Portrait" else "Landscape")
        scheduleHide()
    }

    /** Long press: back to automatic. */
    private fun screenAuto() {
        userRotated = false
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        applyOrientation()
        showHud("Auto rotate")
    }

    // ---------------------------------------------------------------- gestures
    /** Tap = show/hide controls, double-tap left/right = -10 s / +10 s, double-tap middle = pause, swipe up/down: left half brightness, right half volume. */
    private fun setupGestures() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (inPip) return true
                setControls(!controlsOn)
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val p = player ?: return true
                val f = e.x / maxOf(touch.width, 1)
                if (f < 0.33f) { p.seekTo(maxOf(0L, p.currentPosition - 10_000)); showHud("-10 s") }
                else if (f > 0.67f) { p.seekTo(p.currentPosition + 10_000); showHud("+10 s") }
                else togglePlay()
                return true
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                val s = e1 ?: return false
                val ady = Math.abs(e2.y - s.y)
                val adx = Math.abs(e2.x - s.x)
                if (gMode == 0) {
                    if (ady > dp(12) && ady > adx) {
                        gMode = if (s.x < touch.width / 2f) 1 else 2
                        val cur = window.attributes.screenBrightness
                        gB0 = if (cur >= 0f) cur else try { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f } catch (_: Exception) { 0.5f }
                        gV0 = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    } else if (adx > dp(12)) gMode = -1
                }
                if (gMode <= 0) return false
                val d = (s.y - e2.y) / maxOf(touch.height, 1) * 1.3f
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
        touch.setOnTouchListener { _, e ->
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
                if (local(s.getString("url"))) subs.add(Sub(s.optString("name"), com.lanshare.app.core.RootShell.sign(s.getString("url"))))
            }
            var mt = it.optLong("mtime", 0L)
            if (mt > 100_000_000_000L) mt /= 1000          // milliseconds -> seconds
            out.add(Item(it.optString("name"), com.lanshare.app.core.RootShell.sign(url), it.optString("key", url), subs, mt, it.optLong("size", 0L)))
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
        mtimes.clear(); sizes.clear(); metaAsked.clear()
        val my = ++gen
        errTv.visibility = View.GONE
        rotTarget = 0; applyVideoTransform(false)
        updateInfo(); updateNav()
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
                errTv.visibility = View.GONE
                if (mediaItem != null) { rotTarget = 0; applyVideoTransform(false) }   // every video starts upright
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                    val pos = savedPos(i)
                    if (pos > 0) p.seekTo(pos)
                }
                if (p.playbackState == Player.STATE_IDLE) p.prepare()   // next / previous after a playback error
                updateInfo(); updateNav(); updatePlayIcon()
            }
            override fun onPlayerError(error: PlaybackException) {
                errTv.text = "Cannot play this file\n(" + error.errorCodeName + ")\n\nUse next / previous or \u2190 to go back."
                errTv.visibility = View.VISIBLE
                setControls(true)
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0) { applyOrientation(); applyVideoTransform(false) }   // screen follows the video's shape in fullscreen; the turned picture is refitted
                updateInfo()
                if (Build.VERSION.SDK_INT >= 26 && inPip) try { setPictureInPictureParams(pipParams()) } catch (_: Exception) {}
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { updatePlayIcon() }
            override fun onPlaybackStateChanged(state: Int) { updatePlayIcon(); updateNav() }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                updatePlayIcon()
                if (playWhenReady) scheduleHide() else if (!inPip) setControls(true)   // paused: the controls stay
            }
        })
        val sp = savedPos(startIdx)
        p.setMediaItems(mis, startIdx, if (sp > 0) sp else C.TIME_UNSET)
        p.prepare()
        p.playWhenReady = true
        view.player = p
        player = p
        updateInfo(); updateNav(); updatePlayIcon()
        ui.postDelayed(saver, 5000)
        setControls(true)
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

    // ---------------------------------------------------------------- dialogs (More menu)
    private fun dlg(b: AlertDialog.Builder): AlertDialog {
        holds++
        val d = b.create()
        d.setOnDismissListener { holds = maxOf(0, holds - 1); scheduleHide(); applyBars() }
        d.show()
        return d
    }

    private fun showMore() {
        val names = arrayOf("Fit", "Zoom", "Stretch")
        val labels = ArrayList<String>(); val acts = ArrayList<() -> Unit>()
        labels += "Playback speed (" + speedText(player?.playbackParameters?.speed ?: 1f) + ")"; acts += { pickSpeed() }
        labels += "Audio track"; acts += { pickTrack(C.TRACK_TYPE_AUDIO) }
        labels += "Subtitles"; acts += { pickTrack(C.TRACK_TYPE_TEXT) }
        labels += "Aspect ratio: " + names[resizeIdx]; acts += { cycleAspect() }
        labels += if (fullscreen) "Exit full screen" else "Full screen"; acts += { setFullscreen(!fullscreen) }
        if (Build.VERSION.SDK_INT >= 26) { labels += "Picture-in-picture"; acts += { enterPip() } }
        if (Math.floorMod(rotTarget, 360) != 0) { labels += "Reset video rotation"; acts += { rotTarget = 0; applyVideoTransform(true); subtitlePadding() } }
        labels += "Details"; acts += { showDetails() }
        dlg(AlertDialog.Builder(this).setItems(labels.toTypedArray()) { _, i -> acts[i]() })
    }

    private fun speedText(s: Float) = (if (s == Math.floor(s.toDouble()).toFloat()) s.toInt().toString() else s.toString()) + "x"

    private fun pickSpeed() {
        val p = player ?: return
        val v = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
        val cur = p.playbackParameters.speed
        var at = 3; var best = Float.MAX_VALUE
        for (i in v.indices) if (Math.abs(v[i] - cur) < best) { best = Math.abs(v[i] - cur); at = i }
        dlg(AlertDialog.Builder(this).setTitle("Playback speed")
            .setSingleChoiceItems(Array(v.size) { speedText(v[it]) }, at) { d, i -> p.setPlaybackSpeed(v[i]); d.dismiss() })
    }

    private fun cycleAspect() {
        val modes = intArrayOf(AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_ZOOM, AspectRatioFrameLayout.RESIZE_MODE_FILL)
        resizeIdx = (resizeIdx + 1) % modes.size
        view.resizeMode = modes[resizeIdx]
        applyVideoTransform(false)
        showHud(arrayOf("Fit", "Zoom", "Stretch")[resizeIdx])
    }

    private fun trackLabel(f: Format, n: Int, audio: Boolean): String {
        val parts = ArrayList<String>()
        f.label?.takeIf { it.isNotBlank() }?.let { parts += it }
        val lang = f.language
        if (!lang.isNullOrBlank() && lang != "und") {
            val nm = Locale(lang).displayLanguage
            if (nm.isNotBlank() && parts.none { it.equals(nm, true) }) parts += nm
        }
        if (parts.isEmpty()) parts += "Track ${n + 1}"
        if (audio && f.channelCount > 0) parts += when (f.channelCount) { 1 -> "mono"; 2 -> "stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "${f.channelCount} ch" }
        return parts.joinToString(" \u00B7 ")
    }

    /** Audio track / subtitle list (subtitles have an "Off" entry first). */
    private fun pickTrack(type: Int) {
        val p = player ?: return
        val audio = type == C.TRACK_TYPE_AUDIO
        val gs = ArrayList<androidx.media3.common.Tracks.Group?>(); val ix = ArrayList<Int>(); val names = ArrayList<String>()
        var checked = -1
        if (!audio) { names += "Off"; gs += null; ix += -1 }
        var real = 0
        for (g in p.currentTracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                if (g.isTrackSelected(i)) checked = names.size
                gs += g; ix += i; names += trackLabel(g.getTrackFormat(i), real, audio); real++
            }
        }
        if (real == 0) { Toast.makeText(this, if (audio) "No audio tracks" else "No subtitles for this video", Toast.LENGTH_SHORT).show(); return }
        if (!audio && (checked < 0 || p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))) checked = 0
        dlg(AlertDialog.Builder(this).setTitle(if (audio) "Audio track" else "Subtitles")
            .setSingleChoiceItems(names.toTypedArray(), checked) { d, k ->
                val g = gs[k]
                val tp = p.trackSelectionParameters.buildUpon()
                if (g == null) tp.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                else tp.setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, ix[k]))
                p.trackSelectionParameters = tp.build()
                d.dismiss()
            })
    }

    private fun showDetails() {
        val i = curIndex(); val it = items.getOrNull(i) ?: return
        val v = player?.videoSize
        val d = player?.duration ?: C.TIME_UNSET
        val sb = StringBuilder(it.name)
        if (v != null && v.width > 0) sb.append("\n\nSize: ").append(v.width).append(" \u00D7 ").append(v.height)
        if (d != C.TIME_UNSET && d > 0) sb.append("\nDuration: ").append(SeekPill.fmt(d))
        val sz = if (it.size > 0) it.size else sizes[it.url] ?: 0L
        if (sz > 0) sb.append("\nFile size: ").append(android.text.format.Formatter.formatFileSize(this, sz))
        val mt = if (it.mtime > 0) it.mtime else mtimes[it.url] ?: 0L
        if (mt > 0) sb.append("\nModified: ").append(dateText(mt))
        dlg(AlertDialog.Builder(this).setTitle("Details").setMessage(sb.toString()).setPositiveButton("OK", null))
    }

    // ---------------------------------------------------------------- share / delete
    private fun shareCurrent() {
        val it = items.getOrNull(curIndex()) ?: return
        val u = Uri.parse(it.url)
        val dev = u.getQueryParameter("dev") ?: "local"
        val path = u.getQueryParameter("path") ?: ""
        if (dev == "local" && !path.contains('!')) {   // a file of this phone: shared in place, no copy
            try {
                val f = com.lanshare.app.core.Core.local.real(com.lanshare.app.core.vnorm(path))
                if (f.isFile) { sendFile(f, it.name); return }
            } catch (_: Exception) { }
        }
        val cancel = java.util.concurrent.atomic.AtomicBoolean(false)
        val box = dlg(AlertDialog.Builder(this).setMessage("Preparing\u2026").setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> cancel.set(true) })
        Thread({
            try {
                val dir = File(cacheDir, "open").apply { deleteRecursively(); mkdirs() }
                val dst = File(dir, it.name.replace('/', '_').ifEmpty { "video.mp4" })
                val c = URL(it.url).openConnection().apply { connectTimeout = 15_000; readTimeout = 60_000 }
                val total = maxOf(it.size, sizes[it.url] ?: 0L, c.contentLengthLong)
                var done = 0L; var last = 0L
                c.getInputStream().use { i -> dst.outputStream().use { o ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        if (cancel.get()) throw InterruptedException()
                        val r = i.read(buf); if (r < 0) break
                        o.write(buf, 0, r); done += r
                        val now = System.currentTimeMillis()
                        if (now - last > 300) { last = now; val pc = if (total > 0) " " + (done * 100 / total) + "%" else ""; ui.post { box.setMessage("Preparing\u2026$pc") } }
                    }
                } }
                ui.post { box.dismiss(); sendFile(dst, it.name) }
            } catch (e: InterruptedException) {
                ui.post { box.dismiss() }
            } catch (e: Exception) {
                ui.post { box.dismiss(); Toast.makeText(this, "Cannot share: " + (e.message ?: "error"), Toast.LENGTH_LONG).show() }
            }
        }, "player-share").also { it.isDaemon = true }.start()
    }

    private fun sendFile(f: File, name: String) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val send = Intent(Intent.ACTION_SEND).setType(mimeFor(name)).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) { Toast.makeText(this, "Cannot share: " + (e.message ?: "error"), Toast.LENGTH_LONG).show() }
    }

    /** Asks, then moves the video to the Recycle bin (this phone) / deletes it (other devices) and plays the next one. */
    private fun deleteCurrent() {
        val i = curIndex(); val it = items.getOrNull(i) ?: return
        dlg(AlertDialog.Builder(this).setTitle("Delete this video?")
            .setMessage(it.name + "\n\nOn this phone it goes to the Recycle bin (kept 30 days).")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                val u = Uri.parse(it.url)
                val dev = u.getQueryParameter("dev") ?: "local"
                val path = u.getQueryParameter("path") ?: ""
                Thread({
                    try {
                        com.lanshare.app.core.Jobs.removeUser(com.lanshare.app.core.Jobs.ep(dev), com.lanshare.app.core.vnorm(path))
                        ui.post { if (!isFinishing) afterDelete(it) }
                    } catch (e: Exception) {
                        ui.post { Toast.makeText(this, "Cannot delete: " + (e.message ?: "error"), Toast.LENGTH_LONG).show() }
                    }
                }, "player-delete").also { t -> t.isDaemon = true }.start()
            })
    }

    private fun afterDelete(gone: Item) {
        changed = true
        val i = items.indexOfFirst { x -> x === gone }
        if (i < 0) return
        val left = items.toMutableList().also { l -> l.removeAt(i) }
        if (left.isEmpty()) { finish(); return }
        items = left
        player?.removeMediaItem(i)     // the next video (or the previous, after the last) starts by itself
        updateInfo(); updateNav()
    }

    // ---------------------------------------------------------------- Picture-in-Picture, lifecycle
    private fun pipParams(): PictureInPictureParams {
        val v = player?.videoSize
        var r = if (v != null && v.width > 0 && v.height > 0) v.width * v.pixelWidthHeightRatio / v.height else 16f / 9f
        if (Math.floorMod(rotTarget / 90, 2) == 1) r = 1f / r      // the picture is turned by 90 / 270 degrees
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
        if (inPip) setControls(false, false) else { applyBars(); setControls(true) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyBars()
    }

    override fun onStart() {
        super.onStart()
        ui.removeCallbacks(tick); ui.post(tick)
    }

    override fun onPause() { super.onPause(); savePos() }

    override fun onStop() {
        super.onStop()
        ui.removeCallbacks(tick)
        savePos()
        player?.pause()
        if (inPip) finishAndRemoveTask()   // the floating window was closed
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        rotAnim?.cancel()
        gen++
        releasePlayer()
        File(cacheDir, "subs").deleteRecursively()
        super.onDestroy()
    }
}
