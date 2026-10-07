package com.lanshare.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.text.InputType
import android.text.TextUtils
import android.util.LruCache
import android.util.TypedValue
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import android.widget.*
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.lanshare.app.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fully native file browser: this phone, other LANShare devices and SMB shares (device bar), copy / cut / paste with a
 * native progress bar, recursive search and pull-to-refresh. No WebView, no HTTP round-trip for listings: everything
 * goes through [Core.local] / [Jobs.ep] / [Jobs] / [Clip] in-process. Videos, pictures and PDFs open in the native viewers.
 * Not native (web UI only): archives (paths with '!'), printing, zip, settings.
 */
class BrowserActivity : Activity() {

    companion object {
        // static: survive rotation / re-opening, so a second visit to a folder is instant. Key = "<dev>|<path>"
        private val cache = LruCache<String, List<Item>>(80)
        private val thumbs = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }
        private val failed = HashSet<String>()
        private fun pool(n: Int) = Executors.newFixedThreadPool(n) { r -> Thread(r, "browser").also { it.isDaemon = true } }
        private val io = pool(4)
        private val pre = pool(1)
        private val thumbPool = pool(3)
        private val searchPool = pool(2)
        /** Running copy / move / delete jobs started from this screen (survive rotation). */
        private val jobIds = ArrayList<String>()
        /** Last folder per device, so switching back to a device returns where you were. */
        private val lastPath = HashMap<String, String>()

        private val EXT: HashMap<String, String> = HashMap<String, String>().also { m ->
            fun add(k: String, s: String) { s.split(' ').forEach { m[it] = k } }
            add("img", "jpg jpeg png gif webp bmp heic heif svg")
            add("vid", "mp4 mkv mov avi webm 3gp m4v mpg mpeg flv ogv m2ts mts")
            add("aud", "mp3 wav m4a ogg flac aac opus")
            add("pdf", "pdf")
            add("zip", "zip rar 7z tar gz cbz cbr")
            add("apk", "apk")
            add("doc", "txt md rtf doc docx odt xls xlsx csv ppt pptx")
        }
        private val TEXT_EXT = setOf("php", "phtml", "js", "mjs", "ts", "tsx", "jsx", "css", "scss", "json", "xml", "yml", "yaml", "toml",
            "ini", "cfg", "conf", "env", "md", "log", "sql", "py", "kt", "kts", "java", "c", "h", "cpp", "hpp", "cs", "go", "rs", "rb",
            "sh", "bat", "ps1", "gradle", "properties", "htaccess", "gitignore", "csv", "tsv", "srt", "vtt", "tex", "txt", "html", "htm", "svg")
        private val SUB = Regex("(?i).*\\.(srt|vtt|ass|ssa)$")
    }

    private class Dev(val id: String, val name: String, val ok: Boolean, val kind: String)

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var rv: RecyclerView
    private lateinit var srl: SwipeRefreshLayout
    private lateinit var empty: TextView
    private lateinit var crumbs: LinearLayout
    private lateinit var crumbScroll: HorizontalScrollView
    private lateinit var devRow: LinearLayout
    private lateinit var tTitle: TextView
    private lateinit var tSub: TextView
    private lateinit var titleCol: LinearLayout
    private lateinit var searchBox: EditText
    private lateinit var bLeft: TextView
    private lateinit var bA: TextView
    private lateinit var bB: TextView
    private lateinit var bC: TextView
    private lateinit var bD: TextView
    private lateinit var jobBar: LinearLayout
    private lateinit var jobText: TextView
    private lateinit var jobStat: TextView
    private lateinit var jobProg: ProgressBar
    private lateinit var pasteBar: LinearLayout
    private lateinit var pasteText: TextView
    private val ad = Adapter()

    private var dev = "local"
    private var cur = "/"
    private var raw: List<Item> = emptyList()
    private var items: List<Item> = emptyList()
    private val sel = LinkedHashSet<String>()      // selected items, by full path (search results span several folders)
    private var gen = 0
    private var loaded = false
    private var started = false
    private var free: String? = null
    private val states = HashMap<String, Parcelable?>()
    private val inflight = HashSet<String>()
    private val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

    private var devs: List<Dev> = emptyList()
    private var devSig = ""

    private var searching = false
    private var query = ""
    private var sgen = 0
    private var partial = false
    private var results: List<Item> = emptyList()

    private var grid = false
    private var sortKey = "name"
    private var asc = true
    private var showHidden = false

    // palette
    private var cBg = 0; private var cCard = 0; private var cFg = 0; private var cMut = 0
    private var cDiv = 0; private var cSel = 0
    private val cAccent = 0xFF0D8F7E.toInt()
    private val cBar = 0xFF1C1C1E.toInt()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun attachBaseContext(b: Context) = super.attachBaseContext(UiScale.wrap(b))

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Core.url == null) { Toast.makeText(this, "LANShare is still starting - try again in a moment", Toast.LENGTH_LONG).show(); finish(); return }
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        cBg = if (night) 0xFF121314.toInt() else 0xFFF4F5F6.toInt()
        cCard = if (night) 0xFF1E2022.toInt() else 0xFFFFFFFF.toInt()
        cFg = if (night) 0xFFEDEDED.toInt() else 0xFF1B1B1B.toInt()
        cMut = if (night) 0xFF9AA0A6.toInt() else 0xFF5F6368.toInt()
        cDiv = if (night) 0xFF2C2F31.toInt() else 0xFFE3E5E8.toInt()
        cSel = if (night) 0xFF21423D.toInt() else 0xFFD3EEEA.toInt()

        prefs = getSharedPreferences("ls_native", MODE_PRIVATE)
        grid = prefs.getString("view", "list") == "grid"
        sortKey = prefs.getString("sort", "name") ?: "name"
        asc = prefs.getBoolean("asc", true)
        showHidden = prefs.getBoolean("hidden", false)

        dev = b?.getString("dev") ?: intent.getStringExtra("dev") ?: "local"
        val p0 = b?.getString("cur") ?: intent.getStringExtra("path") ?: "/"
        cur = if (p0.startsWith("/") && !p0.contains('!')) p0 else "/"

        buildUi()
        setGrid(grid, false)
        refreshDevices()
        started = true
        navigate(cur, false)
        if (jobIds.isNotEmpty()) ui.post(jobTick)
    }

    override fun onSaveInstanceState(o: Bundle) { super.onSaveInstanceState(o); o.putString("cur", cur); o.putString("dev", dev) }

    private var firstResume = true
    override fun onResume() {   // silent refresh after viewers / other apps (not on the very first resume: onCreate just loaded)
        super.onResume()
        ui.removeCallbacks(devTick); ui.post(devTick)
        if (firstResume) { firstResume = false; return }
        if (started && !searching) load(cur)
    }

    override fun onPause() { ui.removeCallbacks(devTick); super.onPause() }

    override fun onDestroy() { ui.removeCallbacks(devTick); ui.removeCallbacks(jobTick); super.onDestroy() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            sel.isNotEmpty() -> { sel.clear(); ad.notifyDataSetChanged(); updateChrome() }
            searching -> exitSearch()
            cur != "/" -> navigate(parent(cur), true)
            dev != "local" -> switchDev("local")
            else -> super.onBackPressed()
        }
    }

    // ------------------------------------------------------------------ UI construction

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(cBg) }

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(cBar) }
        bLeft = btn(22f) { if (sel.isNotEmpty()) { sel.clear(); ad.notifyDataSetChanged(); updateChrome() } else onBackPressed() }
        tTitle = TextView(this).apply { textSize = 18f; setTypeface(null, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        tSub = TextView(this).apply { textSize = 12f; setTextColor(0xFFB0B4B8.toInt()); maxLines = 1 }
        titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(tTitle); addView(tSub) }
        searchBox = EditText(this).apply {
            visibility = View.GONE; setSingleLine(); textSize = 16f; setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFF9AA0A6.toInt())
            background = null; imeOptions = EditorInfo.IME_ACTION_SEARCH; inputType = InputType.TYPE_CLASS_TEXT
            setOnEditorActionListener { v, action, ev ->
                if (action == EditorInfo.IME_ACTION_SEARCH || (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)) {
                    runSearch(v.text.toString().trim()); true
                } else false
            }
        }
        bA = btn(20f) {}; bB = btn(20f) {}; bC = btn(20f) {}; bD = btn(22f) {}
        top.addView(bLeft, LinearLayout.LayoutParams(dp(52), dp(56)))
        top.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(searchBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        for (v in listOf(bA, bB, bC, bD)) top.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)))
        root.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        devRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(8), 0) }
        val devScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; setBackgroundColor(cBg); addView(devRow) }
        root.addView(devScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))

        crumbs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(8), 0) }
        crumbScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; setBackgroundColor(cCard); addView(crumbs) }
        root.addView(crumbScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
        root.addView(View(this).apply { setBackgroundColor(cDiv) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))

        val body = FrameLayout(this)
        rv = RecyclerView(this).apply { itemAnimator = null; setHasFixedSize(true); setItemViewCacheSize(24); adapter = ad }
        srl = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(cAccent); setProgressBackgroundColorSchemeColor(cCard)
            setOnRefreshListener { pullRefresh() }
            addView(rv)
        }
        empty = TextView(this).apply { textSize = 15f; setTextColor(cMut); gravity = Gravity.CENTER; setPadding(dp(32), 0, dp(32), 0); visibility = View.GONE }
        body.addView(srl, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        body.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // running job (copy / move / delete) with progress + Cancel
        jobBar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(cCard); setPadding(dp(14), dp(8), dp(14), dp(8)); visibility = View.GONE }
        jobText = TextView(this).apply { textSize = 14f; setTypeface(null, Typeface.BOLD); setTextColor(cFg); maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE }
        val cancel = TextView(this).apply {
            text = "Cancel"; textSize = 14f; setTextColor(cAccent); setTypeface(null, Typeface.BOLD); setPadding(dp(12), dp(4), 0, dp(4))
            setOnClickListener { jobIds.toList().forEach { id -> Jobs.all[id]?.cancel = true }; jobText.text = "Cancelling…" }
        }
        val jrow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        jrow.addView(jobText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); jrow.addView(cancel)
        jobProg = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progressTintList = ColorStateList.valueOf(cAccent) }
        jobStat = TextView(this).apply { textSize = 12f; setTextColor(cMut); maxLines = 1 }
        jobBar.addView(jrow); jobBar.addView(jobProg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(4); bottomMargin = dp(4) }); jobBar.addView(jobStat)
        root.addView(View(this).apply { setBackgroundColor(cDiv) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        root.addView(jobBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // clipboard bar: shown while something is copied / cut
        pasteBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(cBar); setPadding(dp(14), 0, dp(4), 0); visibility = View.GONE }
        pasteText = TextView(this).apply { textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val pasteBtn = TextView(this).apply {
            text = "Paste here"; textSize = 14f; setTypeface(null, Typeface.BOLD); setTextColor(0xFF58D6C5.toInt()); gravity = Gravity.CENTER; setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { paste() }
        }
        val clearBtn = btn(18f) { Clip.clear(); updateChrome() }.apply { text = "✕" }
        pasteBar.addView(pasteText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        pasteBar.addView(pasteBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pasteBar.addView(clearBtn, LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(pasteBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        setContentView(root)
    }

    private fun btn(size: Float, onClick: () -> Unit): TextView = TextView(this).apply {
        textSize = size; gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt()); setPadding(dp(12), 0, dp(12), 0)
        val tv = TypedValue(); theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        if (tv.resourceId != 0) background = getDrawable(tv.resourceId)
        setOnClickListener { onClick() }
    }

    private fun setBtn(b: TextView, text: String, size: Float, desc: String, onClick: () -> Unit) {
        b.visibility = View.VISIBLE; b.text = text; b.textSize = size; b.contentDescription = desc; b.setOnClickListener { onClick() }
    }

    private fun updateChrome() {
        val sm = sel.isNotEmpty()
        bLeft.text = if (sm || searching) (if (sm) "✕" else "←") else "←"
        titleCol.visibility = if (searching) View.GONE else View.VISIBLE
        searchBox.visibility = if (searching) View.VISIBLE else View.GONE
        tTitle.text = if (sm) "${sel.size} selected" else if (cur == "/") (if (dev == "local") "Main storage" else devName(dev)) else cur.substringAfterLast('/')
        val sub = if (sm) "" else "${items.size} item${if (items.size == 1) "" else "s"}" + (free?.let { " · $it free" } ?: "")
        tSub.text = sub; tSub.visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE
        if (sm) {
            setBtn(bA, "Copy", 14f, "Copy") { clipSelected("copy") }
            setBtn(bB, "Cut", 14f, "Cut") { clipSelected("cut") }
            setBtn(bC, "🗑", 18f, "Delete") { confirmDelete() }
            setBtn(bD, "⋮", 22f, "More") { selectionMenu() }
        } else if (searching) {
            bA.visibility = View.GONE; bB.visibility = View.GONE; bC.visibility = View.GONE
            setBtn(bD, "⋮", 22f, "Menu") { mainMenu() }
        } else {
            setBtn(bA, if (grid) "☰" else "▦", 20f, "Switch list / grid") { setGrid(!grid, true) }
            setBtn(bB, "⇅", 20f, "Sort") { sortMenu() }
            setBtn(bC, "🔍", 18f, "Search") { enterSearch() }
            setBtn(bD, "⋮", 22f, "Menu") { mainMenu() }
        }
        // clipboard bar
        val show = !Clip.isEmpty() && !sm && !searching
        pasteBar.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            val n = Clip.paths.size
            pasteText.text = "$n item${if (n == 1) "" else "s"} to ${if (Clip.op == "cut") "move" else "copy"} · from ${devName(Clip.dev ?: "local")}"
        }
        srl.isEnabled = !searching
    }

    private fun buildCrumbs() {
        crumbs.removeAllViews()
        fun crumb(label: String, path: String, last: Boolean) {
            if (crumbs.childCount > 0) crumbs.addView(TextView(this).apply { text = "›"; setTextColor(cMut); textSize = 16f })
            crumbs.addView(TextView(this).apply {
                text = label; textSize = 14f; setPadding(dp(8), dp(8), dp(8), dp(8))
                setTextColor(if (last) cFg else cMut); if (last) setTypeface(null, Typeface.BOLD)
                setOnClickListener { if (searching) { exitSearch(); navigate(path, true) } else if (path != cur) navigate(path, true) }
            })
        }
        if (searching) {
            crumbs.addView(TextView(this).apply {
                text = if (query.isEmpty()) "Type a name and press search — searches this folder and everything below it"
                       else "${items.size} result${if (items.size == 1) "" else "s"} for “$query”" + (if (partial) " · partial, narrow your search" else "")
                textSize = 13f; setTextColor(cMut); setPadding(dp(8), 0, dp(8), 0)
            })
            return
        }
        crumb(if (dev == "local") "Storage" else devName(dev), "/", cur == "/")
        var acc = ""
        val segs = cur.split('/').filter { it.isNotEmpty() }
        segs.forEachIndexed { i, s -> acc += "/$s"; crumb(s, acc, i == segs.size - 1) }
        crumbScroll.post { crumbScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    // ------------------------------------------------------------------ devices (this phone, LANShare peers, SMB shares)

    private fun devName(id: String): String = devs.firstOrNull { it.id == id }?.name ?: if (id == "local") "This phone" else "device"

    private fun ep(d: String): Endpoint = if (d == "local") Core.local else Jobs.ep(d)

    private fun ck(d: String, p: String) = "$d|$p"
    private fun ck(p: String) = ck(dev, p)

    private val devTick = object : Runnable {
        override fun run() { refreshDevices(); ui.postDelayed(this, 3000) }
    }

    private fun refreshDevices() {
        val l = ArrayList<Dev>()
        l.add(Dev("local", "This phone", true, "local"))
        try { Core.discOrNull()?.list()?.forEach { l.add(Dev(it.id, it.name, it.ok, "peer")) } } catch (_: Exception) {}
        try { Smb.peers().forEach { l.add(Dev(it.getString("id"), it.getString("name"), it.optBoolean("ok", true), "smb")) } } catch (_: Exception) {}
        val sig = l.joinToString(";") { "${it.id}|${it.name}|${it.ok}" } + "#" + dev
        devs = l
        if (sig == devSig) return
        devSig = sig
        buildDevBar()
    }

    private fun chip(label: String, on: Boolean, ok: Boolean, click: () -> Unit, long: (() -> Unit)?): TextView = TextView(this).apply {
        text = label; textSize = 13f; maxLines = 1; setPadding(dp(14), dp(7), dp(14), dp(7))
        setTextColor(if (on) 0xFFFFFFFF.toInt() else if (ok) cFg else cMut)
        background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(if (on) cAccent else cCard); setStroke(1, cDiv) }
        alpha = if (ok) 1f else 0.55f
        setOnClickListener { click() }
        if (long != null) setOnLongClickListener { long(); true }
    }

    private fun buildDevBar() {
        devRow.removeAllViews()
        fun add(v: View) = devRow.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) })
        for (d in devs) {
            val prefix = when (d.kind) { "smb" -> "🖥 "; "peer" -> "📱 "; else -> "" }
            add(chip(prefix + d.name, d.id == dev, d.ok, { switchDev(d.id) },
                if (d.kind == "smb") ({ confirmRemoveSmb(d) }) else null))
        }
        add(chip("＋", false, true, { addMenu(devRow.getChildAt(devRow.childCount - 1)) }, null))
    }

    private fun switchDev(id: String) {
        if (searching) exitSearch()
        if (id == dev) { if (cur != "/") navigate("/", true); return }
        states[ck(cur)] = rv.layoutManager?.onSaveInstanceState()
        lastPath[dev] = cur
        dev = id; free = null; devSig = ""
        refreshDevices()
        navigate(lastPath[id] ?: "/", false)
    }

    private fun addMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, "Add SMB share (Windows / NAS)…")
        m.menu.add(0, 2, 1, "Add LANShare device by IP…")
        m.menu.add(0, 3, 2, "Scan network again")
        m.setOnMenuItemClickListener {
            when (it.itemId) { 1 -> addSmbDialog(); 2 -> addIpDialog(); 3 -> { Core.rescan(); toast("Scanning the network…") } }
            true
        }
        m.show()
    }

    private fun field(box: LinearLayout, hint: String, pwd: Boolean = false): EditText {
        val et = EditText(this).apply {
            this.hint = hint; setSingleLine()
            inputType = if (pwd) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        box.addView(et)
        return et
    }

    private fun addSmbDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val host = field(box, "PC address (IP, optional :port)")
        val user = field(box, "Username (empty = guest)")
        val pass = field(box, "Password", true)
        val name = field(box, "Display name (optional)")
        AlertDialog.Builder(this).setTitle("Add SMB share").setView(box)
            .setPositiveButton("Connect") { _, _ ->
                val h = host.text.toString(); val u = user.text.toString(); val pw = pass.text.toString(); val n = name.text.toString()
                toast("Connecting…")
                io.execute {
                    val r = try { Result.success(Smb.add(h, u, pw, n)) } catch (e: Exception) { Result.failure(e) }
                    ui.post {
                        val id = r.getOrNull()
                        if (id != null) { refreshDevices(); switchDev(id) }
                        else AlertDialog.Builder(this).setTitle("Could not connect").setMessage(r.exceptionOrNull()?.let { errText(it) } ?: "unknown error").setPositiveButton("OK", null).show()
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun addIpDialog() {
        input("Device IP address", "", "Add") { ip ->
            toast("Looking for a LANShare device…")
            io.execute {
                val err = try { if (Core.disc.addIp(ip)) null else "no LANShare device found at that address" } catch (e: Exception) { errText(e) }
                ui.post { if (err != null) toast(err) else { toast("Device added"); refreshDevices() } }
            }
        }
    }

    private fun confirmRemoveSmb(d: Dev) {
        AlertDialog.Builder(this).setTitle("Remove “${d.name}”?").setMessage("The saved login is deleted from this phone. Files on the PC are not touched.")
            .setPositiveButton("Remove") { _, _ ->
                io.execute {
                    try { Smb.remove(d.id) } catch (_: Exception) {}
                    ui.post { if (dev == d.id) switchDev("local") else { devSig = ""; refreshDevices() } }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    // ------------------------------------------------------------------ navigation + loading

    private fun jn(a: String, b: String) = (if (a == "/") "" else a) + "/" + b
    private fun parent(p: String): String { val i = p.lastIndexOf('/'); return if (i <= 0) "/" else p.substring(0, i) }
    private fun pathOf(i: Item): String = i.path ?: jn(cur, i.name)

    private fun navigate(path: String, saveScroll: Boolean) {
        if (saveScroll) states[ck(cur)] = rv.layoutManager?.onSaveInstanceState()
        cur = path; sel.clear()
        val hit = cache.get(ck(path))
        loaded = hit != null
        if (hit != null) setList(hit) else { raw = emptyList(); items = emptyList(); ad.notifyDataSetChanged(); showEmpty("Loading…") }
        buildCrumbs(); updateChrome()
        load(path)
    }

    private fun load(path: String) {
        val g = ++gen
        val d = dev
        io.execute {
            val r: Result<List<Item>> = try { Result.success(if (d == "local") Core.local.ls(path, false) else ep(d).ls(path)) } catch (e: Exception) { Result.failure(e) }
            ui.post {
                if (g != gen || path != cur || d != dev) return@post
                srl.isRefreshing = false
                val list = r.getOrNull()
                if (list != null) applyList(path, list)
                else { raw = emptyList(); items = emptyList(); ad.notifyDataSetChanged(); showEmpty(r.exceptionOrNull()?.let { errText(it) } ?: "Cannot open this folder") }
                updateChrome()
            }
            // free space after the list is on screen (a peer / SMB share needs one more round-trip)
            val sp = try { if (d == "local") Core.local.space("/") else ep(d).space(path) } catch (_: Exception) { null }
            ui.post {
                if (d != dev || path != cur) return@post
                val f = sp?.let { humanSize(it.first) }
                if (f != free) { free = f; updateChrome() }
            }
        }
    }

    private fun pullRefresh() {
        if (searching) { srl.isRefreshing = false; return }
        cache.remove(ck(cur))
        if (cur == "/") Core.rescan()
        refreshDevices()
        load(cur)
        ui.postDelayed({ srl.isRefreshing = false }, 8000)   // never spin forever on a dead peer
    }

    private fun same(a: List<Item>, b: List<Item>): Boolean {
        if (a.size != b.size) return false
        val x = a.sortedBy { it.name }; val y = b.sortedBy { it.name }
        return x.indices.all { x[it].name == y[it].name && x[it].dir == y[it].dir && x[it].size == y[it].size && x[it].mtime == y[it].mtime }
    }

    private fun applyList(path: String, list: List<Item>) {
        val old = HashMap<String, Int?>()
        raw.forEach { if (it.dir) old[it.name] = it.n }
        val nl = list.map { if (it.dir && it.n == null) it.copy(n = old[it.name]) else it }
        cache.put(ck(path), nl)
        if (!(loaded && same(raw, nl))) setList(nl)
        loaded = true
        if (dev != "local") return   // counts / prefetch only for this phone's storage (cheap there, slow over the network)
        fetchCounts(path)
        val dirs = items.filter { it.dir && !it.name.startsWith(".") }.take(4)   // warm the next likely taps
        ui.postDelayed({ if (cur == path && dev == "local") dirs.forEach { prefetch(jn(path, it.name)) } }, 250)
    }

    private fun fetchCounts(path: String) {
        if (raw.none { it.dir }) return
        io.execute {
            val c = try { Core.local.counts(path) } catch (_: Exception) { emptyMap<String, Int>() }
            if (c.isEmpty()) return@execute
            ui.post {
                if (path != cur || dev != "local") return@post
                var changed = false
                fun upd(l: List<Item>) = l.map { if (it.dir && c[it.name] != null && it.n != c[it.name]) { changed = true; it.copy(n = c[it.name]) } else it }
                raw = upd(raw); items = if (searching) items else upd(items)
                cache.put(ck("local", path), raw)
                if (changed && !searching) { if (sortKey == "size") { items = visibleSorted(raw); ad.notifyDataSetChanged() } else ad.notifyItemRangeChanged(0, items.size) }
            }
        }
    }

    private fun prefetch(p: String) {
        val k = ck("local", p)
        if (cache.get(k) != null || !inflight.add(k)) return
        pre.execute {
            val l = try { Core.local.ls(p, false) } catch (_: Exception) { null }
            ui.post { inflight.remove(k); if (l != null && cache.get(k) == null) cache.put(k, l) }
        }
    }

    private fun setList(list: List<Item>) {
        raw = list
        if (!searching) items = visibleSorted(list)
        sel.retainAll(items.map { pathOf(it) }.toSet())
        ad.notifyDataSetChanged()
        if (items.isEmpty()) showEmpty(if (list.isEmpty()) "This folder is empty" else "No visible files (hidden files are off)") else empty.visibility = View.GONE
        if (!searching) states.remove(ck(cur))?.let { rv.layoutManager?.onRestoreInstanceState(it) }
    }

    private fun showEmpty(t: String) { empty.text = t; empty.visibility = View.VISIBLE }

    private fun natural(a: String, b: String): Int {
        var i = 0; var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]; val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i; while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j; while (ej < b.length && b[ej].isDigit()) ej++
                val na = a.substring(i, ei).trimStart('0'); val nb = b.substring(j, ej).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb); if (c != 0) return c
                i = ei; j = ej
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar()); if (c != 0) return c
                i++; j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    private fun visibleSorted(src: List<Item>): List<Item> {
        val l = if (showHidden) src else src.filter { !it.name.startsWith(".") }
        val key: Comparator<Item> = when (sortKey) {
            "date" -> compareBy<Item> { it.mtime }
            "size" -> compareBy<Item> { if (it.dir) (it.n ?: 0).toLong() else it.size }
            else -> Comparator<Item> { a, b -> natural(a.name, b.name) }
        }
        val dirsFirst = compareBy<Item> { !it.dir }
        return l.sortedWith(dirsFirst.then(if (asc) key else key.reversed()))
    }

    /** Re-draws whatever is on screen after the sort / hidden settings changed. */
    private fun relist() { if (searching) { items = visibleSorted(results); ad.notifyDataSetChanged(); if (items.isEmpty()) showEmpty("No matches") else empty.visibility = View.GONE; buildCrumbs() } else setList(raw) }

    /** After a file operation: the listing (or the search) is read again. */
    private fun refreshAfter() {
        cache.evictAll()
        if (searching) { if (query.isNotEmpty()) runSearch(query) } else load(cur)
    }

    // ------------------------------------------------------------------ search

    private fun enterSearch() {
        if (searching) return
        searching = true; query = ""; results = emptyList(); partial = false; sel.clear()
        items = emptyList(); ad.notifyDataSetChanged()
        showEmpty("Searches “${if (cur == "/") devName(dev) else cur.substringAfterLast('/')}” and everything below it")
        searchBox.setText(""); searchBox.hint = "Search in ${if (cur == "/") devName(dev) else cur.substringAfterLast('/')}"
        buildCrumbs(); updateChrome()
        searchBox.requestFocus()
        searchBox.post { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(searchBox, 0) }
    }

    private fun exitSearch() {
        if (!searching) return
        searching = false; sgen++; query = ""; results = emptyList(); partial = false; sel.clear()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(searchBox.windowToken, 0)
        items = visibleSorted(raw); ad.notifyDataSetChanged()
        if (items.isEmpty()) showEmpty(if (raw.isEmpty()) "This folder is empty" else "No visible files (hidden files are off)") else empty.visibility = View.GONE
        buildCrumbs(); updateChrome()
    }

    private fun runSearch(q: String) {
        if (q.isEmpty()) return
        query = q; sel.clear()
        val g = ++sgen; val d = dev; val base = cur
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(searchBox.windowToken, 0)
        items = emptyList(); ad.notifyDataSetChanged(); showEmpty("Searching…"); buildCrumbs(); updateChrome()
        searchPool.execute {
            val r: Result<SearchResult> = try { Result.success(ep(d).search(base, q)) } catch (e: Exception) { Result.failure(e) }
            ui.post {
                if (g != sgen || !searching) return@post
                val sr = r.getOrNull()
                if (sr == null) { results = emptyList(); partial = false; items = emptyList(); ad.notifyDataSetChanged(); showEmpty(r.exceptionOrNull()?.let { errText(it) } ?: "Search failed") }
                else { results = sr.items; partial = sr.partial; relist() }
                buildCrumbs(); updateChrome()
            }
        }
    }

    // ------------------------------------------------------------------ menus

    private fun mainMenu() {
        val m = PopupMenu(this, bD)
        m.menu.add(0, 1, 0, "New folder")
        m.menu.add(0, 2, 1, "Refresh")
        m.menu.add(0, 3, 2, if (showHidden) "Hide hidden files" else "Show hidden files")
        m.menu.add(0, 4, 3, "Full app (web UI)")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> newFolder()
                2 -> { if (searching) refreshAfter() else { cache.remove(ck(cur)); Core.rescan(); load(cur) } }
                3 -> { showHidden = !showHidden; prefs.edit().putBoolean("hidden", showHidden).apply(); relist(); updateChrome() }
                4 -> finish()
            }
            true
        }
        m.show()
    }

    private fun sortMenu() {
        val m = PopupMenu(this, bB)
        fun mark(k: String, t: String) = (if (sortKey == k) "✓ " else "    ") + t
        m.menu.add(0, 1, 0, mark("name", "Name")); m.menu.add(0, 2, 1, mark("date", "Date modified")); m.menu.add(0, 3, 2, mark("size", "Size"))
        m.menu.add(0, 4, 3, if (asc) "Order: ascending" else "Order: descending")
        m.setOnMenuItemClickListener {
            when (it.itemId) { 1 -> sortKey = "name"; 2 -> sortKey = "date"; 3 -> sortKey = "size"; 4 -> asc = !asc }
            prefs.edit().putString("sort", sortKey).putBoolean("asc", asc).apply()
            relist(); true
        }
        m.show()
    }

    private fun selectionMenu() {
        val m = PopupMenu(this, bD)
        val one = if (sel.size == 1) items.firstOrNull { pathOf(it) == sel.first() } else null
        m.menu.add(0, 1, 0, "Select all")
        m.menu.add(0, 2, 1, "Share")
        if (devs.size > 1) m.menu.add(0, 3, 2, "Send to device…")
        if (one != null) m.menu.add(0, 4, 3, "Rename")
        if (one != null && !one.dir) m.menu.add(0, 5, 4, "Open with…")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> { items.forEach { i -> sel.add(pathOf(i)) }; ad.notifyDataSetChanged(); updateChrome() }
                2 -> shareSelected()
                3 -> sendSelected()
                4 -> renameSelected()
                5 -> one?.let { i -> openWith(i, true) }
            }
            true
        }
        m.show()
    }

    private fun setGrid(on: Boolean, save: Boolean) {
        grid = on
        if (save) prefs.edit().putString("view", if (on) "grid" else "list").apply()
        rv.layoutManager = if (on) GridLayoutManager(this, (resources.displayMetrics.widthPixels / dp(112)).coerceAtLeast(3)) else LinearLayoutManager(this)
        rv.adapter = ad
        updateChrome()
    }

    // ------------------------------------------------------------------ file operations

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun selectedItems(): List<Item> = items.filter { pathOf(it) in sel }

    /** Quick single operations (new folder, rename): run on the current device, then reload. */
    private fun runOp(block: (Endpoint) -> Unit) {
        val d = dev
        io.execute {
            val err = try { block(ep(d)); null } catch (e: Exception) { errText(e) }
            ui.post {
                err?.let { toast(it) }
                sel.clear()
                if (d == dev) refreshAfter()
                updateChrome(); ad.notifyDataSetChanged()
            }
        }
    }

    private fun confirmDelete() {
        val d = dev
        val paths = selectedItems().map { pathOf(it) }
        if (paths.isEmpty()) return
        AlertDialog.Builder(this).setTitle("Delete ${paths.size} item${if (paths.size == 1) "" else "s"}?")
            .setMessage(if (d == "local") "This cannot be undone." else "This deletes them on ${devName(d)} and cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                sel.clear(); updateChrome(); ad.notifyDataSetChanged()
                io.execute {
                    val r = try { Result.success(Jobs.startDelete(d, paths)) } catch (e: Exception) { Result.failure(e) }
                    ui.post { r.getOrNull()?.let { startJob(it) } ?: toast(r.exceptionOrNull()?.let { errText(it) } ?: "Cannot delete") }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun input(title: String, initial: String, ok: String, done: (String) -> Unit) {
        val et = EditText(this).apply { setText(initial); setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT; setSelection(0, initial.length) }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et) }
        AlertDialog.Builder(this).setTitle(title).setView(box)
            .setPositiveButton(ok) { _, _ -> val t = et.text.toString().trim(); if (t.isNotEmpty()) done(t) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun newFolder() {
        if (searching) { toast("Leave the search first"); return }
        if (dev.startsWith("smb:") && cur == "/") { toast("Open a drive first"); return }
        val base = cur
        input("New folder", "", "Create") { n -> runOp { e -> e.mkdir(jn(base, n)) } }
    }

    private fun renameSelected() {
        val i = selectedItems().firstOrNull() ?: return
        val p = pathOf(i)
        input("Rename", i.name, "Rename") { n -> runOp { e -> e.rename(p, n) } }
    }

    // --- clipboard (shared with the web UI through Clip) + copy / move jobs

    private fun clipSelected(op: String) {
        val paths = selectedItems().map { vnorm(pathOf(it)) }
        if (paths.isEmpty()) return
        Clip.set(op, dev, paths)
        sel.clear(); ad.notifyDataSetChanged(); updateChrome()
        toast("${paths.size} item${if (paths.size == 1) "" else "s"} ${if (op == "cut") "cut" else "copied"} — open a folder (any device) and tap Paste here")
    }

    private fun paste() {
        if (Clip.isEmpty()) return
        if (searching) { toast("Leave the search first"); return }
        if (dev.startsWith("smb:") && cur == "/") { toast("Open a drive first"); return }
        val cut = Clip.op == "cut"; val srcDev = Clip.dev ?: return; val paths = Clip.paths
        val d = dev; val dir = cur
        io.execute {
            val r = try { Result.success(Jobs.start(srcDev, paths, d, dir, cut, if (cut) "Moving" else "Copying")) } catch (e: Exception) { Result.failure(e) }
            ui.post { r.getOrNull()?.let { startJob(it) } ?: toast(r.exceptionOrNull()?.let { errText(it) } ?: "Cannot paste") }
        }
    }

    private fun sendSelected() {
        val targets = devs.filter { it.id != dev }
        val paths = selectedItems().map { vnorm(pathOf(it)) }
        if (targets.isEmpty() || paths.isEmpty()) return
        val src = dev
        AlertDialog.Builder(this).setTitle("Send ${paths.size} item${if (paths.size == 1) "" else "s"} to…")
            .setItems(targets.map { it.name }.toTypedArray()) { _, w ->
                val t = targets[w].id
                sel.clear(); ad.notifyDataSetChanged(); updateChrome()
                io.execute {
                    val r = try { Result.success(Jobs.start(src, paths, t, INBOX, false, "Sending")) } catch (e: Exception) { Result.failure(e) }
                    ui.post { r.getOrNull()?.let { startJob(it) } ?: toast(r.exceptionOrNull()?.let { errText(it) } ?: "Cannot send") }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private val jobTick = object : Runnable {
        override fun run() { pollJobs(); if (jobIds.isNotEmpty()) ui.postDelayed(this, 400) }
    }

    private fun startJob(id: String) {
        jobIds.add(id)
        ui.removeCallbacks(jobTick); ui.post(jobTick)
    }

    private fun pollJobs() {
        var finished = false
        var shown: Job? = null
        for (id in jobIds.toList()) {
            val j = Jobs.all[id]
            if (j == null) { jobIds.remove(id); finished = true; continue }
            if (j.state == "run") { if (shown == null) shown = j; continue }
            jobIds.remove(id); finished = true
            when (j.state) {
                "done" -> toast(j.note ?: j.label)
                "cancel" -> toast("Cancelled")
                else -> AlertDialog.Builder(this).setTitle(j.label.substringBefore(' ').ifEmpty { "Problem" }).setMessage(j.error ?: "failed").setPositiveButton("OK", null).show()
            }
        }
        if (shown == null) jobBar.visibility = View.GONE
        else {
            val j = shown
            jobBar.visibility = View.VISIBLE
            val more = jobIds.size - 1
            jobText.text = j.label + (if (more > 0) "  (+$more more)" else "")
            jobProg.progress = if (j.total > 0) (j.done * 1000 / j.total).toInt().coerceIn(0, 1000) else 0
            val o = j.toJson()
            val sp = o.optLong("speed", 0); val eta = o.optLong("eta", 0)
            jobStat.text = (if (j.bytes) "${humanSize(j.done)} / ${humanSize(j.total)}" else "${j.done} / ${j.total}") +
                (if (sp > 0) " · ${humanSize(sp)}/s" else "") + (if (eta > 0) " · ${eta2(eta)} left" else "")
        }
        if (finished) { refreshAfter(); updateChrome() }
    }

    private fun eta2(s: Long): String = if (s < 90) "${s}s" else if (s < 5400) "${s / 60} min" else String.format("%.1f h", s / 3600.0)

    // --- files that other apps must read: fetched into cache/open first when they are not on this phone

    private fun openDir(): File = File(Core.cacheDir.parentFile ?: Core.cacheDir, "open").also { it.mkdirs() }

    private fun fetchAll(chosen: List<Item>, title: String, done: (List<File>) -> Unit) {
        val d = dev
        val base = openDir()
        try { base.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3_600_000 }?.forEach { it.deleteRecursively() } } catch (_: Exception) {}
        val work = File(base, "f" + System.nanoTime()).also { it.mkdirs() }
        val cancel = AtomicBoolean(false)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progressTintList = ColorStateList.valueOf(cAccent) }
        val msg = TextView(this).apply { textSize = 13f; setTextColor(cMut) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), 0); addView(bar); addView(msg) }
        val dlg = AlertDialog.Builder(this).setTitle(title).setView(box).setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> cancel.set(true) }.show()
        val total = maxOf(chosen.sumOf { it.size }, 1L)
        io.execute {
            val out = ArrayList<File>()
            var err: String? = null
            try {
                val e = ep(d)
                var got = 0L
                var last = 0L
                for ((n, i) in chosen.withIndex()) {
                    val f = File(File(work, "$n").also { it.mkdirs() }, i.name)
                    e.open(vnorm(pathOf(i))).use { src ->
                        f.outputStream().use { os ->
                            val buf = ByteArray(128 * 1024)
                            while (true) {
                                if (cancel.get()) throw Cancelled()
                                val r = src.read(buf, 0, buf.size)
                                if (r < 0) break
                                os.write(buf, 0, r)
                                got += r
                                val now = System.currentTimeMillis()
                                if (now - last > 200) {
                                    last = now
                                    val g = got
                                    ui.post { bar.progress = (g * 1000 / total).toInt().coerceIn(0, 1000); msg.text = "${humanSize(g)} / ${humanSize(total)}" }
                                }
                            }
                        }
                    }
                    out.add(f)
                }
            } catch (x: Cancelled) { err = ""
            } catch (x: Exception) { err = errText(x) }
            ui.post {
                try { dlg.dismiss() } catch (_: Exception) {}
                val msgErr = err
                if (msgErr == null) done(out)
                else { work.deleteRecursively(); if (msgErr.isNotEmpty()) toast(msgErr) }
            }
        }
    }

    private fun uriFor(f: File): Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)

    private fun shareSelected() {
        val chosen = selectedItems()
        if (chosen.any { it.dir }) { toast("Folders can't be shared - select files only"); return }
        if (chosen.isEmpty()) return
        if (dev == "local") try { shareFiles(chosen.map { Core.local.real(pathOf(it)) }) } catch (e: Exception) { toast("Cannot share: ${errText(e)}") }
        else fetchAll(chosen, "Getting ${chosen.size} file${if (chosen.size == 1) "" else "s"}…") { files -> shareFiles(files) }
    }

    private fun shareFiles(files: List<File>) {
        try {
            val uris = ArrayList<Uri>(files.map { uriFor(it) })
            val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
                    else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            i.type = "*/*"; i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) { toast("Cannot share: ${errText(e)}") }
    }

    // ------------------------------------------------------------------ opening files

    private fun kindOf(i: Item): String = if (i.dir) "dir" else EXT[i.name.substringAfterLast('.', "").lowercase()] ?: "file"

    /** Viewers read through this phone's own server (Range requests, works for peers and SMB too). */
    private fun url(p: String) = Core.url + "/api/dl?dev=" + URLEncoder.encode(dev, "UTF-8") + "&path=" + URLEncoder.encode(p, "UTF-8").replace("+", "%20")

    private fun imgOk(i: Item) = kindOf(i) == "img" && !Regex("(?i).*\\.(svg|gif)$").matches(i.name) &&
        (Build.VERSION.SDK_INT >= 28 || !Regex("(?i).*\\.(heic|heif)$").matches(i.name))

    private fun openItem(i: Item) {
        when {
            i.dir -> { if (searching) exitSearch(); navigate(pathOf(i), true) }
            kindOf(i) == "vid" -> playVideo(i)
            imgOk(i) -> viewImages(i)
            kindOf(i) == "pdf" -> viewPdf(i)
            else -> openWith(i, false)
        }
    }

    private fun playVideo(i: Item) {
        val vids = items.filter { !it.dir && kindOf(it) == "vid" }
        val subs = items.filter { !it.dir && SUB.matches(it.name) }
        fun base(n: String) = n.substringBeforeLast('.').lowercase()
        val arr = JSONArray()
        vids.forEach { v ->
            val b = base(v.name); val vp = pathOf(v)
            val sa = JSONArray()
            subs.filter { parent(pathOf(it)) == parent(vp) && base(it.name).let { c -> c == b || c.startsWith("$b.") } }
                .forEach { sa.put(JSONObject().put("name", it.name).put("url", url(pathOf(it)))) }
            arr.put(JSONObject().put("name", v.name).put("url", url(vp)).put("key", "$dev|$vp|${v.size}").put("subs", sa))
        }
        val at = vids.indexOfFirst { pathOf(it) == pathOf(i) }.coerceAtLeast(0)
        PlayerActivity.pending = JSONObject().put("start", at).put("items", arr).toString()
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    private fun viewImages(i: Item) {
        val imgs = items.filter { imgOk(it) }
        val arr = JSONArray()
        imgs.forEach { arr.put(JSONObject().put("name", it.name).put("url", url(pathOf(it))).put("size", it.size)) }
        val at = imgs.indexOfFirst { pathOf(it) == pathOf(i) }.coerceAtLeast(0)
        ImageViewerActivity.pending = JSONObject().put("start", at).put("items", arr).toString()
        startActivity(Intent(this, ImageViewerActivity::class.java))
    }

    private fun viewPdf(i: Item) {
        val p = pathOf(i)
        PdfViewerActivity.pending = JSONObject().put("name", i.name).put("url", url(p)).put("size", i.size)
            .put("key", "$dev|$p|${i.size}").toString()
        startActivity(Intent(this, PdfViewerActivity::class.java))
    }

    /** This phone: opened in place through FileProvider (root-path), instantly. Peers / SMB: fetched into cache/open first (with progress + Cancel). */
    private fun openWith(i: Item, pick: Boolean) {
        if (dev == "local") {
            val f = try { Core.local.real(pathOf(i)) } catch (e: Exception) { toast("Cannot open: ${errText(e)}"); return }
            openFile(f, pick)
        } else fetchAll(listOf(i), "Getting ${i.name}…") { files -> openFile(files[0], pick) }
    }

    private fun openFile(f: File, pick: Boolean) {
        val ext = f.extension.lowercase()
        val mime = if (ext in TEXT_EXT || f.name.startsWith(".")) "text/plain"
                   else MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        val uri = try { uriFor(f) } catch (e: Exception) { toast("Cannot open: ${errText(e)}"); return }
        fun go(m: String) {
            val v = Intent(Intent.ACTION_VIEW).setDataAndType(uri, m).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(if (pick) Intent.createChooser(v, "Open with").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) else v)
        }
        try { go(mime) }
        catch (e: ActivityNotFoundException) {
            try { if (mime != "*/*") go("*/*") else throw e } catch (e2: Exception) { toast("No app can open .$ext") }
        } catch (e: Exception) { toast("Cannot open: ${errText(e)}") }
    }

    // ------------------------------------------------------------------ list adapter

    private fun humanSize(n: Long): String {
        val u = arrayOf("B", "KB", "MB", "GB", "TB"); var v = n.toDouble(); var i = 0
        while (v >= 1024 && i < 4) { v /= 1024; i++ }
        return if (i == 0) "$n B" else String.format("%.1f %s", v, u[i])
    }

    private fun glyph(k: String) = when (k) { "dir" -> "📁"; "img" -> "🖼"; "vid" -> "🎬"; "aud" -> "🎵"
        "pdf" -> "📕"; "zip" -> "🗜"; "apk" -> "📦"; "doc" -> "📝"; else -> "📄" }

    private fun tint(k: String): Int = when (k) { "dir" -> 0xFFE2AC5F; "img" -> 0xFF4CAF7D; "vid" -> 0xFFD9534F; "aud" -> 0xFF8E6BD1
        "pdf" -> 0xFFD9534F; "zip" -> 0xFF8D6E63; "apk" -> 0xFF3DDC84; "doc" -> 0xFF4A90D9; else -> 0xFF78909C }.toInt()

    private fun ripple(): Drawable? {
        val tv = TypedValue(); theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        return if (tv.resourceId != 0) getDrawable(tv.resourceId) else null
    }

    private fun rounded(r: Int) = object : ViewOutlineProvider() {
        override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(r).toFloat()) }
    }

    private class Square(c: Context) : FrameLayout(c) {
        override fun onMeasure(w: Int, h: Int) { super.onMeasure(w, w) }
    }

    private inner class VH(val root: FrameLayout, val icon: TextView, val img: ImageView, val name: TextView, val sub: TextView?,
                           val check: TextView, val gridMode: Boolean) : RecyclerView.ViewHolder(root) {
        var key: String? = null
        var job: Future<*>? = null
    }

    private fun checkBadge() = TextView(this).apply {
        text = "✓"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt()); visibility = View.GONE
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(cAccent) }
    }

    private fun makeList(): VH {
        val root = FrameLayout(this).apply { layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); foreground = ripple() }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10)) }
        val lead = FrameLayout(this)
        val icon = TextView(this).apply { gravity = Gravity.CENTER; textSize = 20f }
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE; outlineProvider = rounded(8); clipToOutline = true }
        val check = checkBadge()
        lead.addView(icon, FrameLayout.LayoutParams(dp(44), dp(44)))
        lead.addView(img, FrameLayout.LayoutParams(dp(44), dp(44)))
        lead.addView(check, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.BOTTOM or Gravity.END))
        val name = TextView(this).apply { textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(cFg); maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE }
        val sub = TextView(this).apply { textSize = 12.5f; setTextColor(cMut); maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0); addView(name); addView(sub) }
        row.addView(lead, LinearLayout.LayoutParams(dp(44), dp(44)))
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)
        root.addView(View(this).apply { setBackgroundColor(cDiv) }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1, Gravity.BOTTOM).apply { leftMargin = dp(72) })
        return VH(root, icon, img, name, sub, check, false)
    }

    private fun makeGrid(): VH {
        val root = FrameLayout(this).apply { layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); foreground = ripple() }
        val colv = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(6)) }
        val sq = Square(this).apply { outlineProvider = rounded(10); clipToOutline = true }
        val icon = TextView(this).apply { gravity = Gravity.CENTER; textSize = 34f }
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE }
        val check = checkBadge()
        sq.addView(icon, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        sq.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        sq.addView(check, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(6), 0) })
        val name = TextView(this).apply { textSize = 12f; setTextColor(cFg); maxLines = 2; ellipsize = TextUtils.TruncateAt.MIDDLE; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(2), dp(4), dp(2), 0) }
        colv.addView(sq, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        colv.addView(name)
        root.addView(colv)
        return VH(root, icon, img, name, null, check, true)
    }

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = items.size
        override fun getItemViewType(position: Int) = if (grid) 1 else 0
        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val h = if (t == 1) makeGrid() else makeList()
            h.root.setOnClickListener {
                val pos = h.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) { val i = items[pos]; if (sel.isNotEmpty()) toggle(i, pos) else openItem(i) }
            }
            h.root.setOnLongClickListener {
                val pos = h.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && sel.isEmpty()) { h.root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); toggle(items[pos], pos) }
                true
            }
            h.root.setOnTouchListener { _, e ->   // head start: the folder listing loads while the finger is still lifting (this phone only)
                if (e.actionMasked == MotionEvent.ACTION_DOWN && sel.isEmpty() && dev == "local" && !searching) {
                    val pos = h.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION && items[pos].dir) prefetch(jn(cur, items[pos].name))
                }
                false
            }
            return h
        }
        override fun onBindViewHolder(h: VH, position: Int) = bind(h, items[position])
        override fun onViewRecycled(h: VH) { h.job?.cancel(false); h.job = null; h.key = null }
    }

    private fun toggle(i: Item, pos: Int) {
        val p = pathOf(i)
        if (!sel.add(p)) sel.remove(p)
        ad.notifyItemChanged(pos); updateChrome()
    }

    private fun bind(h: VH, i: Item) {
        val k = kindOf(i); val p = pathOf(i); val s = sel.contains(p)
        h.name.text = i.name
        val where = if (searching) parent(p).let { if (it == "/") devName(dev) else it } else null
        h.sub?.text = if (where != null) where + " · " + (if (i.dir) "Folder" else humanSize(i.size))
            else (if (i.dir) (i.n?.let { "$it item${if (it == 1) "" else "s"}" } ?: "Folder") else humanSize(i.size)) +
                (if (i.mtime > 0) " · " + df.format(Date(i.mtime * 1000)) else "")
        h.icon.text = glyph(k)
        h.icon.background = GradientDrawable().apply { cornerRadius = dp(if (h.gridMode) 10 else 8).toFloat(); setColor((tint(k) and 0x00FFFFFF) or 0x33000000) }
        h.root.setBackgroundColor(if (s) cSel else 0)
        h.check.visibility = if (s) View.VISIBLE else View.GONE
        h.job?.cancel(false); h.job = null
        val key = "$dev|$p|${i.size}|${i.mtime}"
        h.key = key
        h.img.visibility = View.GONE
        if (dev != "local") return   // thumbnails only for this phone's own files (a peer / SMB thumbnail would mean downloading the file)
        if (k != "img" && k != "vid" || i.dir) return
        if (k == "img" && (i.size > 30_000_000L || Regex("(?i).*\\.(svg|heic|heif)$").matches(i.name))) return
        thumbs.get(key)?.let { h.img.setImageBitmap(it); h.img.visibility = View.VISIBLE; return }
        if (synchronized(failed) { failed.contains(key) }) return
        h.job = thumbPool.submit(Runnable {
            val bm: Bitmap? = try {
                val data = if (k == "vid") VideoThumbs.make(Core.local.open(p)).first else Thumbs.make(Core.local.real(p))
                BitmapFactory.decodeByteArray(data, 0, data.size)
            } catch (_: Throwable) { null }
            if (bm != null) {
                thumbs.put(key, bm)
                ui.post { if (h.key == key) { h.img.setImageBitmap(bm); h.img.visibility = View.VISIBLE } }
            } else synchronized(failed) { failed.add(key) }
        })
    }
}
