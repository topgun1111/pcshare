package com.lanshare.app.ops

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Native progress bubble for the viewer / editor / player screens (the main screen has its own progress card in ui.html).
 * Shows every running job of [JobView]; tap = card with one row per job (progress, Cancel), Clear, Minimize.
 * Plain [Activity] + Handler polling: no coroutines, lifecycle or window-manager dependency.
 *
 * - Sized from its OWN window (onSizeChanged), so split screen / pop-up / fold halves are handled: card <= 340 dp and <= window - 32 dp,
 *   list <= 60 % of the window height and scrolls, bubble shrinks in tiny windows.
 * - Drag the bubble, it snaps to the nearest corner (remembered in prefs ops_overlay/corner).
 * - Hidden in picture-in-picture and when nothing runs.
 */
class JobOverlay private constructor(private val act: Activity) : FrameLayout(act) {
    private val dp = resources.displayMetrics.density
    /** Part 9b: rows whose job id starts with this are not shown in THIS overlay (the analyzer screen shows its own scan progress; the notification still lists it). */
    var hidePrefix: String? = null
    private fun px(v: Int) = (v * dp).toInt()
    private val ui = Handler(Looper.getMainLooper())
    private val prefs = act.getSharedPreferences("ops_overlay", Context.MODE_PRIVATE)

    private var pal = Palette.of(act)
    private var expanded = false
    private var corner = prefs.getInt("corner", CORNER_BOTTOM_END)
    private var rows: List<JobView.Row> = emptyList()
    private val holders = LinkedHashMap<String, RowHolder>()

    private lateinit var bubble: BubbleView
    private lateinit var card: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var scroll: MaxHeightScroll
    private lateinit var clearBtn: TextView

    init {
        tag = TAG
        clipChildren = false
        clipToPadding = false
        build()
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            setPadding(b.left, b.top, b.right, b.bottom)
            ins
        }
    }

    private fun build() {
        removeAllViews(); holders.clear()
        bubble = BubbleView(act, pal).apply {
            visibility = View.GONE
            contentDescription = "Running tasks"
            setOnClickListener { setExpanded(true) }
        }
        card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply { setColor(pal.card); cornerRadius = px(16).toFloat(); setStroke(px(1), pal.track) }
            elevation = px(8).toFloat()
            setPadding(px(12), px(8), px(12), px(8))
            isClickable = true                                   // swallow taps on the card
        }
        card.addView(header())
        list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        scroll = MaxHeightScroll(act).apply { addView(list); isVerticalScrollBarEnabled = false }
        card.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        addView(card, LayoutParams(px(340), -2))
        addView(bubble, LayoutParams(px(52), px(52)))
        attachDrag()
        recomputeSizing()
        positionAll()
    }

    private fun header() = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(act).apply {
            text = "Running tasks"; setTextColor(pal.text); textSize = 15f; setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        clearBtn = TextView(act).apply {
            text = "Clear"; setTextColor(pal.accent); textSize = 13f; setPadding(px(10), px(8), px(10), px(8))
            setOnClickListener { JobView.clearFinished(); poll(false) }
        }
        addView(clearBtn)
        addView(TextView(act).apply {
            text = "\u2013"; setTextColor(pal.text); textSize = 22f; gravity = Gravity.CENTER
            contentDescription = "Minimize"
            setOnClickListener { setExpanded(false) }
        }, LinearLayout.LayoutParams(px(40), px(40)))
    }

    // ---- lifecycle ----
    private val tick = object : Runnable { override fun run() { poll(true) } }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); requestApplyInsets(); poll(true) }
    /** Activity stopped / app in the background: stop the 400-800 ms polling, resume when visible again. */
    override fun onWindowVisibilityChanged(v: Int) {
        super.onWindowVisibilityChanged(v)
        if (v == View.VISIBLE) poll(true) else ui.removeCallbacks(tick)
    }
    override fun onDetachedFromWindow() { ui.removeCallbacks(tick); super.onDetachedFromWindow() }

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        val np = Palette.of(act)
        if (np.card != pal.card || np.text != pal.text) { pal = np; build(); render(rows) }   // day / night switched without recreate
    }

    private fun poll(again: Boolean) {
        ui.removeCallbacks(tick)
        val pip = try { act.isInPictureInPictureMode } catch (_: Throwable) { false }
        rows = if (pip) emptyList() else try { JobView.rows().let { r -> hidePrefix?.let { p -> r.filter { !it.id.startsWith(p) } } ?: r } } catch (_: Throwable) { emptyList() }
        render(rows)
        if (again) ui.postDelayed(tick, if (rows.isEmpty()) 800L else 400L)
    }

    // ---- sizing / position ----
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); recomputeSizing(); positionAll() }

    private fun recomputeSizing() {
        val w = width - paddingLeft - paddingRight
        val h = height - paddingTop - paddingBottom
        if (w <= 0 || h <= 0) return
        val small = w < px(360) || h < px(360)
        bubble.layoutParams = (bubble.layoutParams as LayoutParams).also { it.width = px(if (small) 40 else 52); it.height = it.width }
        val margins = px(MARGIN_DP) * 2
        card.layoutParams = (card.layoutParams as LayoutParams).also { it.width = minOf(px(340), w - margins).coerceAtLeast(px(160)) }
        // card height budget: 60 % of the window minus header and card padding; the list scrolls inside it
        scroll.maxH = (h * 0.6f).toInt() - px(56)
        scroll.requestLayout()
    }

    private fun positionAll() {
        val g = when (corner) {
            CORNER_TOP_START -> Gravity.TOP or Gravity.START
            CORNER_TOP_END -> Gravity.TOP or Gravity.END
            CORNER_BOTTOM_START -> Gravity.BOTTOM or Gravity.START
            else -> Gravity.BOTTOM or Gravity.END
        }
        for (v in arrayOf<View>(bubble, card)) {
            (v.layoutParams as LayoutParams).also { it.gravity = g; it.setMargins(px(MARGIN_DP), px(MARGIN_DP), px(MARGIN_DP), px(MARGIN_DP)) }
            v.translationX = 0f; v.translationY = 0f
            v.requestLayout()
        }
    }

    private fun setExpanded(e: Boolean) {
        expanded = e && rows.isNotEmpty()
        render(rows)
    }

    // ---- touches: bubble drag + snap; tap outside the card collapses it ----
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (expanded && ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val r = Rect(); card.getHitRect(r)
            if (!r.contains(ev.x.toInt(), ev.y.toInt())) { setExpanded(false); return true }
        }
        return false
    }
    override fun onTouchEvent(ev: MotionEvent): Boolean = expanded

    private fun attachDrag() {
        val slop = ViewConfiguration.get(act).scaledTouchSlop
        var dx = 0f; var dy = 0f; var sx = 0f; var sy = 0f; var drag = false
        bubble.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; dx = v.translationX; dy = v.translationY; drag = false }
                MotionEvent.ACTION_MOVE -> {
                    val mx = e.rawX - sx; val my = e.rawY - sy
                    if (!drag && (Math.abs(mx) > slop || Math.abs(my) > slop)) drag = true
                    if (drag) { v.translationX = dx + mx; v.translationY = dy + my }
                }
                MotionEvent.ACTION_UP -> { if (drag) snapToNearestCorner() else v.performClick() }
                MotionEvent.ACTION_CANCEL -> if (drag) snapToNearestCorner()
            }
            true
        }
    }

    private fun snapToNearestCorner() {
        val cx = bubble.left + bubble.translationX + bubble.width / 2f
        val cy = bubble.top + bubble.translationY + bubble.height / 2f
        val left = cx < width / 2f; val top = cy < height / 2f
        corner = if (top) (if (left) CORNER_TOP_START else CORNER_TOP_END) else (if (left) CORNER_BOTTOM_START else CORNER_BOTTOM_END)
        prefs.edit().putInt("corner", corner).apply()
        positionAll()
    }

    // ---- rendering ----
    private fun render(l: List<JobView.Row>) {
        if (l.isEmpty()) { expanded = false; bubble.visibility = View.GONE; card.visibility = View.GONE; return }
        bubble.visibility = if (expanded) View.GONE else View.VISIBLE
        card.visibility = if (expanded) View.VISIBLE else View.GONE
        bubble.set(l)
        if (!expanded) return
        val ids = l.map { it.id }
        val gone = holders.keys.filter { it !in ids }
        for (g in gone) { holders.remove(g)?.let { list.removeView(it.root) } }
        for ((i, r) in l.withIndex()) {
            val h = holders.getOrPut(r.id) { RowHolder().also { list.addView(it.root, LinearLayout.LayoutParams(-1, -2)) } }
            if (list.getChildAt(i) !== h.root) { list.removeView(h.root); list.addView(h.root, i) }
            h.bind(r)
        }
        clearBtn.visibility = if (l.any { it.state != "run" }) View.VISIBLE else View.INVISIBLE
    }

    private inner class RowHolder {
        val root = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(6), 0, px(6)) }
        private val title = TextView(act).apply { setTextColor(pal.text); textSize = 14f; maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE }
        private val bar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(pal.accent); progressBackgroundTintList = ColorStateList.valueOf(pal.track)
            indeterminateTintList = ColorStateList.valueOf(pal.accent)
        }
        private val detail = TextView(act).apply { setTextColor(pal.subtext); textSize = 11f; maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE }
        private val action = TextView(act).apply { textSize = 20f; gravity = Gravity.CENTER }
        private var id = ""

        init {
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            col.addView(title); col.addView(bar, LinearLayout.LayoutParams(-1, px(8)).also { it.topMargin = px(4); it.bottomMargin = px(2) }); col.addView(detail)
            root.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            root.addView(action, LinearLayout.LayoutParams(px(40), px(40)).also { it.leftMargin = px(6) })
            action.setOnClickListener { if (action.tag == "cancel") { JobView.cancel(id); poll(false) } }
        }

        fun bind(r: JobView.Row) {
            id = r.id
            title.text = r.label
            if (r.pct < 0) bar.isIndeterminate = true else { bar.isIndeterminate = false; bar.progress = r.pct }
            detail.text = r.detail; detail.visibility = if (r.detail.isEmpty()) View.GONE else View.VISIBLE
            when {
                r.state == "run" && r.cancellable -> { action.text = "\u2715"; action.setTextColor(pal.text); action.tag = "cancel"; action.contentDescription = "Cancel" }
                r.state == "run" -> { action.text = "\u2026"; action.setTextColor(pal.subtext); action.tag = null }
                r.state == "done" -> { action.text = "\u2713"; action.setTextColor(pal.ok); action.tag = null }
                r.state == "error" -> { action.text = "!"; action.setTextColor(pal.err); action.tag = null }
                else -> { action.text = "\u2013"; action.setTextColor(pal.subtext); action.tag = null }   // cancelled
            }
        }
    }

    private class MaxHeightScroll(c: Context) : ScrollView(c) {
        var maxH = Int.MAX_VALUE
        override fun onMeasure(w: Int, h: Int) =
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH.coerceAtLeast(80), MeasureSpec.AT_MOST))
    }

    private class BubbleView(c: Context, private val pal: Palette) : View(c) {
        private var n = 0; private var pct = -1; private var failed = false; private var done = false
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        init {
            elevation = 6f * resources.displayMetrics.density
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setOval(0, 0, v.width, v.height) } }
        }
        fun set(l: List<JobView.Row>) {
            val run = l.filter { it.state == "run" }
            n = run.size; pct = JobView.overallPct(l)
            failed = run.isEmpty() && l.any { it.state == "error" }
            done = run.isEmpty() && !failed
            invalidate()
        }
        override fun onDraw(c: Canvas) {
            val s = Math.min(width, height).toFloat(); val sw = s * 0.09f
            p.style = Paint.Style.FILL; p.color = pal.card; c.drawCircle(width / 2f, height / 2f, s / 2f, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = sw
            rect.set(sw, sw, width - sw, height - sw)
            p.color = pal.track; c.drawOval(rect, p)
            p.strokeCap = Paint.Cap.ROUND
            when {
                failed -> { p.color = pal.err; c.drawOval(rect, p) }
                done -> { p.color = pal.ok; c.drawOval(rect, p) }
                pct >= 0 -> { p.color = pal.accent; c.drawArc(rect, -90f, 360f * pct / 100f, false, p) }
                else -> { p.color = pal.accent; c.drawArc(rect, (System.currentTimeMillis() % 1200L) * 0.3f, 100f, false, p); postInvalidateOnAnimation() }
            }
            p.style = Paint.Style.FILL; p.textAlign = Paint.Align.CENTER; p.color = pal.text; p.textSize = s * 0.38f; p.typeface = Typeface.DEFAULT_BOLD
            val t = if (failed) "!" else if (done) "\u2713" else n.toString()
            c.drawText(t, width / 2f, height / 2f - (p.descent() + p.ascent()) / 2f, p)
        }
    }

    private class Palette(val card: Int, val text: Int, val subtext: Int, val accent: Int, val ok: Int, val err: Int, val track: Int) {
        companion object {
            fun of(c: Context): Palette {
                val night = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                return if (night) Palette(0xFF2B2B2B.toInt(), 0xFFF2F2F2.toInt(), 0xFFB0B0B0.toInt(), 0xFFFF8A3D.toInt(), 0xFF66BB6A.toInt(), 0xFFEF5350.toInt(), 0xFF444444.toInt())
                else Palette(0xFFFFFFFF.toInt(), 0xFF161616.toInt(), 0xFF5F5F5F.toInt(), 0xFFC25100.toInt(), 0xFF2E7D32.toInt(), 0xFFC62828.toInt(), 0xFFDDDDDD.toInt())
            }
        }
    }

    companion object {
        private const val TAG = "job_overlay"
        private const val MARGIN_DP = 16
        private const val CORNER_TOP_START = 0; private const val CORNER_TOP_END = 1
        private const val CORNER_BOTTOM_START = 2; private const val CORNER_BOTTOM_END = 3

        /** Call once at the end of onCreate (after setContentView). Idempotent. */
        fun install(act: Activity, hidePrefix: String? = null) {
            val root = act.findViewById<ViewGroup>(android.R.id.content) ?: return
            if (root.findViewWithTag<View>(TAG) != null) return
            root.addView(JobOverlay(act).also { it.hidePrefix = hidePrefix }, ViewGroup.LayoutParams(-1, -1))
        }
    }
}
