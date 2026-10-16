package com.lanshare.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Toast
import android.widget.TextView
import com.lanshare.app.core.Bin
import com.lanshare.app.core.Core
import com.lanshare.app.core.SCat
import com.lanshare.app.core.SNode
import com.lanshare.app.core.Scan
import com.lanshare.app.core.StorageScan
import com.lanshare.app.core.vbase
import com.lanshare.app.core.vjoin
import com.lanshare.app.core.vnorm
import com.lanshare.app.ops.JobOverlay

/**
 * Storage Analyzer, part 3 = the screen shell (part 4 added the treemap, part 5 the size list + Treemap / List switch in the done state, part 6 the navigation: drill-in, breadcrumb, Back goes up, details dialog): header (title + volume Used / Free / Total), scan progress with Cancel,
 * and a summary of the finished scan. Part 7 added the file-type bar (stacked bar + chips: tap = highlight in the treemap / filter in the lists) and the third tab "Largest" (Scan.top). Part 8a added the non-destructive actions (open, share, show in the file manager: [StorageActions], rows in the details dialog, "File manager" button for the open folder); part 8b added deleting a FILE (confirmation, move to the recycle bin on a worker thread, the tree patched in place by [StorageScan.removeFile], views refreshed: [confirmDelete] / [deleteFile] / [afterDelete]); folders follow in part 8c, the rest in part 9
 * (see HANDOVER_STORAGE_ANALYZER.md); [content] is the area they will fill.
 *
 * Opened from the storage pill of the path bar (ui.html -> LSAndroid.openStorage(path)): path "/" = whole main storage,
 * anything else = that folder (recursive). Reads [StorageScan] directly (no HTTP); the same scan objects the /api/sscan* routes use.
 * Leaving the screen does NOT cancel a running scan (it keeps going on its daemon threads; opening the pill again re-attaches to it).
 */
class StorageActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private var vpath = "/"
    private var scan: Scan? = null
    private var night = false
    private var shownState = ""     // what the content area currently shows: run | done | cancel | error
    private var mode = 0            // done state view: 0 = treemap, 1 = size list, 2 = largest files (kept across rebuilds / rotation)
    private var lastBase = 0        // last of the two folder views (0 / 1): where Back and "show in folder" return to from the Largest tab
    private var hl = -1             // part 7: highlighted / filtered file category (SCat index, -1 = none)
    // navigation (part 6)
    private var curScan: Scan? = null   // the scan `cur` belongs to (a new scan object = tree rebuilt = cur must be looked up again)
    private var cur: SNode? = null      // folder shown by the treemap / list
    private var pendingPath: String? = null   // Rescan: scan-relative path to go back to once the new scan is done
    private var lastTm: SNode? = null   // treemap: node tapped last (second tap on the same node = open / details)
    private var selNode: SNode? = null  // node described in the info line
    private var deleting = false        // part 8b: a delete runs on its worker thread (a second one waits)

    // palette
    private var cBg = Color.WHITE; private var cFg = 0xFF161616.toInt(); private var cDim = 0xFF5F5F5F.toInt()
    private var cLine = 0xFFDDDDDD.toInt(); private var cCard = 0xFFF3F3F3.toInt(); private var cAcc = 0xFFC25100.toInt()
    private var cErr = 0xFFC62828.toInt()

    // views
    private lateinit var titleTv: TextView
    private lateinit var subTv: TextView
    private lateinit var volBar: ProgressBar
    private lateinit var volTv: TextView
    private lateinit var content: FrameLayout
    private lateinit var volCard: LinearLayout

    // views of the "running" state
    private var pBar: ProgressBar? = null
    private var pTitle: TextView? = null
    private var pCount: TextView? = null
    private var pCur: TextView? = null
    // views of the "done" state
    private var dBody: LinearLayout? = null
    private var sumSize: TextView? = null
    private var sumCnt: TextView? = null
    private var notesBox: LinearLayout? = null
    private var crumbRow: LinearLayout? = null
    private var crumbScroll: HorizontalScrollView? = null
    private var infoTv: TextView? = null
    private var actBtn: TextView? = null
    private var tmV: StorageTreemap? = null
    private var lsV: StorageList? = null
    private var tpV: StorageList? = null              // Largest files (flat list)
    private var catBox: LinearLayout? = null          // file-type bar + chips
    private val segB = arrayOfNulls<TextView>(3)      // Treemap / List / Largest buttons

    private val tick = object : Runnable {
        override fun run() {
            update()
            if (scan?.state == "run") ui.postDelayed(this, 300)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float) = v * resources.displayMetrics.density

    override fun attachBaseContext(b: Context) = super.attachBaseContext(UiScale.wrap(b))

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        vpath = vnorm(b?.getString(X_PATH) ?: intent.getStringExtra(X_PATH) ?: "/")
        night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (night) { cBg = 0xFF1E1E1E.toInt(); cFg = 0xFFF2F2F2.toInt(); cDim = 0xFFB0B0B0.toInt(); cLine = 0xFF444444.toInt(); cCard = 0xFF2B2B2B.toInt(); cAcc = 0xFFFF8A3D.toInt(); cErr = 0xFFEF5350.toInt() }
        window.statusBarColor = cBg
        window.navigationBarColor = cBg
        if (!night && Build.VERSION.SDK_INT >= 23) {
            var f = window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= 26) f = f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility = f
        }
        buildUi()
        setContentView(rootView)
        JobOverlay.install(this)
        attach(rescan = false)
    }

    override fun onSaveInstanceState(o: Bundle) { super.onSaveInstanceState(o); o.putString(X_PATH, vpath) }

    override fun onResume() {
        super.onResume()
        // came back to the screen: show the live state of the scan again (it may have finished while we were away)
        if (scan != null) { shownState = ""; update(); if (scan?.state == "run") { ui.removeCallbacks(tick); ui.post(tick) } }
    }

    override fun onPause() { ui.removeCallbacks(tick); super.onPause() }

    // ---------------------------------------------------------------- UI
    private lateinit var rootView: LinearLayout

    private fun buildUi() {
        rootView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(cBg) }

        // header: back + title / subtitle + Rescan
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(8), dp(8), dp(6)) }
        head.addView(TextView(this).apply {
            text = "←"; textSize = 24f; setTextColor(cFg); gravity = Gravity.CENTER; contentDescription = "Back"
            setPadding(dp(14), dp(4), dp(14), dp(4)); setOnClickListener { onBackPressed() }
        })
        val tcol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleTv = TextView(this).apply { textSize = 20f; setTextColor(cFg); setTypeface(typeface, Typeface.BOLD); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        subTv = TextView(this).apply { textSize = 13f; setTextColor(cDim); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }
        tcol.addView(titleTv); tcol.addView(subTv)
        head.addView(tcol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(Button(this).apply { text = "Rescan"; isAllCaps = false; setOnClickListener { attach(rescan = true) } })
        rootView.addView(head)

        // volume card: used bar + Used / Free / Total (moved here from the old spaceInfo sheet)
        val vol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)); background = rounded(cCard, 12f) }
        volBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progress = 0; progressDrawable = barDrawable(cAcc, cLine) }
        vol.addView(volBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)))
        volTv = TextView(this).apply { textSize = 14f; setTextColor(cFg); setPadding(0, dp(8), 0, 0) }
        vol.addView(volTv)
        rootView.addView(vol, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(12), dp(4), dp(12), dp(8)) })
        volCard = vol

        content = FrameLayout(this)
        rootView.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        titleTv.text = if (vpath == "/") "Storage analyzer" else vbase(vpath)
        subTv.text = if (vpath == "/") "Whole storage" else vpath
        fillVolume()
    }

    private fun rounded(c: Int, r: Float) = GradientDrawable().apply { setColor(c); cornerRadius = dpf(r) }

    private fun barDrawable(fg: Int, bg: Int): android.graphics.drawable.Drawable {
        val b = GradientDrawable().apply { setColor(bg); cornerRadius = dpf(5f) }
        val p = android.graphics.drawable.ClipDrawable(GradientDrawable().apply { setColor(fg); cornerRadius = dpf(5f) }, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)
        return android.graphics.drawable.LayerDrawable(arrayOf(b, p)).also { it.setId(0, android.R.id.background); it.setId(1, android.R.id.progress) }
    }

    private fun fmt(n: Long) = NlModel.fmt(n)

    /** Used / Free / Total of the main storage volume (not of the folder: the pill's old sheet showed the same). */
    private fun fillVolume() {
        try {
            val r = Core.local.root
            val total = r.totalSpace; val free = r.usableSpace
            if (total <= 0) { volTv.text = "No size information available."; volBar.progress = 0; return }
            val used = (total - free).coerceAtLeast(0L)
            val pc = Math.round(used * 100.0 / total).toInt()
            volBar.progress = (used * 1000 / total).toInt()
            volTv.text = "Used ${fmt(used)} ($pc%)   ·   Free ${fmt(free)} (${100 - pc}%)   ·   Total ${fmt(total)}"
            if (free.toDouble() / total < 0.05) volBar.progressDrawable = barDrawable(cErr, cLine)
        } catch (_: Throwable) { volTv.text = "No size information available." }
    }

    // ---------------------------------------------------------------- scan
    /** Attach to the running / newest finished scan of this folder, or start one ([rescan] = ignore a finished one). */
    private fun attach(rescan: Boolean) {
        ui.removeCallbacks(tick)
        pendingPath = if (rescan && shownState == "done") cur?.path() else null
        try {
            if (vpath.contains('!')) throw IllegalArgumentException("not inside an archive")
            val dir = Core.local.real(vpath)
            val whole = vpath == "/"
            var s = StorageScan.running(dir)
            if (s == null && !rescan) s = StorageScan.latest(dir)
            if (s == null) {
                var used = 0L
                if (whole) try { val r = Core.local.root; used = maxOf(0L, r.totalSpace - r.usableSpace) } catch (_: Exception) {}   // only feeds the percentage
                s = StorageScan.start(dir, if (whole) "" else vbase(vpath), used)
            }
            scan = s
        } catch (e: Throwable) {
            scan = null
            showMessage("Cannot analyze this folder", com.lanshare.app.core.errText(e), canRetry = false)
            return
        }
        shownState = ""
        fillVolume()
        update()
        if (scan?.state == "run") ui.post(tick)
    }

    private fun update() {
        val s = scan ?: return
        val st = s.state
        if (st != shownState) {
            shownState = st
            when (st) {
                "run" -> buildRunning()
                "done" -> buildDone(s)
                "cancel" -> showMessage("Scan cancelled", "Nothing was kept. " + fmt(s.bytes.get()) + " in " + s.files.get() + " files had been read.", canRetry = true)
                else -> showMessage("Scan failed", s.error ?: "unknown error", canRetry = true)
            }
        }
        if (st == "run") paintRunning(s)
    }

    private fun buildRunning() {
        content.removeAllViews(); volCard.visibility = View.VISIBLE
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(24), dp(24), dp(24), dp(24)) }
        pTitle = TextView(this).apply { textSize = 18f; setTextColor(cFg); setTypeface(typeface, Typeface.BOLD); text = "Scanning…" }
        col.addView(pTitle)
        pBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progressDrawable = barDrawable(cAcc, cLine) }
        col.addView(pBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)).apply { topMargin = dp(14) })
        pCount = TextView(this).apply { textSize = 15f; setTextColor(cFg); gravity = Gravity.CENTER; setPadding(0, dp(14), 0, 0) }
        col.addView(pCount)
        pCur = TextView(this).apply { textSize = 12f; setTextColor(cDim); gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; setPadding(0, dp(8), 0, 0) }
        col.addView(pCur)
        col.addView(Button(this).apply {
            text = "Cancel"; isAllCaps = false
            setOnClickListener { scan?.let { StorageScan.cancel(it.id) }; isEnabled = false; text = "Cancelling…"; ui.postDelayed({ update() }, 150) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })
        content.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
    }

    private fun paintRunning(s: Scan) {
        val pc = s.percent()
        pBar?.let { if (pc >= 0) { it.isIndeterminate = false; it.progress = pc } else it.isIndeterminate = true }
        pTitle?.text = if (pc >= 0) "Scanning… $pc%" else "Scanning…"
        pCount?.text = s.files.get().toString() + " files · " + s.dirs.get() + " folders · " + fmt(s.bytes.get())
        pCur?.text = s.cur
        // the scan may have ended between two ticks: the next update() switches the state
        if (s.state != "run") ui.post { update() }
    }

    private fun showMessage(title: String, text: String, canRetry: Boolean) {
        shownState = if (scan == null) "error" else shownState
        content.removeAllViews(); volCard.visibility = View.VISIBLE
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(24), dp(32), dp(24), dp(24)) }
        col.addView(TextView(this).apply { this.text = title; textSize = 18f; setTextColor(cFg); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER })
        col.addView(TextView(this).apply { this.text = text; textSize = 14f; setTextColor(cDim); gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) })
        if (canRetry) col.addView(Button(this).apply { this.text = "Scan again"; isAllCaps = false; setOnClickListener { attach(rescan = true) } },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
        content.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
    }

    /** Finished: summary, breadcrumb, Treemap / List switch (part 5), the chosen view, info line + action button. Tap folder = drill in (part 6). */
    private fun buildDone(s: Scan) {
        content.removeAllViews()
        if (curScan !== s) {            // new tree: start at the root, or at the folder the user was in before Rescan
            curScan = s
            cur = pendingPath?.let { s.find(it) } ?: s.root
            pendingPath = null; lastTm = null; selNode = null
        }
        val root = s.root
        val total = root?.size ?: s.bytes.get()
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(4)) }
        dBody = col
        sumSize = TextView(this).apply { textSize = 28f; setTextColor(cFg); setTypeface(typeface, Typeface.BOLD) }
        sumCnt = TextView(this).apply { textSize = 14f; setTextColor(cDim); setPadding(0, 0, 0, dp(4)) }
        col.addView(sumSize); col.addView(sumCnt)
        // notes about the whole scan: only shown while the scan root is open (saves room when drilled in)
        val nb = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        notesBox = nb
        if (vpath == "/") {
            // whole storage: what the scan could not read = used minus scanned (part 9 explains it properly)
            val gap = s.usedBytes - total
            if (s.usedBytes > 0 && gap > 0) nb.addView(note("System / other: " + fmt(gap) + " (used space the scan could not see)"))
        }
        if (s.denied.get() > 0) nb.addView(note(s.denied.get().toString() + " folder(s) could not be read" + (if (s.deniedSample.isNotEmpty()) ", e.g. " + s.deniedSample.first() else "")))
        if (root == null || total == 0L) nb.addView(note("This folder is empty."))
        col.addView(nb)
        wrap.addView(col, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        volCard.visibility = View.VISIBLE
        tmV = null; lsV = null; tpV = null; catBox = null; infoTv = null; actBtn = null; crumbRow = null; crumbScroll = null
        if (root != null && total > 0L) {
            // breadcrumb: Storage > DCIM > Camera (tap a part = go there)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(8), 0) }
            val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
            crumbRow = row; crumbScroll = hs
            wrap.addView(hs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            val info = TextView(this).apply {
                textSize = 13f; setTextColor(cDim); maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }
            infoTv = info
            val act = TextView(this).apply {
                textSize = 14f; gravity = Gravity.CENTER; isClickable = true; minimumHeight = dp(40); minimumWidth = dp(72)
                setPadding(dp(14), dp(8), dp(14), dp(8)); background = rounded(cAcc, 10f); setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD); visibility = View.GONE
                setOnClickListener {
                    val n = selNode
                    if (n == null) { cur?.let { c -> StorageActions.reveal(this@StorageActivity, StorageActions.folderOf(vpath, c)) } }   // nothing selected: the open folder in the file manager (part 8a)
                    else if (mode == 2) showIn(n) else if (canOpen(n)) showNode(n) else details(n)
                }
            }
            actBtn = act
            val tm = StorageTreemap(this).apply {
                colBg = cBg; colFg = cFg; colDim = cDim; colAcc = cAcc
                onTap = { nd ->
                    val again = nd === lastTm          // second tap on the same tile = open (folder) / details
                    lastTm = nd
                    setInfo(nd)
                    if (again) { if (canOpen(nd)) showNode(nd) else details(nd) }
                }
            }
            val ls = StorageList(this).apply {
                colBg = cBg; colFg = cFg; colDim = cDim; colAcc = cAcc; colLine = cLine
                onTap = { nd -> setInfo(nd); if (canOpen(nd)) showNode(nd) else details(nd) }   // folder = open at once
            }
            // Largest files: the 100 biggest files of the whole scan as a flat list (part 7); a tap = info + details, the button / dialog shows the file in its folder
            val tp = StorageList(this).apply {
                colBg = cBg; colFg = cFg; colDim = cDim; colAcc = cAcc; colLine = cLine
                onTap = { nd -> setInfo(nd); details(nd) }
                setFlat(s.top, root.size)
            }
            tmV = tm; lsV = ls; tpV = tp
            tm.hl = hl; ls.setFilter(hl); tp.setFilter(hl)
            val views = FrameLayout(this)
            views.addView(tm, FrameLayout.LayoutParams(-1, -1))
            views.addView(ls, FrameLayout.LayoutParams(-1, -1))
            views.addView(tp, FrameLayout.LayoutParams(-1, -1))

            // file-type bar (part 7): between the breadcrumb and the Treemap / List / Largest switch
            val cb = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(2), dp(12), dp(4)) }
            catBox = cb
            wrap.addView(cb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            // Treemap / List / Largest switch
            val sw = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(2), dp(12), dp(8)) }
            val names = arrayOf("Treemap", "List", "Largest")
            for (i in 0..2) {
                val b = segButton(names[i])
                segB[i] = b
                b.setOnClickListener { setMode(i) }
                sw.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) leftMargin = dp(8) })
            }
            wrap.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            wrap.addView(views, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(dp(8), 0, dp(8), 0) })
            val ir = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(6), dp(12), dp(8)) }
            ir.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            ir.addView(act, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
            wrap.addView(ir, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            showNode(cur ?: root)
            setMode(mode)
        } else paintSummary()
        content.addView(wrap, FrameLayout.LayoutParams(-1, -1))
    }

    // ---------------------------------------------------------------- navigation (part 6)
    /** A folder with something inside can be opened (empty / unreadable folders, files and the "small files" node cannot). */
    private fun canOpen(n: SNode) = n.dir && !n.agg && n.kids.isNotEmpty()

    /** Show folder [n] (a node of the current scan) in both views; refreshes summary, breadcrumb, info line. */
    private fun showNode(n: SNode) {
        cur = n; lastTm = null
        tmV?.setRoot(n); lsV?.setRoot(n)
        val atRoot = n === curScan?.root
        volCard.visibility = if (atRoot) View.VISIBLE else View.GONE      // room for the treemap when drilled in
        notesBox?.visibility = if (atRoot) View.VISIBLE else View.GONE
        paintSummary()
        paintCrumb()
        paintCats()
        setInfo(null)
    }

    /** Switch the done-state view: 0 treemap, 1 size list, 2 largest files (part 7). */
    private fun setMode(m: Int) {
        mode = m
        if (m != 2) lastBase = m
        tmV?.visibility = if (m == 0) View.VISIBLE else View.GONE
        lsV?.visibility = if (m == 1) View.VISIBLE else View.GONE
        tpV?.visibility = if (m == 2) View.VISIBLE else View.GONE
        for (i in 0..2) segB[i]?.let { paintSeg(it, i == m) }
        lastTm = null
        paintCats()
        setInfo(null)
    }

    /** Open folder [n] from the breadcrumb: leaves the Largest tab first (it does not show folders). */
    private fun openFolder(n: SNode) {
        if (mode == 2) setMode(lastBase)
        showNode(n)
    }

    /** Largest tab: show file [n] inside its folder (List tab, row highlighted). */
    private fun showIn(n: SNode) {
        val up = n.parent ?: return
        setMode(1)
        showNode(up)
        lsV?.select(n)
        setInfo(n)
    }

    // ---------------------------------------------------------------- file types (part 7)
    /** Set the highlighted / filtered category ([c] = same as now: switch it off) and tell both views. */
    private fun setHl(c: Int) {
        hl = if (hl == c) -1 else c
        applyHl()
    }

    private fun applyHl() {
        tmV?.hl = hl; lsV?.setFilter(hl); tpV?.setFilter(hl)
        lastTm = null
        paintCats()
        setInfo(null)
    }

    /** File-type bar of the open folder (of the whole scan on the Largest tab): stacked bar + one chip per category, biggest first. Tap = highlight / filter. */
    private fun paintCats() {
        val box = catBox ?: return
        box.removeAllViews()
        val s = curScan ?: return
        val node = (if (mode == 2) s.root else cur) ?: return
        val cats = node.cats
        var total = 0L
        if (cats != null) for (i in 0 until Math.min(cats.size, SCat.COUNT)) total += cats[i]
        if (cats == null || total <= 0L) { box.visibility = View.GONE; return }
        if (hl >= 0 && (hl >= cats.size || cats[hl] <= 0L)) { hl = -1; tmV?.hl = -1; lsV?.setFilter(-1); tpV?.setFilter(-1) }   // nothing of it here
        box.visibility = View.VISIBLE
        val order = (0 until Math.min(cats.size, SCat.COUNT)).filter { cats[it] > 0L }.sortedByDescending { cats[it] }
        // stacked bar: one coloured segment per category (weight = bytes), the others faded while one is highlighted
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = rounded(cLine, 4f) }
        for (c in order) {
            bar.addView(View(this).apply {
                setBackgroundColor(StorageTreemap.CAT_COL[c]); alpha = if (hl < 0 || hl == c) 1f else 0.25f
                setOnClickListener { setHl(c) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, cats[c].toFloat()).apply { rightMargin = 1 })
        }
        box.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)))
        // chips: dot + name + size (selected chip = filled with the category colour)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        for (c in order) {
            val on = hl == c
            val col = StorageTreemap.CAT_COL[c]
            val label = CAT_NAMES[c] + " " + fmt(cats[c])
            val sp = SpannableString("● " + label)
            if (!on) sp.setSpan(ForegroundColorSpan(col), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            row.addView(TextView(this).apply {
                text = sp; textSize = 13f; maxLines = 1; isClickable = true
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = if (on) rounded(col, 14f) else rounded(cCard, 14f)
                setTextColor(if (on) (if (lumOf(col) > 0.6) 0xFF111111.toInt() else Color.WHITE) else cFg)
                setOnClickListener { setHl(c) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) })
        }
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
        box.addView(hs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun lumOf(c: Int) = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0

    /** Big size + counts of the open folder (at the scan root: the scan totals and its duration). */
    private fun paintSummary() {
        val s = curScan ?: return
        val root = s.root
        val n = cur
        if (n == null || root == null || n === root) {
            sumSize?.text = fmt(root?.size ?: s.bytes.get())
            sumCnt?.text = (root?.files ?: s.files.get()).toString() + " files · " + (root?.folders ?: s.dirs.get()) + " folders · scanned in " +
                String.format(java.util.Locale.US, "%.1f", s.millis() / 1000.0) + " s"
        } else {
            val pct = if (root.size > 0) String.format(java.util.Locale.US, "%.1f", n.size * 100.0 / root.size) else "0"
            sumSize?.text = fmt(n.size)
            sumCnt?.text = n.files.toString() + " files · " + n.folders + " folders · " + pct + "% of scan"
        }
    }

    private fun rootLabel() = if (vpath == "/") "Storage" else vbase(vpath)

    /** Breadcrumb of the open folder: every part but the last is tappable. */
    private fun paintCrumb() {
        val row = crumbRow ?: return
        row.removeAllViews()
        val chain = ArrayList<SNode>()
        var n = cur
        while (n != null) { chain.add(0, n); n = n.parent }
        for (i in chain.indices) {
            val nd = chain[i]
            val last = i == chain.size - 1
            if (i > 0) row.addView(TextView(this).apply { text = "›"; textSize = 16f; setTextColor(cDim) })
            row.addView(TextView(this).apply {
                text = if (nd.parent == null) rootLabel() else nd.name
                textSize = 14f; maxLines = 1
                setTextColor(if (last) cFg else cAcc)
                setTypeface(null, if (last) Typeface.BOLD else Typeface.NORMAL)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                if (!last) setOnClickListener { openFolder(nd) }
            })
        }
        crumbScroll?.post { crumbScroll?.fullScroll(View.FOCUS_RIGHT) }
    }

    /** Info line + action button for the selected node ([n] = null: hint, no button). */
    private fun setInfo(n: SNode?) {
        selNode = n
        val tv = infoTv ?: return
        val b = actBtn ?: return
        val c = cur ?: return
        if (n == null) {
            val only = if (hl >= 0) "Only " + CAT_NAMES[hl].lowercase() + " shown (tap its chip again to show all). " else ""
            tv.text = only + when (mode) {
                0 -> "Tap a tile for details, tap it again to open a folder."
                1 -> "Tap a folder to open it, a file for details."
                else -> "The 100 largest files of the whole scan. Tap a file for details."
            }
            if (mode == 2) b.visibility = View.GONE
            else { b.text = "File manager"; b.visibility = View.VISIBLE }      // part 8a: shows the open folder in the file manager
            return
        }
        tv.text = if (mode == 2) describe(n, curScan?.root?.size ?: c.size, true) else describe(n, c.size, false)
        b.text = if (mode == 2) "Show" else if (canOpen(n)) "Open" else "Details"
        b.visibility = View.VISIBLE
    }

    /** Dialog with the details of a file, of the "small files" node or of a folder that cannot be opened. */
    private fun details(n: SNode) {
        val c = cur ?: return
        val s = curScan ?: return
        val rootSize = s.root?.size ?: 0L
        fun pc(v: Long, of: Long) = if (of > 0) String.format(java.util.Locale.US, "%.1f%%", v * 100.0 / of) else "-"
        val v = vjoin(vpath, n.path())
        val real = try { Core.local.real(v).absolutePath } catch (_: Throwable) { v }
        val sb = StringBuilder()
        if (n.agg) {
            sb.append(n.files).append(" small files kept together\n")
            sb.append("Total size: ").append(fmt(n.size)).append('\n')
            if (n.files > 0) sb.append("Average: ").append(fmt(n.size / n.files)).append('\n')
            sb.append("In: ").append(real)
        } else {
            sb.append(if (n.dir) "Folder" else "Type: " + CAT_NAMES[n.cat.coerceIn(0, SCat.COUNT - 1)]).append('\n')
            sb.append("Size: ").append(fmt(n.size)).append(" (").append(String.format(java.util.Locale.US, "%,d", n.size)).append(" bytes)\n")
            if (n.dir) {
                sb.append(n.files).append(" files · ").append(n.folders).append(" folders\n")
                if (n.denied) sb.append("Android does not let this app read this folder.\n")
                else if (n.kids.isEmpty()) sb.append("Empty.\n")
            }
            if (mode == 2) sb.append(pc(n.size, rootSize)).append(" of the scan\n")
            else sb.append(pc(n.size, c.size)).append(" of the open folder · ").append(pc(n.size, rootSize)).append(" of the scan\n")
            sb.append("Path: ").append(real)
        }
        val up = n.parent
        // part 8a: message + one row per action (a plain AlertDialog only has three buttons)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(4)) }
        box.addView(TextView(this).apply { text = sb.toString(); textSize = 14f; setTextIsSelectable(true) })
        val dlg = AlertDialog.Builder(this)
            .setTitle(if (n.agg) "Small files" else n.name)
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Close", null)
            .create()
        fun act(label: String, color: Int = cAcc, fn: () -> Unit) {
            box.addView(TextView(this).apply {
                text = label; textSize = 15f; setTextColor(color); setTypeface(typeface, Typeface.BOLD); isClickable = true
                minimumHeight = dp(44); gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { dlg.dismiss(); fn() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        }
        if (!n.dir && !n.agg) {
            act("Open") { StorageActions.realOrToast(this, vpath, n)?.let { StorageActions.open(this, it) } }
            act("Open with…") { StorageActions.realOrToast(this, vpath, n)?.let { StorageActions.open(this, it, pick = true) } }
            act("Share") { StorageActions.realOrToast(this, vpath, n)?.let { StorageActions.share(this, it) } }
        }
        act("Show in file manager") { StorageActions.reveal(this, StorageActions.folderOf(vpath, n)) }
        if (mode == 2 && up != null) act("Show in folder") { showIn(n) }
        else if (up != null && up !== c) act("Open containing folder") { showNode(up) }
        if (!n.dir && !n.agg) act("Delete…", cErr) { confirmDelete(n) }       // part 8b (folders: part 8c)
        dlg.show()
    }

    // ---------------------------------------------------------------- delete (part 8b)
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    /** Ask before deleting file [n]; the file only goes to the recycle bin (restorable), like a delete in the file manager. */
    private fun confirmDelete(n: SNode) {
        if (n.dir || n.agg) return
        val s = curScan ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete this file?")
            .setMessage(n.name + "\n" + fmt(n.size) + "\n\nIt is moved to the recycle bin and kept there for " + Bin.KEEP_DAYS + " days. You can restore it from the recycle bin of the file manager.")
            .setPositiveButton("Delete") { _, _ -> deleteFile(s, n) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Move file [n] of scan [s] into the recycle bin (worker thread; a rename on the same volume, so it is instant), then patch the tree on the main thread. */
    private fun deleteFile(s: Scan, n: SNode) {
        if (deleting) { toast("Another delete is still running"); return }
        val v = StorageActions.virt(vpath, n)
        if (Bin.inBin(v)) { toast("This file is already in the recycle bin. Restore or empty it from the file manager."); return }
        if (!Bin.canTrash(v)) { toast("This file cannot be moved to the recycle bin"); return }
        deleting = true
        Thread({
            var err: String? = null
            var gone = false
            try {
                val f = Core.local.real(v)
                if (!f.exists() && !Core.local.isLink(f)) gone = true else Bin.trash(v)
            } catch (e: Throwable) { err = com.lanshare.app.core.errText(e) }
            ui.post {
                deleting = false
                if (err != null) toast("Could not delete: " + err)
                else afterDelete(s, n, if (gone) "It was already gone - removed from the result" else "Moved to the recycle bin: " + n.name + " (" + fmt(n.size) + ")")
            }
        }, "sscan-del").also { it.isDaemon = true }.start()
    }

    /** The file is gone from the storage: patch the tree (also when the screen is closed meanwhile: the scan object is shared), then refresh what is shown. */
    private fun afterDelete(s: Scan, n: SNode, msg: String) {
        val near = StorageScan.removeFile(s, n)
        toast(msg)
        if (near == null || isDestroyed || isFinishing || curScan !== s || shownState != "done") return
        val root = s.root ?: return
        selNode = null; lastTm = null
        if (root.size <= 0L) { shownState = ""; update(); return }          // nothing left: the "empty" summary
        var c = cur ?: root
        if (!attached(c, root)) c = near                                      // the open folder became empty and was dropped: go to the nearest one left
        val lp = lsV?.firstVisiblePosition ?: 0
        val tp = tpV?.firstVisiblePosition ?: 0
        showNode(c)                                                           // both folder views, summary, breadcrumb, file-type bar, info line
        tpV?.setFlat(s.top, root.size)                                        // Largest tab (the category filter stays)
        lsV?.setSelection(lp); tpV?.setSelection(tp)                          // keep the scroll position of the lists
    }

    /** Is [n] still reachable from [root] (a folder that became empty is dropped from its parent's kids)? */
    private fun attached(n: SNode, root: SNode): Boolean {
        var x = n
        while (true) {
            val up = x.parent ?: return x === root
            if (up.kids.none { it === x }) return false
            x = up
        }
    }

    /** Back goes up one folder; at the scan root (or while scanning) it leaves the screen. */
    override fun onBackPressed() {
        val up = cur?.parent
        if (shownState == "done" && mode == 2) setMode(lastBase)           // Largest tab: back to the folder view first
        else if (shownState == "done" && up != null) showNode(up) else super.onBackPressed()
    }

    /** One-line description of a tapped tile: path, size, share of the open folder (of the scan at the root). */
    private fun describe(n: SNode, base: Long, scanWide: Boolean): String {
        val pct = if (base > 0) String.format(java.util.Locale.US, "%.1f", n.size * 100.0 / base) else "0"
        val of = if (scanWide || cur === curScan?.root) "% of scan" else "% of folder"
        return if (n.agg) n.files.toString() + " small files in " + n.path() + " · " + fmt(n.size) + " · " + pct + of
        else n.path() + " · " + fmt(n.size) + " · " + pct + of + (if (n.dir) " · " + n.files + " files" else "") + (if (n.denied) " · not readable" else "")
    }

    /** Segment button of the Treemap / List switch. */
    private fun segButton(t: String) = TextView(this).apply {
        text = t; textSize = 14f; gravity = Gravity.CENTER; isClickable = true; minimumHeight = dp(40)
        setPadding(dp(12), dp(8), dp(12), dp(8))
    }

    private fun paintSeg(b: TextView, on: Boolean) {
        b.background = rounded(if (on) cAcc else cCard, 10f)
        b.setTextColor(if (on) Color.WHITE else cFg)
        b.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun note(t: String) = TextView(this).apply { text = t; textSize = 13f; setTextColor(cDim); setPadding(0, dp(4), 0, dp(4)) }

    companion object {
        const val X_PATH = "path"
        private val CAT_NAMES = arrayOf("Other", "Images", "Video", "Audio", "Documents", "Archives", "Apps")
        fun intent(c: Context, vpath: String): Intent = Intent(c, StorageActivity::class.java).putExtra(X_PATH, vpath)
    }
}
