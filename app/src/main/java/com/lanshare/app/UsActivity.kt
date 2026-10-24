package com.lanshare.app

import android.app.Activity
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
    private lateinit var list: ListView
    private lateinit var selBar: LinearLayout
    private lateinit var selTv: TextView
    private val chips = ArrayList<Pair<String, TextView>>()

    private fun dp(v: Float) = Math.round(v * d)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        d = resources.displayMetrics.density
        p = AppPal.pal(this); night = AppPal.night(this)
        val sp = getSharedPreferences("us_ui", MODE_PRIVATE)
        sortKey = sp.getString("sort", "best") ?: "best"; rev = sp.getBoolean("rev", false); ff = sp.getBoolean("ff", false)
        oCase = sp.getBoolean("case", false); oWord = sp.getBoolean("word", false); oPath = sp.getBoolean("path", false)
        oDiac = sp.getBoolean("diac", false); oRe = sp.getBoolean("re", false)
        kind = b?.getString("kind") ?: intent.getStringExtra(X_KIND) ?: ""
        val q0 = b?.getString("q") ?: intent.getStringExtra(X_Q) ?: ""
        paintBars()
        build()
        et.setText(q0); et.setSelection(q0.length)
        et.requestFocus()
        et.postDelayed({ (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT) }, 150)
        markChips()
        refreshBanner(true)
        runQuery(true, 0)
    }

    override fun onSaveInstanceState(o: Bundle) { super.onSaveInstanceState(o); o.putString("q", et.text.toString()); o.putString("kind", kind) }

    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }
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
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(col); if (bold) typeface = Typeface.DEFAULT_BOLD
        isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
    }

    private fun iconBtn(path: String, desc: String, click: () -> Unit): FrameLayout = FrameLayout(this).apply {
        contentDescription = desc
        addView(NlGlyph(context, NlIcons.path(path), p.fg, dp(24f)), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setOnClickListener { click() }
    }

    private fun build() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(p.bg); fitsSystemWindows = true }
        // search row
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4f), dp(4f), dp(4f), dp(2f)) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(p.cont); cornerRadius = dp(24f).toFloat() }
        }
        box.addView(iconBtn("back", "Back") { finish() }, LinearLayout.LayoutParams(dp(40f), dp(44f)))
        et = EditText(this).apply {
            background = null; setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH; hint = "Search this phone"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setTextColor(p.fg); setHintTextColor(p.mut); setPadding(dp(2f), 0, dp(2f), 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) { }
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) { }
                override fun afterTextChanged(s: Editable?) { endSel(false); runQuery(true, 0) }
            })
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEARCH) { hideKb(); true } else false }
        }
        box.addView(et, LinearLayout.LayoutParams(0, dp(44f), 1f))
        box.addView(iconBtn("close", "Clear") { et.setText(""); et.requestFocus() }, LinearLayout.LayoutParams(dp(40f), dp(44f)))
        bar.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val menuBtn = TextView(this).apply {
            text = "\u22EE"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f); setTextColor(p.fg); gravity = Gravity.CENTER
            contentDescription = "Sort and options"; setOnClickListener { menu() }
        }
        bar.addView(menuBtn, LinearLayout.LayoutParams(dp(40f), dp(44f)))
        root.addView(bar)
        // chips
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val cr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8f), dp(2f), dp(8f), dp(4f)) }
        for ((k, t) in listOf("" to "All", "file" to "Files", "folder" to "Folders", "image" to "Images", "doc" to "Docs", "video" to "Video", "audio" to "Audio")) {
            val c = tv(13f, p.fg).apply {
                text = t; gravity = Gravity.CENTER; setPadding(dp(12f), 0, dp(12f), 0)
                setOnClickListener { kind = k; markChips(); endSel(false); runQuery(true, 0) }
            }
            chips.add(k to c)
            cr.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32f)).apply { rightMargin = dp(6f) })
        }
        hs.addView(cr); root.addView(hs)
        // banner (indexing) + info line
        banner = tv(12f, p.onac).apply {
            setPadding(dp(12f), dp(4f), dp(12f), dp(4f)); visibility = View.GONE
            background = GradientDrawable().apply { setColor(p.ac) }
        }
        root.addView(banner)
        info = tv(12f, p.mut).apply { setPadding(dp(14f), dp(2f), dp(14f), dp(4f)) }
        root.addView(info)
        // list
        list = ListView(this).apply {
            divider = null; setBackgroundColor(p.bg); isFastScrollEnabled = true
            adapter = ad
            setOnItemClickListener { _, _, i, _ -> tap(i) }
            setOnItemLongClickListener { _, _, i, _ -> longTap(i); true }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(v: AbsListView?, s: Int) { if (s == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) hideKb() }
                override fun onScroll(v: AbsListView?, f: Int, c: Int, t: Int) { if (more && !loading && f + c >= t - 8 && t > 0) runQuery(false, 0) }
            })
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        // selection bar
        selBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
            setBackgroundColor(p.cont); setPadding(dp(8f), 0, dp(4f), 0)
        }
        selTv = tv(14f, p.fg, true)
        selBar.addView(selTv, LinearLayout.LayoutParams(0, dp(48f), 1f).apply { leftMargin = dp(4f) })
        for ((t, f) in listOf<Pair<String, () -> Unit>>("Copy" to this::actCopy, "Share" to this::actShare, "Details" to this::actDetails, "Folder" to this::actFolder, "Delete" to this::actDelete)) {
            selBar.addView(tv(13f, p.ac, true).apply { text = t; gravity = Gravity.CENTER; setPadding(dp(8f), 0, dp(8f), 0); setOnClickListener { f() } },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48f)))
        }
        selBar.addView(iconBtn("close", "Cancel selection") { endSel() }, LinearLayout.LayoutParams(dp(40f), dp(48f)))
        root.addView(selBar)
        setContentView(root)
    }

    private fun markChips() {
        for ((k, c) in chips) {
            val on = k == kind
            c.setTextColor(if (on) p.onac else p.fg)
            c.background = GradientDrawable().apply { cornerRadius = dp(16f).toFloat(); if (on) setColor(p.ac) else { setColor(p.bg); setStroke(dp(1f), p.bd) } }
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
        if (effective.isBlank()) {
            token.cancel(); seq++
            rows.clear(); total = 0; more = false; loading = false; terms = emptyList(); ad.notifyDataSetChanged(); setInfo(); return
        }
        token.cancel()
        val tk = CancelToken(); token = tk
        val s = ++seq
        val off = if (reset) 0 else rows.size
        val lim = if (reset) maxOf(PAGE, keep) else PAGE
        val k = kind; val o = opts(); val so = UsSearch.sortOf(sortKey, rev, ff)
        loading = true
        exec.execute {
            val out = try { UsSearch.run(effective, k, o, so, off, lim, tk) } catch (_: Throwable) { null }
            ui.post {
                if (s != seq) return@post
                loading = false
                if (out == null) { rows.clear(); total = 0; more = false; ad.notifyDataSetChanged(); setInfo(); refreshBanner(true); return@post }
                if (reset) { rows.clear(); list.setSelection(0) }
                rows.addAll(out.rows)
                total = out.total; approx = out.approx; ms = out.ms
                more = out.complete && rows.size < total && out.rows.isNotEmpty() && rows.size < 20000
                terms = try { QueryParser.parse(effective).plainWords().map { Fold.fold(it, oDiac) }.filter { it.isNotEmpty() } } catch (_: Throwable) { emptyList() }
                ad.notifyDataSetChanged()
                setInfo()
            }
        }
    }

    private fun setInfo() {
        if (IndexManager.index() == null) { info.text = "Search index is off (Settings \u203A Search index)"; return }
        if (lastText.isBlank() && kind.isEmpty()) { info.text = "Type a name.  ext:pdf  size:>10mb  dm:today  !not  \"exact\"  a|b"; return }
        val n = String.format(Locale.getDefault(), "%,d", total) + (if (approx) "+" else "")
        info.text = (if (total == 1) "1 result" else "$n results") + "  \u00B7  " + ms + " ms"
    }

    private val tick = object : Runnable {
        override fun run() { refreshBanner(false); ui.postDelayed(this, 1500) }
    }

    /** Index progress banner; when the index content changed (indexing grows it, rename / delete / external change) the query runs again. */
    private fun refreshBanner(force: Boolean) {
        val ix = IndexManager.index()
        if (ix == null) { banner.text = "Search index is off \u2013 turn it on in Settings \u203A Search index"; banner.visibility = View.VISIBLE; return }
        val st = IndexManager.status()
        val state = st.optString("state")
        val pct = st.optInt("pct", -1)
        val complete = st.optBoolean("complete")
        val s = state + "|" + st.optInt("entries") + "|" + st.optInt("dead") + "|" + st.optInt("epoch") + "|" + pct
        when {
            state == "paused" -> { banner.text = "Indexing paused \u2013 results may be incomplete"; banner.visibility = View.VISIBLE }
            state == "scanning" && !complete -> { banner.text = "Indexing " + (if (pct >= 0) "$pct % " else "") + "\u2013 results may be incomplete"; banner.visibility = View.VISIBLE }
            !complete -> { banner.text = "Index not complete yet \u2013 results may be incomplete"; banner.visibility = View.VISIBLE }
            else -> banner.visibility = View.GONE
        }
        if (s == stamp) return
        if (force) { stamp = s; return }
        if (loading || sel.isNotEmpty()) return          // try again on the next tick (stamp kept)
        stamp = s
        if (lastText.isNotBlank() || kind.isNotEmpty()) runQuery(true, maxOf(PAGE, rows.size))
    }

    // ---------------------------------------------------------------- list

    private val ad = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(i: Int) = rows[i]
        override fun getItemId(i: Int) = rows[i].id.toLong()
        override fun getView(i: Int, cv: View?, par: ViewGroup?): View {
            val h = (cv?.tag as? Holder) ?: Holder(this@UsActivity).also { it.root.tag = it }
            h.bind(rows[i], i)
            return h.root
        }
    }

    private class Ico(c: Context, val a: UsActivity) : View(c) {
        var name = ""; var dir = false; var ticked = false
        private val pt = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onMeasure(w: Int, h: Int) { val s = a.dp(40f); setMeasuredDimension(s, s) }
        override fun onDraw(cv: Canvas) {
            val s = a.dp(40f).toFloat()
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
            val fl = FrameLayout(c)
            fl.addView(ico, FrameLayout.LayoutParams(dp(40f), dp(40f)))
            img.scaleType = ImageView.ScaleType.CENTER_CROP; img.visibility = View.GONE
            fl.addView(img, FrameLayout.LayoutParams(dp(40f), dp(40f)))
            root.addView(fl, LinearLayout.LayoutParams(dp(40f), dp(40f)))
            val mid = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10f), 0, dp(8f), 0) }
            mid.addView(nameTv); mid.addView(pathTv)
            root.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val rt = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
            rt.addView(sizeTv); rt.addView(dateTv)
            root.addView(rt, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        fun bind(r: UsSearch.Row, i: Int) {
            val picked = sel.contains(r.id)
            root.setBackgroundColor(if (picked) (p.ac and 0x00FFFFFF) or 0x33000000 else Color.TRANSPARENT)
            nameTv.text = hl(r.name)
            val parent = r.path.substringBeforeLast('/', "")
            val v = UsSearch.virt(r.path)
            pathTv.text = (if (v != null) vdir(v) else parent.ifEmpty { "/" }) + (if (r.snippet.isNotEmpty()) "  \u2022  " + (if (r.line > 0) "L" + r.line + ": " else "") + r.snippet else "")
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
        if (sel.isNotEmpty()) { toggle(r); return }
        hideKb()
        val v = UsSearch.virt(r.path)
        if (r.dir) {
            if (v == null) { toast("Folders outside the main storage cannot be opened here"); return }
            StorageActions.reveal(this, v); return
        }
        val f = File(r.path)
        if (!f.exists()) { toast("It no longer exists"); runQuery(true, maxOf(PAGE, rows.size)); return }
        StorageActions.open(this, f, false, v, if (r.line > 0 && !f.extension.lowercase().let { it in MARKUP_EXT }) r.line else 0)
    }

    private fun longTap(i: Int) { val r = rows.getOrNull(i) ?: return; hideKb(); toggle(r) }

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
        val items = arrayOf("Sort: " + sortLabel(), "Match options\u2026", "Rescan index now")
        AppPal.dlg(this).setItems(items) { _, w ->
            when (w) {
                0 -> sortDlg()
                1 -> optDlg()
                else -> { IndexManager.rescan(); toast("Rescanning the phone"); ui.postDelayed({ refreshBanner(false) }, 600) }
            }
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
            .putBoolean("case", oCase).putBoolean("word", oWord).putBoolean("path", oPath).putBoolean("diac", oDiac).putBoolean("re", oRe).apply()
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
        fun intent(c: Context, q: String = "", kind: String = ""): Intent = Intent(c, UsActivity::class.java).putExtra(X_Q, q).putExtra(X_KIND, kind)

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
