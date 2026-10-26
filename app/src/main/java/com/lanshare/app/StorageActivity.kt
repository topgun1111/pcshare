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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
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
import com.lanshare.app.core.StorageStore
import com.lanshare.app.core.Volumes
import com.lanshare.app.core.arcSplit
import com.lanshare.app.core.vbase
import com.lanshare.app.core.vjoin
import com.lanshare.app.core.vnorm
import com.lanshare.app.ops.JobOverlay
import java.io.File

/**
 * Storage Analyzer, part 3 = the screen shell (part 4 added the treemap, part 5 the size list + Treemap / List switch in the done state, part 6 the navigation: drill-in, breadcrumb, Back goes up, details dialog): header (title + volume Used / Free / Total), scan progress with Cancel,
 * and a summary of the finished scan. Part 7 added the file-type bar (stacked bar + chips: tap = highlight in the treemap / filter in the lists) and the third tab "Largest" (Scan.top). Part 8a added the non-destructive actions (open, share, show in the file manager: [StorageActions], rows in the details dialog, "File manager" button for the open folder); part 8b added deleting a FILE (confirmation, move to the recycle bin on a worker thread, the tree patched in place by [StorageScan.removeFile], views refreshed: [confirmDelete] / [deleteNode] / [afterDelete]); part 8c added deleting a FOLDER ([StorageScan.removeNode]); part 9a added the unreadable-part warning and multi-select delete (long-press in the List / Largest tab, [deleteMany]); part 9b added the whole-storage / robustness work (System / other explanation, All files access banner, SD card / USB volumes, saved last result, rescan polish, scan progress bubble); polish + device test in part 10
 * (see HANDOVER_STORAGE_ANALYZER.md); [content] is the area they filled.
 *
 * Opened from the storage pill of the path bar (ui.html -> LSAndroid.openStorage(path)): path "/" = whole main storage,
 * anything else = that folder (recursive). Reads [StorageScan] directly (no HTTP); the same scan objects the /api/sscan* routes use.
 * Leaving the screen does NOT cancel a running scan (it keeps going on its daemon threads; opening the pill again re-attaches to it).
 */
class StorageActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private var vpath = "/"
    private var arcInner: String? = null     // opened from inside an archive: the folder (inside it) to show once the scan is done
    private var dev = "local"                  // another device (LANShare peer id / "smb:..." / "ftp:...") is analyzed: read-only, scanned by StorageScan.startRemote
    private var devName = ""
    private val ro: Boolean get() = inArc || dev != "local"   // nothing can be opened / shared / deleted / revealed
    private val inArc: Boolean get() = dev == "local" && vpath.endsWith("!")   // analyzing the file list of a ZIP / RAR: read-only, nothing can be opened / shared / deleted
    private var scan: Scan? = null
    private var night = false
    private var shownState = ""     // what the content area currently shows: run | done | cancel | error
    private var mode = 1            // done state view: 0 = pie chart, 1 = size list, 2 = largest files (kept across rebuilds / rotation)
    private var lastBase = 1        // last of the two folder views (0 / 1): where Back and "show in folder" return to from the Largest tab
    private var hl = -1             // part 7: highlighted / filtered file category (SCat index, -1 = none)
    // navigation (part 6)
    private var curScan: Scan? = null   // the scan `cur` belongs to (a new scan object = tree rebuilt = cur must be looked up again)
    private var cur: SNode? = null      // folder shown by the pie chart / list
    private var pendingPath: String? = null   // Rescan: scan-relative path to go back to once the new scan is done
    private var lastTm: SNode? = null   // pie chart: node tapped last (second tap on the same node = open / details)
    private var selNode: SNode? = null  // node described in the info line
    private var wiz: WizScreen? = null         // WizTree view (StorageWiz.kt) while it is shown; null = classic screens
    private val wizOn: Boolean get() = getSharedPreferences("storage_ui", MODE_PRIVATE).getBoolean("wiz", true)
    private fun setWizOn(v: Boolean) { getSharedPreferences("storage_ui", MODE_PRIVATE).edit().putBoolean("wiz", v).apply(); val s = curScan ?: return; wiz = null; buildDone(s) }
    /** System bars + window in the colour of the shown screen (the WizTree view has its own themes); [darkIcons] = light background. */
    private fun paintBars(bg: Int, darkIcons: Boolean) {
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bg))
        window.statusBarColor = bg; window.navigationBarColor = bg
        if (::rootView.isInitialized) rootView.setBackgroundColor(bg)
        if (Build.VERSION.SDK_INT >= 23) {
            var f = window.decorView.systemUiVisibility
            f = if (darkIcons) f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else f and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            if (Build.VERSION.SDK_INT >= 26) f = if (darkIcons) f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else f and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            window.decorView.systemUiVisibility = f
        }
    }
    private val wizHost = object : WizHost {
        override val label: String get() = rootLabel()
        override fun details(n: SNode) { if (cur == null) cur = curScan?.root; this@StorageActivity.details(n) }
        override fun classic() { paintBars(cBg, !night); setWizOn(false) }
        override fun bars(bg: Int, darkIcons: Boolean) = paintBars(bg, darkIcons)
        override fun rebuild() { val sc = curScan ?: return; wiz = null; buildDone(sc) }
        override fun rescan() = attach(rescan = true)
        override fun volume(): LongArray? = try { val r = volRoot(); val t = r.totalSpace; val f = r.usableSpace; if (t > 0 && vpath == "/") longArrayOf((t - f).coerceAtLeast(0L), f, t) else null } catch (_: Throwable) { null }
        override fun realFile(n: SNode): File? = try { realOf(n) } catch (_: Throwable) { null }
        override fun nearest(n: SNode): SNode { val r = curScan?.root ?: return n; return nearestAttached(n, r) }
        override fun appsOk(): Boolean = AppsUtil.supported && dev == "local" && vpath == "/"
    }
    private var deleting = false        // part 8b: a delete runs on its worker thread (a second one waits)
    private var selBar: LinearLayout? = null   // part 9a: bar shown instead of the info line while rows are ticked
    private var selTv: TextView? = null
    private var irRow: LinearLayout? = null    // the info line + action button row
    private var sortMode = 0                   // StorageList sort (kept across rebuilds)
    private var query = ""                     // name filter of the List / Largest tabs (cleared when another folder is opened)
    private var qEt: EditText? = null
    private var sortBtn: TextView? = null
    // part 9b
    private var volPath: String? = null        // absolute path of the OTHER volume (SD card / USB) being analyzed; null = the main storage. Only used while vpath == "/"
    private var volName = ""
    private var vols: List<Volumes.Vol> = emptyList()
    private var attachSeq = 0                  // bumped by every attach(): an answer of an older one (saved result loading) is ignored
    private var rescanBtn: Button? = null
    private var accessBar: LinearLayout? = null
    private var volScroll: HorizontalScrollView? = null
    private var volRow: LinearLayout? = null
    private var pHint: TextView? = null

    // palette
    private var cBg = Color.WHITE; private var cFg = 0xFF161616.toInt(); private var cDim = 0xFF5F5F5F.toInt()
    private var cLine = 0xFFDDDDDD.toInt(); private var cCard = 0xFFF3F3F3.toInt(); private var cAcc = 0xFFC25100.toInt()
    private var cErr = 0xFFC62828.toInt()
    private var cOnAcc = Color.WHITE            // text / icon colour on cAcc
    private var sysNight = false
    private var palSig: String? = null          // app theme palette this screen was built with

    /** App theme first (the palette the page pushed, AppPal), the system day / night mode only when the page never sent one. */
    private fun applyTheme() {
        sysNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        palSig = AppPal.json(this)
        val p = AppPal.load(this)
        if (p != null) {
            cBg = p.bg; cFg = p.fg; cDim = p.mut; cLine = p.bd; cCard = p.cont; cAcc = p.ac; cOnAcc = p.onac
        } else if (sysNight) {
            cBg = 0xFF1E1E1E.toInt(); cFg = 0xFFF2F2F2.toInt(); cDim = 0xFFB0B0B0.toInt(); cLine = 0xFF444444.toInt(); cCard = 0xFF2B2B2B.toInt(); cAcc = 0xFFFF8A3D.toInt(); cOnAcc = Color.WHITE
        }
        night = lumOf(cBg) < 0.5
        cErr = if (night) 0xFFEF5350.toInt() else 0xFFC62828.toInt()
    }

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        // no palette from the page: follow the system mode live; with a palette the page pushes the new one (checked in onResume)
        val nn = (c.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (palSig == null && nn != sysNight) recreate()
    }

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
    private var tmV: StoragePie? = null
    private var lsV: StorageList? = null
    private var tpV: StorageList? = null              // Largest files (flat list)
    private var catBox: LinearLayout? = null          // file-type bar + chips
    private val segB = arrayOfNulls<TextView>(3)      // Pie / List / Largest buttons

    private val tick = object : Runnable {
        override fun run() {
            update()
            if (scan?.state == "run") ui.postDelayed(this, 300)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float) = v * resources.displayMetrics.density

    override fun attachBaseContext(b: Context) { super.attachBaseContext(b); UiScale.attach(this, b) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        wizState = b?.getString("wizstate")?.takeIf { it.isNotEmpty() }
        vpath = vnorm(b?.getString(X_PATH) ?: intent.getStringExtra(X_PATH) ?: "/")
        dev = b?.getString(X_DEV) ?: intent.getStringExtra(X_DEV) ?: "local"
        devName = b?.getString(X_DEVNAME) ?: intent.getStringExtra(X_DEVNAME) ?: ""
        if (dev == "local") arcSplit(vpath)?.let { (a, inner) -> vpath = "$a!"; arcInner = inner.takeIf { it != "/" } }   // "/x/a.zip!/dir" -> scan the whole archive, then show "dir"
        vols = try { Volumes.extra(this) } catch (_: Throwable) { emptyList() }
        val wantVol = b?.getString(X_VOL) ?: intent.getStringExtra(X_VOL)
        if (dev == "local" && vpath == "/" && wantVol != null) vols.firstOrNull { it.path == wantVol }?.let { volPath = it.path; volName = it.name }
        applyTheme()
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(cBg))
        window.statusBarColor = cBg
        window.navigationBarColor = cBg
        if (!night && Build.VERSION.SDK_INT >= 23) {
            var f = window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= 26) f = f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility = f
        }
        buildUi()
        setContentView(rootView)
        JobOverlay.install(this, StorageScan.JOB_PREFIX)    // the scan's own bubble is hidden here: this screen shows the progress itself
        paintAccess()
        attach(rescan = false)
    }

    private var wizState: String? = null      // where the user was in the WizTree view (restored once the screen is built again)

    override fun onSaveInstanceState(o: Bundle) { super.onSaveInstanceState(o); wiz?.let { o.putString("wizstate", it.saveState()) }; o.putString(X_PATH, vpath); o.putString(X_DEV, dev); o.putString(X_DEVNAME, devName); volPath?.let { o.putString(X_VOL, it) } }

    override fun onResume() {
        super.onResume()
        if (AppPal.json(this) != palSig) { recreate(); return }      // the app theme was changed meanwhile
        wiz?.onResume()
        // part 9b: a volume may have been removed / inserted, the All files access setting may have changed (the user comes back from the settings screen)
        vols = try { Volumes.extra(this) } catch (_: Throwable) { emptyList() }
        if (volPath != null && vols.none { it.path == volPath }) { volPath = null; volName = ""; paintHead(); paintVols(); toast("The SD card / volume is no longer there"); attach(rescan = false); return }
        paintVols(); paintAccess()
        scan?.let { sc -> if (sc.limited && !needAccess() && sc.state != "run") { toast("All files access is on now - scanning again"); attach(rescan = true); return } }
        // came back to the screen: show the live state of the scan again (it may have finished while we were away)
        if (scan != null) { if (!(wiz != null && scan?.state == "done")) shownState = ""; update(); if (scan?.state == "run") { ui.removeCallbacks(tick); ui.post(tick) } }
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
        rescanBtn = Button(this).apply { text = "Rescan"; isAllCaps = false; setOnClickListener { attach(rescan = true) } }
        head.addView(rescanBtn)
        // header (back / title / path / Rescan) is intentionally not attached: the screen starts with the volume chips / treemap (system Back still works)

        // part 9b: "All files access" banner (only while the permission is off) and the volume chips (only at the storage root, when an SD card / USB volume is mounted)
        val ab = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10)); background = rounded(cCard, 12f); visibility = View.GONE }
        ab.addView(TextView(this).apply {
            text = "All files access is off, so Android hides most folders from this scan and the sizes are far too small."
            textSize = 13f; setTextColor(cErr)
        })
        ab.addView(barBtn("Allow all files access", cAcc, cOnAcc) { openAccessSettings() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        accessBar = ab
        rootView.addView(ab, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(12), dp(2), dp(12), dp(6)) })
        val vr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(2), dp(12), dp(6)) }
        val vs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(vr); visibility = View.GONE }
        volRow = vr; volScroll = vs
        rootView.addView(vs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // volume card: used bar + Used / Free / Total (moved here from the old spaceInfo sheet)
        val vol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)); background = rounded(cCard, 12f) }
        volBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progress = 0; progressDrawable = barDrawable(cAcc, cLine) }
        vol.addView(volBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)))
        volTv = TextView(this).apply { textSize = 14f; setTextColor(cFg); setPadding(0, dp(8), 0, 0) }
        vol.addView(volTv)
        // volume card (Used / Free / Total) intentionally not attached to the screen; the views stay alive so the fill / visibility code keeps working
        volCard = vol

        content = FrameLayout(this)
        rootView.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        paintHead()
        paintVols()
        fillVolume()
    }

    // ---------------------------------------------------------------- part 9b: header, volumes, access
    private fun paintHead() {
        titleTv.text = if (dev != "local") (devName.ifEmpty { "Device" }) else if (vpath == "/") "Storage analyzer" else vname(vpath)
        subTv.text = if (dev != "local") (if (vpath == "/") "Whole device" else vpath) else if (vpath != "/") vpath else if (volPath != null) volName + " · " + volPath else if (vols.isNotEmpty()) "Internal storage" else "Whole storage"
    }

    /** One chip per storage volume (Internal storage + every mounted SD card / USB volume); hidden when there is only the main one or a folder is analyzed. */
    private fun paintVols() {
        val row = volRow ?: return
        val sc = volScroll ?: return
        row.removeAllViews()
        if (dev != "local" || vpath != "/" || vols.isEmpty()) { sc.visibility = View.GONE; return }
        sc.visibility = View.VISIBLE
        fun chip(label: String, on: Boolean, fn: () -> Unit) = TextView(this).apply {
            text = label; textSize = 14f; maxLines = 1; isClickable = true; gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = rounded(if (on) cAcc else cCard, 16f)
            setTextColor(if (on) cOnAcc else cFg)
            setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            setOnClickListener { fn() }
        }
        val lp = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) } }
        row.addView(chip("Internal storage", volPath == null) { pickVolume(null) }, lp())
        for (v in vols) row.addView(chip(v.name, volPath == v.path) { pickVolume(v) }, lp())
    }

    private fun pickVolume(v: Volumes.Vol?) {
        val p = v?.path
        if (p == volPath) return
        volPath = p; volName = v?.name ?: ""
        curScan = null; cur = null; selNode = null; lastTm = null; pendingPath = null
        exitMulti()
        paintHead(); paintVols()
        attach(rescan = false)
    }

    /** All files access (Android 11+) is off: the scan only sees media and this app's files. */
    private fun needAccess() = dev == "local" && Core.storageOk == false

    private fun paintAccess() { accessBar?.visibility = if (needAccess()) View.VISIBLE else View.GONE }

    private fun openAccessSettings() {
        try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
        catch (_: Throwable) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            catch (e: Throwable) { toast("Cannot open the settings: " + com.lanshare.app.core.errText(e)) }
        }
    }

    /** The volume that is analyzed at the storage root: the chosen SD card / USB volume, else the main storage. */
    private fun volRoot(): File = volPath?.let { File(it) } ?: Core.local.root

    /** Folder that is scanned. */
    private fun scanDir(): File = if (dev != "local") StorageScan.remoteKey(dev, vpath) else if (vpath == "/" && volPath != null) File(volPath!!) else Core.local.real(vpath.removeSuffix("!"))

    /** Real file of a node of the current scan (below the other volume when one is chosen). */
    private fun realOf(n: SNode): File {
        val vp = volPath
        return if (vpath == "/" && vp != null) File(vp, n.path().trimStart('/')) else Core.local.real(vjoin(vpath, n.path()))
    }

    private fun volBase(): File? = if (vpath == "/") volPath?.let { File(it) } else null

    /** "3 h ago" for a scan older than a minute and a half, else "". */
    private fun ageText(s: Scan): String {
        val a = System.currentTimeMillis() - s.t1
        return when {
            a < 90_000L -> ""
            a < 3_600_000L -> (a / 60_000L).toString() + " min ago"
            a < 86_400_000L -> (a / 3_600_000L).toString() + " h ago"
            else -> (a / 86_400_000L).toString() + " d ago"
        }
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
        if (dev != "local") {   // another device: its free / total space, asked off the main thread
            val d = dev; val p = vpath
            Thread({
                val sp = try { com.lanshare.app.core.Jobs.ep(d).space(p) } catch (_: Throwable) { null }
                runOnUiThread {
                    try {
                        if (sp == null || sp.second <= 0) { volTv.text = "No size information available."; volBar.progress = 0 }
                        else {
                            val total = sp.second; val used = (total - sp.first).coerceAtLeast(0L)
                            val pc = Math.round(used * 100.0 / total).toInt()
                            volBar.progress = (used * 1000 / total).toInt()
                            volTv.text = "Used ${fmt(used)} ($pc%)   ·   Free ${fmt(sp.first)} (${100 - pc}%)   ·   Total ${fmt(total)}"
                        }
                    } catch (_: Throwable) {}
                }
            }, "sscan-space").also { it.isDaemon = true }.start()
            return
        }
        try {
            val r = volRoot()
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
    /**
     * Attach to the running scan of this folder, else the newest finished one (in memory, else the copy saved on disk by [StorageStore]),
     * else start a new one. [rescan] = ignore finished / saved results (a running scan is still reused). Part 9b: a result made while
     * "All files access" was off is not reused once it is on.
     */
    private fun attach(rescan: Boolean) {
        ui.removeCallbacks(tick)
        val seq = ++attachSeq
        pendingPath = if (rescan && shownState == "done") cur?.path() else null
        try {
            if (dev != "local") {   // another device: read-only tree built by StorageScan.startRemote (kept in memory only)
                val key = scanDir()
                var a = StorageScan.running(key)
                if (a == null && !rescan) a = StorageScan.latest(key)
                if (a == null) a = StorageScan.startRemote(dev, vpath, if (vpath == "/") devName else vname(vpath))
                scan = a
                shownState = ""
                fillVolume()
                update()
                if (scan?.state == "run") ui.post(tick)
                return
            }
            if (inArc) {   // an archive: the tree comes from its file list (kept in memory only, not saved); a newer archive file means a new scan
                val av = vpath.removeSuffix("!")
                val dir = Core.local.real(av)
                var a = StorageScan.running(dir)
                if (a == null && !rescan) a = StorageScan.latest(dir)?.takeUnless { it.arc.not() || it.t1 < dir.lastModified() }
                if (a == null) a = StorageScan.startArchive(av, vname(vpath))
                if (pendingPath == null && arcInner != null) pendingPath = arcInner
                arcInner = null
                scan = a
                shownState = ""
                fillVolume()
                update()
                if (scan?.state == "run") ui.post(tick)
                return
            }
            val dir = scanDir()
            var s = StorageScan.running(dir)
            if (s == null && !rescan) s = StorageScan.latest(dir)?.takeUnless { it.limited && !needAccess() }
            if (s == null && !rescan && StorageStore.has(dir)) { loadSaved(dir, seq); return }
            if (s == null) s = startScan(dir)
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

    /** Starts a new scan of [dir]; at the storage root the used size of the volume feeds the percentage. */
    private fun startScan(dir: File): Scan {
        val whole = vpath == "/"
        var used = 0L
        if (whole) try { val r = volRoot(); used = maxOf(0L, r.totalSpace - r.usableSpace) } catch (_: Exception) {}
        return StorageScan.start(dir, if (whole) volName else vname(vpath), used)
    }

    /** Read the saved result of [dir] off the main thread; a result that is missing / unreadable / made without All files access (and the access is on now) starts a new scan instead. */
    private fun loadSaved(dir: File, seq: Int) {
        scan = null; shownState = "load"
        showBusy("Loading the last result…")
        Thread({
            val l = try { StorageStore.load(dir) } catch (_: Throwable) { null }
            ui.post {
                if (seq != attachSeq || isDestroyed || isFinishing) return@post
                val sc: Scan = if (l != null && !(l.limited && !needAccess()) && !StorageScan.hasFolded(l)) { StorageScan.adopt(l); l }   // an old result with folded "small files" is scanned again: every file is listed now
                    else try { startScan(dir) }
                    catch (e: Throwable) { showMessage("Cannot analyze this folder", com.lanshare.app.core.errText(e), canRetry = false); return@post }
                scan = sc; shownState = ""
                fillVolume(); update()
                if (sc.state == "run") ui.post(tick)
            }
        }, "sscan-load").also { it.isDaemon = true }.start()
    }

    private fun update() {
        val s = scan ?: return
        val st = s.state
        rescanBtn?.visibility = if (st == "run") View.GONE else View.VISIBLE      // Cancel is in the content while it runs
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
        pHint = TextView(this).apply {
            textSize = 12f; setTextColor(cDim); gravity = Gravity.CENTER; setPadding(0, dp(14), 0, 0)
            text = "You can leave this screen: the scan keeps running and shows as a progress bubble and in the notification."
        }
        col.addView(pHint)
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
        pCount?.text = s.files.get().toString() + " files · " + s.dirs.get() + " folders · " + fmt(s.bytes.get()) + " · " + (s.millis() / 1000) + " s"
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
        fun btn(t: String, fn: () -> Unit) = col.addView(Button(this).apply { this.text = t; isAllCaps = false; setOnClickListener { fn() } },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        // part 9b: after Cancel / an error the earlier finished result of this folder is still in memory: offer it instead of leaving nothing
        val st = scan?.state
        val prev = if (st == "cancel" || st == "error") try { StorageScan.latest(scanDir()) } catch (_: Throwable) { null } else null
        if (prev != null) btn("Show the previous result" + ageText(prev).let { if (it.isEmpty()) "" else " ($it)" }) { scan = prev; shownState = ""; fillVolume(); update() }
        if (canRetry) btn("Scan again") { attach(rescan = true) }
        if (needAccess()) btn("Allow all files access") { openAccessSettings() }
        content.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
    }

    /** Part 9b: spinner + text while the saved result is read from disk. */
    private fun showBusy(text: String) {
        content.removeAllViews(); volCard.visibility = View.VISIBLE
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(24), dp(40), dp(24), dp(24)) }
        col.addView(ProgressBar(this).apply { isIndeterminate = true })
        col.addView(TextView(this).apply { this.text = text; textSize = 14f; setTextColor(cDim); gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) })
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
        if (wizOn && root != null && total > 0L) {      // WizTree view
            volCard.visibility = View.GONE
            val w = WizScreen(this, s, wizHost)
            wiz = w
            content.addView(w.view, FrameLayout.LayoutParams(-1, -1))
            wizState?.let { w.restoreState(it); wizState = null }
            return
        }
        wiz = null
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(4)) }
        dBody = col
        sumSize = TextView(this).apply { textSize = if (compact) 22f else 28f; setTextColor(cFg); setTypeface(typeface, Typeface.BOLD) }
        sumCnt = TextView(this).apply { textSize = 14f; setTextColor(cDim); setPadding(0, 0, 0, dp(4)) }
        // big size + "N files · N folders · scanned ..." lines intentionally not attached (sumSize / sumCnt stay as detached views)
        // notes about the whole scan: only shown while the scan root is open (saves room when drilled in)
        val nb = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        notesBox = nb
        if (vpath == "/") {
            // whole storage / volume: what the scan could not see = used minus scanned (items moved to the bin in this screen are added back: they are still on the volume)
            val scanned = total + s.removed
            val gap = s.usedBytes - scanned
        }
        if (s.truncated) nb.addView(note("Very large result: to save memory, the files of some folders are only counted as one \"small files\" block. All sizes and totals are still exact."))
        if (root == null || total == 0L) nb.addView(note("This folder is empty."))
        col.addView(nb)
        wrap.addView(col, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        volCard.visibility = if (compact) View.GONE else View.VISIBLE      // part 10: small / split screen -> the treemap gets the room
        tmV = null; lsV = null; tpV = null; catBox = null; infoTv = null; actBtn = null; crumbRow = null; crumbScroll = null
        selBar = null; selTv = null; irRow = null
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
                setPadding(dp(14), dp(8), dp(14), dp(8)); background = rounded(cAcc, 10f); setTextColor(cOnAcc)
                setTypeface(typeface, Typeface.BOLD); visibility = View.GONE
                setOnClickListener {
                    val n = selNode
                    if (n == null) { if (volPath == null && !ro) cur?.let { c -> StorageActions.reveal(this@StorageActivity, StorageActions.folderOf(vpath, c)) } }   // nothing selected: the open folder in the file manager (part 8a)
                    else if (mode == 2) showIn(n) else if (canOpen(n)) showNode(n) else details(n)
                }
            }
            actBtn = act
            val tm = StoragePie(this).apply {
                colBg = cBg; colFg = cFg; colDim = cDim; colAcc = cAcc
                onCenter = { cur?.parent?.let { showNode(it) } }       // tap on the hole = one folder up
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
                onMulti = { if (lsV?.multi == true) tpV?.endMulti(); paintSelBar() }               // part 9a: long-press = select several
                canSelect = volPath == null && !ro                                               // part 9b: delete only on the main storage (not inside an archive)
            }
            // Largest files: the 100 biggest files of the whole scan as a flat list (part 7); a tap = info + details, the button / dialog shows the file in its folder
            val tp = StorageList(this).apply {
                colBg = cBg; colFg = cFg; colDim = cDim; colAcc = cAcc; colLine = cLine
                onTap = { nd -> setInfo(nd); details(nd) }
                onMulti = { if (tpV?.multi == true) lsV?.endMulti(); paintSelBar() }
                canSelect = volPath == null && !ro
                setFlat(s.top, root.size)
            }
            tmV = tm; lsV = ls; tpV = tp
            tm.hl = hl; ls.setFilter(hl); tp.setFilter(hl)
            ls.setSort(sortMode); tp.setSort(sortMode); ls.setQuery(query); tp.setQuery(query)
            val views = FrameLayout(this)
            views.addView(tm, FrameLayout.LayoutParams(-1, -1))
            views.addView(ls, FrameLayout.LayoutParams(-1, -1))
            views.addView(tp, FrameLayout.LayoutParams(-1, -1))

            // file-type bar (part 7): between the breadcrumb and the Treemap / List / Largest switch
            val cb = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(2), dp(12), dp(4)) }
            catBox = cb
            wrap.addView(cb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            // Pie / List / Largest switch
            val sw = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(2), dp(12), dp(8)) }
            val names = arrayOf("Pie", "List", "Largest")
            for (i in 0..2) {
                val b = segButton(names[i])
                segB[i] = b
                b.setOnClickListener { setMode(i) }
                sw.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) leftMargin = dp(8) })
            }
            wrap.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // tools row: name filter + sort (List / Largest tabs), report (all tabs)
            val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), dp(6)) }
            val et = EditText(this).apply {
                hint = "Filter by name"; textSize = 14f; setTextColor(cFg); setHintTextColor(cDim); setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT; setPadding(dp(12), dp(8), dp(12), dp(8)); background = rounded(cCard, 10f); setText(query)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                    override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        val q = s?.toString()?.trim() ?: ""
                        if (q == query) return
                        query = q; lsV?.setQuery(q); tpV?.setQuery(q)
                    }
                })
            }
            qEt = et
            tools.addView(et, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val sb = barBtn("", cCard, cFg) { chooseSort() }
            sortBtn = sb
            tools.addView(sb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
            tools.addView(barBtn("WizTree", cCard, cFg) { setWizOn(true) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
            tools.addView(barBtn("Report", cCard, cFg) { shareReport() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
            wrap.addView(tools, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            wrap.addView(views, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(dp(8), 0, dp(8), 0) })
            val ir = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(6), dp(12), dp(8)) }
            ir.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            ir.addView(act, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
            wrap.addView(ir, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            irRow = ir
            // part 9a: selection bar (replaces the info row while rows are ticked): "N selected · size", Cancel, Delete…
            val sbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(6), dp(12), dp(8)); visibility = View.GONE }
            selBar = sbar
            selTv = TextView(this).apply { textSize = 14f; setTextColor(cFg); setTypeface(typeface, Typeface.BOLD); maxLines = 2 }
            sbar.addView(selTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            sbar.addView(barBtn("Cancel", cCard, cFg) { exitMulti() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
            sbar.addView(barBtn("Delete…", cErr, Color.WHITE) { onDeleteSelected() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
            wrap.addView(sbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            showNode(cur ?: root)
            setMode(mode)
        } else paintSummary()
        content.addView(wrap, FrameLayout.LayoutParams(-1, -1))
    }

    // ---------------------------------------------------------------- navigation (part 6)
    /** A folder with something inside can be opened (empty / unreadable folders, files and the "small files" node cannot). */
    /** Part 10: short screens (small phone, split screen, landscape) drop the volume card and use a smaller size line. */
    private val compact: Boolean get() = resources.configuration.screenHeightDp < 640

    private fun canOpen(n: SNode) = n.dir && !n.agg && n.kids.isNotEmpty()

    /** Show folder [n] (a node of the current scan) in both views; refreshes summary, breadcrumb, info line. */
    private fun showNode(n: SNode) {
        wiz?.let { it.select(n); return }
        cur = n; lastTm = null
        exitMulti()
        if (query.isNotEmpty()) qEt?.setText("")                 // the name filter belongs to the folder it was typed in
        tmV?.canUp = n.parent != null
        tmV?.setRoot(n); lsV?.setRoot(n)
        val atRoot = n === curScan?.root
        volCard.visibility = if (atRoot && !compact) View.VISIBLE else View.GONE      // room for the treemap when drilled in
        notesBox?.visibility = if (atRoot) View.VISIBLE else View.GONE
        paintSummary()
        paintCrumb()
        paintCats()
        setInfo(null)
    }

    /** Switch the done-state view: 0 pie chart, 1 size list, 2 largest files (part 7). */
    private fun setMode(m: Int) {
        if (m != mode) exitMulti()
        mode = m
        if (m != 2) lastBase = m
        tmV?.visibility = if (m == 0) View.VISIBLE else View.GONE
        lsV?.visibility = if (m == 1) View.VISIBLE else View.GONE
        tpV?.visibility = if (m == 2) View.VISIBLE else View.GONE
        for (i in 0..2) segB[i]?.let { paintSeg(it, i == m) }
        lastTm = null
        paintTools()
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
            val took = String.format(java.util.Locale.US, "%.1f", s.millis() / 1000.0)
            val age = ageText(s)
            sumCnt?.text = (root?.files ?: s.files.get()).toString() + " files · " + (root?.folders ?: s.dirs.get()) + " folders · " +
                (if (age.isEmpty()) "scanned in $took s" else "scanned $age (took $took s)")
        } else {
            val pct = if (root.size > 0) String.format(java.util.Locale.US, "%.1f", n.size * 100.0 / root.size) else "0"
            sumSize?.text = fmt(n.size)
            sumCnt?.text = n.files.toString() + " files · " + n.folders + " folders · " + pct + "% of scan"
        }
    }

    private fun rootLabel() = if (dev != "local" && vpath == "/") devName.ifEmpty { "Device" } else if (vpath == "/") (if (volPath != null) volName.ifEmpty { "Volume" } else "Storage") else vname(vpath)
    private fun vname(p: String) = vbase(p).removeSuffix("!")

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
            tv.text = ""; b.visibility = View.GONE      // no hint line and no "File manager" button while nothing is selected
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
        val real = try { realOf(n).absolutePath } catch (_: Throwable) { vjoin(vpath, n.path()) }
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
        val infoTv = TextView(this).apply { text = sb.toString(); textSize = 14f; setTextIsSelectable(true) }
        box.addView(infoTv)
        if (!n.dir && !n.agg) {   // pictures / videos / audio: container, codec, track and tag details, read straight from the file
            val baseText = sb.toString()
            Thread({
                val extra = MediaText.format(MediaText.local(java.io.File(real)))
                if (extra.isNotEmpty()) runOnUiThread { if (!isFinishing) infoTv.text = baseText + "\n" + extra }
            }, "storage-media").also { t -> t.isDaemon = true }.start()
        }
        val dlg = AppPal.dlg(this)
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
        if (!n.dir && !n.agg && !ro) {
            act("Open") { StorageActions.realOrToast(this, vpath, n, volBase())?.let { StorageActions.open(this, it, virtPath = if (volBase() == null) StorageActions.virt(vpath, n) else null) } }   // own viewers first (video / picture / PDF / Office / text), other apps for the rest
            act("Open with…") { StorageActions.realOrToast(this, vpath, n, volBase())?.let { StorageActions.open(this, it, pick = true) } }
            act("Share") { StorageActions.realOrToast(this, vpath, n, volBase())?.let { StorageActions.share(this, it) } }
        }
        if (volPath == null && !ro) act("Show in file manager") { StorageActions.reveal(this, StorageActions.folderOf(vpath, n)) }   // part 9b: not for another volume
        if (mode == 2 && up != null) act("Show in folder") { showIn(n) }
        else if (up != null && (wiz != null || up !== c)) act("Open containing folder") { val w = wiz; if (w != null) w.reveal(n) else showNode(up) }
        if (!n.agg && n.parent != null && volPath == null && !ro) act("Delete…", cErr) { confirmDelete(n) }   // part 8b files, 8c folders (not the scan root, not "small files")
        dlg.show()
    }

    // ---------------------------------------------------------------- delete (part 8b)
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
    /** A failed delete: the full message in a dialog (a toast cuts it off). */
    private fun errBox(title: String, m: String) { try { AppPal.dlg(this).setTitle(title).setMessage(m).setPositiveButton("OK", null).show() } catch (_: Throwable) { toast(m) } }

    /** Ask before deleting file [n]; the file only goes to the recycle bin (restorable), like a delete in the file manager. */
    private fun confirmDelete(n: SNode) {
        if (n.agg || n.parent == null || volPath != null || ro) return
        val s = curScan ?: return
        val what = if (n.dir) n.files.toString() + " files · " + n.folders + " folders\n" else ""
        val dn = if (n.dir) StorageScan.deniedBelow(n) else 0
        val warn = if (dn > 0) "\nAndroid did not let the scan read " + (if (dn == 1) "one folder" else "$dn folders") + " inside it, so the size shown is only what was scanned; the real size may be larger.\n" else ""
        AppPal.dlg(this)
            .setTitle(if (n.dir) "Delete this folder?" else "Delete this file?")
            .setMessage(n.name + "\n" + fmt(n.size) + "\n" + what + warn + "\nIt is moved to the recycle bin and kept there for " + Bin.KEEP_DAYS + " days. You can restore it from the recycle bin of the file manager.")
            .setPositiveButton("Delete") { _, _ -> deleteNode(s, n) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Move file or folder [n] of scan [s] into the recycle bin (worker thread; a rename on the same volume, so it is instant), then patch the tree on the main thread. */
    private fun deleteNode(s: Scan, n: SNode) {
        if (deleting) { toast("Another delete is still running"); return }
        val v = StorageActions.virt(vpath, n)
        if (Bin.inBin(v)) { toast("This item is already in the recycle bin. Restore or empty it from the file manager."); return }
        if (!Bin.canTrash(v)) { toast("This item cannot be moved to the recycle bin"); return }
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
                if (err != null) errBox("Could not delete", err)
                else afterDelete(s, n, if (gone) "It was already gone - removed from the result" else "Moved to the recycle bin: " + n.name + " (" + fmt(n.size) + ")")
            }
        }, "sscan-del").also { it.isDaemon = true }.start()
    }

    /** The item is gone from the storage: patch the tree (also when the screen is closed meanwhile: the scan object is shared), then refresh what is shown. */
    private fun afterDelete(s: Scan, n: SNode, msg: String) = afterDeleteMany(s, listOf(n), msg)

    /** Part 9a: patch the tree for every node of [nodes] (files and folders, none inside another), then refresh the views ONCE. */
    private fun afterDeleteMany(s: Scan, nodes: List<SNode>, msg: String) {
        var any = false
        for (n in nodes) {
            val near = if (n.dir) StorageScan.removeNode(s, n) else StorageScan.removeFile(s, n)
            if (near != null) any = true
        }
        toast(msg)
        if (any) StorageStore.saveAsync(s)                                    // part 9b: the saved copy follows the deletes
        if (!any || isDestroyed || isFinishing || curScan !== s || shownState != "done") return
        val root = s.root ?: return
        selNode = null; lastTm = null
        if (root.size <= 0L) { shownState = ""; update(); return }          // nothing left: the "empty" summary
        wiz?.let { it.refresh(); return }
        val c = nearestAttached(cur ?: root, root)                           // the open folder may have been deleted / dropped: nearest one left
        val lp = lsV?.firstVisiblePosition ?: 0
        val tp = tpV?.firstVisiblePosition ?: 0
        showNode(c)                                                           // both folder views, summary, breadcrumb, file-type bar, info line (also ends select mode)
        tpV?.setFlat(s.top, root.size)                                        // Largest tab (the category filter stays)
        lsV?.setSelection(lp); tpV?.setSelection(tp)                          // keep the scroll position of the lists
    }

    /** [n] itself when it is still in the tree, else its nearest ancestor that is (the root at worst). */
    private fun nearestAttached(n: SNode, root: SNode): SNode {
        var x: SNode = n
        while (!attached(x, root)) x = x.parent ?: return root
        return x
    }

    // ---------------------------------------------------------------- multi-select delete (part 9a)
    private fun multiList(): StorageList? = if (lsV?.multi == true) lsV else if (tpV?.multi == true) tpV else null

    /** Selection bar instead of the info row while rows are ticked. */
    private fun paintSelBar() {
        val bar = selBar ?: return
        val l = multiList()
        if (l == null) { bar.visibility = View.GONE; irRow?.visibility = View.VISIBLE; return }
        val sel = l.checkedNodes()
        var sz = 0L
        for (n in sel) sz += n.size
        selTv?.text = sel.size.toString() + " selected · " + fmt(sz)
        bar.visibility = View.VISIBLE; irRow?.visibility = View.GONE
    }

    private fun exitMulti() { lsV?.endMulti(); tpV?.endMulti() }

    private fun barBtn(t: String, bg: Int, fg: Int, fn: () -> Unit) = TextView(this).apply {
        text = t; textSize = 14f; gravity = Gravity.CENTER; isClickable = true; minimumHeight = dp(40); minimumWidth = dp(72)
        setPadding(dp(14), dp(8), dp(14), dp(8)); background = rounded(bg, 10f); setTextColor(fg); setTypeface(typeface, Typeface.BOLD)
        setOnClickListener { fn() }
    }

    private fun onDeleteSelected() {
        val l = multiList() ?: return
        val s = curScan ?: return
        val nodes = StorageScan.pruneNested(l.checkedNodes())
        if (nodes.isEmpty()) return
        if (nodes.size == 1) confirmDelete(nodes[0]) else confirmDeleteMany(s, nodes)
    }

    private fun confirmDeleteMany(s: Scan, nodes: List<SNode>) {
        var size = 0L; var files = 0L; var folders = 0; var dn = 0
        for (n in nodes) { size += n.size; files += n.files; if (n.dir) { folders++; dn += StorageScan.deniedBelow(n) } }
        val sb = StringBuilder()
        for (i in 0 until Math.min(5, nodes.size)) sb.append(nodes[i].name).append('\n')
        if (nodes.size > 5) sb.append("and ").append(nodes.size - 5).append(" more\n")
        sb.append('\n').append(fmt(size)).append(" · ").append(files).append(if (files == 1L) " file" else " files")
        if (folders > 0) sb.append(" · ").append(folders).append(if (folders == 1) " folder selected" else " folders selected")
        sb.append('\n')
        if (dn > 0) sb.append("\nAndroid did not let the scan read ").append(if (dn == 1) "one folder" else "$dn folders").append(" inside them, so the size shown is only what was scanned; the real size may be larger.\n")
        sb.append("\nThey are moved to the recycle bin and kept there for ").append(Bin.KEEP_DAYS).append(" days. You can restore them from the recycle bin of the file manager.")
        AppPal.dlg(this)
            .setTitle("Delete " + nodes.size + " items?")
            .setMessage(sb.toString())
            .setPositiveButton("Delete") { _, _ -> deleteMany(s, nodes) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Move every node of [nodes] (already without nested ones) into the recycle bin one after the other on one worker thread, then patch the tree and refresh once. */
    private fun deleteMany(s: Scan, nodes: List<SNode>) {
        if (deleting) { toast("Another delete is still running"); return }
        if (volPath != null || ro) return
        val todo = ArrayList<SNode>(); val vs = ArrayList<String>()
        var skipped = 0
        for (n in nodes) {
            val v = StorageActions.virt(vpath, n)
            if (Bin.inBin(v) || !Bin.canTrash(v)) { skipped++; continue }
            todo.add(n); vs.add(v)
        }
        if (todo.isEmpty()) { toast("These items cannot be moved to the recycle bin"); return }
        deleting = true
        Thread({
            val ok = ArrayList<SNode>()
            var gone = 0; var failed = 0; var firstErr: String? = null
            var bytes = 0L
            for (i in todo.indices) {
                try {
                    val f = Core.local.real(vs[i])
                    if (!f.exists() && !Core.local.isLink(f)) gone++ else { Bin.trash(vs[i]); bytes += todo[i].size }
                    ok.add(todo[i])
                } catch (e: Throwable) { failed++; if (firstErr == null) firstErr = com.lanshare.app.core.errText(e) }
            }
            ui.post {
                deleting = false
                val sb = StringBuilder()
                if (ok.isNotEmpty()) sb.append("Moved ").append(ok.size - gone).append(" items to the recycle bin (").append(fmt(bytes)).append(")")
                if (gone > 0) sb.append(if (sb.isEmpty()) "" else ". ").append(gone).append(" already gone")
                if (skipped > 0) sb.append(if (sb.isEmpty()) "" else ". ").append(skipped).append(" skipped (already in the bin or not allowed)")
                if (failed > 0) sb.append(if (sb.isEmpty()) "" else ". ").append(failed).append(" failed: ").append(firstErr)
                if (ok.isEmpty()) { if (failed > 0) errBox("Could not delete", sb.toString()) else toast(sb.toString()) } else afterDeleteMany(s, ok, sb.toString())
            }
        }, "sscan-del").also { it.isDaemon = true }.start()
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
    /** Filter box + sort button only on the list tabs. */
    private fun paintTools() {
        val list = mode != 0
        qEt?.visibility = if (list) View.VISIBLE else View.GONE
        sortBtn?.visibility = if (list) View.VISIBLE else View.GONE
        sortBtn?.text = "\u21C5 " + StorageList.SORT_LABELS[sortMode]
    }

    private fun chooseSort() {
        AppPal.dlg(this).setTitle("Sort by")
            .setSingleChoiceItems(StorageList.SORT_LABELS, sortMode) { dlg, i ->
                sortMode = i; lsV?.setSort(i); tpV?.setSort(i); paintTools(); dlg.dismiss()
            }.show()
    }

    /** Plain-text report of the open folder (size, categories, biggest items, biggest files of the scan) through the share sheet. */
    private fun shareReport() {
        val c = cur ?: return
        val sc = curScan
        val where = subTv.text.toString() + (if (c.parent == null) "" else c.path().let { if (it == "/") "" else it })
        val sb = StringBuilder()
        sb.append("Storage report\n").append(where).append('\n')
        sb.append(fmt(c.size)).append(" \u00b7 ").append(c.files).append(" files \u00b7 ").append(c.folders).append(" folders\n")
        val end = sc?.t1?.takeIf { it > 0L } ?: System.currentTimeMillis()
        sb.append(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(end))).append("\n\n")
        c.cats?.let { cs ->
            sb.append("By type\n")
            for (i in 0 until Math.min(cs.size, SCat.COUNT)) if (cs[i] > 0)
                sb.append("  ").append(StorageList.LABELS[i]).append(": ").append(fmt(cs[i])).append(" (")
                    .append(String.format(java.util.Locale.US, "%.1f", cs[i] * 100.0 / Math.max(1L, c.size))).append("%)\n")
            sb.append('\n')
        }
        sb.append("Biggest items\n")
        var k = 0
        for (n in c.kids) {
            if (k >= 25) break
            k++
            sb.append("  ").append(fmt(n.size)).append("  ")
                .append(if (n.agg) n.files.toString() + " small files" else n.name + (if (n.dir) "/" else "")).append('\n')
        }
        sc?.top?.let { top ->
            if (top.isNotEmpty()) {
                sb.append("\nLargest files\n")
                for (n in top.take(15)) sb.append("  ").append(fmt(n.size)).append("  ").append(n.path()).append('\n')
            }
        }
        try {
            val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Storage report").putExtra(Intent.EXTRA_TEXT, sb.toString())
            startActivity(Intent.createChooser(i, "Share report"))
        } catch (_: Throwable) { toast("Nothing can share the report") }
    }

    override fun onBackPressed() {
        val up = cur?.parent
        if (wiz?.onBack() == true) return
        if (tmV?.visibility == View.VISIBLE && tmV?.zoomed == true) { tmV?.resetZoom(); return }     // Classic chart: Back resets the zoom first
        if (multiList() != null) exitMulti()                                  // part 9a: Back leaves select mode first
        else if (shownState == "done" && mode == 2) setMode(lastBase)           // Largest tab: back to the folder view first
        else if (shownState == "done" && up != null) showNode(up) else super.onBackPressed()
    }

    /** One-line description of a tapped tile: path, size, share of the open folder (of the scan at the root). */
    private fun describe(n: SNode, base: Long, scanWide: Boolean): String {
        val pct = if (base > 0) String.format(java.util.Locale.US, "%.1f", n.size * 100.0 / base) else "0"
        val of = if (scanWide || cur === curScan?.root) "% of scan" else "% of folder"
        return if (n.agg) n.files.toString() + " small files in " + n.path() + " · " + fmt(n.size) + " · " + pct + of
        else n.path() + " · " + fmt(n.size) + " · " + pct + of + (if (n.dir) " · " + n.files + " files" else "") + (if (n.denied) " · not readable" else "")
    }

    /** Segment button of the Pie / List switch. */
    private fun segButton(t: String) = TextView(this).apply {
        text = t; textSize = 14f; gravity = Gravity.CENTER; isClickable = true; minimumHeight = dp(40)
        setPadding(dp(12), dp(8), dp(12), dp(8))
    }

    private fun paintSeg(b: TextView, on: Boolean) {
        b.background = rounded(if (on) cAcc else cCard, 10f)
        b.setTextColor(if (on) cOnAcc else cFg)
        b.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun note(t: String, tap: Boolean = false, fn: (() -> Unit)? = null) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(if (tap) cAcc else cDim); setPadding(0, dp(4), 0, dp(4))
        if (fn != null) { isClickable = true; setOnClickListener { fn() } }
    }

    /** Part 9b: what "System / other" is, with the numbers it is made of. */
    private fun sysDialog(s: Scan, scanned: Long, gap: Long) {
        val sb = StringBuilder()
        sb.append("Used on this storage: ").append(fmt(s.usedBytes)).append('\n')
        sb.append("Found by the scan: ").append(fmt(scanned)).append('\n')
        sb.append("System / other: ").append(fmt(gap)).append("\n\n")
        sb.append("This is used space the scan cannot see or count: Android itself and its updates, apps' private data and caches, other users or profiles, and the rounding every file gets on the disk (the scan adds up file sizes, the volume counts whole blocks, which matters most with very many small files).\n")
        if (Build.VERSION.SDK_INT >= 30) sb.append("\nOn Android 11 and newer the folders Android/data and Android/obb belong to the other apps and are closed to every file manager, even with All files access, so what is in them is always part of this number.\n")
        if (s.limited) sb.append("\nThis scan was made while All files access was off, so most of it is simply hidden. Turn it on and scan again to see more.\n")
        if (s.denied.get() > 0) {
            sb.append("\n").append(s.denied.get()).append(if (s.denied.get() == 1) " folder" else " folders").append(" could not be read")
            val smp = s.deniedSample.toList()
            if (smp.isNotEmpty()) { sb.append(", for example:\n"); for (p in smp.take(8)) sb.append(p).append('\n') } else sb.append(".\n")
        }
        val b = AppPal.dlg(this).setTitle("System / other").setMessage(sb.toString()).setPositiveButton("OK", null)
        if (needAccess()) b.setNeutralButton("Allow access") { _, _ -> openAccessSettings() }
        b.show()
    }

    companion object {
        const val X_PATH = "path"
        const val X_DEV = "dev"          // another device to analyze (peer id / "smb:..." / "ftp:..."), read-only
        const val X_DEVNAME = "devname"
        const val X_VOL = "vol"          // part 9b: absolute path of an SD card / USB volume to analyze (with path "/")
        private val CAT_NAMES = arrayOf("Other", "Images", "Video", "Audio", "Documents", "Archives", "Apps")
        fun intentDev(c: Context, dev: String, name: String, vpath: String): Intent = Intent(c, StorageActivity::class.java).putExtra(X_PATH, vpath).putExtra(X_DEV, dev).putExtra(X_DEVNAME, name)
        fun intent(c: Context, vpath: String, vol: String? = null): Intent = Intent(c, StorageActivity::class.java).putExtra(X_PATH, vpath).also { if (vol != null) it.putExtra(X_VOL, vol) }
    }
}
