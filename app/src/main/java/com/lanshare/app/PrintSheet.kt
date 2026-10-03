package com.lanshare.app

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.exifinterface.media.ExifInterface
import com.lanshare.app.core.Jobs
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** One file to print: [url] is the app's own local server (`/api/dl`, 127.0.0.1) - only used for the preview. */
class PrintFile(val name: String, val path: String, val url: String)

class PrintPal(val bg: Int, val card: Int, val fg: Int, val mut: Int, val div: Int, val accent: Int)

/**
 * Print options for a PC running pcprint.py (FinePrint-style): printer, presets, copies, sides, colour, pages per sheet, booklet,
 * page selection, paper, scaling, watermark, header / footer, plus a live preview of the sheets.
 * Only pictures selected -> they are laid out together on shared sheets (each can be turned); the result then carries `sheet=1` + `rots`.
 * The layout maths (pictures on sheets) must stay identical to images_to_sheets() in pcprint.py.
 * [show] calls back with the options to pass to [Jobs.startPrint], or null when cancelled.
 */
class PrintSheet(private val act: Activity, private val pal: PrintPal) {
    class Target(val id: String, val name: String)

    fun show(target: Target, files: List<PrintFile>, onlyFiles: Boolean, done: (JSONObject?) -> Unit) {
        Session(target, files, onlyFiles, done).open()
    }

    // ------------------------------------------------------------------ options
    private class St {
        var printer = ""; var copies = 1; var duplex = ""; var color = ""; var nup = 1
        var booklet = false; var border = false; var pages = ""; var range = ""; var reverse = false
        var paper = ""; var fit = "shrink"; var wm = ""; var wmUnder = false; var hdr = ""; var ftr = ""; var noauto = false
        var rots = IntArray(0)

        fun reset(keepPrinter: Boolean) {
            val p = printer
            printer = if (keepPrinter) p else ""; copies = 1; duplex = ""; color = ""; nup = 1
            booklet = false; border = false; pages = ""; range = ""; reverse = false
            paper = ""; fit = "shrink"; wm = ""; wmUnder = false; hdr = ""; ftr = ""; noauto = false
        }

        fun load(o: JSONObject) {
            printer = o.optString("printer", printer)
            copies = o.optInt("copies", copies).coerceIn(1, 99)
            duplex = o.optString("duplex", duplex); color = o.optString("color", color)
            nup = o.optInt("nup", nup); booklet = o.optInt("booklet", if (booklet) 1 else 0) != 0
            border = o.optInt("border", if (border) 1 else 0) != 0
            pages = o.optString("pages", pages); reverse = o.optInt("reverse", if (reverse) 1 else 0) != 0
            paper = o.optString("paper", paper); fit = o.optString("fit", fit).ifEmpty { "shrink" }
            wm = o.optString("wm", wm); wmUnder = o.optInt("wm_under", if (wmUnder) 1 else 0) != 0
            hdr = o.optString("hdr", hdr); ftr = o.optString("ftr", ftr); noauto = o.optInt("noauto", if (noauto) 1 else 0) != 0
        }

        /** Everything except page range / turns (those belong to one job only). */
        fun store(withPrinter: Boolean): JSONObject = JSONObject().apply {
            if (withPrinter) put("printer", printer)
            put("copies", copies); put("duplex", duplex); put("color", color); put("nup", nup)
            put("booklet", if (booklet) 1 else 0); put("border", if (border) 1 else 0); put("pages", pages)
            put("reverse", if (reverse) 1 else 0); put("paper", paper); put("fit", fit); put("wm", wm)
            put("wm_under", if (wmUnder) 1 else 0); put("hdr", hdr); put("ftr", ftr); put("noauto", if (noauto) 1 else 0)
        }
    }

    private class Info(val kind: String, val pages: Int, val img: Bitmap?)   // kind: img | doc | none

    private class SheetDraw(val sw: Float, val sh: Float, val label: String, val draw: (Canvas) -> Unit)

    private class Cell(val i: Int, val r: Int, val k: Float, val w: Float, val h: Float, val cx: Float, val cy: Float)
    private class ImgPlan(val sw: Float, val sh: Float, val sheets: List<List<Cell>>, val score: Double)

    private companion object {
        val PAPER = mapOf("A3" to floatArrayOf(842f, 1191f), "A4" to floatArrayOf(595f, 842f), "A5" to floatArrayOf(420f, 595f),
            "Letter" to floatArrayOf(612f, 792f), "Legal" to floatArrayOf(612f, 1008f))
        val LAY = mapOf(1 to intArrayOf(1, 1, 0), 2 to intArrayOf(2, 1, 1), 4 to intArrayOf(2, 2, 0), 6 to intArrayOf(3, 2, 1), 9 to intArrayOf(3, 3, 0))
        // pictures on shared sheets: nup -> (cols, rows) for a portrait sheet, then for a landscape sheet
        val GRID = mapOf(1 to arrayOf(intArrayOf(1, 1), intArrayOf(1, 1)), 2 to arrayOf(intArrayOf(1, 2), intArrayOf(2, 1)),
            4 to arrayOf(intArrayOf(2, 2), intArrayOf(2, 2)), 6 to arrayOf(intArrayOf(2, 3), intArrayOf(3, 2)), 9 to arrayOf(intArrayOf(3, 3), intArrayOf(3, 3)))
        val IMG_EXT = setOf("png", "jpg", "jpeg", "bmp", "gif", "tif", "tiff")
        val TXT_EXT = setOf("txt", "log", "md")
        val BUILTIN = linkedMapOf(
            "Normal" to JSONObject(),
            "Save paper" to JSONObject().put("nup", 2).put("duplex", "long").put("color", "mono"),
            "Booklet" to JSONObject().put("booklet", 1).put("duplex", "short"),
            "Draft" to JSONObject().put("color", "mono").put("nup", 2))
        const val COL_ERR = 0xFFD9534F.toInt()
        const val COL_OK = 0xFF2E9E6B.toInt()

        fun ext(n: String) = n.substringAfterLast('.', "").lowercase()

        /** "1-3,5,8-" -> zero-based page indexes (same rules as pcprint.py); empty / unusable -> all pages. */
        fun pageSet(rng: String, n: Int): MutableList<Int> {
            val all = MutableList(n) { it }
            if (rng.isBlank()) return all
            val out = ArrayList<Int>()
            val re = Regex("^(\\d*)(-?)(\\d*)$")
            for (part in rng.replace(" ", "").split(',')) {
                if (part.isEmpty()) continue
                val m = re.find(part) ?: continue
                val g1 = m.groupValues[1]; val dash = m.groupValues[2]; val g3 = m.groupValues[3]
                var a: Int; var b: Int
                if (dash.isNotEmpty()) { a = if (g1.isNotEmpty()) (g1.toIntOrNull() ?: n) else 1; b = if (g3.isNotEmpty()) (g3.toIntOrNull() ?: n) else n }
                else { if (g1.isEmpty()) continue; a = g1.toIntOrNull() ?: n; b = a }
                a = a.coerceIn(1, n); b = b.coerceIn(1, n)
                if (a <= b) for (i in a..b) out.add(i - 1) else for (i in a downTo b) out.add(i - 1)
            }
            return if (out.isEmpty()) all else out
        }

        /** Booklet imposition: sheet sides in print order (null = blank page to fill up to a multiple of 4). */
        fun booklet(n: Int): List<Int?> {
            val m = (n + 3) / 4 * 4
            val q = ArrayList<Int>()
            for (i in 0 until m / 4) { q.add(m - 1 - 2 * i); q.add(2 * i); q.add(2 * i + 1); q.add(m - 2 - 2 * i) }
            return q.map { if (it < n) it else null }
        }

        fun fill(t: String, i: Int, n: Int, name: String): String {
            val c = Calendar.getInstance()
            fun z(x: Int) = x.toString().padStart(2, '0')
            return t.replace("{page}", (i + 1).toString()).replace("{pages}", n.toString())
                .replace("{date}", "${c.get(Calendar.YEAR)}-${z(c.get(Calendar.MONTH) + 1)}-${z(c.get(Calendar.DAY_OF_MONTH))}")
                .replace("{time}", "${z(c.get(Calendar.HOUR_OF_DAY))}:${z(c.get(Calendar.MINUTE))}").replace("{file}", name)
        }

        fun imgSheets(dims: List<Pair<Int, Int>>, rots: IntArray, st: St): ImgPlan {
            val pd = PAPER[st.paper] ?: PAPER["A4"]!!
            val mg = 14f; val gap = 8f
            val per = if (GRID.containsKey(st.nup)) st.nup else 1
            val order = dims.indices.toMutableList()
            if (st.reverse) order.reverse()
            fun plan(portrait: Boolean): ImgPlan {
                val sw = if (portrait) pd[0] else pd[1]
                val sh = if (portrait) pd[1] else pd[0]
                val g = GRID[per]!![if (portrait) 0 else 1]
                val cols = g[0]; val rows = g[1]
                val cw = (sw - 2 * mg - gap * (cols - 1)) / cols
                val ch = (sh - 2 * mg - gap * (rows - 1)) / rows
                val sheets = ArrayList<List<Cell>>()
                var score = 0.0
                var s0 = 0
                while (s0 < order.size) {
                    val cells = ArrayList<Cell>()
                    val chunk = order.subList(s0, min(order.size, s0 + cols * rows))
                    for ((j, i) in chunk.withIndex()) {
                        var best: Cell? = null
                        var bestDw = 0f; var bestDh = 0f
                        for (ex in (if (st.noauto) intArrayOf(0) else intArrayOf(0, 90))) {
                            val r = ((if (i < rots.size) rots[i] else 0) + ex) % 360
                            val w = dims[i].first.toFloat(); val h = dims[i].second.toFloat()
                            val dw = if (r % 180 != 0) h else w
                            val dh = if (r % 180 != 0) w else h
                            val k = min(cw / dw, ch / dh)
                            if (best == null || k > best.k * 1.0001f) { best = Cell(i, r, k, dw * k, dh * k, 0f, 0f); bestDw = dw; bestDh = dh }
                        }
                        val b = best!!
                        score += (b.k * b.k * bestDw * bestDh).toDouble()
                        cells.add(Cell(i, b.r, b.k, b.w, b.h, mg + (j % cols) * (cw + gap) + cw / 2, mg + (j / cols) * (ch + gap) + ch / 2))
                    }
                    sheets.add(cells)
                    s0 += cols * rows
                }
                return ImgPlan(sw, sh, sheets, score)
            }
            val a = plan(true); val b = plan(false)
            return if (b.score > a.score * 1.0001) b else a
        }

        fun exifMatrix(o: Int): Matrix {
            val m = Matrix()
            when (o) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> { m.setRotate(180f); m.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
                else -> {}
            }
            return m
        }

        /** Small, EXIF-rotated copy of a picture for the preview. */
        fun decodeThumb(b: ByteArray, maxSide: Int): Bitmap? {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(b, 0, b.size, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return null
            val big = max(o.outWidth, o.outHeight)
            var s = 1
            while (big / (s * 2) >= maxSide) s *= 2
            var bm = BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
            val ori = try { ExifInterface(ByteArrayInputStream(b)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }
            val m = exifMatrix(ori)
            if (!m.isIdentity) {
                val r = Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, m, true)
                if (r !== bm) bm.recycle()
                bm = r
            }
            return bm
        }

        fun fetch(url: String, cap: Long, timeoutMs: Int): ByteArray? {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 5000; c.readTimeout = timeoutMs
                if (c.responseCode != 200) return null
                if (c.contentLengthLong > cap) return null
                val bo = ByteArrayOutputStream()
                c.inputStream.use { ins ->
                    val buf = ByteArray(65536)
                    var tot = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        tot += n
                        if (tot > cap) return null
                        bo.write(buf, 0, n)
                    }
                }
                return bo.toByteArray()
            } catch (_: Exception) { return null } finally { c.disconnect() }
        }

        fun fetchToFile(url: String, f: File, cap: Long, timeoutMs: Int): Boolean {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 5000; c.readTimeout = timeoutMs
                if (c.responseCode != 200) return false
                if (c.contentLengthLong > cap) return false
                c.inputStream.use { ins -> f.outputStream().use { out ->
                    val buf = ByteArray(65536)
                    var tot = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        tot += n
                        if (tot > cap) return false
                        out.write(buf, 0, n)
                    }
                } }
                return true
            } catch (_: Exception) { return false } finally { c.disconnect() }
        }
    }

    // ------------------------------------------------------------------ one open dialog
    private inner class Session(val target: Target, val files: List<PrintFile>, onlyFiles: Boolean, val done: (JSONObject?) -> Unit) {
        val prefs = act.getSharedPreferences("ls_print", Context.MODE_PRIVATE)
        val ui = Handler(Looper.getMainLooper())
        val pool: ExecutorService = Executors.newFixedThreadPool(3) { r -> Thread(r, "printsheet").also { it.isDaemon = true } }
        val d = act.resources.displayMetrics.density
        val st = St()
        val redraw = ArrayList<() -> Unit>()
        val imgMode = onlyFiles && files.isNotEmpty() && files.all { ext(it.name) in IMG_EXT }
        val thumbs = arrayOfNulls<Bitmap>(files.size)
        val thumbDone = BooleanArray(files.size)
        val thumbViews = arrayOfNulls<ImageView>(files.size)
        var info: Info? = null
        var closed = false
        var printersBuilt = false

        lateinit var dialog: Dialog
        lateinit var content: LinearLayout
        lateinit var infoTv: TextView
        lateinit var hintTv: TextView
        lateinit var cap: TextView
        lateinit var pv: PvView
        lateinit var printerRow: LinearLayout
        lateinit var printerSec: View
        lateinit var presetRow: LinearLayout

        fun dp(v: Int) = (v * d).toInt()

        init {
            try { st.load(JSONObject(prefs.getString("opts", "{}") ?: "{}")) } catch (_: Exception) {}
            st.range = ""
            st.rots = IntArray(files.size)
            if (imgMode) st.booklet = false
        }

        // ---------------------------------------------------------- preview view
        inner class PvView : View(act) {
            var sheets: List<SheetDraw> = emptyList()
            var boxW = 220f; var boxH = 170f
            private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = pal.div; strokeWidth = 1f }
            private val fill = Paint().apply { color = Color.WHITE }
            private val lab = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.mut; textAlign = Paint.Align.CENTER; textSize = 11 * d }
            private val gray = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
            private fun scaleFor(s: SheetDraw) = min(boxW / s.sw, boxH / s.sh)

            fun setMono(on: Boolean) { setLayerType(LAYER_TYPE_HARDWARE, if (on) gray else null) }

            fun setSheets(l: List<SheetDraw>, w: Float, h: Float) { sheets = l; boxW = w; boxH = h; requestLayout(); invalidate() }

            override fun onMeasure(wSpec: Int, hSpec: Int) {
                var w = 0f; var h = 0f
                for (s in sheets) { w += s.sw * scaleFor(s) * d + 10 * d; h = max(h, s.sh * scaleFor(s) * d) }
                setMeasuredDimension(max(w.toInt(), 1), (h + 22 * d).toInt().coerceAtLeast((24 * d).toInt()))
            }

            override fun onDraw(c: Canvas) {
                var x = 0f
                for (s in sheets) {
                    val k = scaleFor(s) * d
                    val w = s.sw * k; val h = s.sh * k
                    c.drawRect(x, 0f, x + w, h, fill)
                    c.save(); c.translate(x, 0f); c.clipRect(0f, 0f, w, h); c.scale(k, k)
                    try { s.draw(c) } catch (_: Exception) {}
                    c.restore()
                    c.drawRect(x, 0f, x + w, h, frame)
                    c.drawText(s.label, x + w / 2, h + 15 * d, lab)
                    x += w + 10 * d
                }
            }
        }

        // ---------------------------------------------------------- UI helpers
        fun lp(right: Int = 0, top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(right); topMargin = dp(top) }
        fun refreshAll() { redraw.forEach { it() } }
        private var ptm = Runnable {}
        fun sched() { ui.removeCallbacks(ptm); ptm = Runnable { drawPv() }; ui.postDelayed(ptm, 90) }

        fun chipView(label: String, minW: Int = 0) = TextView(act).apply {
            text = label; textSize = 13f; gravity = Gravity.CENTER; maxLines = 1
            setPadding(dp(14), dp(8), dp(14), dp(8)); if (minW > 0) minWidth = dp(minW)
        }

        fun styleChip(t: TextView, on: Boolean) {
            t.setTextColor(if (on) Color.WHITE else pal.fg)
            t.background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(if (on) pal.accent else pal.card); setStroke(1, pal.div) }
        }

        fun hscroll(row: View) = HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(row) }

        fun sec(title: String, vararg kids: View): LinearLayout {
            val b = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, 0) }
            if (title.isNotEmpty()) b.addView(TextView(act).apply { text = title; textSize = 12f; setTypeface(typeface, Typeface.BOLD); setTextColor(pal.mut); setPadding(0, 0, 0, dp(4)) })
            kids.forEach { b.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) }) }
            content.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return b
        }

        fun <T> seg(opts: List<Pair<T, String>>, get: () -> T, set: (T) -> Unit, minW: Int = 0): View {
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            for ((v, l) in opts) {
                val c = chipView(l, minW)
                c.setOnClickListener { set(v); refreshAll() }
                redraw.add { styleChip(c, get() == v) }
                row.addView(c, lp(right = 8))
            }
            return hscroll(row)
        }

        fun sw(label: String, get: () -> Boolean, set: (Boolean) -> Unit): View {
            val s = Switch(act)
            s.text = label; s.textSize = 14f; s.setTextColor(pal.fg)
            var busy = false
            s.setOnCheckedChangeListener { _, c -> if (!busy) { set(c); refreshAll() } }
            redraw.add { busy = true; s.isChecked = get(); busy = false }
            return s
        }

        fun txt(hint: String, get: () -> String, set: (String) -> Unit): EditText {
            val e = EditText(act)
            e.hint = hint; e.setSingleLine(); e.textSize = 14f; e.setTextColor(pal.fg); e.setHintTextColor(pal.mut)
            e.inputType = InputType.TYPE_CLASS_TEXT
            e.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { val v = s?.toString() ?: ""; if (v != get()) { set(v); sched() } }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
            redraw.add { if (e.text.toString() != get()) e.setText(get()) }
            return e
        }

        // ---------------------------------------------------------- open
        fun open() {
            dialog = Dialog(act, android.R.style.Theme_DeviceDefault_NoActionBar)
            dialog.window?.setBackgroundDrawable(ColorDrawable(pal.bg))
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialog.setOnCancelListener { finish(null) }

            val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(pal.bg) }
            val head = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(6)) }
            head.addView(TextView(act).apply { text = "Print on " + target.name; textSize = 19f; setTypeface(typeface, Typeface.BOLD); setTextColor(pal.fg); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            infoTv = TextView(act).apply { text = "Checking printer…"; textSize = 12f; setTextColor(pal.mut); setPadding(0, dp(2), 0, 0) }
            head.addView(infoTv)
            root.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            content = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(16)) }
            val scroll = ScrollView(act).apply { addView(content) }
            root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

            buildBody()

            val bar = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(dp(12), dp(8), dp(12), dp(8)); setBackgroundColor(pal.card) }
            val cancel = chipView("Cancel", 90).also { styleChip(it, false); it.setOnClickListener { finish(null) } }
            val ok = chipView("Print", 110).also { styleChip(it, true); it.setOnClickListener { confirm() } }
            bar.addView(cancel, lp(right = 8)); bar.addView(ok, lp())
            root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            dialog.setContentView(root)
            dialog.show()
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            refreshAll()
            startPreviewLoads()
            status()
        }

        fun finish(v: JSONObject?) {
            if (closed) return
            closed = true
            ui.removeCallbacksAndMessages(null)
            pool.shutdownNow()
            try { if (dialog.isShowing) dialog.dismiss() } catch (_: Exception) {}
            done(v)
        }

        fun confirm() {
            val o = JSONObject()
            if (st.printer.isNotEmpty()) o.put("printer", st.printer)
            if (st.copies != 1) o.put("copies", st.copies)
            if (st.duplex.isNotEmpty()) o.put("duplex", st.duplex)
            if (st.color.isNotEmpty()) o.put("color", st.color)
            if (st.nup != 1) o.put("nup", st.nup)
            if (st.booklet) o.put("booklet", 1)
            if (st.border) o.put("border", 1)
            if (st.pages.isNotEmpty()) o.put("pages", st.pages)
            if (st.range.isNotBlank()) o.put("range", st.range.trim())
            if (st.reverse) o.put("reverse", 1)
            if (st.paper.isNotEmpty()) o.put("paper", st.paper)
            if (st.fit != "shrink") o.put("fit", st.fit)
            if (st.wm.isNotEmpty()) o.put("wm", st.wm)
            if (st.wmUnder) o.put("wm_under", 1)
            if (st.hdr.isNotEmpty()) o.put("hdr", st.hdr)
            if (st.ftr.isNotEmpty()) o.put("ftr", st.ftr)
            if (st.noauto) o.put("noauto", 1)
            if (imgMode) {
                o.put("sheet", 1); o.put("rots", JSONArray(st.rots.toList()))
                o.remove("booklet"); o.remove("range"); o.remove("pages")
            } else if (st.booklet && st.duplex.isEmpty()) o.put("duplex", "short")
            prefs.edit().putString("opts", st.store(true).toString()).apply()
            finish(o)
        }

        // ---------------------------------------------------------- body
        fun buildBody() {
            // preview
            pv = PvView()
            cap = TextView(act).apply { textSize = 12f; setTextColor(pal.mut); setPadding(dp(2), dp(4), dp(2), 0) }
            val pvBox = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(8))
                background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(pal.card); setStroke(1, pal.div) }
            }
            pvBox.addView(hscroll(pv)); pvBox.addView(cap)
            content.addView(pvBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            redraw.add { sched() }

            // presets
            presetRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            drawPresets()
            sec("Presets", hscroll(presetRow))

            // printer (only shown when the PC reports several)
            printerRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            printerSec = sec("Printer", hscroll(printerRow)).also { it.visibility = View.GONE }

            // copies
            val cn = TextView(act).apply { textSize = 16f; setTextColor(pal.fg); gravity = Gravity.CENTER; minWidth = dp(40) }
            val mi = chipView("−", 44).also { styleChip(it, false); it.setOnClickListener { st.copies = max(1, st.copies - 1); refreshAll() } }
            val pl = chipView("+", 44).also { styleChip(it, false); it.setOnClickListener { st.copies = min(99, st.copies + 1); refreshAll() } }
            val cr = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; addView(mi); addView(cn); addView(pl) }
            redraw.add { cn.text = st.copies.toString() }
            sec("Copies", cr)

            sec("Sides", seg(listOf("" to "Printer default", "off" to "One-sided", "long" to "Long edge", "short" to "Short edge"), { st.duplex }, { st.duplex = it }))
            sec("Colour", seg(listOf("" to "Default", "color" to "Colour", "mono" to "Black & white"), { st.color }, { st.color = it }))

            val nupViews = ArrayList<View>()
            nupViews.add(seg(listOf(1 to "1", 2 to "2", 4 to "4", 6 to "6", 9 to "9"), { st.nup }, { st.nup = it }, 48))
            nupViews.add(sw(if (imgMode) "Border around each picture" else "Border around each page", { st.border }, { st.border = it }))
            if (!imgMode) nupViews.add(sw("Booklet (folded, two-sided on short edge)", { st.booklet }, { st.booklet = it }))
            sec(if (imgMode) "Pictures per sheet" else "Pages per sheet", *nupViews.toTypedArray())

            if (imgMode) {
                val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
                for (i in files.indices) {
                    val iv = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_INSIDE; setPadding(dp(4), dp(4), dp(4), dp(18)) }
                    val lb = TextView(act).apply { textSize = 11f; setTextColor(pal.mut); gravity = Gravity.CENTER }
                    thumbViews[i] = iv
                    val tile = FrameLayout(act).apply {
                        background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(pal.card); setStroke(1, pal.div) }
                        contentDescription = "Turn " + files[i].name
                        clipChildren = true
                        addView(iv, FrameLayout.LayoutParams(dp(68), dp(68)))
                        addView(lb, FrameLayout.LayoutParams(dp(68), dp(18), Gravity.BOTTOM))
                        setOnClickListener { st.rots[i] = (st.rots[i] + 90) % 360; refreshAll() }
                    }
                    redraw.add { iv.rotation = st.rots[i].toFloat(); lb.text = if (st.rots[i] != 0) st.rots[i].toString() + "°" else "↻" }
                    row.addView(tile, lp(right = 8))
                }
                val auto = sw("Auto-turn pictures to fill the sheet", { !st.noauto }, { st.noauto = !it })
                sec("Turn pictures (tap = 90° clockwise)", hscroll(row), auto)
                sec("Order", sw("Reverse order", { st.reverse }, { st.reverse = it }))
            } else {
                sec("Pages", seg(listOf("" to "All", "odd" to "Odd only", "even" to "Even only"), { st.pages }, { st.pages = it }),
                    txt("Page range, e.g. 1-3,5,8-", { st.range }, { st.range = it }),
                    sw("Reverse order", { st.reverse }, { st.reverse = it }))
            }
            sec("Paper", seg(listOf("" to "Auto", "A4" to "A4", "Letter" to "Letter", "A3" to "A3", "A5" to "A5", "Legal" to "Legal"), { st.paper }, { st.paper = it }))
            if (!imgMode) sec("Scaling", seg(listOf("shrink" to "Shrink to fit", "fit" to "Fit", "noscale" to "Actual size"), { st.fit }, { st.fit = it }))
            sec("Watermark", txt("Text, e.g. DRAFT", { st.wm }, { st.wm = it }), sw("Behind the page content", { st.wmUnder }, { st.wmUnder = it }))
            sec("Header / footer", txt("Header: left | centre | right", { st.hdr }, { st.hdr = it }), txt("Footer: Page {page} of {pages}", { st.ftr }, { st.ftr = it }))
            content.addView(TextView(act).apply {
                text = "Tokens: {page} {pages} {date} {time} {file}. Layout, watermark and header/footer work for PDF, image and text files; other files get printer and copies only."
                textSize = 12f; setTextColor(pal.mut); setPadding(0, dp(8), 0, 0)
            })
            hintTv = TextView(act).apply { textSize = 12f; setTextColor(pal.mut); setPadding(0, dp(4), 0, 0); visibility = View.GONE }
            content.addView(hintTv)
        }

        // ---------------------------------------------------------- presets
        fun savedPresets(): JSONObject = try { JSONObject(prefs.getString("presets", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }

        fun applyPreset(p: JSONObject) { st.reset(true); st.load(p); if (imgMode) st.booklet = false; refreshAll() }

        fun drawPresets() {
            presetRow.removeAllViews()
            val mine = savedPresets()
            for ((n, p) in BUILTIN) {
                presetRow.addView(chipView(n).also { styleChip(it, false); it.setOnClickListener { applyPreset(p) } }, lp(right = 8))
            }
            val keys = mine.keys().asSequence().toList()
            for (n in keys) {
                val p = mine.optJSONObject(n) ?: continue
                presetRow.addView(chipView(n).also {
                    styleChip(it, false)
                    it.setOnClickListener { applyPreset(p) }
                    it.setOnLongClickListener { removePreset(n); true }
                }, lp(right = 8))
            }
            presetRow.addView(chipView("+ Save").also { styleChip(it, false); it.setOnClickListener { savePreset() } }, lp())
        }

        fun savePreset() {
            val e = EditText(act).apply { hint = "Preset name"; setSingleLine() }
            AlertDialog.Builder(act).setTitle("Save preset")
                .setMessage("Saves the options below under a name (press and hold a preset to delete it).")
                .setView(e)
                .setPositiveButton("Save") { _, _ ->
                    val n = e.text.toString().trim().take(24)
                    if (n.isEmpty()) return@setPositiveButton
                    val m = savedPresets()
                    m.put(n, st.store(false))
                    prefs.edit().putString("presets", m.toString()).apply()
                    drawPresets()
                }.setNegativeButton("Cancel", null).show()
        }

        fun removePreset(n: String) {
            AlertDialog.Builder(act).setTitle("Delete preset \"$n\"?")
                .setPositiveButton("Delete") { _, _ ->
                    val m = savedPresets(); m.remove(n)
                    prefs.edit().putString("presets", m.toString()).apply()
                    drawPresets()
                }.setNegativeButton("Cancel", null).show()
        }

        // ---------------------------------------------------------- printer status
        fun status() {
            infoTv.text = "Checking printer…"; infoTv.setTextColor(pal.mut)
            val p = st.printer
            pool.execute {
                val r: JSONObject? = try { Jobs.printerStatus(target.id, p.ifEmpty { null }) } catch (_: Exception) { null }
                ui.post { if (!closed && p == st.printer) showStatus(r) }
            }
        }

        fun showStatus(r: JSONObject?) {
            if (r == null) { infoTv.text = "Printer status unavailable"; infoTv.setTextColor(pal.mut); return }
            if (!r.optBoolean("ok", false)) { infoTv.text = "Print service not reachable on this PC"; infoTv.setTextColor(COL_ERR); return }
            val names = r.optJSONArray("printers")
            if (names != null && names.length() > 0 && !printersBuilt) {
                printersBuilt = true
                val def = if (r.isNull("default")) "" else r.optString("default")
                val all = ArrayList<Pair<String, String>>()
                all.add("" to "Default printer")
                for (i in 0 until names.length()) { val n = names.optString(i); all.add(n to (n + if (n == def) " (default)" else "")) }
                if (all.none { it.first == st.printer }) st.printer = ""
                printerRow.removeAllViews()
                for ((v, l) in all) {
                    val c = chipView(l)
                    c.setOnClickListener { st.printer = v; refreshAll(); status() }
                    redraw.add { styleChip(c, st.printer == v) }
                    printerRow.addView(c, lp(right = 8))
                }
                printerSec.visibility = View.VISIBLE
                refreshAll()
            }
            val pr = if (r.isNull("printer")) "" else r.optString("printer")
            val prob = if (r.isNull("problem")) "" else r.optString("problem")
            if (pr.isEmpty()) { infoTv.text = "No default printer set on the PC"; infoTv.setTextColor(COL_ERR) }
            else if (prob.isNotEmpty()) { infoTv.text = "$pr · $prob"; infoTv.setTextColor(COL_ERR) }
            else { infoTv.text = "$pr · ready"; infoTv.setTextColor(COL_OK) }
            val hint = StringBuilder()
            if (r.has("pypdf") && !r.optBoolean("pypdf", true)) hint.append("Layout, watermark and header/footer need the pypdf package on the PC - run pcprint.py again (it installs it) if they fail.")
            if (r.isNull("engine")) { if (hint.isNotEmpty()) hint.append(' '); hint.append("SumatraPDF is missing on the PC: duplex, colour and paper size are ignored.") }
            hintTv.text = hint.toString(); hintTv.visibility = if (hint.isEmpty()) View.GONE else View.VISIBLE
        }

        // ---------------------------------------------------------- preview data
        fun startPreviewLoads() {
            if (imgMode) {
                for (i in files.indices) pool.execute {
                    val bytes = fetch(files[i].url, 20_000_000L, 20000)
                    val bm = bytes?.let { try { decodeThumb(it, 360) } catch (_: Throwable) { null } }
                    ui.post {
                        if (closed) return@post
                        thumbs[i] = bm; thumbDone[i] = true
                        thumbViews[i]?.setImageBitmap(bm)
                        sched()
                    }
                }
            } else if (files.isNotEmpty()) {
                val f = files[0]
                pool.execute {
                    val i = loadInfo(f)
                    ui.post { if (!closed) { info = i; sched() } }
                }
            } else sched()
        }

        fun loadInfo(f: PrintFile): Info {
            val e = ext(f.name)
            try {
                if (e in IMG_EXT) {
                    val b = fetch(f.url, 20_000_000L, 20000)
                    return Info("img", 1, b?.let { decodeThumb(it, 700) })
                }
                if (e == "pdf") {
                    val tmp = File(act.cacheDir, "printpv_" + System.nanoTime() + ".pdf")
                    try {
                        if (!fetchToFile(f.url, tmp, 60_000_000L, 25000)) return Info("doc", 0, null)
                        val fd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
                        try { val r = PdfRenderer(fd); try { return Info("doc", r.pageCount, null) } finally { r.close() } } finally { fd.close() }
                    } finally { tmp.delete() }
                }
                if (e in TXT_EXT) {
                    val b = fetch(f.url, 8_000_000L, 25000) ?: return Info("doc", 0, null)
                    var lines = 0
                    for (l in String(b, Charsets.UTF_8).replace("\r", "").split('\n')) lines += max(1, (l.length + 81) / 82)
                    return Info("doc", max(1, (lines + 60) / 61), null)
                }
            } catch (_: Throwable) { return Info("doc", 0, null) }
            return Info("none", 0, null)
        }

        // ---------------------------------------------------------- preview drawing
        fun drawPv() {
            if (closed) return
            pv.setMono(st.color == "mono")
            if (imgMode) drawImgPv() else drawDocPv()
        }

        fun twoSided() = st.duplex == "long" || st.duplex == "short"

        fun drawImgPv() {
            if (!thumbDone.all { it }) { cap.text = "Loading preview…"; return }
            val dims = thumbs.map { (it?.width ?: 3) to (it?.height ?: 2) }
            val plan = imgSheets(dims, st.rots, st)
            val n = plan.sheets.size
            val shown = min(n, 60)
            val two = twoSided()
            val k = min(220f / plan.sw, 170f / plan.sh)
            val list = ArrayList<SheetDraw>()
            for (s0 in 0 until shown) {
                val label = if (two) "${s0 / 2 + 1}" + (if (s0 % 2 == 1) " back" else " front") else "${s0 + 1}"
                val cells = plan.sheets[s0]
                list.add(SheetDraw(plan.sw, plan.sh, label) { c ->
                    val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                    val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF555555.toInt(); strokeWidth = max(1f, 0.8f / k) }
                    val gp = Paint().apply { color = 0xFFCCCCCC.toInt() }
                    for (m in cells) {
                        val bm = thumbs[m.i]
                        val pw = dims[m.i].first * m.k; val ph = dims[m.i].second * m.k
                        c.save(); c.translate(m.cx, m.cy); c.rotate(m.r.toFloat())
                        val rect = RectF(-pw / 2, -ph / 2, pw / 2, ph / 2)
                        if (bm != null) c.drawBitmap(bm, null, rect, p) else c.drawRect(rect, gp)
                        c.restore()
                        if (st.border) c.drawRect(m.cx - m.w / 2, m.cy - m.h / 2, m.cx + m.w / 2, m.cy + m.h / 2, bp)
                    }
                })
            }
            pv.setSheets(list, 220f, 170f)
            val paper = if (two) (n + 1) / 2 else n
            cap.text = "${files.size} picture${if (files.size == 1) "" else "s"} → $n side${if (n == 1) "" else "s"}, $paper sheet${if (paper == 1) "" else "s"} of paper" +
                (if (st.copies > 1) " × ${st.copies}" else "") + (if (n > shown) " (first $shown shown)" else "") + " · watermark / header / footer are not shown here"
        }

        fun drawDocPv() {
            pv.setSheets(emptyList(), 190f, 138f)
            val f = files.firstOrNull()
            if (f == null) { cap.text = "Folders are expanded when printing - no preview"; return }
            val inf = info
            if (inf == null) { cap.text = "Loading preview…"; return }
            if (inf.kind == "none") { cap.text = "No layout preview for this file type - only printer and copies apply"; return }
            val total = if (inf.pages > 0) inf.pages else 6
            val sample = inf.pages <= 0
            val name = f.name
            var idx: MutableList<Int> = pageSet(st.range, total)
            if (st.pages.isNotEmpty()) idx = idx.filter { (it + 1) % 2 == (if (st.pages == "odd") 1 else 0) }.toMutableList()
            if (st.reverse) idx.reverse()
            if (idx.isEmpty()) { cap.text = "No pages left after the page selection"; return }
            val order: List<Int?>; val cols: Int; val rows: Int; val land: Boolean
            if (st.booklet) { val src = idx; order = booklet(src.size).map { x -> x?.let { src[it] } }; cols = 2; rows = 1; land = true }
            else { order = idx; val l = LAY[st.nup] ?: LAY[1]!!; cols = l[0]; rows = l[1]; land = l[2] != 0 }
            val per = cols * rows
            val pd = PAPER[st.paper] ?: PAPER["A4"]!!
            val im = inf.img
            val iland = im != null && im.width > im.height
            val pw0 = if (iland) pd[1] else pd[0]
            val ph0 = if (iland) pd[0] else pd[1]
            val sw: Float; val sh: Float
            if (per == 1) { sw = pw0; sh = ph0 } else { sw = if (land) pd[1] else pd[0]; sh = if (land) pd[0] else pd[1] }
            val k = min(190f / sw, 138f / sh)
            val nSheets = (order.size + per - 1) / per
            val shown = min(nSheets, 60)
            val two = twoSided() || st.booklet
            val list = ArrayList<SheetDraw>()
            for (s0 in 0 until shown) {
                val chunk = order.subList(s0 * per, min(order.size, s0 * per + per))
                val label = if (two) "${s0 / 2 + 1}" + (if (s0 % 2 == 1) " back" else " front") else "${s0 + 1}"
                list.add(SheetDraw(sw, sh, label) { c -> drawDocSheet(c, chunk, s0, nSheets, sw, sh, k, cols, rows, per, pw0, ph0, name, im) })
            }
            pv.setSheets(list, 190f, 138f)
            val paper = if (two) (nSheets + 1) / 2 else nSheets
            cap.text = "$total page${if (total == 1) "" else "s"}" + (if (sample) " (sample - count unknown)" else "") +
                (if (order.size != total) " → ${order.size} printed" else "") + " → $nSheets side${if (nSheets == 1) "" else "s"}, $paper sheet${if (paper == 1) "" else "s"} of paper" +
                (if (st.copies > 1) " × ${st.copies}" else "") + (if (nSheets > shown) " (first $shown shown)" else "") +
                (if (files.size > 1) " · previewing $name (+${files.size - 1} more)" else "")
        }

        fun drawDocSheet(c: Canvas, chunk: List<Int?>, s0: Int, nSheets: Int, sw: Float, sh: Float, k: Float, cols: Int, rows: Int, per: Int,
                         pw0: Float, ph0: Float, name: String, im: Bitmap?) {
            val cw = sw / cols; val ch = sh / rows
            val mg = if (per == 1) 0f else if (st.booklet) 8f else 12f
            class C(val pi: Int, val sc: Float, val x: Float, val y: Float)
            val cells = ArrayList<C>()
            for ((j, pi) in chunk.withIndex()) {
                if (pi == null) continue
                val sc = if (per == 1) 1f else min((cw - 2 * mg) / pw0, (ch - 2 * mg) / ph0)
                cells.add(C(pi, sc, (j % cols) * cw + (cw - pw0 * sc) / 2, (j / cols) * ch + (ch - ph0 * sc) / 2))
            }
            val lw = max(1f, 1f / k * 0.8f)
            val white = Paint().apply { color = Color.WHITE }
            val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFCFCFCF.toInt(); strokeWidth = lw }
            fun watermark() {
                if (st.wm.isEmpty()) return
                val t = fill(st.wm, 0, nSheets, name)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER; color = 0xFF808080.toInt(); alpha = 71 }
                p.textSize = 100f
                val w1 = p.measureText(t) / 100f
                val size = max(8f, min(150f, hypot(sw, sh) * 0.7f / (if (w1 <= 0f) 1f else w1)))
                p.textSize = size
                val fm = p.fontMetrics
                c.save(); c.translate(sw / 2, sh / 2); c.rotate(-Math.toDegrees(atan2(sh.toDouble(), sw.toDouble())).toFloat())
                c.drawText(t, 0f, -(fm.ascent + fm.descent) / 2, p)
                c.restore()
            }
            for (m in cells) { c.drawRect(m.x, m.y, m.x + pw0 * m.sc, m.y + ph0 * m.sc, white); c.drawRect(m.x, m.y, m.x + pw0 * m.sc, m.y + ph0 * m.sc, edge) }
            if (st.wm.isNotEmpty() && st.wmUnder) watermark()
            val line = Paint().apply { color = 0xFFD6D6D6.toInt() }
            val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textSize = 260f; textAlign = Paint.Align.CENTER; color = 0xFF9AA7BD.toInt(); alpha = 140 }
            val bmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            for (m in cells) {
                c.save(); c.translate(m.x, m.y); c.scale(m.sc, m.sc)
                if (im != null) {
                    val a = 28f; val fw = pw0 - 2 * a; val fh = ph0 - 2 * a
                    val q = min(fw / im.width, fh / im.height)
                    val w = im.width * q; val h = im.height * q
                    c.drawBitmap(im, null, RectF((pw0 - w) / 2, (ph0 - h) / 2, (pw0 + w) / 2, (ph0 + h) / 2), bmp)
                } else {
                    for (l in 0 until 22) {
                        val w = (pw0 - 120) * (0.55f + 0.45f * ((((m.pi + 1) * (l + 3) * 37) % 100) / 100f))
                        c.drawRect(60f, 70f + l * 30, 60f + (if (l % 7 == 6) w * 0.5f else w), 79f + l * 30, line)
                    }
                    val fm = num.fontMetrics
                    c.drawText((m.pi + 1).toString(), pw0 / 2, ph0 / 2 - (fm.ascent + fm.descent) / 2, num)
                }
                c.restore()
            }
            if (!(st.wm.isNotEmpty() && st.wmUnder)) watermark()
            if (st.border && per > 1) {
                val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF555555.toInt(); strokeWidth = lw }
                for (m in cells) c.drawRect(m.x, m.y, m.x + pw0 * m.sc, m.y + ph0 * m.sc, bp)
            }
            val fs = max(9f, 5.5f / k)
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF333333.toInt(); textSize = fs }
            fun zone(txt: String, y: Float) {
                if (txt.isEmpty()) return
                val pr = txt.split('|')
                val z = if (pr.size == 1) listOf("", pr[0], "") else listOf(pr.getOrElse(0) { "" }, pr.getOrElse(1) { "" }, pr.getOrElse(2) { "" })
                val xs = floatArrayOf(28f, sw / 2, sw - 28f)
                val al = arrayOf(Paint.Align.LEFT, Paint.Align.CENTER, Paint.Align.RIGHT)
                for (i2 in 0 until 3) {
                    val t = fill(z[i2].trim(), s0, nSheets, name)
                    if (t.isEmpty()) continue
                    tp.textAlign = al[i2]
                    c.drawText(t, xs[i2], y, tp)
                }
            }
            zone(st.hdr, 22 + fs * 0.6f); zone(st.ftr, sh - 14)
        }
    }
}
