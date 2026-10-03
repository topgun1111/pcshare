package com.lanshare.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import android.webkit.MimeTypeMap
import android.widget.*
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lanshare.app.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * TRIAL: fully native file browser for THIS phone's storage (no WebView, no HTTP round-trip for listings).
 * Plain Views + RecyclerView, programmatic layout (no XML). Opens videos / pictures / PDFs in the existing
 * native viewers. Peers, SMB, archives, copy/move/paste and transfers stay in the web UI (menu > "Full app").
 */
class BrowserActivity : Activity() {

    companion object {
        // static: survive rotation / re-opening, so a second visit to a folder is instant
        private val cache = LruCache<String, List<Item>>(80)
        private val thumbs = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }
        private val failed = HashSet<String>()
        private fun pool(n: Int) = Executors.newFixedThreadPool(n) { r -> Thread(r, "browser").also { it.isDaemon = true } }
        private val io = pool(2)
        private val pre = pool(1)
        private val thumbPool = pool(3)

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

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var rv: RecyclerView
    private lateinit var empty: TextView
    private lateinit var crumbs: LinearLayout
    private lateinit var crumbScroll: HorizontalScrollView
    private lateinit var tTitle: TextView
    private lateinit var tSub: TextView
    private lateinit var bLeft: TextView
    private lateinit var bA: TextView
    private lateinit var bB: TextView
    private lateinit var bC: TextView
    private val ad = Adapter()

    private var cur = "/"
    private var raw: List<Item> = emptyList()
    private var items: List<Item> = emptyList()
    private val sel = LinkedHashSet<String>()
    private var gen = 0
    private var loaded = false
    private var started = false
    private var free: String? = null
    private val states = HashMap<String, Parcelable?>()
    private val inflight = HashSet<String>()
    private val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

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

        val p0 = b?.getString("cur") ?: intent.getStringExtra("path") ?: "/"
        cur = if (p0.startsWith("/") && !p0.contains('!')) p0 else "/"

        buildUi()
        setGrid(grid, false)
        started = true
        navigate(cur, false)
    }

    override fun onSaveInstanceState(o: Bundle) { super.onSaveInstanceState(o); o.putString("cur", cur) }

    private var firstResume = true
    override fun onResume() {   // silent refresh after viewers / other apps (not on the very first resume: onCreate just loaded)
        super.onResume()
        if (firstResume) { firstResume = false; return }
        if (started) load(cur)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            sel.isNotEmpty() -> { sel.clear(); ad.notifyDataSetChanged(); updateChrome() }
            cur != "/" -> navigate(parent(cur), true)
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
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(tTitle); addView(tSub) }
        bA = btn(20f) {}; bB = btn(20f) {}; bC = btn(22f) {}
        top.addView(bLeft, LinearLayout.LayoutParams(dp(52), dp(56)))
        top.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        for (v in listOf(bA, bB, bC)) top.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)))
        root.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        crumbs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(8), 0) }
        crumbScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; setBackgroundColor(cCard); addView(crumbs) }
        root.addView(crumbScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
        root.addView(View(this).apply { setBackgroundColor(cDiv) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))

        val body = FrameLayout(this)
        rv = RecyclerView(this).apply { itemAnimator = null; setHasFixedSize(true); setItemViewCacheSize(24); adapter = ad }
        empty = TextView(this).apply { textSize = 15f; setTextColor(cMut); gravity = Gravity.CENTER; setPadding(dp(32), 0, dp(32), 0); visibility = View.GONE }
        body.addView(rv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        body.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun btn(size: Float, onClick: () -> Unit): TextView = TextView(this).apply {
        textSize = size; gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt()); setPadding(dp(12), 0, dp(12), 0)
        val tv = TypedValue(); theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        if (tv.resourceId != 0) background = getDrawable(tv.resourceId)
        setOnClickListener { onClick() }
    }

    private fun setBtn(b: TextView, text: String, size: Float, desc: String, onClick: () -> Unit) {
        b.text = text; b.textSize = size; b.contentDescription = desc; b.setOnClickListener { onClick() }
    }

    private fun updateChrome() {
        val sm = sel.isNotEmpty()
        bLeft.text = if (sm) "\u2715" else "\u2190"
        tTitle.text = if (sm) "${sel.size} selected" else if (cur == "/") "Main storage" else cur.substringAfterLast('/')
        val sub = if (sm) "" else "${items.size} item${if (items.size == 1) "" else "s"}" + (free?.let { " \u00b7 $it free" } ?: "")
        tSub.text = sub; tSub.visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE
        if (sm) {
            setBtn(bA, "Share", 14f, "Share") { shareSelected() }
            setBtn(bB, "Delete", 14f, "Delete") { confirmDelete() }
            setBtn(bC, "\u22ee", 22f, "More") { selectionMenu() }
        } else {
            setBtn(bA, if (grid) "\u2630" else "\u25a6", 20f, "Switch list / grid") { setGrid(!grid, true) }
            setBtn(bB, "\u21c5", 20f, "Sort") { sortMenu() }
            setBtn(bC, "\u22ee", 22f, "Menu") { mainMenu() }
        }
    }

    private fun buildCrumbs() {
        crumbs.removeAllViews()
        fun crumb(label: String, path: String, last: Boolean) {
            if (crumbs.childCount > 0) crumbs.addView(TextView(this).apply { text = "\u203a"; setTextColor(cMut); textSize = 16f })
            crumbs.addView(TextView(this).apply {
                text = label; textSize = 14f; setPadding(dp(8), dp(8), dp(8), dp(8))
                setTextColor(if (last) cFg else cMut); if (last) setTypeface(null, Typeface.BOLD)
                setOnClickListener { if (path != cur) navigate(path, true) }
            })
        }
        crumb("Storage", "/", cur == "/")
        var acc = ""
        val segs = cur.split('/').filter { it.isNotEmpty() }
        segs.forEachIndexed { i, s -> acc += "/$s"; crumb(s, acc, i == segs.size - 1) }
        crumbScroll.post { crumbScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    // ------------------------------------------------------------------ navigation + loading

    private fun jn(a: String, b: String) = (if (a == "/") "" else a) + "/" + b
    private fun parent(p: String): String { val i = p.lastIndexOf('/'); return if (i <= 0) "/" else p.substring(0, i) }

    private fun navigate(path: String, saveScroll: Boolean) {
        if (saveScroll) states[cur] = rv.layoutManager?.onSaveInstanceState()
        cur = path; sel.clear()
        val hit = cache.get(path)
        loaded = hit != null
        if (hit != null) setList(hit) else { raw = emptyList(); items = emptyList(); ad.notifyDataSetChanged(); showEmpty("Loading\u2026") }
        buildCrumbs(); updateChrome()
        load(path)
    }

    private fun load(path: String) {
        val g = ++gen
        io.execute {
            val r: Result<List<Item>> = try { Result.success(Core.local.ls(path, false)) } catch (e: Exception) { Result.failure(e) }
            val sp = try { Core.local.space("/") } catch (_: Exception) { null }
            ui.post {
                if (g != gen || path != cur) return@post
                sp?.let { free = humanSize(it.first) }
                val list = r.getOrNull()
                if (list != null) applyList(path, list)
                else { raw = emptyList(); items = emptyList(); ad.notifyDataSetChanged(); showEmpty(r.exceptionOrNull()?.message ?: "Cannot open this folder") }
                updateChrome()
            }
        }
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
        cache.put(path, nl)
        if (!(loaded && same(raw, nl))) setList(nl)
        loaded = true
        fetchCounts(path)
        val dirs = items.filter { it.dir && !it.name.startsWith(".") }.take(4)   // warm the next likely taps
        ui.postDelayed({ if (cur == path) dirs.forEach { prefetch(jn(path, it.name)) } }, 250)
    }

    private fun fetchCounts(path: String) {
        if (raw.none { it.dir }) return
        io.execute {
            val c = try { Core.local.counts(path) } catch (_: Exception) { emptyMap<String, Int>() }
            if (c.isEmpty()) return@execute
            ui.post {
                if (path != cur) return@post
                var changed = false
                fun upd(l: List<Item>) = l.map { if (it.dir && c[it.name] != null && it.n != c[it.name]) { changed = true; it.copy(n = c[it.name]) } else it }
                raw = upd(raw); items = upd(items)
                cache.put(path, raw)
                if (changed) { if (sortKey == "size") { items = visibleSorted(raw); ad.notifyDataSetChanged() } else ad.notifyItemRangeChanged(0, items.size) }
            }
        }
    }

    private fun prefetch(p: String) {
        if (cache.get(p) != null || !inflight.add(p)) return
        pre.execute {
            val l = try { Core.local.ls(p, false) } catch (_: Exception) { null }
            ui.post { inflight.remove(p); if (l != null && cache.get(p) == null) cache.put(p, l) }
        }
    }

    private fun setList(list: List<Item>) {
        raw = list; items = visibleSorted(list)
        sel.retainAll(items.map { it.name }.toSet())
        ad.notifyDataSetChanged()
        if (items.isEmpty()) showEmpty(if (list.isEmpty()) "This folder is empty" else "No visible files (hidden files are off)") else empty.visibility = View.GONE
        states.remove(cur)?.let { rv.layoutManager?.onRestoreInstanceState(it) }
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

    // ------------------------------------------------------------------ menus

    private fun mainMenu() {
        val m = PopupMenu(this, bC)
        m.menu.add(0, 1, 0, "New folder")
        m.menu.add(0, 2, 1, "Refresh")
        m.menu.add(0, 3, 2, if (showHidden) "Hide hidden files" else "Show hidden files")
        m.menu.add(0, 4, 3, "Full app (web UI)")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> newFolder()
                2 -> { cache.remove(cur); load(cur) }
                3 -> { showHidden = !showHidden; prefs.edit().putBoolean("hidden", showHidden).apply(); setList(raw); updateChrome() }
                4 -> finish()
            }
            true
        }
        m.show()
    }

    private fun sortMenu() {
        val m = PopupMenu(this, bB)
        fun mark(k: String, t: String) = (if (sortKey == k) "\u2713 " else "    ") + t
        m.menu.add(0, 1, 0, mark("name", "Name")); m.menu.add(0, 2, 1, mark("date", "Date modified")); m.menu.add(0, 3, 2, mark("size", "Size"))
        m.menu.add(0, 4, 3, if (asc) "Order: ascending" else "Order: descending")
        m.setOnMenuItemClickListener {
            when (it.itemId) { 1 -> sortKey = "name"; 2 -> sortKey = "date"; 3 -> sortKey = "size"; 4 -> asc = !asc }
            prefs.edit().putString("sort", sortKey).putBoolean("asc", asc).apply()
            setList(raw); true
        }
        m.show()
    }

    private fun selectionMenu() {
        val m = PopupMenu(this, bC)
        m.menu.add(0, 1, 0, "Select all")
        if (sel.size == 1) m.menu.add(0, 2, 1, "Rename")
        if (sel.size == 1 && items.firstOrNull { it.name == sel.first() }?.dir == false) m.menu.add(0, 3, 2, "Open with\u2026")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> { items.forEach { i -> sel.add(i.name) }; ad.notifyDataSetChanged(); updateChrome() }
                2 -> renameSelected()
                3 -> items.firstOrNull { i -> i.name == sel.first() }?.let { i -> openWith(i, true) }
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

    private fun runOp(base: String, block: () -> Unit) {
        io.execute {
            val err = try { block(); null } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
            ui.post {
                err?.let { toast(it) }
                sel.clear(); cache.remove(base)
                if (base == cur) load(base)
                updateChrome(); ad.notifyDataSetChanged()
            }
        }
    }

    private fun confirmDelete() {
        val base = cur; val names = sel.toList()
        AlertDialog.Builder(this).setTitle("Delete ${names.size} item${if (names.size == 1) "" else "s"}?")
            .setMessage("This cannot be undone.")
            .setPositiveButton("Delete") { _, _ -> runOp(base) { names.forEach { Core.local.remove(jn(base, it)) } } }
            .setNegativeButton("Cancel", null).show()
    }

    private fun input(title: String, initial: String, ok: String, done: (String) -> Unit) {
        val et = EditText(this).apply { setText(initial); setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT; setSelection(0, initial.length) }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et) }
        AlertDialog.Builder(this).setTitle(title).setView(box)
            .setPositiveButton(ok) { _, _ -> val t = et.text.toString().trim(); if (t.isNotEmpty()) done(t) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun newFolder() { val base = cur; input("New folder", "", "Create") { n -> runOp(base) { Core.local.mkdir(jn(base, n)) } } }

    private fun renameSelected() {
        val base = cur; val old = sel.firstOrNull() ?: return
        input("Rename", old, "Rename") { n -> runOp(base) { Core.local.rename(jn(base, old), n) } }
    }

    private fun uriFor(f: File): Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)

    private fun shareSelected() {
        val chosen = items.filter { it.name in sel }
        if (chosen.any { it.dir }) { toast("Folders can't be shared - select files only"); return }
        try {
            val uris = ArrayList<Uri>(chosen.map { uriFor(Core.local.real(jn(cur, it.name))) })
            val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
                    else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            i.type = "*/*"; i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) { toast("Cannot share: ${e.message}") }
    }

    // ------------------------------------------------------------------ opening files

    private fun kindOf(i: Item): String = if (i.dir) "dir" else EXT[i.name.substringAfterLast('.', "").lowercase()] ?: "file"

    private fun url(p: String) = Core.url + "/api/dl?dev=local&path=" + URLEncoder.encode(p, "UTF-8").replace("+", "%20")

    private fun imgOk(i: Item) = kindOf(i) == "img" && !Regex("(?i).*\\.(svg|gif)$").matches(i.name) &&
        (Build.VERSION.SDK_INT >= 28 || !Regex("(?i).*\\.(heic|heif)$").matches(i.name))

    private fun openItem(i: Item) {
        when {
            i.dir -> navigate(jn(cur, i.name), true)
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
            val b = base(v.name)
            val sa = JSONArray()
            subs.filter { val c = base(it.name); c == b || c.startsWith("$b.") }
                .forEach { sa.put(JSONObject().put("name", it.name).put("url", url(jn(cur, it.name)))) }
            arr.put(JSONObject().put("name", v.name).put("url", url(jn(cur, v.name)))
                .put("key", "local|$cur/${v.name}|${v.size}").put("subs", sa))
        }
        val at = vids.indexOfFirst { it.name == i.name }.coerceAtLeast(0)
        PlayerActivity.pending = JSONObject().put("start", at).put("items", arr).toString()
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    private fun viewImages(i: Item) {
        val imgs = items.filter { imgOk(it) }
        val arr = JSONArray()
        imgs.forEach { arr.put(JSONObject().put("name", it.name).put("url", url(jn(cur, it.name))).put("size", it.size)) }
        val at = imgs.indexOfFirst { it.name == i.name }.coerceAtLeast(0)
        ImageViewerActivity.pending = JSONObject().put("start", at).put("items", arr).toString()
        startActivity(Intent(this, ImageViewerActivity::class.java))
    }

    private fun viewPdf(i: Item) {
        PdfViewerActivity.pending = JSONObject().put("name", i.name).put("url", url(jn(cur, i.name))).put("size", i.size)
            .put("key", "local|$cur/${i.name}|${i.size}").toString()
        startActivity(Intent(this, PdfViewerActivity::class.java))
    }

    /** Opened in place through FileProvider (root-path): no copy to cache, so it starts instantly. */
    private fun openWith(i: Item, pick: Boolean) {
        val f = try { Core.local.real(jn(cur, i.name)) } catch (e: Exception) { toast("Cannot open: ${e.message}"); return }
        val ext = f.extension.lowercase()
        val mime = if (ext in TEXT_EXT || f.name.startsWith(".")) "text/plain"
                   else MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        val uri = try { uriFor(f) } catch (e: Exception) { toast("Cannot open: ${e.message}"); return }
        fun go(m: String) {
            val v = Intent(Intent.ACTION_VIEW).setDataAndType(uri, m).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(if (pick) Intent.createChooser(v, "Open with").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) else v)
        }
        try { go(mime) }
        catch (e: ActivityNotFoundException) {
            try { if (mime != "*/*") go("*/*") else throw e } catch (e2: Exception) { toast("No app can open .$ext") }
        } catch (e: Exception) { toast("Cannot open: ${e.message}") }
    }

    // ------------------------------------------------------------------ list adapter

    private fun humanSize(n: Long): String {
        val u = arrayOf("B", "KB", "MB", "GB", "TB"); var v = n.toDouble(); var i = 0
        while (v >= 1024 && i < 4) { v /= 1024; i++ }
        return if (i == 0) "$n B" else String.format("%.1f %s", v, u[i])
    }

    private fun glyph(k: String) = when (k) { "dir" -> "\uD83D\uDCC1"; "img" -> "\uD83D\uDDBC"; "vid" -> "\uD83C\uDFAC"; "aud" -> "\uD83C\uDFB5"
        "pdf" -> "\uD83D\uDCD5"; "zip" -> "\uD83D\uDDDC"; "apk" -> "\uD83D\uDCE6"; "doc" -> "\uD83D\uDCDD"; else -> "\uD83D\uDCC4" }

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
        text = "\u2713"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt()); visibility = View.GONE
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
        val sub = TextView(this).apply { textSize = 12.5f; setTextColor(cMut); maxLines = 1 }
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
            h.root.setOnTouchListener { _, e ->   // head start: the folder listing loads while the finger is still lifting
                if (e.actionMasked == MotionEvent.ACTION_DOWN && sel.isEmpty()) {
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
        if (!sel.add(i.name)) sel.remove(i.name)
        ad.notifyItemChanged(pos); updateChrome()
    }

    private fun bind(h: VH, i: Item) {
        val k = kindOf(i); val p = jn(cur, i.name); val s = sel.contains(i.name)
        h.name.text = i.name
        h.sub?.text = (if (i.dir) (i.n?.let { "$it item${if (it == 1) "" else "s"}" } ?: "Folder") else humanSize(i.size)) +
            (if (i.mtime > 0) " \u00b7 " + df.format(Date(i.mtime * 1000)) else "")
        h.icon.text = glyph(k)
        h.icon.background = GradientDrawable().apply { cornerRadius = dp(if (h.gridMode) 10 else 8).toFloat(); setColor((tint(k) and 0x00FFFFFF) or 0x33000000) }
        h.root.setBackgroundColor(if (s) cSel else 0)
        h.check.visibility = if (s) View.VISIBLE else View.GONE
        h.job?.cancel(false); h.job = null
        val key = "$p|${i.size}|${i.mtime}"
        h.key = key
        h.img.visibility = View.GONE
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
