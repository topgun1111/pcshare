package com.lanshare.app

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.Spannable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.lanshare.app.core.Endpoint
import com.lanshare.app.core.Jobs
import com.lanshare.app.core.arcSplit
import com.lanshare.app.core.errText
import com.lanshare.app.core.uniqueName
import com.lanshare.app.core.vdir
import com.lanshare.app.core.vjoin
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Keep-style text editor. Opens any text file of this phone, of another LANShare device or of an SMB share (through the same
 * [Endpoint] layer as the file browser), or starts a new note in a folder. Title = file name, body = file content.
 * Coloured note background (Google Keep palette, light + dark variants), undo / redo, find & replace, text size, monospace.
 * Keeps the file's encoding (UTF-8/16 BOM, falls back to Windows-1254 for legacy Turkish files) and line endings (LF / CRLF).
 * Saves on back / leaving the screen (like Keep) and with the check button; warns when the file changed on disk meanwhile.
 * Archives, files above 3 MB and binary files are never overwritten (read-only view / refused).
 */
class TextEditorActivity : Activity() {
    companion object {
        private const val X_DEV = "dev"
        private const val X_PATH = "path"
        private const val X_DIR = "dir"
        private const val X_NEW = "new"
        private const val MAX_BYTES = 3 shl 20
        private const val UNDO_CHARS = 6_000_000L
        private const val SNAP_MS = 700L

        /** Set after a successful save: MainActivity refreshes the file list when it comes back to the front. */
        @Volatile private var changed = false
        fun consumeChanged(): Boolean { val c = changed; changed = false; return c }

        /** [json] from ui.html: {dev, path} = open this file, {dev, dir, isNew:true} = new note in that folder. */
        fun intent(c: Context, json: String): Intent {
            val o = JSONObject(json)
            return Intent(c, TextEditorActivity::class.java)
                .putExtra(X_DEV, o.optString("dev", "local"))
                .putExtra(X_PATH, o.optString("path", ""))
                .putExtra(X_DIR, o.optString("dir", "/"))
                .putExtra(X_NEW, o.optBoolean("isNew", false))
        }

        private class Pal(val light: Int, val dark: Int)
        // Google Keep note colours: default, red, orange, yellow, green, teal, blue, dark blue, purple, pink, brown, gray
        private val PALS = listOf(
            Pal(0xFFFFFFFF.toInt(), 0xFF202124.toInt()), Pal(0xFFF28B82.toInt(), 0xFF5C2B29.toInt()),
            Pal(0xFFFBBC04.toInt(), 0xFF614A19.toInt()), Pal(0xFFFFF475.toInt(), 0xFF635D19.toInt()),
            Pal(0xFFCCFF90.toInt(), 0xFF345920.toInt()), Pal(0xFFA7FFEB.toInt(), 0xFF16504B.toInt()),
            Pal(0xFFCBF0F8.toInt(), 0xFF2D555E.toInt()), Pal(0xFFAECBFA.toInt(), 0xFF1E3A5F.toInt()),
            Pal(0xFFD7AEFB.toInt(), 0xFF42275E.toInt()), Pal(0xFFFDCFE8.toInt(), 0xFF5B2245.toInt()),
            Pal(0xFFE6C9A8.toInt(), 0xFF442F19.toInt()), Pal(0xFFE8EAED.toInt(), 0xFF3C3F43.toInt()))
    }

    // ---------------------------------------------------------------- state
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "text-edit").also { it.isDaemon = true } }
    private val prefs by lazy { getSharedPreferences("editor", MODE_PRIVATE) }

    private var dev = "local"
    private var path: String? = null      // virtual path of the open file (null while a new note is not saved yet)
    private var dir = "/"                 // folder of a new note
    private var origName = ""
    private var devName = ""
    private var ep: Endpoint? = null

    private var cs: Charset = Charsets.UTF_8
    private var bom = ByteArray(0)
    private var crlf = false
    private var readOnly = false
    private var loaded = false
    private var dirty = false
    private var saving = false
    private var loading = false           // programmatic setText: the watchers stay quiet
    private var diskMtime = -1L           // as seen when opened / last saved: detects edits made elsewhere meanwhile
    private var diskSize = -1L
    private var diskSizeShown = 0L

    private var color = 0
    private var sizeSp = 16f
    private var mono = false
    private var night = false
    private var textColor = Color.BLACK

    // undo / redo (snapshots, debounced)
    private val undo = ArrayDeque<String>()
    private val redo = ArrayDeque<String>()
    private var cur = ""
    private var undoChars = 0L
    private var snapPending = false
    private val snapRun = Runnable { snapPending = false; snap() }

    // find
    private var hits = IntArray(0)
    private var hi = -1
    private val hl = ArrayList<Any>()
    private val findRun = Runnable { runFind(false) }

    // views
    private lateinit var root: LinearLayout
    private lateinit var top: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var column: LinearLayout
    private lateinit var locTv: TextView
    private lateinit var titleEt: EditText
    private lateinit var body: EditText
    private lateinit var banner: TextView
    private lateinit var bar: LinearLayout
    private lateinit var status: TextView
    private lateinit var back: TextView
    private lateinit var saveBtn: TextView
    private lateinit var moreBtn: TextView
    private lateinit var palBtn: TextView
    private lateinit var sizeBtn: TextView
    private lateinit var undoBtn: TextView
    private lateinit var redoBtn: TextView
    private lateinit var spin: ProgressBar
    private lateinit var findBox: LinearLayout
    private lateinit var findEt: EditText
    private lateinit var replEt: EditText
    private lateinit var findCount: TextView
    private lateinit var replBtn: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- lifecycle
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Fnt.init(this)
        night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        color = prefs.getInt("color", 0).coerceIn(0, PALS.size - 1)
        sizeSp = prefs.getFloat("size", 16f).coerceIn(12f, 30f)
        mono = prefs.getBoolean("mono", false)
        dev = intent.getStringExtra(X_DEV) ?: "local"
        val p = intent.getStringExtra(X_PATH).orEmpty()
        dir = intent.getStringExtra(X_DIR) ?: "/"
        val isNew = intent.getBooleanExtra(X_NEW, false) || p.isEmpty()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED)
        buildUi()
        applyTheme()
        if (isNew) {
            path = null
            loaded = true
            locTv.text = "New note in " + (if (dev == "local") "this phone" else "device") + " › " + dir
            setBody("", keepUndo = false)
            updateStatus(); updateButtons()
            body.requestFocus()
            ui.postDelayed({ try { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(body, 0) } catch (_: Throwable) {} }, 250)
            io.execute { try { val e = Jobs.ep(dev); ep = e; ui.post { devName = e.name; if (!isDestroyed) locTv.text = "New note in ${e.name} › $dir" } } catch (_: Throwable) {} }
        } else {
            path = p
            origName = p.substringAfterLast('/')
            titleEt.setText(origName)
            readOnly = arcSplit(p) != null
            locTv.text = p
            load()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isFinishing && dirty && !readOnly && loaded && !saving) save(auto = true)   // Keep style: nothing is lost when you switch away
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (findBox.visibility == View.VISIBLE) { hideFind(); return }
        leave()
    }

    private fun leave() {
        if (!dirty || readOnly || !loaded) { finish(); return }
        save(auto = false) { finish() }
    }

    // ---------------------------------------------------------------- UI (built in code, like the other viewers)
    private fun tvBtn(txt: String, sp: Float = 20f): TextView = TextView(this).apply {
        text = txt; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); gravity = Gravity.CENTER
        minWidth = dp(44); minimumHeight = dp(44); setPadding(dp(6), 0, dp(6), 0); typeface = Fnt.med()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // top bar: back, (spacer), save, more
        back = tvBtn("←", 24f).apply { setOnClickListener { leave() } }
        saveBtn = tvBtn("✓", 24f).apply { setOnClickListener { save(auto = false) }; visibility = View.GONE }
        moreBtn = tvBtn("⋮", 24f).apply { setOnClickListener { showMenu(it) } }
        spin = ProgressBar(this).apply { isIndeterminate = true; layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(8) }; visibility = View.GONE }
        top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(2), dp(4), 0)
            addView(back)
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(spin); addView(saveBtn); addView(moreBtn)
        }
        root.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // find & replace bar (hidden until asked for)
        findEt = EditText(this).apply {
            hint = "Find"; setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); background = null; typeface = Fnt.med()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { runFind(true) }
            })
            setOnEditorActionListener { _, _, _ -> stepFind(1); true }
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        findCount = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setPadding(dp(6), 0, dp(6), 0); typeface = Fnt.med() }
        val fPrev = tvBtn("▲", 15f).apply { setOnClickListener { stepFind(-1) } }
        val fNext = tvBtn("▼", 15f).apply { setOnClickListener { stepFind(1) } }
        val fClose = tvBtn("✕", 16f).apply { setOnClickListener { hideFind() } }
        replEt = EditText(this).apply {
            hint = "Replace with"; setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); background = null; typeface = Fnt.med()
        }
        replBtn = TextView(this).apply {
            text = "Replace all"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); typeface = Fnt.semi(); gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8)); setOnClickListener { replaceAll() }
        }
        val fRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(findEt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(findCount); addView(fPrev); addView(fNext); addView(fClose)
        }
        val fRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(replEt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); addView(replBtn)
        }
        findBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(8), dp(4)); visibility = View.GONE
            addView(fRow1); addView(fRow2)
        }
        root.addView(findBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        banner = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); typeface = Fnt.med(); setPadding(dp(20), dp(6), dp(20), dp(6)); visibility = View.GONE
        }
        root.addView(banner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // note: location line, title, body
        locTv = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); typeface = Fnt.med(); maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(0, dp(4), 0, 0)
        }
        titleEt = EditText(this).apply {
            hint = "Title"; background = null; typeface = Fnt.semi(); setPadding(0, dp(4), 0, dp(8))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 3; setHorizontallyScrolling(false)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
            setOnEditorActionListener { _, _, _ -> body.requestFocus(); true }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { if (!loading && loaded) markDirty() }
            })
        }
        body = EditText(this).apply {
            hint = "Note"; background = null; gravity = Gravity.TOP or Gravity.START; setPadding(0, dp(4), 0, dp(24))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 8; setHorizontallyScrolling(false)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (loading || !loaded) return
                    markDirty()
                    ui.removeCallbacks(snapRun); snapPending = true; ui.postDelayed(snapRun, SNAP_MS)
                    if (findBox.visibility == View.VISIBLE) { ui.removeCallbacks(findRun); ui.postDelayed(findRun, 400) }
                }
            })
        }
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(2), dp(20), dp(8))
            addView(locTv); addView(titleEt); addView(body)
            setOnClickListener { if (!readOnly) { body.requestFocus(); body.setSelection(body.length()); (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(body, 0) } }
        }
        scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // bottom bar: colour, text size, undo, redo, status
        palBtn = tvBtn("🎨", 20f).apply { setOnClickListener { pickColor() } }
        sizeBtn = tvBtn("Aa", 17f).apply { typeface = Fnt.semi(); setOnClickListener { sizeMenu(it) } }
        undoBtn = tvBtn("↶", 22f).apply { setOnClickListener { doUndo() } }
        redoBtn = tvBtn("↷", 22f).apply { setOnClickListener { doRedo() } }
        status = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); typeface = Fnt.med(); gravity = Gravity.CENTER_VERTICAL or Gravity.END
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(8), 0, dp(12), 0)
        }
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(2), dp(4), dp(2))
            addView(palBtn); addView(sizeBtn); addView(undoBtn); addView(redoBtn)
            addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        applyTypography()
    }

    private fun applyTypography() {
        val face = if (mono) Typeface.MONOSPACE else Fnt.med()
        body.typeface = face
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        body.setLineSpacing(0f, if (mono) 1.05f else 1.15f)
        titleEt.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp + 6f)
    }

    private fun applyTheme() {
        val p = PALS[color]
        val bg = if (night) p.dark else p.light
        textColor = if (night) 0xFFE8EAED.toInt() else 0xFF202124.toInt()
        val sub = ColorUtils.setAlphaComponent(textColor, 150)
        val hint = ColorUtils.setAlphaComponent(textColor, 110)
        root.setBackgroundColor(bg)
        window.statusBarColor = bg; window.navigationBarColor = bg
        val light = ColorUtils.calculateLuminance(bg) > 0.5
        WindowInsetsControllerCompat(window, window.decorView).apply { isAppearanceLightStatusBars = light; isAppearanceLightNavigationBars = light }
        for (t in listOf(back, saveBtn, moreBtn, palBtn, sizeBtn, undoBtn, redoBtn)) t.setTextColor(textColor)
        for (t in listOf(titleEt, body, findEt, replEt)) { t.setTextColor(textColor); t.setHintTextColor(hint) }
        for (t in listOf(locTv, status, findCount)) t.setTextColor(sub)
        banner.setTextColor(textColor)
        banner.setBackgroundColor(ColorUtils.setAlphaComponent(textColor, 28))
        findBox.setBackgroundColor(ColorUtils.setAlphaComponent(textColor, 18))
        replBtn.setTextColor(textColor)
        spin.indeterminateTintList = android.content.res.ColorStateList.valueOf(textColor)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun busy(on: Boolean) { spin.visibility = if (on) View.VISIBLE else View.GONE }

    private fun updateButtons() {
        saveBtn.visibility = if (dirty && !readOnly && loaded) View.VISIBLE else View.GONE
        val ed = !readOnly && loaded
        undoBtn.visibility = if (ed) View.VISIBLE else View.GONE
        redoBtn.visibility = if (ed) View.VISIBLE else View.GONE
        undoBtn.alpha = if (undo.isNotEmpty() || snapPending) 1f else 0.3f
        redoBtn.alpha = if (redo.isNotEmpty()) 1f else 0.3f
    }

    private fun updateStatus() {
        val t = body.text
        var lines = 1
        for (i in 0 until t.length) if (t[i] == '\n') lines++
        val base = when {
            !loaded -> "Loading…"
            readOnly -> "Read-only"
            dirty -> "Unsaved"
            path == null -> "New note"
            diskMtime > 0 -> "Edited " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(diskMtime * 1000))
            else -> "Saved"
        }
        status.text = "$base · $lines lines · ${t.length} chars"
    }

    private fun markDirty() {
        if (readOnly) return
        if (!dirty) { dirty = true; updateButtons() }
        ui.removeCallbacks(statusRun); ui.postDelayed(statusRun, 500)
    }
    private val statusRun = Runnable { updateStatus(); updateButtons() }

    // ---------------------------------------------------------------- reading
    private class Dec(val text: String, val cs: Charset, val bom: ByteArray, val crlf: Boolean)

    private fun strict(cs: Charset, b: ByteArray, off: Int, end: Int): String? {
        val d: CharsetDecoder = cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return try { d.decode(ByteBuffer.wrap(b, off, end - off)).toString() } catch (_: Exception) { null }
    }

    /** null = binary. */
    private fun decode(b: ByteArray, truncated: Boolean): Dec? {
        var off = 0
        var charset: Charset = Charsets.UTF_8
        var mark = ByteArray(0)
        val s: String
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) { mark = b.copyOf(3); off = 3 }
        else if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) { charset = Charsets.UTF_16LE; mark = b.copyOf(2); off = 2 }
        else if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) { charset = Charsets.UTF_16BE; mark = b.copyOf(2); off = 2 }
        if (mark.isEmpty()) {
            for (i in 0 until minOf(b.size, 8192)) if (b[i] == 0.toByte()) return null   // NUL byte: not a text file
        }
        var out: String? = null
        for (cut in 0..(if (truncated) 3 else 0)) { out = strict(charset, b, off, b.size - cut); if (out != null) break }   // a cut-off last character is fine
        if (out == null) {   // not valid UTF-8: legacy single-byte text (Turkish Windows files first)
            charset = if (Charset.isSupported("windows-1254")) Charset.forName("windows-1254") else Charsets.ISO_8859_1
            out = String(b, off, b.size - off, charset)
        }
        s = out
        val cr = s.contains("\r\n")
        return Dec(if (cr) s.replace("\r\n", "\n") else s, charset, mark, cr)
    }

    private fun load() {
        loaded = false; busy(true); updateStatus()
        val p = path ?: return
        io.execute {
            try {
                val e = Jobs.ep(dev); ep = e
                val st = try { e.stat(p) } catch (_: Exception) { null }
                if (st != null && st.optBoolean("dir")) throw IOException("This is a folder, not a file")
                val bo = ByteArrayOutputStream()
                var total = 0
                e.open(p).use { src ->
                    val buf = ByteArray(64 * 1024)
                    while (total <= MAX_BYTES) {
                        val n = src.read(buf, 0, minOf(buf.size, MAX_BYTES + 1 - total))
                        if (n < 0) break
                        bo.write(buf, 0, n); total += n
                    }
                }
                val trunc = total > MAX_BYTES
                var data = bo.toByteArray()
                if (trunc) data = data.copyOf(MAX_BYTES)
                val dec = decode(data, trunc)
                ui.post {
                    if (isDestroyed) return@post
                    busy(false)
                    devName = e.name
                    locTv.text = "${e.name} › ${vdir(p)}"
                    if (dec == null) { failLoad("This doesn't look like a text file (binary content)."); return@post }
                    cs = dec.cs; bom = dec.bom; crlf = dec.crlf
                    diskMtime = st?.optLong("mtime", -1L) ?: -1L
                    diskSize = st?.optLong("size", -1L) ?: -1L
                    diskSizeShown = if (trunc) total.toLong() else data.size.toLong()
                    if (trunc) { readOnly = true; showBanner("Large file: showing the first ${MAX_BYTES shr 20} MB, read-only.") }
                    else if (readOnly) showBanner("Inside an archive: read-only. Extract the file to edit it.")
                    loaded = true
                    setBody(dec.text, keepUndo = false)
                    dirty = false
                    if (readOnly) makeReadOnly()
                    updateStatus(); updateButtons()
                }
            } catch (x: Throwable) {
                ui.post { if (!isDestroyed) { busy(false); failLoad("Cannot open: ${errText(x)}") } }
            }
        }
    }

    private fun failLoad(msg: String) {
        AlertDialog.Builder(this).setMessage(msg).setCancelable(false)
            .setPositiveButton("Close") { _, _ -> finish() }
            .setNegativeButton("Retry") { _, _ -> load() }
            .show()
    }

    private fun showBanner(s: String) { banner.text = s; banner.visibility = View.VISIBLE }

    private fun makeReadOnly() {
        titleEt.keyListener = null; body.keyListener = null
        titleEt.setTextIsSelectable(true); body.setTextIsSelectable(true)
    }

    private fun setBody(t: String, keepUndo: Boolean) {
        loading = true
        val sel = body.selectionStart.coerceAtLeast(0)
        body.setText(t)
        if (keepUndo) body.setSelection(minOf(sel, t.length))
        loading = false
        cur = t
        if (!keepUndo) { undo.clear(); redo.clear(); undoChars = 0 }
    }

    // ---------------------------------------------------------------- undo / redo
    private fun snap() {
        if (!loaded || readOnly) return
        val t = body.text.toString()
        if (t != cur) {
            undo.addLast(cur); undoChars += cur.length
            cur = t; redo.clear()
            while (undo.size > 300 || (undoChars > UNDO_CHARS && undo.size > 1)) undoChars -= undo.removeFirst().length
        }
        updateButtons()
    }

    private fun doUndo() {
        ui.removeCallbacks(snapRun); snapPending = false; snap()
        if (undo.isEmpty()) return
        redo.addLast(cur)
        val t = undo.removeLast(); undoChars -= t.length
        setBody(t, keepUndo = true); markDirty(); updateButtons()
    }

    private fun doRedo() {
        ui.removeCallbacks(snapRun); snapPending = false; snap()
        if (redo.isEmpty()) return
        undo.addLast(cur); undoChars += cur.length
        val t = redo.removeLast()
        setBody(t, keepUndo = true); markDirty(); updateButtons()
    }

    // ---------------------------------------------------------------- saving
    private fun cleanName(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), " ").replace(Regex("\\s+"), " ").trim().trimEnd('.', ' ').take(120)

    private fun hasExt(n: String) = n.lastIndexOf('.') > 0 || (n.startsWith(".") && n.length > 1)

    /** [auto] = leaving the screen: never asks, never overwrites a file that changed elsewhere. [then] runs after a successful save (or when there was nothing to save). */
    private fun save(auto: Boolean, force: Boolean = false, then: (() -> Unit)? = null) {
        if (readOnly || !loaded) { then?.invoke(); return }
        if (saving) { ui.postDelayed({ save(auto, force, then) }, 300); return }
        ui.removeCallbacks(snapRun); snapPending = false; snap()
        val text = body.text.toString()
        val typed = cleanName(titleEt.text.toString())
        val existing = path
        if (existing == null && text.isEmpty() && typed.isEmpty()) { dirty = false; then?.invoke(); return }   // empty note: nothing is created (like Keep)
        if (existing != null && !dirty) { then?.invoke(); return }
        var name = typed
        if (existing == null) {
            if (name.isEmpty()) name = cleanName(text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()).take(40)
            if (name.isEmpty()) name = "Note " + SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.US).format(Date())
            if (!hasExt(name)) name += ".txt"
        } else if (name.isEmpty() || name == "." || name == "..") name = origName

        saving = true; busy(true)
        val wasNew = existing == null
        val charset = cs; val marks = bom; val useCr = crlf
        val d = dir; val oldM = diskMtime; val oldS = diskSize
        io.execute {
            try {
                val e = ep ?: Jobs.ep(dev).also { ep = it }
                var target: String
                var finalName = name
                if (wasNew) {
                    val folder = d
                    val taken = e.names(folder)
                    finalName = uniqueName(name, taken, false)
                    target = vjoin(folder, finalName)
                } else {
                    target = existing!!
                    if (!force && oldM > 0) {   // changed on disk since we opened it?
                        val st = try { e.stat(target) } catch (_: Exception) { null }
                        if (st != null && (st.optLong("mtime", oldM) != oldM || st.optLong("size", oldS) != oldS)) {
                            ui.post {
                                saving = false; busy(false)
                                if (isDestroyed || auto) return@post
                                AlertDialog.Builder(this).setTitle("File changed").setMessage("\"$origName\" was changed somewhere else after you opened it. Overwrite it with your version?")
                                    .setPositiveButton("Overwrite") { _, _ -> save(false, true, then) }
                                    .setNegativeButton("Cancel", null)
                                    .setNeutralButton("Discard mine") { _, _ -> dirty = false; finish() }
                                    .show()
                            }
                            return@execute
                        }
                    }
                    if (finalName != origName) {   // renamed through the title
                        if (finalName in e.names(vdir(target))) throw IOException("\"$finalName\" already exists in this folder")
                        e.rename(target, finalName)
                        target = vjoin(vdir(target), finalName)
                    }
                }
                var outCs = charset; var outMarks = marks
                if (!outCs.newEncoder().canEncode(text)) { outCs = Charsets.UTF_8; outMarks = ByteArray(0) }   // the old encoding can't hold the new characters
                val enc = (if (useCr) text.replace("\n", "\r\n") else text).toByteArray(outCs)
                val bytes = if (outMarks.isEmpty()) enc else outMarks + enc
                e.write(target, ByteArrayInputStream(bytes), bytes.size.toLong())
                val st2 = try { e.stat(target) } catch (_: Exception) { null }
                val converted = outCs != charset
                ui.post {
                    saving = false; busy(false)
                    changed = true
                    if (isDestroyed) return@post
                    cs = outCs; bom = outMarks
                    path = target; origName = finalName; devName = e.name
                    diskMtime = st2?.optLong("mtime", -1L) ?: -1L
                    diskSize = st2?.optLong("size", -1L) ?: bytes.size.toLong()
                    if (diskMtime < 0) diskMtime = System.currentTimeMillis() / 1000
                    dirty = body.text.toString() != text   // typed more while the save was running
                    if (titleEt.text.toString() != finalName) { loading = true; titleEt.setText(finalName); loading = false }
                    locTv.text = "${e.name} › ${vdir(target)}"
                    updateStatus(); updateButtons()
                    if (!auto) toast(if (converted) "Saved as UTF-8" else "Saved")
                    then?.invoke()
                }
            } catch (x: Throwable) {
                ui.post {
                    saving = false; busy(false)
                    if (isDestroyed || auto) return@post
                    val m = "Could not save: ${errText(x)}"
                    if (then != null) AlertDialog.Builder(this).setMessage(m)
                        .setPositiveButton("Retry") { _, _ -> save(false, force, then) }
                        .setNegativeButton("Stay", null)
                        .setNeutralButton("Discard") { _, _ -> dirty = false; finish() }
                        .show()
                    else AlertDialog.Builder(this).setMessage(m).setPositiveButton("OK", null).show()
                }
            }
        }
    }

    // ---------------------------------------------------------------- menus / dialogs
    private fun showMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, "Find & replace").isEnabled = loaded
        if (path != null) m.menu.add(0, 2, 1, "Reload from disk")
        m.menu.add(0, 3, 2, if (mono) "Proportional font" else "Monospace font")
        m.menu.add(0, 4, 3, "Share as text")
        m.menu.add(0, 5, 4, "Copy all")
        m.menu.add(0, 6, 5, "Details")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> showFind()
                2 -> if (dirty && !readOnly) AlertDialog.Builder(this).setMessage("Discard your changes and reload the file?")
                        .setPositiveButton("Reload") { _, _ -> readOnly = path?.let { arcSplit(it) != null } ?: false; banner.visibility = View.GONE; load() }.setNegativeButton("Cancel", null).show()
                     else { banner.visibility = View.GONE; load() }
                3 -> { mono = !mono; prefs.edit().putBoolean("mono", mono).apply(); applyTypography() }
                4 -> shareText()
                5 -> { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("note", body.text.toString())); toast("Copied") }
                6 -> details()
            }
            true
        }
        m.show()
    }

    private fun sizeMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, "Larger text"); m.menu.add(0, 2, 1, "Smaller text"); m.menu.add(0, 3, 2, "Default size")
        m.setOnMenuItemClickListener {
            sizeSp = when (it.itemId) { 1 -> sizeSp + 2f; 2 -> sizeSp - 2f; else -> 16f }.coerceIn(12f, 30f)
            prefs.edit().putFloat("size", sizeSp).apply(); applyTypography(); true
        }
        m.show()
    }

    private fun pickColor() {
        val dlg = Dialog(this)
        val grid = GridLayout(this).apply { columnCount = 6; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        val sel = color   // GradientDrawable.apply has its own 'color' (ColorStateList?)
        PALS.forEachIndexed { i, p ->
            val c = if (night) p.dark else p.light
            val v = TextView(this).apply {
                gravity = Gravity.CENTER; text = if (i == sel) "✓" else ""; setTextColor(if (ColorUtils.calculateLuminance(c) > 0.5) Color.BLACK else Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c); setStroke(dp(2), if (i == sel) 0xFF1565C0.toInt() else 0x33000000) }
                layoutParams = GridLayout.LayoutParams().apply { width = dp(42); height = dp(42); setMargins(dp(6), dp(6), dp(6), dp(6)) }
                setOnClickListener { color = i; prefs.edit().putInt("color", i).apply(); applyTheme(); dlg.dismiss() }
            }
            grid.addView(v)
        }
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dlg.setContentView(grid)
        dlg.show()
    }

    private fun shareText() {
        val t = body.text.toString()
        if (t.length > 400_000) { toast("Too large to share as text - use Share in the file list"); return }
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, t).putExtra(Intent.EXTRA_SUBJECT, titleEt.text.toString()), "Share"))
        } catch (x: Exception) { toast("Cannot share: ${errText(x)}") }
    }

    private fun details() {
        val sz = if (diskSizeShown > 0) humanSize(diskSizeShown) else "-"
        val mt = if (diskMtime > 0) DateFormat.getDateTimeInstance().format(Date(diskMtime * 1000)) else "-"
        val enc = cs.name() + (if (bom.isNotEmpty()) " (BOM)" else "")
        val msg = "Device: ${devName.ifEmpty { dev }}\nPath: ${path ?: "(new note in $dir)"}\nSize: $sz\nModified: $mt\nEncoding: $enc\nLine endings: ${if (crlf) "CRLF (Windows)" else "LF"}"
        AlertDialog.Builder(this).setTitle("Details").setMessage(msg).setPositiveButton("OK", null).show()
    }

    private fun humanSize(n: Long): String {
        val u = arrayOf("B", "KB", "MB", "GB"); var v = n.toDouble(); var i = 0
        while (v >= 1024 && i < 3) { v /= 1024; i++ }
        return if (i == 0) "$n B" else String.format(Locale.US, "%.1f %s", v, u[i])
    }

    // ---------------------------------------------------------------- find & replace
    private fun showFind() {
        findBox.visibility = View.VISIBLE
        (findBox.getChildAt(1)).visibility = if (readOnly) View.GONE else View.VISIBLE
        val sel = body.selectionStart; val e = body.selectionEnd
        if (sel >= 0 && e > sel && e - sel < 80) findEt.setText(body.text.substring(sel, e))
        findEt.requestFocus(); findEt.setSelection(findEt.length())
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(findEt, 0)
        runFind(true)
    }

    private fun hideFind() {
        findBox.visibility = View.GONE
        clearHits()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(findEt.windowToken, 0)
    }

    private fun clearHits() {
        val t = body.text
        for (s in hl) t.removeSpan(s)
        hl.clear(); hits = IntArray(0); hi = -1; findCount.text = ""
    }

    private fun runFind(jump: Boolean) {
        clearHits()
        val q = findEt.text.toString()
        if (q.isEmpty() || findBox.visibility != View.VISIBLE) return
        val t = body.text.toString()
        val acc = ArrayList<Int>()
        var from = 0
        while (acc.size < 5000) {
            val i = t.indexOf(q, from, ignoreCase = true)
            if (i < 0) break
            acc.add(i); from = i + q.length
        }
        hits = acc.toIntArray()
        if (hits.isEmpty()) { findCount.text = "0"; return }
        if (hi < 0 || hi >= hits.size) hi = 0
        if (jump) {   // start at the first hit after the cursor
            val c = body.selectionStart.coerceAtLeast(0)
            hi = hits.indexOfFirst { it >= c }.let { if (it < 0) 0 else it }
        }
        paintHits(q.length, jump)
    }

    private fun paintHits(len: Int, scrollTo: Boolean) {
        val t = body.text
        for (s in hl) t.removeSpan(s)
        hl.clear()
        val soft = 0x66FFC107; val strong = 0xFFFF9800.toInt()
        for (k in hits.indices) {
            if (k != hi && k >= 400) continue
            val s = BackgroundColorSpan(if (k == hi) strong else soft)
            t.setSpan(s, hits[k], hits[k] + len, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE); hl.add(s)
        }
        findCount.text = "${hi + 1}/${hits.size}" + (if (hits.size >= 5000) "+" else "")
        if (scrollTo) body.post {
            val lay = body.layout ?: return@post
            val y = column.top + body.top + lay.getLineTop(lay.getLineForOffset(hits[hi]))
            scroll.smoothScrollTo(0, (y - dp(120)).coerceAtLeast(0))
        }
    }

    private fun stepFind(d: Int) {
        if (hits.isEmpty()) return
        hi = (hi + d + hits.size) % hits.size
        paintHits(findEt.text.length, true)
    }

    private fun replaceAll() {
        if (readOnly || !loaded) return
        val q = findEt.text.toString()
        if (q.isEmpty()) return
        val t = body.text.toString()
        val n = hits.size.takeIf { it > 0 } ?: 0
        if (n == 0) { toast("Nothing to replace"); return }
        ui.removeCallbacks(snapRun); snapPending = false; snap()
        clearHits()
        val r = t.replace(q, replEt.text.toString(), ignoreCase = true)
        setBody(r, keepUndo = true)
        snap(); markDirty()
        toast("Replaced $n")
        runFind(false)
    }
}
