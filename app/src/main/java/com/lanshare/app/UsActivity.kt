package com.lanshare.app

import android.app.Activity
import com.lanshare.app.core.usearch.QText
import com.lanshare.app.core.usearch.UsHistory
import com.lanshare.app.core.usearch.UsSections
import com.lanshare.app.core.usearch.UsExport
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.lanshare.app.core.Bin
import com.lanshare.app.core.Clip
import com.lanshare.app.core.IndexManager
import com.lanshare.app.core.Thumbs
import com.lanshare.app.core.UsSearch
import com.lanshare.app.core.usearch.CancelToken
import com.lanshare.app.core.usearch.Fold
import com.lanshare.app.core.usearch.QueryParser
import com.lanshare.app.core.vdir
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Universal Search, Part 4: "Everything for names". One text box, results while typing (every key press cancels the running scan and
 * starts a new one on the worker thread), kind chips, sort + match options (menu), name / folder / size / date rows with bold matches,
 * thumbnails for pictures and videos, tap = open with the existing viewers, long-press = multi-select (Copy / Share / Details / Folder / Delete).
 * Reads the name index directly ([UsSearch]); the same query runs for GET /api/usearch. Plain Activity + ListView (no AppCompat, no RecyclerView).
 * Kill switch: [UniversalSearch.ENABLED] / Cfg.usOn(). NOT compiled / NOT device-tested.
 */
class UsActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "usearch-ui").also { it.isDaemon = true } }
    private val thPool = Executors.newFixedThreadPool(2) { r -> Thread(r, "usearch-th").also { it.isDaemon = true } }
    private val thumbs = LruCache<String, Bitmap>(160)
    private val dateF = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    private var token = CancelToken()
    private var seq = 0
    private val rows = ArrayList<UsSearch.Row>()
    private var total = 0
    private var approx = false
    private var ms = 0L
    private var more = false
    private var loading = false
    private var lastText = ""
    private var kind = ""
    private var stamp = ""
    private var terms: List<String> = emptyList()
    private val sel = LinkedHashSet<Int>()
    private var vmode = "list"; private var vthumb = "s"; private var vcols = 0   // view options like the file manager: list / compact / grid, small / large thumbnails, grid columns (0 = automatic)
    private var units: List<IntArray> = emptyList(); private var unitsFor = -1; private var unitsMode = ""   // one list row = one unit: a single row index, or up to N row indexes in grid mode
    private var everywhere = true                       // Part 8: sections Names / Contents / Pictures
    private var scope = ""                               // Part 8: real folder path of "Search here" (blank = whole phone)
    private var recent: List<String> = emptyList()
    private var saved: List<UsHistory.Saved> = emptyList()
    private var secs: List<UsSearch.Sect> = emptyList()
    private var lastNote = ""
    private lateinit var scopeTv: TextView

    // options (kept in SharedPreferences "us_ui")
    private var sortKey = "best"
    private var rev = false
    private var ff = false
    private var oCase = false
    private var oWord = false
    private var oPath = false
    private var oDiac = false
    private var oRe = false

    private lateinit var p: AppPal.P
    private var night = false
    private var d = 1f
    private lateinit var root: LinearLayout
    private lateinit var et: EditText
    private lateinit var info: TextView
    private lateinit var banner: TextView
    private lateinit var sortBtn: TextView
    private lateinit var viewBtn: TextView
    private lateinit var list: ListView
    private lateinit var selBar: LinearLayout
    private lateinit var selTv: TextView
    private val chips = ArrayList<Pair<String, TextView>>()

    private fun dp(v: Float) = Math.round(v * d)

    override fun attachBaseContext(b: Context) { super.attachBaseContext(b); UiScale.attach(this, b) }   // Settings > Interface size / Text size, like the other native screens

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        d = resources.displayMetrics.density
        Fnt.init(applicationContext)   // the app font (Settings > Interface > Font)
        p = AppPal.pal(this); night = AppPal.night(this)
        val sp = getSharedPreferences("us_ui", MODE_PRIVATE)
        sortKey = sp.getString("sort", "best") ?: "best"; rev = sp.getBoolean("rev", false); ff = sp.getBoolean("ff", false)
        oCase = sp.getBoolean("case", false); oWord = sp.getBoolean("word", false); oPath = sp.getBoolean("path", false)
        oDiac = sp.getBoolean("diac", false); oRe = sp.getBoolean("re", false)
        everywhere = sp.getBoolean("every2", true)   // v2: Search everywhere is ON by default (plain words also look inside files and pictures); the old "every" key is ignored
        vmode = sp.getString("vmode", "list") ?: "list"; vthumb = sp.getString("vthumb", "s") ?: "s"; vcols = sp.getInt("vcols", 0)
        if (vmode != "list" && vmode != "compact" && vmode != "grid") vmode = "list"
        recent = UsHistory.decode(sp.getString("recent", "")); saved = UsHistory.decodeSaved(sp.getString("saved", ""))
        scope = b?.getString("scope") ?: intent.getStringExtra(X_SCOPE) ?: ""
        kind = b?.getString("kind") ?: intent.getStringExtra(X_KIND) ?: ""
        val q0 = b?.getString("q") ?: intent.getStringExtra(X_Q) ?: ""
        paintBars()
        build()
        et.setText(q0); et.setSelection(q0.length)
        et.requestFocus()
        et.postDelayed({ (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT) }, 150)
        markChips(); paintScope()
        refreshBanner(true)
        runQuery(true, 0)
    }

    override fun onSaveInstanceState(o: Bundle) { super.onSaveInstanceState(o); o.putString("q", et.text.toString()); o.putString("kind", kind); o.putString("scope", scope) }

    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick); commit() }
    override fun onDestroy() { super.onDestroy(); token.cancel(); seq++; ui.removeCallbacksAndMessages(null); exec.shutdownNow(); thPool.shutdownNow() }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() { if (sel.isNotEmpty()) endSel() else super.onBackPressed() }

    // ---------------------------------------------------------------- UI

    private fun paintBars() {
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(p.bg))
        window.statusBarColor = p.bg; window.navigationBarColor = p.bg
        if (Build.VERSION.SDK_INT >= 23) {
            var f = window.decorView.systemUiVisibility
            f = if (!night) f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else f and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            if (Build.VERSION.SDK_INT >= 26) f = if (!night) f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else f and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            window.decorView.systemUiVisibility = f
        }
    }

    private fun tv(size: Float, col: Int, bold: Boolean = false): TextView = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(col); typeface = if (bold) Fnt.semi() else Fnt.med()
        isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
    }

    private fun iconBtn(path: String, desc: String, click: () -> Unit): FrameLayout = FrameLayout(this).apply {
        contentDescription = desc
        addView(NlGlyph(context, NlIcons.path(path), p.fg, dp(24f)), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setOnClickListener { click() }
    }

    /** Rounded press highlight for the selection dock cells. */
    private fun pressBg(r: Int): android.graphics.drawable.StateListDrawable = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { cornerRadius = r.toFloat(); setColor((p.ac and 0x00FFFFFF) or 0x33000000) })
        addState(intArrayOf(), GradientDrawable().apply { cornerRadius = r.toFloat(); setColor(Color.TRANSPARENT) })
    }

    private fun build() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(p.bg); fitsSystemWindows = true }
        // search row
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10f), dp(8f), dp(4f), dp(4f)) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(p.cont); cornerRadius = dp(24f).toFloat() }
        }
        box.addView(iconBtn("back", "Back") { finish() }, LinearLayout.LayoutParams(dp(44f), dp(52f)))
        et = EditText(this).apply {
            background = null; setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH; hint = if (scope.isEmpty()) "Search this phone" else "Search in " + File(scope).name
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); setTextColor(p.fg); setHintTextColor(p.mut); setPadding(dp(2f), 0, dp(2f), 0); typeface = Fnt.med()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) { }
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) { }
                override fun afterTextChanged(s: Editable?) { endSel(false); runQuery(true, 0) }
            })
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEARCH) { hideKb(); true } else false }
        }
        box.addView(et, LinearLayout.LayoutParams(0, dp(52f), 1f))
        box.addView(iconBtn("close", "Clear") { et.setText(""); et.requestFocus() }, LinearLayout.LayoutParams(dp(44f), dp(52f)))
        bar.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val menuBtn = object : View(this) {   // three dots drawn here: no dependency on a font glyph
            private val pt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.fg }
            override fun onDraw(cv: Canvas) { val cx = width / 2f; val cy = height / 2f; val g = dp(6f).toFloat(); for (k in -1..1) cv.drawCircle(cx, cy + k * g, dp(2f).toFloat(), pt) }
        }.apply { contentDescription = "Sort and options"; setOnClickListener { menu() } }
        bar.addView(menuBtn, LinearLayout.LayoutParams(dp(44f), dp(52f)))
        root.addView(bar)
        // chips
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val cr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12f), dp(4f), dp(12f), dp(6f)) }
        for ((k, t) in listOf("" to "All", "file" to "Files", "folder" to "Folders", "image" to "Images", "doc" to "Docs", "video" to "Video", "audio" to "Audio")) {
            val c = tv(14f, p.fg).apply {
                text = t; gravity = Gravity.CENTER; setPadding(dp(16f), 0, dp(16f), 0)
                setOnClickListener { kind = k; markChips(); endSel(false); runQuery(true, 0) }
            }
            chips.add(k to c)
            cr.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36f)).apply { rightMargin = dp(8f) })
        }
        hs.addView(cr); root.addView(hs)
        scopeTv = tv(13f, p.ac, true).apply {
            setPadding(dp(14f), dp(6f), dp(14f), dp(6f)); visibility = View.GONE
            background = GradientDrawable().apply { setColor(p.cont); cornerRadius = dp(16f).toFloat() }
            setOnClickListener { scope = ""; paintScope(); endSel(false); runQuery(true, 0) }
        }
        root.addView(scopeTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(12f); bottomMargin = dp(6f) })
        // banner (indexing) + info line
        banner = tv(12.5f, p.fg).apply {
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f)); visibility = View.GONE; compoundDrawablePadding = dp(10f)
            background = GradientDrawable().apply { setColor(p.cont); cornerRadius = dp(14f).toFloat() }
            setCompoundDrawablesWithIntrinsicBounds(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.ac); setSize(dp(8f), dp(8f)) }, null, null, null)
        }
        root.addView(banner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(12f), dp(2f), dp(12f), dp(4f)) })
        info = tv(12.5f, p.mut).apply { setPadding(dp(16f), dp(4f), dp(8f), dp(4f)) }
        sortBtn = tv(13f, p.ac, true).apply { setPadding(dp(10f), dp(8f), dp(10f), dp(8f)); setOnClickListener { sortDlg() } }
        viewBtn = tv(13f, p.ac, true).apply { setPadding(dp(10f), dp(8f), dp(14f), dp(8f)); setOnClickListener { viewDlg() } }
        val infoRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        infoRow.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); infoRow.addView(sortBtn); infoRow.addView(viewBtn)
        root.addView(infoRow)
        paintBar()
        // list
        list = ListView(this).apply {
            divider = null; setBackgroundColor(p.bg); isFastScrollEnabled = true
            adapter = ad
            setOnItemClickListener { _, _, i, _ -> units.getOrNull(i)?.let { tap(it[0]) } }
            setOnItemLongClickListener { _, _, i, _ -> units.getOrNull(i)?.let { longTap(it[0]) }; true }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(v: AbsListView?, s: Int) { if (s == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) hideKb() }
                override fun onScroll(v: AbsListView?, f: Int, c: Int, t: Int) { if (more && !loading && f + c >= t - 8 && t > 0) runQuery(false, 0) }
            })
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        // selection bar: file-manager style dock (icon above label, equal cells, count row on top)
        selBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            background = GradientDrawable().apply { setColor(p.cont); val r = dp(24f).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
            setPadding(dp(4f), dp(4f), dp(4f), dp(4f)); elevation = dp(8f).toFloat()
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        selTv = tv(14f, p.fg, true)
        head.addView(selTv, LinearLayout.LayoutParams(0, dp(40f), 1f).apply { leftMargin = dp(14f) }.also { it.gravity = Gravity.CENTER_VERTICAL })
        head.addView(iconBtn("close", "Cancel selection") { endSel() }, LinearLayout.LayoutParams(dp(44f), dp(40f)))
        selBar.addView(head)
        val line = View(this).apply { setBackgroundColor(p.bd) }
        selBar.addView(line, LinearLayout.LayoutParams(-1, 1).apply { setMargins(dp(8f), 0, dp(8f), dp(2f)) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val acts = listOf<Array<Any>>(
            arrayOf("Select all", "M3 5h2V3c-1.1 0-2 .9-2 2zm0 8h2v-2H3v2zm4 8h2v-2H7v2zM3 9h2V7H3v2zm10-6h-2v2h2V3zm6 0v2h2c0-1.1-.9-2-2-2zM5 21v-2H3c0 1.1.9 2 2 2zm-2-4h2v-2H3v2zM9 3H7v2h2V3zm2 18h2v-2h-2v2zm8-8h2v-2h-2v2zm0 8c1.1 0 2-.9 2-2h-2v2zm0-12h2V7h-2v2zm0 8h2v-2h-2v2zm-4 4h2v-2h-2v2zm0-16h2V3h-2v2zM7 17h10V7H7v10zm2-8h6v6H9V9z", 0xFF8E24AA.toInt(), { for (r in rows) sel.add(r.id); updateSelBar(); ad.notifyDataSetChanged() }),
            arrayOf("Copy", "M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z", 0xFF1E88E5.toInt(), { actCopy() }),
            arrayOf("Share", "M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7l7.05-4.11c.54.5 1.25.81 2.04.81 1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3c0 .24.04.47.09.7L8.04 9.81C7.5 9.31 6.79 9 6 9c-1.66 0-3 1.34-3 3s1.34 3 3 3c.79 0 1.5-.31 2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65 0 1.61 1.31 2.92 2.92 2.92 1.61 0 2.92-1.31 2.92-2.92s-1.31-2.92-2.92-2.92z", 0xFF43A047.toInt(), { actShare() }),
            arrayOf("Details", "M11 7h2v2h-2zm0 4h2v6h-2zm1-9C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z", 0xFF00ACC1.toInt(), { actDetails() }),
            arrayOf("Folder", "M20 6h-8l-2-2H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2zm0 12H4V8h16v10z", 0xFFFB8C00.toInt(), { actFolder() }),
            arrayOf("Delete", "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z", 0xFFE53935.toInt(), { actDelete() })
        )
        for (a in acts) {
            @Suppress("UNCHECKED_CAST") val fn = a[3] as () -> Unit
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; background = pressBg(dp(14f)); setPadding(0, dp(6f), 0, dp(4f))
                contentDescription = a[0] as String; setOnClickListener { fn() }
            }
            cell.addView(NlGlyph(this, NlGlyph.parse(a[1] as String), a[2] as Int, dp(26f)), LinearLayout.LayoutParams(-2, -2))
            cell.addView(tv(11f, if (a[0] == "Delete") (a[2] as Int) else p.fg, true).apply { text = a[0] as String; gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3f) })
            row.addView(cell, LinearLayout.LayoutParams(0, dp(58f), 1f).apply { setMargins(dp(1f), 0, dp(1f), 0) })
        }
        selBar.addView(row)
        root.addView(selBar)
        setContentView(root)
    }

    private fun markChips() {
        for ((k, c) in chips) {
            val on = k == kind
            c.setTextColor(if (on) p.onac else p.fg)
            c.background = GradientDrawable().apply { cornerRadius = dp(18f).toFloat(); setColor(if (on) p.ac else p.cont) }
        }
    }

    private fun hideKb() { (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(et.windowToken, 0); et.clearFocus() }

    // ---------------------------------------------------------------- search

    private fun opts() = UsSearch.optsOf(oCase, oWord, oPath, oDiac, oRe)

    /** [reset] = new query (rows replaced); otherwise the next page is appended. [keep] = refresh: reload at least that many rows. */
    private fun runQuery(reset: Boolean, keep: Int) {
        val text = et.text.toString()
        lastText = text
        val effective = if (text.isBlank() && kind.isNotEmpty()) "*" else text
        if (effective.isBlank()) { token.cancel(); seq++; showHome(); return }
        token.cancel()
        val tk = CancelToken(); token = tk
        val s = ++seq
        val every = everywhere && text.isNotBlank() && !UsSections.hasEngine(text)
        val off = if (reset) 0 else rows.size
        val lim = if (reset) maxOf(PAGE, keep) else PAGE
        val k = kind; val o = opts(); val so = UsSearch.sortOf(sortKey, rev, ff); val sc = scope
        loading = true
        exec.execute {
            val groups = if (every) (try { UsSearch.everywhere(effective, k, o, so, sc, SEC_ROWS, tk) } catch (_: Throwable) { null }) else null
            val out = if (every) null else try { UsSearch.run(effective, k, o, so, off, lim, tk, sc) } catch (_: Throwable) { null }
            ui.post {
                if (s != seq) return@post
                loading = false
                if (every) {
                    rows.clear(); list.setSelection(0)
                    val g = groups?.filter { it.out.rows.isNotEmpty() } ?: emptyList()
                    secs = g
                    var n = 0; var t = 0L
                    for (x in g) { rows.add(pseudoRow(-1000 - n, UsSections.title(UsSections.Sec(x.id, x.title, x.query), x.out.total, x.out.approx), "#h:" + x.id)); rows.addAll(x.out.rows); n++; t += x.out.ms }
                    total = g.sumOf { it.out.rows.size }; approx = false; ms = t; more = false
                    lastNote = groups?.flatMap { it.out.notes }?.firstOrNull() ?: ""
                } else {
                    secs = emptyList()
                    if (out == null) { rows.clear(); total = 0; more = false; ad.notifyDataSetChanged(); setInfo(); refreshBanner(true); return@post }
                    if (reset) { rows.clear(); list.setSelection(0) }
                    rows.addAll(out.rows)
                    total = out.total; approx = out.approx; ms = out.ms
                    more = out.complete && rows.size < total && out.rows.isNotEmpty() && rows.size < 20000
                    lastNote = out.notes.firstOrNull() ?: ""
                }
                terms = try {
                    val qq = QueryParser.parse(effective)
                    (qq.plainWords() + (qq.content?.terms ?: emptyList()).filterIsInstance<QText>().filter { !it.regex && !it.wild }.map { it.text })
                        .map { Fold.fold(it, oDiac) }.filter { it.isNotEmpty() }
                } catch (_: Throwable) { emptyList() }
                ad.notifyDataSetChanged()
                setInfo()
            }
        }
    }

    // ---------------------------------------------------------------- Part 8: home (recent + saved), scope, history

    private fun pseudo(r: UsSearch.Row) = r.badge.startsWith("#")
    private fun pseudoRow(id: Int, name: String, tag: String, snip: String = "") = UsSearch.Row(id, name, "", false, 0L, 0L, snip, 0, tag)

    /** Empty query: saved searches, then recent ones (tap = run, long-press = remove). */
    private fun showHome() {
        rows.clear(); total = 0; more = false; loading = false; terms = emptyList(); secs = emptyList(); lastNote = ""
        if (saved.isNotEmpty()) { rows.add(pseudoRow(-900, "Saved searches", "#t")); saved.forEachIndexed { i, x -> rows.add(pseudoRow(-2000 - i, x.name, "#s", x.query)) } }
        if (recent.isNotEmpty()) { rows.add(pseudoRow(-901, "Recent searches", "#t")); recent.forEachIndexed { i, x -> rows.add(pseudoRow(-3000 - i, x, "#r")) } }
        ad.notifyDataSetChanged(); setInfo()
    }

    private fun paintScope() {
        if (scope.isEmpty()) { scopeTv.visibility = View.GONE; return }
        scopeTv.text = "In " + (UsSearch.virt(scope) ?: scope) + "   \u2715"; scopeTv.visibility = View.VISIBLE
    }

    private fun saveHist() {
        getSharedPreferences("us_ui", MODE_PRIVATE).edit().putString("recent", UsHistory.encode(recent)).putString("saved", UsHistory.encodeSaved(saved)).apply()
    }

    /** Remember the typed query once it found something (called when a result is opened and when the screen is left). */
    private fun commit() {
        val t = lastText.trim()
        if (total <= 0 || t.isEmpty() || recent.firstOrNull() == t) return
        recent = UsHistory.push(recent, t); saveHist()
    }

    // ---------------------------------------------------------------- list

    private fun gridCols(): Int = if (vcols > 0) vcols else (resources.displayMetrics.widthPixels / dp(118f)).coerceIn(2, 8)

    private fun buildUnits() {
        val u = ArrayList<IntArray>(); val n = if (vmode == "grid") gridCols() else 1
        var i = 0
        while (i < rows.size) {
            if (n == 1 || pseudo(rows[i])) { u.add(intArrayOf(i)); i++; continue }
            var j = i
            while (j < rows.size && j - i < n && !pseudo(rows[j])) j++
            val a = i; u.add(IntArray(j - i) { a + it }); i = j
        }
        units = u; unitsFor = rows.size; unitsMode = vmode + gridCols()
    }

    private val ad = object : BaseAdapter() {
        override fun notifyDataSetChanged() { buildUnits(); super.notifyDataSetChanged() }
        override fun getCount(): Int { if (unitsFor != rows.size || unitsMode != vmode + gridCols()) buildUnits(); return units.size }
        override fun getItem(i: Int) = rows[units[i][0]]
        override fun getItemId(i: Int) = rows[units[i][0]].id.toLong()
        override fun getViewTypeCount() = 3
        override fun getItemViewType(i: Int) = if (pseudo(rows[units[i][0]])) 1 else if (vmode == "grid") 2 else 0
        override fun isEnabled(i: Int) = true
        override fun getView(i: Int, cv: View?, par: ViewGroup?): View {
            val u = units.getOrNull(i) ?: return View(this@UsActivity)
            val r = rows[u[0]]
            if (pseudo(r)) {
                val t = (cv as? TextView) ?: tv(15f, p.fg)
                val head = r.badge == "#t" || r.badge.startsWith("#h:")
                t.setPadding(dp(14f), if (head) dp(12f) else dp(10f), dp(14f), dp(if (head) 4f else 10f))
                t.setTextColor(if (head) p.ac else p.fg); t.typeface = if (head) Fnt.semi() else Fnt.med()
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (head) 13f else 15f)
                t.text = (if (r.badge == "#s") "\u2605  " + r.name + (if (r.snippet != r.name) "   " + r.snippet else "") else if (r.badge == "#r") "\u21BA  " + r.name else r.name) + (if (r.badge.startsWith("#h:")) "   \u203A" else "")
                if (r.badge.startsWith("#h:") && sel.isNotEmpty()) t.text = (if (sectionAllSel(u[0])) "\u2611  " else "\u2610  ") + t.text
                return t
            }
            if (vmode == "grid") {
                val g = (cv?.tag as? GridRow)?.takeIf { it.cols == gridCols() } ?: GridRow(this@UsActivity, gridCols()).also { it.root.tag = it }
                g.bind(u)
                return g.root
            }
            val h = (cv?.tag as? Holder) ?: Holder(this@UsActivity).also { it.root.tag = it }
            h.bind(r, u[0])
            return h.root
        }
    }

    private class Ico(c: Context, val a: UsActivity) : View(c) {
        var name = ""; var dir = false; var ticked = false
        private val pt = Paint(Paint.ANTI_ALIAS_FLAG)
        var sz = 0
        private fun s0() = if (sz > 0) sz else a.dp(40f)
        override fun onMeasure(w: Int, h: Int) { val s = s0(); setMeasuredDimension(s, s) }
        override fun onDraw(cv: Canvas) {
            val s = s0().toFloat()
            if (ticked) {
                pt.color = a.p.ac; cv.drawCircle(s / 2, s / 2, s / 2 - a.dp(2f), pt)
                val ck = NlIcons.path("check")
                if (ck != null) { pt.color = a.p.onac; cv.save(); cv.translate(s / 2 - a.dp(12f), s / 2 - a.dp(12f)); val k = a.dp(24f) / 24f; cv.scale(k, k); cv.drawPath(ck, pt); cv.restore() }
                return
            }
            if (dir) {
                val fp = NlIcons.path("folder") ?: return
                pt.color = a.p.ac; cv.save(); cv.translate(a.dp(2f).toFloat(), a.dp(2f).toFloat()); val k = (s - a.dp(4f)) / 24f; cv.scale(k, k); cv.drawPath(fp, pt); cv.restore()
            } else NlFileIcons.draw(cv, name, kindOfName(name), a.dp(3f).toFloat(), a.dp(3f).toFloat(), s - a.dp(6f))
        }
    }

    private inner class Holder(c: Context) {
        val root = LinearLayout(c)
        val fl = FrameLayout(c)
        val mid = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10f), 0, dp(8f), 0) }
        val rt = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        val ico = Ico(c, this@UsActivity)
        val img = ImageView(c)
        val nameTv = tv(15f, p.fg)
        val pathTv = tv(12f, p.mut).apply { ellipsize = TextUtils.TruncateAt.START }
        val sizeTv = tv(12f, p.fg).apply { gravity = Gravity.END }
        val dateTv = tv(11f, p.mut).apply { gravity = Gravity.END }
        var key = ""

        init {
            root.orientation = LinearLayout.HORIZONTAL; root.gravity = Gravity.CENTER_VERTICAL
            root.setPadding(dp(12f), dp(5f), dp(12f), dp(5f))
            fl.addView(ico, FrameLayout.LayoutParams(dp(40f), dp(40f)))
            img.scaleType = ImageView.ScaleType.CENTER_CROP; img.visibility = View.GONE
            fl.addView(img, FrameLayout.LayoutParams(dp(40f), dp(40f)))
            root.addView(fl, LinearLayout.LayoutParams(dp(40f), dp(40f)))
            mid.addView(nameTv); mid.addView(pathTv)
            root.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            rt.addView(sizeTv); rt.addView(dateTv)
            root.addView(rt, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        fun bind(r: UsSearch.Row, i: Int) {
            val picked = sel.contains(r.id)
            root.setBackgroundColor(if (picked) (p.ac and 0x00FFFFFF) or 0x33000000 else Color.TRANSPARENT)
            val compact = vmode == "compact"
            val side = dp(if (compact) 30f else if (vthumb == "l") 72f else 40f)
            if (ico.sz != side) {
                ico.sz = side
                for (v in listOf<View>(ico, img)) v.layoutParams = FrameLayout.LayoutParams(side, side)
                fl.layoutParams = LinearLayout.LayoutParams(side, side)
            }
            root.setPadding(dp(12f), dp(if (compact) 2f else 5f), dp(12f), dp(if (compact) 2f else 5f))
            nameTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 14f else 15f)
            pathTv.visibility = if (compact) View.GONE else View.VISIBLE
            dateTv.visibility = if (compact) View.GONE else View.VISIBLE
            nameTv.text = hl(r.name)
            val parent = r.path.substringBeforeLast('/', "")
            val v = UsSearch.virt(r.path)
            pathTv.text = hl((if (v != null) vdir(v) else parent.ifEmpty { "/" }) + (if (r.snippet.isNotEmpty()) "  \u2022  " + (if (r.line > 0) "L" + r.line + ": " else "") + r.snippet else ""))
            sizeTv.text = if (r.dir) "" else NlModel.fmt(r.size)
            dateTv.text = if (r.mtime > 0) dateF.format(Date(r.mtime * 1000L)) else ""
            ico.name = r.name; ico.dir = r.dir; ico.ticked = picked; ico.invalidate()
            key = r.path
            val k = if (r.dir) "" else kindOfName(r.name)
            if (picked || (k != "img" && k != "vid")) { img.visibility = View.GONE; return }
            val hit = thumbs.get(r.path)
            if (hit != null) { img.setImageBitmap(hit); img.visibility = View.VISIBLE; return }
            img.visibility = View.GONE
            val want = r.path
            thPool.execute {
                val bm = (try { makeThumb(File(want), k == "vid") } catch (_: Throwable) { null }) ?: return@execute
                thumbs.put(want, bm)
                ui.post { if (key == want && !sel.contains(r.id)) { img.setImageBitmap(bm); img.visibility = View.VISIBLE } }
            }
        }
    }

    /** One grid row: [cols] equal cells. */
    private inner class GridRow(c: Context, val cols: Int) {
        val root = LinearLayout(c)
        val cells = List(cols) { GridCell(c, cols) }
        init {
            root.orientation = LinearLayout.HORIZONTAL; root.setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
            for (x in cells) root.addView(x.root, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        fun bind(u: IntArray) { for (k in cells.indices) if (k < u.size) cells[k].bind(rows[u[k]], u[k]) else cells[k].hide() }
    }

    private inner class GridCell(c: Context, cols: Int) {
        val root = LinearLayout(c)
        private val side = resources.displayMetrics.widthPixels / cols - dp(12f)
        private val ico = Ico(c, this@UsActivity).also { it.sz = side * 55 / 100 }
        private val img = ImageView(c)
        private val nameTv = tv(12f, p.fg).apply { gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.MIDDLE }
        private var key = ""; private var idx = -1
        init {
            root.orientation = LinearLayout.VERTICAL; root.gravity = Gravity.CENTER_HORIZONTAL; root.setPadding(dp(2f), dp(2f), dp(2f), dp(2f))
            val fl = FrameLayout(c)
            fl.addView(ico, FrameLayout.LayoutParams(side * 55 / 100, side * 55 / 100, Gravity.CENTER))
            img.scaleType = ImageView.ScaleType.CENTER_CROP; img.visibility = View.GONE
            fl.addView(img, FrameLayout.LayoutParams(side, side))
            root.addView(fl, LinearLayout.LayoutParams(side, side))
            root.addView(nameTv, LinearLayout.LayoutParams(side, ViewGroup.LayoutParams.WRAP_CONTENT))
            root.setOnClickListener { if (idx >= 0) tap(idx) }
            root.setOnLongClickListener { if (idx >= 0) longTap(idx); true }
        }
        fun hide() { root.visibility = View.INVISIBLE; idx = -1; key = "" }
        fun bind(r: UsSearch.Row, i: Int) {
            root.visibility = View.VISIBLE; idx = i; key = r.path
            val picked = sel.contains(r.id)
            root.setBackgroundColor(if (picked) (p.ac and 0x00FFFFFF) or 0x33000000 else Color.TRANSPARENT)
            nameTv.text = hl(r.name)
            ico.name = r.name; ico.dir = r.dir; ico.ticked = picked; ico.invalidate()
            val k = if (r.dir) "" else kindOfName(r.name)
            if (picked || (k != "img" && k != "vid")) { img.visibility = View.GONE; return }
            val hit = thumbs.get(r.path)
            if (hit != null) { img.setImageBitmap(hit); img.visibility = View.VISIBLE; return }
            img.visibility = View.GONE
            val want = r.path
            thPool.execute {
                val bm = (try { makeThumb(File(want), k == "vid") } catch (_: Throwable) { null }) ?: return@execute
                thumbs.put(want, bm)
                ui.post { if (key == want && !sel.contains(r.id)) { img.setImageBitmap(bm); img.visibility = View.VISIBLE } }
            }
        }
    }

    private fun viewDlg() {
        val items = arrayOf("List", "List \u00B7 large thumbnails", "Compact list", "Grid \u00B7 automatic size", "Grid \u00B7 2 columns", "Grid \u00B7 3 columns", "Grid \u00B7 4 columns", "Grid \u00B7 5 columns")
        val cur = when {
            vmode == "compact" -> 2
            vmode == "grid" -> if (vcols in 2..5) 2 + vcols else 3
            vthumb == "l" -> 1
            else -> 0
        }
        AppPal.dlg(this).setTitle("View").setSingleChoiceItems(items, cur) { d, w ->
            when (w) {
                0 -> { vmode = "list"; vthumb = "s" }
                1 -> { vmode = "list"; vthumb = "l" }
                2 -> vmode = "compact"
                3 -> { vmode = "grid"; vcols = 0 }
                else -> { vmode = "grid"; vcols = w - 2 }
            }
            saveOpts(); d.dismiss()
            paintBar()
            list.adapter = ad   // new adapter binding: drops recycled rows of the other mode
            ad.notifyDataSetChanged()
        }.setNegativeButton("Cancel", null).show()
    }

    @Suppress("DEPRECATION")
    private fun makeThumb(f: File, video: Boolean): Bitmap? {
        if (!f.isFile) return null
        if (video) return android.media.ThumbnailUtils.createVideoThumbnail(f.path, android.provider.MediaStore.Images.Thumbnails.MINI_KIND)
        val bytes = Thumbs.make(f)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var ss = 1
        while (o.outWidth / (ss * 2) >= 160 && o.outHeight / (ss * 2) >= 160) ss *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = ss })
    }

    /** Bold + accent colour on the typed words; only when the folded name has the same length as the name (else the offsets would not line up). */
    private fun hl(name: String): CharSequence {
        if (terms.isEmpty()) return name
        val f = Fold.fold(name, oDiac)
        if (f.length != name.length) return name
        val sp = SpannableString(name)
        for (t in terms) {
            if (t.contains('*') || t.contains('?')) continue
            var i = f.indexOf(t)
            while (i >= 0) {
                sp.setSpan(StyleSpan(Typeface.BOLD), i, i + t.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sp.setSpan(ForegroundColorSpan(p.ac), i, i + t.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                i = f.indexOf(t, i + t.length)
            }
        }
        return sp
    }

    // ---------------------------------------------------------------- taps and menu

    private fun tap(i: Int) {
        val r = rows.getOrNull(i) ?: return
        if (pseudo(r) && r.badge.startsWith("#h:") && sel.isNotEmpty()) { toggleSection(i); return }   // selecting: a section header selects / clears the whole section
        if (pseudo(r)) { pseudoTap(r); return }
        if (sel.isNotEmpty()) { toggle(r); return }
        hideKb()
        commit()
        val v = UsSearch.virt(r.path)
        if (r.dir) {
            if (v == null) { toast("Folders outside the main storage cannot be opened here"); return }
            StorageActions.reveal(this, v); return
        }
        val f = File(r.path)
        if (!f.exists()) { toast("It no longer exists"); runQuery(true, maxOf(PAGE, rows.size)); return }
        val pics = if (r.badge.isNotEmpty()) rows.filter { it.badge.isNotEmpty() && !pseudo(it) && UsSearch.virt(it.path) != null }.map { File(it.path) } else null   // picture results: the viewer swipes through the result list
        StorageActions.open(this, f, false, v, if (r.line > 0 && !f.extension.lowercase().let { it in MARKUP_EXT }) r.line else 0, if (pics != null && pics.size > 1) pics else null)
    }

    private fun pseudoTap(r: UsSearch.Row) {
        val q = when {
            r.badge == "#r" -> r.name
            r.badge == "#s" -> r.snippet
            r.badge.startsWith("#h:") -> secs.firstOrNull { it.id == r.badge.substring(3) }?.let { it.query }   // header: open only this section, with paging
            else -> null
        } ?: return
        et.setText(q); et.setSelection(q.length)
    }

    private fun longTap(i: Int) {
        val r = rows.getOrNull(i) ?: return
        if (pseudo(r)) {
            if (r.badge.startsWith("#h:")) { hideKb(); toggleSection(i) }
            else if (r.badge == "#r") { recent = UsHistory.remove(recent, r.name); saveHist(); showHome() }
            else if (r.badge == "#s") { saved = saved.filter { it.name != r.name }; saveHist(); showHome() }
            return
        }
        hideKb(); toggle(r)
    }

    /** Rows of the section whose header is at [i]: everything up to the next header / title row. */
    private fun sectionIds(i: Int): List<Int> {
        val out = ArrayList<Int>()
        var k = i + 1
        while (k < rows.size && !pseudo(rows[k])) { out.add(rows[k].id); k++ }
        return out
    }

    private fun sectionAllSel(i: Int): Boolean { val ids = sectionIds(i); return ids.isNotEmpty() && ids.all { sel.contains(it) } }

    /** Select every result of one category (Names / Contents / Pictures); when all of it is already selected, clear it instead. */
    private fun toggleSection(i: Int) {
        val ids = sectionIds(i); if (ids.isEmpty()) return
        if (ids.all { sel.contains(it) }) sel.removeAll(ids.toSet()) else sel.addAll(ids)
        updateSelBar(); ad.notifyDataSetChanged()
    }

    private fun toggle(r: UsSearch.Row) {
        if (!sel.remove(r.id)) sel.add(r.id)
        updateSelBar(); ad.notifyDataSetChanged()
    }

    private fun endSel(repaint: Boolean = true) {
        if (sel.isEmpty()) return
        sel.clear(); updateSelBar(); if (repaint) ad.notifyDataSetChanged()
    }

    private fun updateSelBar() {
        selBar.visibility = if (sel.isEmpty()) View.GONE else View.VISIBLE
        selTv.text = sel.size.toString() + " selected"
    }

    private fun picked(): List<UsSearch.Row> = rows.filter { sel.contains(it.id) }

    private fun menu() {
        hideKb()
        val items = arrayOf("Sort: " + sortLabel(), "Match options\u2026", "Search everywhere: " + (if (everywhere) "on" else "off"), "Save this search\u2026", "Clear recent searches", "Share results list\u2026", "Index status\u2026", "View options\u2026", "Rescan index now")
        AppPal.dlg(this).setItems(items) { _, w ->
            when (w) {
                0 -> sortDlg()
                1 -> optDlg()
                2 -> { everywhere = !everywhere; saveOpts(); endSel(false); runQuery(true, 0); toast(if (everywhere) "Sections: Names, Contents, Pictures" else "Names only (use content: ocr: ai: for the rest)") }
                3 -> saveDlg()
                4 -> { recent = emptyList(); saveHist(); if (lastText.isBlank()) showHome() }
                5 -> exportList()
                6 -> statusDlg()
                7 -> viewDlg()
                else -> { IndexManager.rescan(); toast("Rescanning the phone"); ui.postDelayed({ refreshBanner(false) }, 600) }
            }
        }.show()
    }

    private fun exportList() {
        val real = rows.filter { !pseudo(it) && it.path.isNotEmpty() }
        if (real.isEmpty()) { toast("Nothing to export"); return }
        val ex = real.map { UsExport.ExRow(it.name, it.path, it.dir, it.size, it.mtime, it.snippet, it.line) }
        AppPal.dlg(this).setTitle(real.size.toString() + " results").setItems(arrayOf("CSV (spreadsheet)", "Paths only")) { _, w ->
            val body = if (w == 0) UsExport.csv(ex) else UsExport.text(ex)
            try {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body)
                    .putExtra(Intent.EXTRA_SUBJECT, "Search results: " + lastText), "Share list"))
            } catch (e: Exception) { toast("Cannot share: " + (e.message ?: e.javaClass.simpleName)) }
        }.show()
    }

    private val sortKeys = listOf("best" to "Best match", "name" to "Name", "path" to "Path", "size" to "Size (biggest first)", "date" to "Date (newest first)")
    private fun sortLabel() = sortKeys.firstOrNull { it.first == sortKey }?.second ?: "Best match"

    private fun sortDlg() {
        AppPal.dlg(this).setTitle("Sort by").setSingleChoiceItems(sortKeys.map { it.second }.toTypedArray(), sortKeys.indexOfFirst { it.first == sortKey }) { dlg, w ->
            sortKey = sortKeys[w].first; saveOpts(); dlg.dismiss(); endSel(false); runQuery(true, 0)
        }.show()
    }

    private fun optDlg() {
        val names = arrayOf("Match case", "Whole word", "Match path", "Diacritics (\u00E7 \u2260 c)", "Regular expression", "Reverse order", "Folders first")
        val on = booleanArrayOf(oCase, oWord, oPath, oDiac, oRe, rev, ff)
        AppPal.dlg(this).setTitle("Match options").setMultiChoiceItems(names, on) { _, w, v -> on[w] = v }
            .setPositiveButton("OK") { _, _ ->
                oCase = on[0]; oWord = on[1]; oPath = on[2]; oDiac = on[3]; oRe = on[4]; rev = on[5]; ff = on[6]
                saveOpts(); endSel(false); runQuery(true, 0)
            }.setNegativeButton("Cancel", null).show()
    }

    private fun saveOpts() {
        getSharedPreferences("us_ui", MODE_PRIVATE).edit().putString("sort", sortKey).putBoolean("rev", rev).putBoolean("ff", ff)
            .putBoolean("case", oCase).putBoolean("word", oWord).putBoolean("path", oPath).putBoolean("diac", oDiac).putBoolean("re", oRe).putBoolean("every2", everywhere).putString("vmode", vmode).putString("vthumb", vthumb).putInt("vcols", vcols).apply()
    }

    // ---------------------------------------------------------------- selection actions

    private fun actCopy() {
        val vs = picked().mapNotNull { UsSearch.virt(it.path) }
        if (vs.isEmpty()) { toast("Only items of the main storage can be copied from here"); return }
        Clip.set("copy", "local", vs)
        toast(vs.size.toString() + " copied \u2013 paste in the file manager")
        endSel()
    }

    private fun actShare() {
        val fs = picked().filter { !it.dir }.map { File(it.path) }.filter { it.isFile }
        if (fs.isEmpty()) { toast("Only files can be shared"); return }
        try {
            val uris = ArrayList<Uri>()
            for (f in fs) uris.add(FileProvider.getUriForFile(this, packageName + ".fileprovider", f))
            val si = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
                     else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            si.type = if (uris.size == 1) (android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(fs[0].extension.lowercase()) ?: "*/*") else "*/*"
            si.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val cd = android.content.ClipData.newRawUri("", uris[0]); for (k in 1 until uris.size) cd.addItem(android.content.ClipData.Item(uris[k]))
            si.clipData = cd
            startActivity(Intent.createChooser(si, "Share").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: IllegalArgumentException) { toast("Cannot share files outside the main storage from here")
        } catch (e: Exception) { toast("Cannot share: " + (e.message ?: e.javaClass.simpleName)) }
    }

    private fun actDetails() {
        val ps = picked(); if (ps.isEmpty()) return
        val msg: String
        if (ps.size == 1) {
            val r = ps[0]
            msg = r.name + "\n\n" + r.path + "\n" + (if (r.dir) "Folder" else NlModel.fmt(r.size)) + "\n" + (if (r.mtime > 0) dateF.format(Date(r.mtime * 1000L)) else "")
        } else {
            val files = ps.count { !it.dir }; val dirs = ps.size - files; val bytes = ps.filter { !it.dir }.sumOf { it.size }
            msg = ps.size.toString() + " items\n" + files + " files, " + dirs + " folders\n" + NlModel.fmt(bytes) + " (files only)"
        }
        AppPal.dlg(this).setTitle("Details").setMessage(msg).setPositiveButton("OK", null).show()
    }

    private fun actFolder() {
        val ps = picked()
        if (ps.size != 1) { toast("Select one item"); return }
        val v = UsSearch.virt(ps[0].path)
        if (v == null) { toast("Only the main storage can be shown in the file manager"); return }
        StorageActions.reveal(this, vdir(v))
    }

    private fun actDelete() {
        val ps = picked(); if (ps.isEmpty()) return
        val vs = ps.mapNotNull { UsSearch.virt(it.path) }.filter { Bin.canTrash(it) }
        if (vs.isEmpty()) { toast("These items cannot be moved to the recycle bin"); return }
        AppPal.dlg(this).setTitle("Delete " + vs.size + (if (vs.size == 1) " item?" else " items?"))
            .setMessage("They are moved to the recycle bin and kept there for " + Bin.KEEP_DAYS + " days.")
            .setPositiveButton("Delete") { _, _ ->
                val keep = maxOf(PAGE, rows.size)
                Thread {
                    var bad = 0
                    for (v in vs) try { Bin.trash(v) } catch (_: Throwable) { bad++ }
                    ui.post {
                        if (bad > 0) toast("$bad could not be deleted")
                        endSel(false); runQuery(true, keep)
                    }
                }.start()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    companion object {
        /** Part 5 strips markup before storing, so line numbers of these files do not match the editor text. */
        val MARKUP_EXT = setOf("html", "htm", "xhtml", "xml", "svg", "gpx")
        const val PAGE = 300
        const val X_Q = "q"
        const val X_KIND = "kind"
        const val X_SCOPE = "scope"
        const val SEC_ROWS = 50
        fun intent(c: Context, q: String = "", kind: String = "", scope: String = ""): Intent = Intent(c, UsActivity::class.java).putExtra(X_Q, q).putExtra(X_KIND, kind).putExtra(X_SCOPE, scope)

        private val IMG = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif")
        private val VID = setOf("mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v", "mpg", "mpeg", "flv", "ogv", "m2ts", "mts")
        private val AUD = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "wma", "amr", "mid")
        private val ZIP = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")

        /** Kind string of [NlFileIcons.draw]: img / vid / aud / zip / pdf / apk or "". */
        fun kindOfName(n: String): String = when (NlFileIcons.extOf(n)) {
            in IMG -> "img"; in VID -> "vid"; in AUD -> "aud"; in ZIP -> "zip"; "pdf" -> "pdf"; "apk" -> "apk"; else -> ""
        }
    }
}
