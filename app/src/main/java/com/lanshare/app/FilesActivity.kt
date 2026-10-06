package com.lanshare.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.provider.Settings
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.lanshare.app.core.Clip
import com.lanshare.app.core.Core
import com.lanshare.app.core.Jobs
import com.lanshare.app.core.RemoteThumbs
import com.lanshare.app.core.Smb
import com.lanshare.app.core.Thumbs
import com.lanshare.app.core.VideoThumbs
import com.lanshare.app.core.errText
import com.lanshare.app.core.viaOf
import com.lanshare.app.core.isArcName
import com.lanshare.app.core.vjoin
import java.util.concurrent.Executors
import java.util.concurrent.Future
import com.lanshare.app.core.Cfg as CoreCfg

/**
 * The fully native file browser: plain Android views + FsController (state, navigation, selection, file operations) + Prefs + the
 * existing leaf row views (NlRowView / NlGridView) and the row pipeline (NlModel). No WebView, no JS bridge, no overlay.
 * Main launcher screen ("LANShare", .NativeAlias); the old WebView app and ui.html are gone.
 *
 * Done: browsing (this phone, other LANShare devices, SMB, archives), list / compact / grid / large thumbnails, sort, hidden files,
 * selection, copy / cut / paste / delete / rename / new folder / zip / extract / details / open with, progress + cancel, viewers
 * (video / pictures / PDF), places (quick folders, favourites, recent, devices), theme.
 * Print (see NativePrint / NativePrintPc / PrintPv): "Print on..." picker with device badges, this phone / Wi-Fi printer / PC, options dialogs with live
 * page preview, job progress, documents printed from other apps (PcPrintService -> ACTION_PRINT_SHARED), "Printers" entry in the drawer.
 * Also done since: search, printing (PC and Wi-Fi printers share the FinePrint-style dialog; the phone lays out for Wi-Fi), settings, SMB dialog,
 * "Send to...", share-sheet print intake. Add IP: "+ IP" chip or the drawer footer. Device chips under the top bar, side drawer in NativeDrawer.kt (menu button / left-edge swipe). Not yet: split screen.
 * NOT compiled / NOT device-tested.
 */
class FilesActivity : Activity(), FsController.Listener {
    companion object { const val ACTION_PRINT_SHARED = "com.lanshare.app.PRINT_SHARED" }

    private val d: Float by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = Math.round(v * d)

    private lateinit var ctl: FsController
    private lateinit var c: NlTheme.Cols
    private lateinit var pal: NlPal
    private val ui = Handler(Looper.getMainLooper())

    // ---- views
    private lateinit var barFrame: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var selBar: LinearLayout
    private lateinit var btnUp: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvSub: TextView
    private lateinit var tvSel: TextView
    private lateinit var crumbs: LinearLayout
    private lateinit var crumbScroll: HorizontalScrollView
    private lateinit var tvSum: TextView
    private lateinit var srl: SwipeRefreshLayout
    private lateinit var rv: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var toastBar: LinearLayout
    private lateinit var tvToast: TextView
    private lateinit var tvCancel: TextView
    private lateinit var progress: ProgressBar
    private lateinit var clipBar: LinearLayout
    private lateinit var tvClip: TextView
    private lateinit var dock: HorizontalScrollView
    private lateinit var dockRow: LinearLayout
    private lateinit var searchRow: LinearLayout
    private lateinit var deviceRow: LinearLayout
    private lateinit var deviceScroll: HorizontalScrollView
    private var devSig = ""
    private lateinit var drawer: NativeDrawer
    private val devPoll = object : Runnable {
        override fun run() { renderDevices(); drawerSig(); ui.postDelayed(this, 1500) }
    }
    private lateinit var etSearch: EditText
    private var lastQuery: String? = null
    private val searchRun = Runnable { runSearch() }

    // ---- list model
    private var rows: List<NlRow> = emptyList()
    private var vp = Prefs.ViewPref("list", "name", true, "s")
    private var gridOn = false
    private var spanN = 2
    private var applyAll = false
    private var curKey = ""
    private val saved = HashMap<String, Parcelable>()   // scroll position per folder
    private val ad = Ad()
    private val printer by lazy { NativePrint(this, ctl, c) { msg -> onToast(msg, false) } }
    private val settings by lazy {
        NativeSettings(this, c, { ctl.dev }, { ctl.openDev("local") }, { rebuild(); header() }, { m, l -> onToast(m, l) }, { })
    }

    // ---- thumbnails
    private class Th(val bm: Bitmap, val dur: String?)
    private val pool = Executors.newFixedThreadPool(3) { r -> Thread(r, "fth").also { it.isDaemon = true } }
    private val thumbs = object : LruCache<String, Th>((Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(4 * 1024 * 1024)) {
        override fun sizeOf(k: String, v: Th) = v.bm.byteCount
    }
    private val failed = HashSet<String>()

    // ---------------------------------------------------------------- lifecycle
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Prefs.init(this)
        val dark = when (Prefs.theme) {
            "dark" -> true
            "auto" -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            else -> false
        }
        c = NlTheme.cols(dark); pal = NlTheme.pal(dark)
        window.statusBarColor = if (dark) Color.BLACK else 0xFF1C1C1E.toInt()
        window.navigationBarColor = c.cont
        ctl = FsController(this)
        build()
        askPermissions()
        ContextCompat.startForegroundService(this, Intent(this, LanShareService::class.java))
        showEmpty("Starting\u2026")
        Thread {
            var err: String? = null
            var ok = false
            val t0 = System.currentTimeMillis()
            while (!ok && err == null && System.currentTimeMillis() - t0 < 40_000) {
                ok = Core.url != null
                err = Core.error
                if (!ok && err == null) Thread.sleep(25)
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (ok) { ctl.go("/"); handleIntent(intent) } else showEmpty("Server did not start. Close and reopen the app.\n" + (err ?: "timeout"))
            }
        }.also { it.isDaemon = true }.start()
    }

    @Deprecated("ok")
    override fun onBackPressed() {
        if (drawer.isOpen) { drawer.close(); return }
        if (ctl.sel.isEmpty() && searchRow.visibility == View.VISIBLE) { closeSearch(); return }   // selection first, then the search, then folders
        if (!ctl.back()) finish()
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i); handleIntent(i) }

    /**
     * Print intake: (1) a document printed from another app through Android's print dialog (PcPrintService, files already in /LANShare Shared),
     * (2) files shared to LANShare from any app's share sheet (ACTION_SEND / SEND_MULTIPLE): copied to /LANShare Shared, then the same print flow.
     */
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        if (i.action == ACTION_PRINT_SHARED) {
            val names = i.getStringArrayExtra("names")?.toList().orEmpty()
            try { getSystemService(NotificationManager::class.java).cancel(i.getIntExtra("nid", 0)) } catch (_: Throwable) { }
            i.action = null
            if (names.isNotEmpty()) printer.startShared(names)
            return
        }
        if (i.action != Intent.ACTION_SEND && i.action != Intent.ACTION_SEND_MULTIPLE) return
        val sendEntry = i.component?.className?.endsWith("SendShareAlias") == true   // "LANShare Send" = pick a device; "LANShare" = print
        @Suppress("DEPRECATION")
        val uris: List<Uri> = if (i.action == Intent.ACTION_SEND) listOfNotNull(i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        else i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
        i.action = null   // consume once
        if (uris.isEmpty()) return
        Thread {
            val names = ArrayList<String>()
            val dir = java.io.File(Environment.getExternalStorageDirectory(), "LANShare Shared").apply { mkdirs() }
            for (u in uris) try {
                var name = "shared_" + System.currentTimeMillis()
                contentResolver.query(u, null, null, null, null)?.use { q ->
                    if (q.moveToFirst()) q.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = q.getString(it) ?: name }
                }
                var f = java.io.File(dir, name.replace("/", "_")); var k = 1
                while (f.exists()) { f = java.io.File(dir, "${f.nameWithoutExtension} ($k)${if (f.extension.isEmpty()) "" else "." + f.extension}"); k++ }
                contentResolver.openInputStream(u)?.use { ins -> f.outputStream().use { ins.copyTo(it) } }
                names.add(f.name)
            } catch (_: Exception) { }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (names.isEmpty()) onToast("Could not read the shared file(s)", true)
                else if (sendEntry) askSendShared(names)
                else printer.startShared(names)
            }
        }.also { it.isDaemon = true }.start()
    }

    override fun onResume() { super.onResume(); ui.removeCallbacks(devPoll); ui.post(devPoll) }
    override fun onPause() { ui.removeCallbacks(devPoll); super.onPause() }
    override fun onDestroy() { ui.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun askPermissions() {
        try {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager())
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            else if (Build.VERSION.SDK_INT < 30) requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 2)
        } catch (_: Throwable) { }
    }

    // ---------------------------------------------------------------- views
    private fun pressed(): StateListDrawable = StateListDrawable().also {
        it.addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(c.cont))
        it.addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
    }

    private fun text(t: String, sp: Float, col: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(col)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun button(t: String, col: Int = c.fg, sp: Float = 15f, f: () -> Unit): TextView = text(t, sp, col, true).apply {
        gravity = Gravity.CENTER; minWidth = dp(44); setPadding(dp(12), 0, dp(12), 0); background = pressed()
        isClickable = true; setOnClickListener { f() }
    }

    private fun build() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(c.bg) }

        // top bar / selection bar share one frame
        barFrame = FrameLayout(this)
        topBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        btnUp = button("\u2190", sp = 22f) { ctl.up() }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tvTitle = text("", 18f, c.fg, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        tvSub = text("", 12f, c.mut).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        titles.addView(tvTitle); titles.addView(tvSub)
        topBar.addView(button("\u2630", sp = 20f) { drawer.open() }, LinearLayout.LayoutParams(-2, -1))
        topBar.addView(btnUp, LinearLayout.LayoutParams(-2, -1))
        topBar.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        topBar.addView(button("\uD83D\uDD0D") { openSearch() }, LinearLayout.LayoutParams(-2, -1))
        topBar.addView(button("\u21C5") { sortDialog() }, LinearLayout.LayoutParams(-2, -1))
        topBar.addView(button("\u25A6") { viewDialog() }, LinearLayout.LayoutParams(-2, -1))
        val more = button("\u22EE", sp = 22f) { }
        more.setOnClickListener { moreMenu(it) }
        topBar.addView(more, LinearLayout.LayoutParams(-2, -1))
        selBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE; setBackgroundColor(c.sel) }
        tvSel = text("", 17f, c.fg, true)
        selBar.addView(button("\u2715", sp = 20f) { ctl.clearSel() }, LinearLayout.LayoutParams(-2, -1))
        selBar.addView(tvSel, LinearLayout.LayoutParams(0, -2, 1f))
        selBar.addView(button("Select all", c.ac, 14f) { ctl.selectOnly(rows.map { it.nm }) }, LinearLayout.LayoutParams(-2, -1))
        barFrame.addView(topBar, FrameLayout.LayoutParams(-1, -1))
        barFrame.addView(selBar, FrameLayout.LayoutParams(-1, -1))
        root.addView(barFrame, LinearLayout.LayoutParams(-1, dp(56)))

        // search row (hidden until the magnifier is tapped): recursive name search below the open folder
        searchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE; setBackgroundColor(c.cont); setPadding(dp(14), 0, 0, 0) }
        etSearch = EditText(this).apply {
            hint = "Search this folder and below"; setSingleLine(); setTextColor(c.fg); setHintTextColor(c.mut); background = null
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, a, _ ->
                if (a == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { ui.removeCallbacks(searchRun); runSearch(); hideKeyboard(); true } else false
            }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { ui.removeCallbacks(searchRun); ui.postDelayed(searchRun, 600) }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
            })
        }
        searchRow.addView(etSearch, LinearLayout.LayoutParams(0, -1, 1f))
        searchRow.addView(button("\u2715", sp = 18f) { closeSearch() }, LinearLayout.LayoutParams(-2, -1))
        root.addView(searchRow, LinearLayout.LayoutParams(-1, dp(48)))

        // device strip: the open device, "Devices N" picker, + IP, rescan
        deviceScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; setBackgroundColor(c.bg) }
        deviceRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), 0, dp(10), 0) }
        deviceScroll.addView(deviceRow, FrameLayout.LayoutParams(-2, -1))
        root.addView(deviceScroll, LinearLayout.LayoutParams(-1, dp(44)))

        // breadcrumbs + summary
        val pathRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        crumbScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        crumbs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        crumbScroll.addView(crumbs)
        tvSum = text("", 12f, c.mut).apply { setPadding(dp(8), 0, dp(14), 0) }
        pathRow.addView(crumbScroll, LinearLayout.LayoutParams(0, -1, 1f))
        pathRow.addView(tvSum, LinearLayout.LayoutParams(-2, -2))
        root.addView(pathRow, LinearLayout.LayoutParams(-1, dp(36)))

        // list + empty text + snackbar
        val body = FrameLayout(this)
        rv = RecyclerView(this).apply { setBackgroundColor(pal.card); adapter = ad; layoutManager = LinearLayoutManager(this@FilesActivity); clipToPadding = false; setPadding(0, 0, 0, dp(80)) }
        srl = SwipeRefreshLayout(this).apply {
            addView(rv); setProgressBackgroundColorSchemeColor(c.cont); setColorSchemeColors(c.ac)
            setOnRefreshListener { ctl.refresh() }
        }
        tvEmpty = text("", 15f, c.mut).apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(24)) }
        toastBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(dp(16), dp(10), dp(16), dp(10))
            background = android.graphics.drawable.GradientDrawable().also { it.setColor(0xFF323232.toInt()); it.cornerRadius = dp(8).toFloat() }
        }
        tvToast = text("", 14f, Color.WHITE)
        tvCancel = text("CANCEL", 13f, 0xFF8AB4F8.toInt(), true).apply { setPadding(dp(14), dp(4), 0, dp(4)); visibility = View.GONE; setOnClickListener { ctl.cancelJob() } }
        val trow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        trow.addView(tvToast, LinearLayout.LayoutParams(0, -2, 1f)); trow.addView(tvCancel)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        toastBar.addView(trow); toastBar.addView(progress, LinearLayout.LayoutParams(-1, dp(6)).also { it.topMargin = dp(6) })
        body.addView(srl, FrameLayout.LayoutParams(-1, -1))
        body.addView(tvEmpty, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        body.addView(toastBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).also { it.setMargins(dp(12), 0, dp(12), dp(12)) })
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))

        // clipboard bar + action dock
        clipBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE; setBackgroundColor(c.cont); setPadding(dp(14), 0, 0, 0) }
        tvClip = text("", 13f, c.fg).apply { maxLines = 2 }
        clipBar.addView(tvClip, LinearLayout.LayoutParams(0, -2, 1f))
        clipBar.addView(button("Paste here", c.ac, 14f) { ctl.paste() }, LinearLayout.LayoutParams(-2, dp(48)))
        clipBar.addView(button("\u2715", sp = 18f) { ctl.clearClip() }, LinearLayout.LayoutParams(-2, dp(48)))
        root.addView(clipBar, LinearLayout.LayoutParams(-1, -2))
        dock = HorizontalScrollView(this).apply { visibility = View.GONE; setBackgroundColor(c.cont); isHorizontalScrollBarEnabled = false }
        dockRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        dock.addView(dockRow)
        root.addView(dock, LinearLayout.LayoutParams(-1, dp(52)))
        drawer = NativeDrawer(this, c, ctl, { devName(it) }, { t, i, o, f -> input(t, i, o) { f(it) } },
            { askAddIp() }, { settings.smb() }, { settings.show() }, { printer.showPrinters() }, { onToast(it, false) })
        val frame = FrameLayout(this)
        frame.addView(root, FrameLayout.LayoutParams(-1, -1))
        drawer.attach(frame)
        setContentView(frame)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun showEmpty(msg: String?) { tvEmpty.text = msg ?: ""; tvEmpty.visibility = if (msg == null) View.GONE else View.VISIBLE }

    // ---------------------------------------------------------------- FsController.Listener
    override fun onList() {
        val key = ctl.dev + "|" + ctl.path
        if (key != curKey) {
            if (curKey.isNotEmpty()) rv.layoutManager?.onSaveInstanceState()?.let { saved[curKey] = it }
            if (ctl.err == null) { Prefs.histPush(ctl.dev, ctl.path); if (ctl.dev == "local") Prefs.used = ctl.used }
        }
        if (key != curKey && searchRow.visibility == View.VISIBLE) hideSearchUi()   // navigated away (e.g. tapped a folder in the results)
        rebuild()
        if (ctl.query != lastQuery) { lastQuery = ctl.query; rv.scrollToPosition(0) }
        if (key != curKey) {
            curKey = key
            val st = saved[key]
            if (st != null) rv.layoutManager?.onRestoreInstanceState(st) else rv.scrollToPosition(0)
        }
        srl.isRefreshing = false
        showEmpty(if (rows.isNotEmpty()) null else if (ctl.err != null) "\u26A0 " + ctl.err + "\nRetrying\u2026" else if (ctl.query != null) "No matches" else "This folder is empty")
        header()
        renderDevices(); drawer.refresh()
    }

    override fun onSelection() {
        val s = ctl.selItems()
        selBar.visibility = if (s.isEmpty()) View.GONE else View.VISIBLE
        val sz = s.filter { !it.dir }.sumOf { it.size }
        tvSel.text = s.size.toString() + " selected" + (if (sz > 0) " \u00b7 " + NlModel.fmt(sz) else "")
        ad.notifyDataSetChanged()
        renderDock()
    }

    override fun onClip() = renderDock()

    private val hideToast = Runnable { toastBar.visibility = View.GONE }

    override fun onToast(msg: String, long: Boolean) {
        tvToast.text = msg; progress.visibility = View.GONE; tvCancel.visibility = View.GONE; toastBar.visibility = View.VISIBLE
        ui.removeCallbacks(hideToast); ui.postDelayed(hideToast, if (long) 4500L else 2800L)
    }

    override fun onProgress(label: String, pct: Int?, state: String) {
        if (state != "run") return   // the closing message comes through onToast
        ui.removeCallbacks(hideToast)
        tvToast.text = label + (if (pct != null) " $pct%" else "\u2026")
        progress.isIndeterminate = pct == null
        if (pct != null) progress.progress = pct
        progress.visibility = View.VISIBLE; tvCancel.visibility = View.VISIBLE; toastBar.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------- rows
    private fun rebuild() {
        val items = ctl.items
        val files = items.filter { !it.dir }
        val media = files.count { val k = NlModel.kind(it.name, false); k == "img" || k == "vid" }
        vp = Prefs.viewFor(ctl.dev, ctl.path, files.isNotEmpty() && media * 2 >= files.size)
        val cfg = NlModel.Cfg(vp.sort, vp.asc, Prefs.hidden, false, vp.view, vp.thumb)
        val rs: List<NlRow> = (try { NlModel.rows(items, ctl.path, cfg) } catch (_: Throwable) { null }) ?: emptyList()
        rows = if (ctl.dev == "local") rs else rs.map { NlRow(it.nm, it.dir, it.k, it.a, it.b, it.badge, it.badgeCol, it.dup, it.path, it.size, it.mtime, false, ctl.dev, false) }
        val grid = vp.view == "grid"
        val span = if (resources.configuration.screenWidthDp >= 600) 4 else 2
        if (grid != gridOn || (grid && span != spanN) || rv.layoutManager == null) {
            gridOn = grid; spanN = span
            rv.layoutManager = if (grid) GridLayoutManager(this, span) else LinearLayoutManager(this)
            rv.setPadding(if (grid) dp(4) else 0, if (grid) dp(4) else 0, if (grid) dp(4) else 0, dp(80))
        }
        ad.notifyDataSetChanged()
    }

    private fun applyPref(v: Prefs.ViewPref) {
        Prefs.setView(ctl.dev, ctl.path, v, applyAll)
        rebuild(); header()
    }

    private fun tap(r: NlRow) {
        if (ctl.sel.isNotEmpty()) { ctl.toggle(r.nm); return }
        if (r.dir) { ctl.go(vjoin(ctl.path, r.nm)); return }
        if (isArcName(r.nm)) { ctl.go(vjoin(ctl.path, r.nm) + "!"); return }
        if (FileOpen.viewer(this, ctl.dev, ctl.path, rows, r)) return
        FileOpen.external(this, ctl.dev, vjoin(ctl.path, r.nm), r.nm)
    }

    // ---------------------------------------------------------------- header, breadcrumbs, dock
    private fun devName(dev: String): String {
        if (dev == "local") return CoreCfg.name
        try { Core.discOrNull()?.list()?.firstOrNull { it.id == dev }?.let { return it.name } } catch (_: Throwable) { }
        try { Smb.peers().firstOrNull { it.optString("id") == dev }?.let { return it.optString("name").ifEmpty { dev } } } catch (_: Throwable) { }
        return dev
    }

    private fun header() {
        val p = ctl.path
        val dn = devName(ctl.dev)
        tvTitle.text = if (p == "/") dn else Prefs.baseName(p)
        tvSub.text = if (p == "/") "" else dn
        tvSub.visibility = if (p == "/") View.GONE else View.VISIBLE
        btnUp.visibility = if (p == "/") View.GONE else View.VISIBLE
        crumbs.removeAllViews()
        val segs = p.split('/').filter { it.isNotEmpty() }
        crumbs.addView(button(dn, c.ac, 13f) { ctl.go("/") }, LinearLayout.LayoutParams(-2, -1))
        for ((i, sg) in segs.withIndex()) {
            crumbs.addView(text("\u203A", 14f, c.mut))
            val target = "/" + segs.take(i + 1).joinToString("/")
            crumbs.addView(button(sg.removeSuffix("!"), if (i == segs.size - 1) c.fg else c.ac, 13f) { ctl.go(target) }, LinearLayout.LayoutParams(-2, -1))
        }
        crumbScroll.post { crumbScroll.fullScroll(View.FOCUS_RIGHT) }
        val sum = NlModel.headSum(ctl.items, Prefs.hidden).first
        val u = ctl.used
        tvSum.text = sum + (if (u != null) " \u00b7 $u% used" else "")
        ctl.query?.let { q ->
            tvSub.text = ctl.items.size.toString() + " result" + (if (ctl.items.size == 1) "" else "s") + " for \"" + q + "\"" + (if (ctl.queryPartial) " (stopped at the limit)" else "")
            tvSub.visibility = View.VISIBLE
        }
    }

    private fun renderDock() {
        dockRow.removeAllViews()
        val sel = ctl.selItems()
        val arch = ctl.inArchive()
        val hasClip = !Clip.isEmpty()
        fun b(label: String, danger: Boolean = false, f: () -> Unit) {
            dockRow.addView(button(label, if (danger) 0xFFB3261E.toInt() else c.fg, 14f, f), LinearLayout.LayoutParams(-2, -1))
        }
        if (sel.isNotEmpty()) {
            if (arch || sel.any { ctl.isArc(it) }) b("Extract") { ctl.extract() }
            b("Copy") { ctl.copy(false) }
            val srch = ctl.query != null   // results come from several folders: zip / rename work on the open folder only
            if (!arch) { b("Cut") { ctl.copy(true) }; if (!srch) b("Zip") { askZip() } }
            if (hasClip && !arch) b("Paste") { ctl.paste() }
            if (!arch) b("Send to\u2026") { askSend() }
            if (sel.size == 1 && !arch && !srch) b("Rename") { askRename(sel[0].name) }
            if (sel.size == 1) b("Details") { details(sel[0].name) }
            if (sel.size == 1 && !sel[0].dir) b("Open with") { FileOpen.external(this, ctl.dev, vjoin(ctl.path, sel[0].name), sel[0].name, true) }
            if (sel.none { it.dir }) b("Print") { printer.start() }   // files only, like ui.html
            if (!arch) b("Delete", true) { askDelete(sel.size) }
        }
        dock.visibility = if (sel.isEmpty()) View.GONE else View.VISIBLE
        val showClip = hasClip && sel.isEmpty() && !arch
        clipBar.visibility = if (showClip) View.VISIBLE else View.GONE
        if (showClip) tvClip.text = (if (Clip.op == "cut") "Cut " else "Copied ") + Clip.paths.size + " item(s) from " + devName(Clip.dev ?: "local")
    }

    // ---------------------------------------------------------------- search
    private fun openSearch() {
        searchRow.visibility = View.VISIBLE
        etSearch.requestFocus()
        try { getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(etSearch, 0) } catch (_: Throwable) { }
    }

    private fun hideKeyboard() {
        try { getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(etSearch.windowToken, 0) } catch (_: Throwable) { }
    }

    private fun runSearch() {
        val q = etSearch.text.toString().trim()
        if (q.isEmpty()) { ctl.clearSearch(); return }
        showEmpty("Searching\u2026")
        ctl.search(q)
    }

    /** Close the search row only (the controller already left the search, e.g. after a navigation). */
    private fun hideSearchUi() {
        etSearch.setText(""); ui.removeCallbacks(searchRun)
        searchRow.visibility = View.GONE
        hideKeyboard()
    }

    /** The x button / back: leave the search, the folder comes back. */
    private fun closeSearch() {
        hideSearchUi()
        ctl.clearSearch()
        showEmpty(null)
    }

    // ---------------------------------------------------------------- send to another device
    /** "Send to...": the selection goes to the other device's storage root (its INBOX), same job + progress as copy. */
    private fun askSend() {
        if (ctl.selItems().isEmpty()) return
        val (ids, names) = sendTargets()
        if (ids.isEmpty()) { onToast("No other devices found yet - open the same app on the other phone / PC on this Wi-Fi", true); return }
        AlertDialog.Builder(this).setTitle("Send to\u2026").setItems(names.toTypedArray()) { _, i -> ctl.send(ids[i]) }
            .setNegativeButton("Cancel", null).show()
    }

    /** The other devices a file can be sent to: SMB shares and LANShare devices that answer (never the open device itself). */
    private fun sendTargets(): Pair<List<String>, List<String>> {
        val ids = ArrayList<String>(); val names = ArrayList<String>()
        try { Smb.peers().forEach { s -> val id = s.optString("id"); if (id.isNotEmpty() && id != ctl.dev) { ids.add(id); names.add(s.optString("name").ifEmpty { id } + "  (SMB share)") } } } catch (_: Throwable) { }
        try { Core.discOrNull()?.list()?.forEach { p -> if (p.ok && p.id != ctl.dev) { ids.add(p.id); names.add(p.name) } } } catch (_: Throwable) { }
        return ids to names
    }

    /**
     * Share-sheet entry "LANShare Send": the shared files were copied to /LANShare Shared (handleIntent); the device picker opens at once
     * and the files go to the chosen device's INBOX like any "Send to...". The copies are deleted when the job has ended (or the picker is cancelled).
     */
    private fun askSendShared(names: List<String>) {
        val dir = "/LANShare Shared"
        fun cleanup() { Thread { names.forEach { try { java.io.File(Environment.getExternalStorageDirectory(), "LANShare Shared/$it").delete() } catch (_: Throwable) { } } }.also { it.isDaemon = true }.start() }
        val (ids, labels) = sendTargets()
        if (ids.isEmpty()) {
            onToast("No other devices found yet - open the same app on the other phone / PC on this Wi-Fi", true)
            cleanup(); return
        }
        AlertDialog.Builder(this).setTitle(if (names.size == 1) "Send " + names[0] + " to\u2026" else "Send " + names.size + " files to\u2026")
            .setItems(labels.toTypedArray()) { _, i -> ctl.sendPaths("local", names.map { "$dir/$it" }, ids[i]) { cleanup() } }
            .setNegativeButton("Cancel") { _, _ -> cleanup() }
            .setOnCancelListener { cleanup() }
            .show()
    }

    // ---------------------------------------------------------------- dialogs
    private fun input(title: String, init: String, ok: String, selectBase: Boolean = false, f: (String) -> Unit) {
        val et = EditText(this).apply { setText(init); setSingleLine(); if (selectBase) setSelection(0, init.lastIndexOf('.').let { if (it > 0) it else init.length }) else setSelection(init.length) }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et) }
        val dlg = AlertDialog.Builder(this).setTitle(title).setView(box).setNegativeButton("Cancel", null)
            .setPositiveButton(ok) { _, _ -> val t = et.text.toString().trim(); if (t.isNotEmpty()) f(t) }.create()
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.show()
    }

    /** "Add IP" (ui.html addIp): ip or ip:port of another LANShare device; Discovery.addIp probes it and pins it if it is off-subnet. */
    private fun askAddIp() {
        val et = EditText(this).apply {
            hint = "e.g. 192.168.43.1"; setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et) }
        val dlg = AlertDialog.Builder(this).setTitle("Add another LANShare device")
            .setMessage("IP address of the other device running LANShare")
            .setView(box).setNegativeButton("Cancel", null)
            .setPositiveButton("Connect") { _, _ ->
                val ip = et.text.toString().trim()
                if (ip.isNotEmpty()) addIp(ip)
            }.create()
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.show()
    }

    private fun addIp(ip: String) {
        onToast("Connecting to $ip\u2026", false)
        Thread {
            val msg = try {
                val d = Core.discOrNull() ?: throw IllegalStateException("device discovery is not running yet")
                if (d.addIp(ip)) "Found it!" else "\u26A0 no LANShare device found at that address"
            } catch (e: Throwable) { "\u26A0 " + (e.message ?: "failed") }
            ui.post { onToast(msg, true) }
        }.start()
    }

    private fun askRename(old: String) = input("Rename", old, "Rename", true) { ctl.rename(old, it) }
    private fun askMkdir() = input("New folder", "", "Create") { ctl.mkdir(it) }
    private fun askZip() {
        val s = ctl.selItems()
        val base = if (s.size == 1) s[0].name.substringBeforeLast('.', s[0].name) else Prefs.baseName(ctl.path).ifEmpty { "Archive" }
        input("Zip", "$base.zip", "Zip") { ctl.zip(it) }
    }
    private fun askDelete(n: Int) {
        AlertDialog.Builder(this).setTitle("Delete").setMessage("Delete $n item(s)? This cannot be undone.")
            .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ -> ctl.delete() }.show()
    }

    private fun details(name: String) {
        val dev = ctl.dev; val p = vjoin(ctl.path, name)
        Thread {
            val msg = try {
                val o = Jobs.ep(dev).stat(p)
                o.keys().asSequence().joinToString("\n") { k -> k + ": " + o.opt(k) }
            } catch (e: Throwable) { "\u26A0 " + errText(e) }
            runOnUiThread { if (!isFinishing) AlertDialog.Builder(this).setTitle(name).setMessage(msg).setPositiveButton("OK", null).show() }
        }.also { it.isDaemon = true }.start()
    }

    private fun sortDialog() {
        val keys = arrayOf("name", "date", "size", "type", "none")
        val names = arrayOf("Name", "Date modified", "Size", "Type", "No sort")
        val labels = Array(keys.size) { i -> names[i] + (if (keys[i] == vp.sort && keys[i] != "none") (if (vp.asc) "   \u2191" else "   \u2193") else "") }
        AlertDialog.Builder(this).setTitle("Sort by").setSingleChoiceItems(labels, keys.indexOf(vp.sort)) { dlg, which ->
            val k = keys[which]
            val asc = if (k == vp.sort && k != "none") !vp.asc else k != "date" && k != "size"   // a new date / size sort starts newest / largest first
            applyPref(Prefs.ViewPref(vp.view, k, asc, vp.thumb)); dlg.dismiss()
        }.show()
    }

    private fun viewDialog() {
        fun onoff(b: Boolean) = if (b) "on" else "off"
        val labels = arrayOf("List", "Compact", "Grid", "Large thumbnails: " + onoff(vp.thumb == "l"), "Show hidden files: " + onoff(Prefs.hidden), "Apply to all folders: " + onoff(applyAll))
        AlertDialog.Builder(this).setTitle("View").setItems(labels) { _, i ->
            when (i) {
                0 -> applyPref(Prefs.ViewPref("list", vp.sort, vp.asc, vp.thumb))
                1 -> applyPref(Prefs.ViewPref("compact", vp.sort, vp.asc, vp.thumb))
                2 -> applyPref(Prefs.ViewPref("grid", vp.sort, vp.asc, vp.thumb))
                3 -> applyPref(Prefs.ViewPref(vp.view, vp.sort, vp.asc, if (vp.thumb == "l") "s" else "l"))
                4 -> { Prefs.hidden = !Prefs.hidden; rebuild(); header() }
                5 -> applyAll = !applyAll
            }
        }.show()
    }

    private fun moreMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, "New folder")
        m.menu.add(0, 2, 1, "Refresh")
        m.menu.add(0, 3, 2, if (Prefs.isFav(ctl.dev, ctl.path)) "Remove from favourites" else "Add to favourites")
        m.menu.add(0, 4, 3, "Theme: " + Prefs.theme)
        m.menu.add(0, 5, 4, "Settings")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> askMkdir()
                2 -> ctl.refresh()
                3 -> Prefs.toggleFav(ctl.dev, ctl.path, devName(ctl.dev))
                4 -> { Prefs.theme = when (Prefs.theme) { "light" -> "dark"; "dark" -> "auto"; else -> "light" }; recreate() }
                5 -> settings.show()
            }
            true
        }
        m.show()
    }

    // ---------------------------------------------------------------- device strip
    private class DevInfo(val id: String, val name: String, val ok: Boolean, val via: String)

    private fun peerList(): List<DevInfo> {
        val out = ArrayList<DevInfo>()
        try { Core.discOrNull()?.list()?.forEach { out.add(DevInfo(it.id, it.name, it.ok, viaOf(it.ip))) } } catch (_: Throwable) { }
        try { Smb.peers().forEach { s -> val id = s.optString("id"); if (id.isNotEmpty()) out.add(DevInfo(id, s.optString("name").ifEmpty { id }, true, "SMB")) } } catch (_: Throwable) { }
        return out
    }

    private fun chip(label: String, on: Boolean, lead: View? = null, f: () -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(14), 0)
        background = android.graphics.drawable.GradientDrawable().also { it.cornerRadius = dp(16).toFloat(); it.setColor(if (on) c.sel else c.cont) }
        isClickable = true; setOnClickListener { f() }
        if (lead != null) addView(lead, LinearLayout.LayoutParams(dp(8), dp(8)).also { it.rightMargin = dp(8) })
        addView(text(label, 13f, if (on) c.ac else c.fg, true).apply { maxLines = 1 })
    }

    private fun renderDevices() {
        val peers = peerList()
        val cur = peers.firstOrNull { it.id == ctl.dev }
        val sig = ctl.dev + "|" + peers.joinToString(";") { it.id + it.name + it.ok + it.via } + "|" + CoreCfg.name
        if (sig == devSig) return
        devSig = sig
        deviceRow.removeAllViews()
        fun dot(warn: Boolean) = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().also { it.shape = android.graphics.drawable.GradientDrawable.OVAL; it.setColor(if (warn) c.warn else 0xFF2E7D32.toInt()) }
        }
        val lp = { LinearLayout.LayoutParams(-2, dp(32)).also { it.rightMargin = dp(8) } }
        // only the open device is a chip; every other device lives behind "Devices"
        if (ctl.dev == "local") deviceRow.addView(chip("This device", true) { }, lp())
        else deviceRow.addView(chip(cur?.name ?: devName(ctl.dev), true, dot(cur?.ok == false)) { }, lp())
        val others = peers.filter { it.id != ctl.dev }
        val nOther = others.size + (if (ctl.dev != "local") 1 else 0)
        if (nOther > 0) deviceRow.addView(chip("Devices  $nOther", false) { devPicker() }, lp())
        deviceRow.addView(chip("\uFF0B IP", false) { askAddIp() }, lp())
        deviceRow.addView(chip("\u21BB", false) { onToast("Scanning\u2026", false); Thread { try { Core.rescan() } catch (_: Throwable) { } }.start() }, lp())
        if (peers.isEmpty()) deviceRow.addView(text("Searching for devices\u2026 tap \u21BB or + IP", 12f, c.mut).apply { setPadding(dp(4), 0, dp(8), 0) })
    }

    /** Re-draw the open drawer when the device list changed (the strip already compared its own signature). */
    private var drSig = ""
    private fun drawerSig() {
        if (!drawer.isOpen) return
        val s = peerList().joinToString(";") { it.id + it.ok }
        if (s != drSig) { drSig = s; drawer.refresh() }
    }

    private fun devPicker() {
        val ids = ArrayList<String>(); val labels = ArrayList<String>()
        if (ctl.dev != "local") { ids.add("local"); labels.add(CoreCfg.name + "  (this device)") }
        peerList().filter { it.id != ctl.dev }.forEach {
            ids.add(it.id)
            labels.add(it.name + (if (it.via.isNotEmpty()) "  \u00b7 " + it.via else "") + (if (!it.ok) "  \u00b7 offline" else ""))
        }
        if (ids.isEmpty()) { onToast("No other devices yet", false); return }
        AlertDialog.Builder(this).setTitle("Devices").setItems(labels.toTypedArray()) { _, i -> ctl.openDev(ids[i]) }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------------------------------------------------------------- adapter + thumbnails
    private class VH(val v: View) : RecyclerView.ViewHolder(v) { var job: Future<*>? = null; var key: String? = null }

    private inner class Ad : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = if (gridOn) 1 else 0
        override fun onCreateViewHolder(parent: ViewGroup, t: Int): VH {
            val v: View = if (t == 1) NlGridView(parent.context, d) else NlRowView(parent.context, d)
            val lp = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (t == 1) lp.setMargins(dp(4), dp(4), dp(4), dp(4))
            v.layoutParams = lp
            v.isClickable = true; v.isLongClickable = true
            val h = VH(v)
            v.setOnClickListener { val i = h.bindingAdapterPosition; if (i >= 0 && i < rows.size) tap(rows[i]) }
            v.setOnLongClickListener {
                val i = h.bindingAdapterPosition
                if (i >= 0 && i < rows.size) { it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); ctl.select(rows[i].nm); true } else false
            }
            return h
        }

        override fun onBindViewHolder(h: VH, pos: Int) {
            val r = rows[pos]
            h.job?.cancel(false); h.job = null; h.key = null
            val sel = r.nm in ctl.sel
            val hv = h.v
            if (hv is NlGridView) hv.bind(r, pal, sel, ctl.sel.isNotEmpty())
            else (hv as NlRowView).bind(r, pal, sel, vp.view == "compact", vp.view == "list" && vp.thumb == "l")
            val key = r.thumbKey ?: return
            val hit = thumbs.get(key)
            if (hit != null) { setThumb(hv, hit); return }
            if (synchronized(failed) { failed.contains(key) }) return
            h.key = key
            h.job = loadTh(r, key, { h.key == key }) { th -> setThumb(h.v, th) }
        }

        override fun onViewRecycled(h: VH) { h.job?.cancel(false); h.job = null; h.key = null }
    }

    private fun setThumb(v: View, t: Th) {
        if (v is NlGridView) { v.thumb = t.bm; v.dur = t.dur } else if (v is NlRowView) { v.thumb = t.bm; v.dur = t.dur }
        v.invalidate()
    }

    private fun fmtDur(ms: Long): String {
        val s = Math.round(ms / 1000.0); val h = s / 3600; val m = (s % 3600) / 60; val c = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, c) else String.format("%d:%02d", m, c)
    }

    /** Decode one thumbnail on the pool (this phone: files; other devices / SMB: through the endpoint, disk cached); [ok] is checked on the UI thread. */
    private fun loadTh(r: NlRow, key: String, ok: () -> Boolean, done: (Th) -> Unit): Future<*> = pool.submit(Runnable {
        val th: Th? = try {
            if (r.dev != "local") {
                val (data, ms) = RemoteThumbs.make(r.dev, r.path!!, r.size, r.mtime, r.k == "vid")
                BitmapFactory.decodeByteArray(data, 0, data.size)?.let { Th(it, if (ms > 0) fmtDur(ms) else null) }
            } else if (r.k == "vid") {
                val (data, ms) = VideoThumbs.make(Core.local.open(r.path!!))
                BitmapFactory.decodeByteArray(data, 0, data.size)?.let { Th(it, if (ms > 0) fmtDur(ms) else null) }
            } else {
                val data = Thumbs.make(Core.local.real(r.path!!))
                BitmapFactory.decodeByteArray(data, 0, data.size)?.let { Th(it, null) }
            }
        } catch (_: Throwable) { null }
        if (th != null) { thumbs.put(key, th); runOnUiThread { if (ok()) done(th) } }
        else synchronized(failed) { failed.add(key) }
    })
}
