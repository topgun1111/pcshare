package com.lanshare.app

import android.app.Activity
import android.app.AlertDialog
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
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.ParcelFileDescriptor
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.exifinterface.media.ExifInterface
import com.lanshare.app.core.Jobs
import com.lanshare.app.core.Source
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Calendar
import java.util.concurrent.Executors

/** Colours of the host screen (same palette as [BrowserActivity]). */
class Pal(val card: Int, val fg: Int, val mut: Int, val div: Int, val accent: Int)

/**
 * "Print on <PC>" options dialog: native port of the web UI's printOptions() - printer, copies, sides, colour, pages per sheet,
 * booklet, page selection, paper, scaling, watermark, header/footer, presets and a live sheet preview. Pictures only: they are laid
 * out together on shared sheets (sheet=1, per-picture turn, auto-turn) exactly like the web UI did.
 * The result ([result]) is the opts object [Jobs.startPrint] hands to pcprint.py (null = cancelled).
 * The preview layout maths (pvSheets / pvBooklet / pvPageSet) is a 1:1 port; the picture layout MUST stay identical to
 * images_to_sheets() in pcprint.py.
 */
class PcPrintDialog(
    private val act: Activity,
    private val pcId: String,
    private val pcName: String,
    private val names: List<String>,
    private val paths: List<String>,
    private val opener: (String) -> Source,
    private val ui: Handler,
    private val pal: Pal,
    private val result: (JSONObject?) -> Unit
) {
    private companion object {
        val DEF: Map<String, Any> = linkedMapOf<String, Any>("printer" to "", "copies" to 1, "duplex" to "", "color" to "", "nup" to 1,
            "booklet" to 0, "border" to 0, "pages" to "", "range" to "", "reverse" to 0, "paper" to "", "fit" to "shrink",
            "wm" to "", "wm_under" to 0, "hdr" to "", "ftr" to "", "noauto" to 0)
        val BUILTIN: Map<String, Map<String, Any>> = linkedMapOf(
            "Normal" to emptyMap<String, Any>(),
            "Save paper" to mapOf<String, Any>("nup" to 2, "duplex" to "long", "color" to "mono"),
            "Booklet" to mapOf<String, Any>("booklet" to 1, "duplex" to "short"),
            "Draft" to mapOf<String, Any>("color" to "mono", "nup" to 2))
        val PAPER: Map<String, Pair<Int, Int>> = mapOf("A3" to (842 to 1191), "A4" to (595 to 842), "A5" to (420 to 595),
            "Letter" to (612 to 792), "Legal" to (612 to 1008))
        /** pages per sheet -> (columns, rows, landscape) for documents */
        val LAY: Map<Int, Triple<Int, Int, Int>> = mapOf(1 to Triple(1, 1, 0), 2 to Triple(2, 1, 1), 4 to Triple(2, 2, 0),
            6 to Triple(3, 2, 1), 9 to Triple(3, 3, 0))
        /** pictures per sheet -> (columns, rows) on a portrait / landscape sheet */
        val GRID: Map<Int, List<Pair<Int, Int>>> = mapOf(1 to listOf(1 to 1, 1 to 1), 2 to listOf(1 to 2, 2 to 1), 4 to listOf(2 to 2, 2 to 2),
            6 to listOf(2 to 3, 3 to 2), 9 to listOf(3 to 3, 3 to 3))
        val PV_IMG = setOf("png", "jpg", "jpeg", "bmp", "gif", "tif", "tiff")
        val PV_TXT = setOf("txt", "log", "md")
        const val ERR = 0xFFD9534F.toInt()
        const val OK = 0xFF2E9E5B.toInt()
    }

    private val bg = Executors.newFixedThreadPool(2) { r -> Thread(r, "printdlg").also { it.isDaemon = true } }
    private val prefs = act.getSharedPreferences("ls_print", Context.MODE_PRIVATE)
    private val dens = act.resources.displayMetrics.density
    private fun dp(v: Int) = (v * dens).toInt()

    private val st = HashMap<String, Any>()
    private val rots = IntArray(names.size)
    private val imgMode = names.isNotEmpty() && names.all { ext(it) in PV_IMG }
    private val redraw = ArrayList<() -> Unit>()
    @Volatile private var closed = false

    private fun gs(k: String): String = st[k] as String
    private fun gi(k: String): Int = st[k] as Int
    private fun ext(n: String) = n.substringAfterLast('.', "").lowercase()

    // preview data
    private class DocInfo(val kind: String, val pages: Int, val img: Bitmap?)   // kind: img | doc | none
    private var pinfo: DocInfo? = null
    private val thumbs = arrayOfNulls<Bitmap>(names.size)
    private var thumbsDone = 0

    private lateinit var info: TextView
    private lateinit var hint: TextView
    private lateinit var strip: LinearLayout
    private lateinit var cap: TextView
    private lateinit var spin: Spinner
    private lateinit var spinBox: LinearLayout
    private lateinit var spinAd: ArrayAdapter<String>
    private lateinit var presetRow: LinearLayout
    private val prNames = ArrayList<String>()
    private var spinBusy = false
    private val tileImgs = ArrayList<ImageView>()
    private val pvRun = Runnable { drawPv() }

    init {
        st.putAll(DEF)
        try { merge(JSONObject(prefs.getString("printOpts", "{}") ?: "{}")) } catch (_: Exception) {}
        st["range"] = ""
        if (imgMode) st["booklet"] = 0
    }

    private fun merge(o: JSONObject) {
        for ((k, d) in DEF) if (o.has(k) && !o.isNull(k)) st[k] = if (d is Int) o.optInt(k, d) else o.optString(k, d as String)
    }

    private fun userPresets(): JSONObject = try { JSONObject(prefs.getString("printPresets", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }

    private fun presetMap(o: JSONObject): Map<String, Any> {
        val m = HashMap<String, Any>()
        for ((k, d) in DEF) if (o.has(k) && !o.isNull(k)) m[k] = if (d is Int) o.optInt(k, d) else o.optString(k, d as String)
        return m
    }

    private fun applyPreset(p: Map<String, Any>) {
        val keep = gs("printer")
        st.putAll(DEF); st.putAll(p); st["printer"] = keep
        redraw.forEach { it() }
    }

    private fun sched() { ui.removeCallbacks(pvRun); ui.postDelayed(pvRun, 90) }

    private fun finish(v: JSONObject?) {
        if (closed) return
        closed = true
        ui.removeCallbacks(pvRun)
        bg.shutdownNow()
        result(v)
    }

    /** What is sent to pcprint.py: only options that differ from the defaults (same rules as the web UI). */
    private fun collect(): JSONObject {
        val out = JSONObject()
        for ((k, d) in DEF) { val v = st[k]; if (v != d && v != "" && v != 0) out.put(k, v) }
        if (imgMode) {
            out.put("sheet", 1); out.put("rots", JSONArray(rots.toList()))
            out.remove("booklet"); out.remove("range"); out.remove("pages")
        } else if (gi("booklet") != 0 && gs("duplex").isEmpty()) out.put("duplex", "short")
        val keep = JSONObject()
        for (k in DEF.keys) keep.put(k, st[k])
        keep.remove("range")
        prefs.edit().putString("printOpts", keep.toString()).apply()
        return out
    }

    // ------------------------------------------------------------------ small view helpers

    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

    private fun tv(t: String, size: Float, color: Int) = TextView(act).apply { text = t; textSize = size; setTextColor(color) }

    private fun styleChip(b: TextView, on: Boolean) {
        b.setTextColor(if (on) Color.WHITE else pal.fg)
        b.background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(if (on) pal.accent else pal.card); setStroke(1, pal.div) }
    }

    private fun chipView(label: String): TextView = TextView(act).apply {
        text = label; textSize = 13f; maxLines = 1; gravity = Gravity.CENTER
        setPadding(dp(14), dp(7), dp(14), dp(7))
        styleChip(this, false)
    }

    private fun scrollRow(row: LinearLayout): View = HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(row) }

    private fun addChip(row: LinearLayout, b: View) {
        row.addView(b, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) })
    }

    private fun sec(box: LinearLayout, title: String, vararg kids: View): LinearLayout {
        val d = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, 0) }
        if (title.isNotEmpty()) d.addView(tv(title, 12f, pal.mut).apply { setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, dp(4)) })
        for (k in kids) d.addView(k)
        box.addView(d)
        return d
    }

    private fun seg(key: String, opts: List<Pair<Any, String>>): View {
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        for ((v, l) in opts) {
            val b = chipView(l)
            b.setOnClickListener { st[key] = v; redraw.forEach { it() } }
            redraw.add { styleChip(b, st[key] == v) }
            addChip(row, b)
        }
        return scrollRow(row)
    }

    private fun sw(key: String, label: String): View {
        val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
        val sv = Switch(act)
        r.addView(tv(label, 14f, pal.fg), LinearLayout.LayoutParams(0, WRAP, 1f))
        r.addView(sv)
        var busy = false
        sv.setOnCheckedChangeListener { _, c -> if (!busy) { st[key] = if (c) 1 else 0; redraw.forEach { it() } } }
        redraw.add { val want = gi(key) != 0; if (sv.isChecked != want) { busy = true; sv.isChecked = want; busy = false } }
        return r
    }

    private fun txt(key: String, hintText: String): EditText {
        val e = EditText(act).apply {
            hint = hintText; setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT; textSize = 14f
            setTextColor(pal.fg); setHintTextColor(pal.mut)
        }
        var busy = false
        e.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun afterTextChanged(ed: Editable?) { if (!busy) { st[key] = ed?.toString() ?: ""; sched() } }
        })
        redraw.add { val want = gs(key); if (e.text.toString() != want) { busy = true; e.setText(want); busy = false } }
        return e
    }

    // ------------------------------------------------------------------ dialog

    fun show() {
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(6), dp(18), dp(8)) }
        info = tv("Checking printer…", 12f, pal.mut)
        box.addView(info)

        // preview
        strip = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        cap = tv("", 12f, pal.mut).apply { setPadding(dp(2), dp(4), dp(2), 0) }
        val pv = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0) }
        pv.addView(HorizontalScrollView(act).apply { addView(strip) })
        pv.addView(cap)
        box.addView(pv)
        redraw.add { sched() }

        // presets
        presetRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        drawPresets()
        sec(box, "Presets", scrollRow(presetRow))

        // printer
        spinAd = ArrayAdapter(act, android.R.layout.simple_spinner_dropdown_item, ArrayList<String>().apply { add("Default printer") })
        spin = Spinner(act).apply { adapter = spinAd }
        spin.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (spinBusy) return
                val want = if (pos <= 0 || pos - 1 >= prNames.size) "" else prNames[pos - 1]
                if (want != gs("printer")) { st["printer"] = want; status() }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        spinBox = sec(box, "Printer", spin)
        spinBox.visibility = View.GONE
        hint = tv("", 12f, pal.mut).apply { visibility = View.GONE; setPadding(0, dp(4), 0, 0) }

        // copies
        val cp = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val mi = chipView("−"); val pl = chipView("+"); val cn = tv("1", 16f, pal.fg).apply { gravity = Gravity.CENTER; minWidth = dp(36) }
        mi.setOnClickListener { st["copies"] = maxOf(1, gi("copies") - 1); redraw.forEach { it() } }
        pl.setOnClickListener { st["copies"] = minOf(99, gi("copies") + 1); redraw.forEach { it() } }
        redraw.add { cn.text = gi("copies").toString() }
        cp.addView(mi); cp.addView(cn); cp.addView(pl)
        sec(box, "Copies", cp)

        sec(box, "Sides", seg("duplex", listOf("" to "Printer default", "off" to "One-sided", "long" to "Long edge", "short" to "Short edge")))
        sec(box, "Colour", seg("color", listOf("" to "Default", "color" to "Colour", "mono" to "Black & white")))
        val perSheet = ArrayList<View>()
        perSheet.add(seg("nup", listOf(1 to "1", 2 to "2", 4 to "4", 6 to "6", 9 to "9")))
        perSheet.add(sw("border", if (imgMode) "Border around each picture" else "Border around each page"))
        if (!imgMode) perSheet.add(sw("booklet", "Booklet (folded, two-sided on short edge)"))
        sec(box, if (imgMode) "Pictures per sheet" else "Pages per sheet", *perSheet.toTypedArray())

        if (imgMode) {
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in names.indices) {
                val tile = FrameLayout(act).apply { background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(pal.card); setStroke(1, pal.div) } }
                val im = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_INSIDE; setPadding(dp(4), dp(4), dp(4), dp(4)) }
                val lb = tv("↻", 11f, Color.WHITE).apply { setPadding(dp(4), 0, dp(4), 0); setBackgroundColor(0x99000000.toInt()) }
                tile.addView(im, FrameLayout.LayoutParams(MATCH, MATCH))
                tile.addView(lb, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END))
                tile.contentDescription = "Turn " + names[i]
                tile.setOnClickListener { rots[i] = (rots[i] + 90) % 360; redraw.forEach { it() } }
                tileImgs.add(im)
                redraw.add { im.rotation = rots[i].toFloat(); lb.text = if (rots[i] != 0) "${rots[i]}°" else "↻" }
                row.addView(tile, LinearLayout.LayoutParams(dp(64), dp(64)).apply { rightMargin = dp(6) })
            }
            val auto = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
            val asw = Switch(act)
            auto.addView(tv("Auto-turn pictures to fill the sheet", 14f, pal.fg), LinearLayout.LayoutParams(0, WRAP, 1f)); auto.addView(asw)
            var busy = false
            asw.setOnCheckedChangeListener { _, c -> if (!busy) { st["noauto"] = if (c) 0 else 1; redraw.forEach { it() } } }
            redraw.add { val want = gi("noauto") == 0; if (asw.isChecked != want) { busy = true; asw.isChecked = want; busy = false } }
            sec(box, "Turn pictures (tap = 90° clockwise)", scrollRow(row), auto)
            sec(box, "Order", sw("reverse", "Reverse order"))
        } else {
            sec(box, "Pages", seg("pages", listOf("" to "All", "odd" to "Odd only", "even" to "Even only")),
                txt("range", "Page range, e.g. 1-3,5,8-"), sw("reverse", "Reverse order"))
        }
        sec(box, "Paper", seg("paper", listOf("" to "Auto", "A4" to "A4", "Letter" to "Letter", "A3" to "A3", "A5" to "A5", "Legal" to "Legal")))
        if (!imgMode) sec(box, "Scaling", seg("fit", listOf("shrink" to "Shrink to fit", "fit" to "Fit", "noscale" to "Actual size")))
        sec(box, "Watermark", txt("wm", "Text, e.g. DRAFT"), sw("wm_under", "Behind the page content"))
        sec(box, "Header / footer", txt("hdr", "Header: left | centre | right"), txt("ftr", "Footer: Page {page} of {pages}"))
        box.addView(tv("Tokens: {page} {pages} {date} {time} {file}. Layout, watermark and header/footer work for PDF, image and text files; other files get printer and copies only.",
            12f, pal.mut).apply { setPadding(0, dp(8), 0, 0) })
        box.addView(hint)

        val scroll = ScrollView(act).apply { addView(box) }
        val dlg = AlertDialog.Builder(act).setTitle("Print on $pcName").setView(scroll)
            .setPositiveButton("Print", null)
            .setNegativeButton("Cancel", null).create()
        dlg.setOnDismissListener { finish(null) }
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { val o = collect(); finish(o); dlg.dismiss() }

        redraw.forEach { it() }
        status()
        if (imgMode) loadThumbs() else if (paths.isNotEmpty()) loadInfo()
    }

    private fun drawPresets() {
        presetRow.removeAllViews()
        val mine = userPresets()
        for ((name, p) in BUILTIN) {
            val b = chipView(name)
            b.setOnClickListener { applyPreset(p) }
            addChip(presetRow, b)
        }
        val keys = mine.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val b = chipView(name)
            b.setOnClickListener { applyPreset(presetMap(mine.getJSONObject(name))) }
            b.setOnLongClickListener { deletePreset(name); true }
            addChip(presetRow, b)
        }
        val add = chipView("+ Save").apply { setTextColor(pal.accent) }
        add.setOnClickListener { savePreset() }
        addChip(presetRow, add)
    }

    private fun savePreset() {
        val et = EditText(act).apply { hint = "Preset name"; setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT }
        val holder = FrameLayout(act).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et) }
        AlertDialog.Builder(act).setTitle("Save preset").setMessage("Saves the options below under a name (press and hold a preset to delete it).")
            .setView(holder)
            .setPositiveButton("Save") { _, _ ->
                val n = et.text.toString().trim().take(24)
                if (n.isNotEmpty()) {
                    val m = userPresets()
                    val c = JSONObject()
                    for (k in DEF.keys) c.put(k, st[k])
                    c.remove("printer"); c.remove("range")
                    m.put(n, c)
                    prefs.edit().putString("printPresets", m.toString()).apply()
                    drawPresets()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun deletePreset(name: String) {
        AlertDialog.Builder(act).setTitle("Delete preset \"$name\"?")
            .setPositiveButton("Delete") { _, _ ->
                val m = userPresets(); m.remove(name)
                prefs.edit().putString("printPresets", m.toString()).apply()
                drawPresets()
            }.setNegativeButton("Cancel", null).show()
    }

    // ------------------------------------------------------------------ printer status (asked live from pcprint.py)

    private fun status() {
        info.text = "Checking printer…"; info.setTextColor(pal.mut)
        val chosen = gs("printer")
        bg.execute {
            val r: JSONObject? = try { Jobs.printerStatus(pcId, chosen.ifEmpty { null }) } catch (_: Exception) { null }
            ui.post { if (!closed) applyStatus(r) }
        }
    }

    private fun applyStatus(r: JSONObject?) {
        if (r == null) { info.text = "Printer status unavailable"; info.setTextColor(pal.mut); return }
        if (!r.optBoolean("ok", false)) { info.text = "Print service not reachable on this PC"; info.setTextColor(ERR); return }
        val list = r.optJSONArray("printers")
        if (list != null && list.length() > 0) {
            spinBox.visibility = View.VISIBLE
            if (spinAd.count < 2) {
                val def = if (r.isNull("default")) "" else r.optString("default")
                spinBusy = true
                for (i in 0 until list.length()) {
                    val n = list.optString(i)
                    prNames.add(n)
                    spinAd.add(n + if (n == def) " (default)" else "")
                }
                spin.setSelection(prNames.indexOf(gs("printer")) + 1)   // not found -> -1 + 1 = 0 = default printer
                spinBusy = false
            }
        }
        val pr = if (r.isNull("printer")) "" else r.optString("printer")
        val problem = if (r.isNull("problem")) "" else r.optString("problem")
        if (pr.isEmpty()) { info.text = "No default printer set on the PC"; info.setTextColor(ERR) }
        else if (problem.isNotEmpty()) { info.text = "$pr · $problem"; info.setTextColor(ERR) }
        else { info.text = "$pr · ready"; info.setTextColor(OK) }
        val h = StringBuilder()
        if (!r.optBoolean("pypdf", false)) h.append("Layout, watermark and header/footer need the pypdf package on the PC - run pcprint.py again (it installs it) if they fail.")
        if (r.isNull("engine")) { if (h.isNotEmpty()) h.append(' '); h.append("SumatraPDF is missing on the PC: duplex, colour and paper size are ignored.") }
        hint.text = h.toString()
        hint.visibility = if (h.isEmpty()) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------------ preview data

    private fun readCap(path: String, cap: Long): ByteArray? = try {
        opener(path).use { src ->
            if (src.size > cap) null else {
                val out = ByteArrayOutputStream(minOf(maxOf(src.size, 1024L), cap).toInt())
                val buf = ByteArray(64 * 1024)
                var bad = false
                while (true) {
                    val k = src.read(buf)
                    if (k < 0) break
                    out.write(buf, 0, k)
                    if (out.size() > cap) { bad = true; break }
                }
                if (bad) null else out.toByteArray()
            }
        }
    } catch (_: Throwable) { null }

    private fun turn(bm: Bitmap, ori: Int): Bitmap {
        val m = Matrix()
        when (ori) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> { m.setRotate(180f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
            else -> return bm
        }
        return try { Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, m, true) } catch (_: Throwable) { bm }
    }

    /** Picture as it will look on paper (EXIF turn applied), at most [maxDim] px on the long side. */
    private fun decodeThumb(path: String, maxDim: Int): Bitmap? {
        val b = readCap(path, 24L shl 20) ?: return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var sm = 1
        while (maxOf(o.outWidth, o.outHeight) / (sm * 2) >= maxDim) sm *= 2
        val bm = BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = sm }) ?: return null
        val ori = try { ExifInterface(ByteArrayInputStream(b)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                  catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }
        return turn(bm, ori)
    }

    private fun loadThumbs() {
        val maxDim = if (names.size > 40) 128 else 256
        bg.execute {
            for (i in names.indices) {
                if (closed) return@execute
                val bm = try { decodeThumb(paths[i], maxDim) } catch (_: Throwable) { null }
                ui.post {
                    if (closed) return@post
                    thumbs[i] = bm
                    if (bm != null && i < tileImgs.size) tileImgs[i].setImageBitmap(bm)
                    thumbsDone++
                    sched()
                }
            }
        }
    }

    private fun loadInfo() {
        val e = ext(names[0]); val path = paths[0]
        bg.execute {
            val i: DocInfo = try {
                when {
                    e in PV_IMG -> DocInfo("img", 1, decodeThumb(path, 512))
                    e == "pdf" -> DocInfo("doc", pdfPages(path), null)
                    e in PV_TXT -> DocInfo("doc", txtPages(path), null)
                    else -> DocInfo("none", 0, null)
                }
            } catch (_: Throwable) { DocInfo("doc", 0, null) }
            ui.post { if (!closed) { pinfo = i; sched() } }
        }
    }

    private fun pdfPages(path: String): Int {
        val dir = File(act.cacheDir, "print").apply { mkdirs() }
        val f = File(dir, "pv_" + System.nanoTime() + ".pdf")
        return try {
            var n = 0
            opener(path).use { src ->
                if (src.size > 60_000_000L) return 0
                f.outputStream().use { o -> src.copyTo(o, 64 * 1024) }
            }
            val fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            try { val r = PdfRenderer(fd); n = r.pageCount; r.close() } finally { try { fd.close() } catch (_: Exception) {} }
            n
        } catch (_: Throwable) { 0 } finally { f.delete() }
    }

    private fun txtPages(path: String): Int {
        val b = readCap(path, 16L shl 20) ?: return 0
        var lines = 0
        for (l in String(b, Charsets.UTF_8).replace("\r", "").split('\n')) lines += maxOf(1, Math.ceil(l.length / 82.0).toInt())
        return maxOf(1, Math.ceil(lines / 61.0).toInt())
    }

    // ------------------------------------------------------------------ layout maths (1:1 with the web UI / pcprint.py)

    private fun pageSet(rng: String, n: Int): List<Int> {
        val all = (0 until n).toList()
        if (rng.trim().isEmpty()) return all
        val out = ArrayList<Int>()
        val re = Regex("^(\\d*)(-?)(\\d*)$")
        for (part in rng.replace(" ", "").split(',')) {
            if (part.isEmpty()) continue
            val m = re.find(part) ?: continue
            val g1 = m.groupValues[1]; val dash = m.groupValues[2]; val g3 = m.groupValues[3]
            var a: Int
            var b: Int
            if (dash.isNotEmpty()) {
                a = if (g1.isNotEmpty()) (g1.toIntOrNull() ?: Int.MAX_VALUE) else 1
                b = if (g3.isNotEmpty()) (g3.toIntOrNull() ?: Int.MAX_VALUE) else n
            } else {
                if (g1.isEmpty()) continue
                a = g1.toIntOrNull() ?: Int.MAX_VALUE; b = a
            }
            a = a.coerceIn(1, n); b = b.coerceIn(1, n)
            if (a <= b) for (i in a..b) out.add(i - 1) else for (i in a downTo b) out.add(i - 1)
        }
        return if (out.isEmpty()) all else out
    }

    private fun booklet(n: Int): List<Int?> {
        val m = (n + 3) / 4 * 4
        val q = ArrayList<Int?>()
        for (i in 0 until m / 4) for (x in intArrayOf(m - 1 - 2 * i, 2 * i, 2 * i + 1, m - 2 - 2 * i)) q.add(if (x < n) x else null)
        return q
    }

    private fun fill(t: String, i: Int, n: Int, name: String): String {
        val d = Calendar.getInstance()
        fun z(x: Int) = if (x < 10) "0$x" else "$x"
        return t.replace("{page}", (i + 1).toString()).replace("{pages}", n.toString())
            .replace("{date}", "${d.get(Calendar.YEAR)}-${z(d.get(Calendar.MONTH) + 1)}-${z(d.get(Calendar.DAY_OF_MONTH))}")
            .replace("{time}", z(d.get(Calendar.HOUR_OF_DAY)) + ":" + z(d.get(Calendar.MINUTE)))
            .replace("{file}", name)
    }

    private class Cell(val i: Int, val r: Int, val k: Float, val w: Float, val h: Float, val cx: Float, val cy: Float)
    private class Plan(val sw: Float, val sh: Float, val sheets: List<List<Cell>>, val score: Float)

    private fun plan(dims: List<Pair<Int, Int>>, portrait: Boolean): Plan {
        val pd = PAPER[gs("paper")] ?: PAPER["A4"]!!
        val mg = 14f; val gap = 8f
        val per = if (GRID.containsKey(gi("nup"))) gi("nup") else 1
        val order = dims.indices.toMutableList()
        if (gi("reverse") != 0) order.reverse()
        val sw = (if (portrait) pd.first else pd.second).toFloat()
        val sh = (if (portrait) pd.second else pd.first).toFloat()
        val g = GRID[per]!![if (portrait) 0 else 1]
        val cols = g.first; val rows = g.second
        val cw = (sw - 2 * mg - gap * (cols - 1)) / cols
        val ch = (sh - 2 * mg - gap * (rows - 1)) / rows
        val sheets = ArrayList<List<Cell>>()
        var score = 0f
        var s0 = 0
        while (s0 < order.size) {
            val cells = ArrayList<Cell>()
            val sub = order.subList(s0, minOf(s0 + cols * rows, order.size))
            for ((j, i) in sub.withIndex()) {
                var bestK = -1f; var bestR = 0; var bestW = 0f; var bestH = 0f
                val extra = if (gi("noauto") != 0) listOf(0) else listOf(0, 90)
                for (ex in extra) {
                    val r = (rots[i] + ex) % 360
                    val w = dims[i].first.toFloat(); val h = dims[i].second.toFloat()
                    val dw = if (r % 180 != 0) h else w
                    val dh = if (r % 180 != 0) w else h
                    val k = minOf(cw / dw, ch / dh)
                    if (bestK < 0f || k > bestK * 1.0001f) { bestK = k; bestR = r; bestW = dw; bestH = dh }
                }
                score += bestK * bestK * bestW * bestH
                cells.add(Cell(i, bestR, bestK, bestW * bestK, bestH * bestK,
                    mg + (j % cols) * (cw + gap) + cw / 2, mg + (j / cols) * (ch + gap) + ch / 2))
            }
            sheets.add(cells)
            s0 += cols * rows
        }
        return Plan(sw, sh, sheets, score)
    }

    private fun pvSheets(dims: List<Pair<Int, Int>>): Plan {
        val a = plan(dims, true); val b = plan(dims, false)
        return if (b.score > a.score * 1.0001f) b else a
    }

    // ------------------------------------------------------------------ preview drawing

    private val grayPaint = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }

    /** One paper side, drawn in sheet units (PDF points) and scaled to [k] dp per unit. */
    private inner class Pv(val sw: Float, val sh: Float, val k: Float, val draw: (Canvas) -> Unit) : View(act) {
        private val sc = k * dens
        private val frame = Paint().apply { style = Paint.Style.STROKE; color = pal.div; strokeWidth = 1f }
        override fun onMeasure(w: Int, h: Int) { setMeasuredDimension(Math.round(sw * sc), Math.round(sh * sc)) }
        override fun onDraw(c: Canvas) {
            c.save()
            c.scale(sc, sc)
            c.drawRect(0f, 0f, sw, sh, Paint().apply { color = Color.WHITE })
            draw(c)
            c.restore()
            c.drawRect(0f, 0f, width - 1f, height - 1f, frame)
        }
    }

    private fun addSheet(v: Pv, label: String) {
        if (gs("color") == "mono") v.setLayerType(View.LAYER_TYPE_HARDWARE, grayPaint)
        val w = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        w.addView(v)
        w.addView(tv(label, 11f, pal.mut))
        strip.addView(w, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(10) })
    }

    private fun sideLabel(s0: Int, two: Boolean) = if (two) "${s0 / 2 + 1}" + (if (s0 % 2 == 1) " back" else " front") else "${s0 + 1}"

    private fun drawPv() {
        if (closed) return
        strip.removeAllViews()
        if (imgMode) { drawImgPv(); return }
        if (paths.isEmpty()) { cap.text = "Nothing to preview"; return }
        val p = pinfo
        if (p == null) { cap.text = "Loading preview…"; return }
        if (p.kind == "none") { cap.text = "No layout preview for this file type - only printer and copies apply"; return }
        val total = if (p.pages > 0) p.pages else 6
        val sample = p.pages <= 0
        val name = names[0]
        var idx: List<Int> = pageSet(gs("range"), total)
        if (gs("pages").isNotEmpty()) { val want = if (gs("pages") == "odd") 1 else 0; idx = idx.filter { (it + 1) % 2 == want } }
        if (gi("reverse") != 0) idx = idx.reversed()
        if (idx.isEmpty()) { cap.text = "No pages left after the page selection"; return }
        val order: List<Int?>
        val cols: Int; val rows: Int; val land: Int
        val bk = gi("booklet") != 0
        if (bk) { val ix = idx; order = booklet(ix.size).map { if (it == null) null else ix[it] }; cols = 2; rows = 1; land = 1 }
        else { order = idx; val l = LAY[gi("nup")] ?: LAY[1]!!; cols = l.first; rows = l.second; land = l.third }
        val per = cols * rows
        val pd = PAPER[gs("paper")] ?: PAPER["A4"]!!
        val im = p.img
        val iland = im != null && im.width > im.height
        val pw0 = (if (iland) pd.second else pd.first).toFloat()
        val ph0 = (if (iland) pd.first else pd.second).toFloat()
        val sw: Float; val sh: Float
        if (per == 1) { sw = pw0; sh = ph0 } else { sw = (if (land != 0) pd.second else pd.first).toFloat(); sh = (if (land != 0) pd.first else pd.second).toFloat() }
        val k = minOf(190f / sw, 138f / sh)
        val nSheets = Math.ceil(order.size / per.toDouble()).toInt()
        val shown = minOf(nSheets, 60)
        val two = gs("duplex") == "long" || gs("duplex") == "short" || bk
        val wmText = gs("wm"); val under = gi("wm_under") != 0; val border = gi("border") != 0
        val hdr = gs("hdr"); val ftr = gs("ftr")
        for (s0 in 0 until shown) {
            val chunk = order.drop(s0 * per).take(per)
            val cw = sw / cols; val ch = sh / rows
            val mg = if (per == 1) 0f else if (bk) 8f else 12f
            class PCell(val pi: Int, val sc: Float, val x: Float, val y: Float)
            val cells = ArrayList<PCell>()
            for ((j, pi) in chunk.withIndex()) {
                if (pi == null) continue
                val sc = if (per == 1) 1f else minOf((cw - 2 * mg) / pw0, (ch - 2 * mg) / ph0)
                cells.add(PCell(pi, sc, (j % cols) * cw + (cw - pw0 * sc) / 2, (j / cols) * ch + (ch - ph0 * sc) / 2))
            }
            addSheet(Pv(sw, sh, k) { c ->
                val lw = maxOf(1f, 0.8f / k)
                val line = Paint().apply { style = Paint.Style.STROKE; color = 0xFFCFCFCF.toInt(); strokeWidth = lw }
                val white = Paint().apply { color = Color.WHITE }
                for (m in cells) { c.drawRect(m.x, m.y, m.x + pw0 * m.sc, m.y + ph0 * m.sc, white); c.drawRect(m.x, m.y, m.x + pw0 * m.sc, m.y + ph0 * m.sc, line) }
                if (wmText.isNotEmpty() && under) drawWm(c, sw, sh, nSheets, name)
                for (m in cells) {
                    c.save(); c.translate(m.x, m.y); c.scale(m.sc, m.sc)
                    if (im != null) {
                        val a = 28f; val fw = pw0 - 2 * a; val fh = ph0 - 2 * a
                        val q = minOf(fw / im.width, fh / im.height)
                        val w = im.width * q; val h = im.height * q
                        c.drawBitmap(im, null, RectF((pw0 - w) / 2, (ph0 - h) / 2, (pw0 - w) / 2 + w, (ph0 - h) / 2 + h), Paint(Paint.FILTER_BITMAP_FLAG))
                    } else {
                        val bar = Paint().apply { color = 0xFFD6D6D6.toInt() }
                        for (l in 0 until 22) {
                            val w = (pw0 - 120) * (0.55f + 0.45f * (((m.pi + 1) * (l + 3) * 37) % 100) / 100f)
                            c.drawRect(60f, 70f + l * 30f, 60f + (if (l % 7 == 6) w * 0.5f else w), 79f + l * 30f, bar)
                        }
                        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textSize = 260f; textAlign = Paint.Align.CENTER; color = Color.argb(140, 154, 167, 189) }
                        c.drawText((m.pi + 1).toString(), pw0 / 2, ph0 / 2 - (tp.ascent() + tp.descent()) / 2, tp)
                    }
                    c.restore()
                }
                if (wmText.isNotEmpty() && !under) drawWm(c, sw, sh, nSheets, name)
                if (border && per > 1) {
                    val bp = Paint().apply { style = Paint.Style.STROKE; color = 0xFF555555.toInt(); strokeWidth = lw }
                    for (m in cells) c.drawRect(m.x, m.y, m.x + pw0 * m.sc, m.y + ph0 * m.sc, bp)
                }
                val fs = maxOf(9f, 5.5f / k)
                drawZone(c, hdr, 22f + fs * 0.6f, sw, fs, s0, nSheets, name)
                drawZone(c, ftr, sh - 14f, sw, fs, s0, nSheets, name)
            }, sideLabel(s0, two))
        }
        val paper = if (two) (nSheets + 1) / 2 else nSheets
        cap.text = "$total page${if (total == 1) "" else "s"}" + (if (sample) " (sample - count unknown)" else "") +
            (if (order.size != total) " → ${order.size} printed" else "") +
            " → $nSheets side${if (nSheets == 1) "" else "s"}, $paper sheet${if (paper == 1) "" else "s"} of paper" +
            (if (gi("copies") > 1) " × ${gi("copies")}" else "") + (if (nSheets > shown) " (first $shown shown)" else "") +
            (if (names.size > 1) " · previewing $name (+${names.size - 1} more)" else "")
    }

    private fun drawImgPv() {
        if (thumbsDone < names.size) { cap.text = "Loading preview…"; return }
        val dims = thumbs.map { if (it != null) it.width to it.height else 3 to 2 }
        val L = pvSheets(dims)
        val k = minOf(220f / L.sw, 170f / L.sh)
        val two = gs("duplex") == "long" || gs("duplex") == "short"
        val n = L.sheets.size
        val shown = minOf(n, 60)
        val border = gi("border") != 0
        for (s0 in 0 until shown) {
            val cells = L.sheets[s0]
            addSheet(Pv(L.sw, L.sh, k) { c ->
                val bmp = Paint(Paint.FILTER_BITMAP_FLAG)
                val grey = Paint().apply { color = 0xFFCCCCCC.toInt() }
                val bp = Paint().apply { style = Paint.Style.STROKE; color = 0xFF555555.toInt(); strokeWidth = maxOf(1f, 0.8f / k) }
                for (m in cells) {
                    val bm = thumbs[m.i]
                    val pw = dims[m.i].first * m.k; val ph = dims[m.i].second * m.k
                    c.save(); c.translate(m.cx, m.cy); c.rotate(m.r.toFloat())
                    val rect = RectF(-pw / 2, -ph / 2, pw / 2, ph / 2)
                    if (bm != null) c.drawBitmap(bm, null, rect, bmp) else c.drawRect(rect, grey)
                    c.restore()
                    if (border) c.drawRect(m.cx - m.w / 2, m.cy - m.h / 2, m.cx + m.w / 2, m.cy + m.h / 2, bp)
                }
            }, sideLabel(s0, two))
        }
        val paper = if (two) (n + 1) / 2 else n
        cap.text = "${names.size} picture${if (names.size == 1) "" else "s"} → $n side${if (n == 1) "" else "s"}, $paper sheet${if (paper == 1) "" else "s"} of paper" +
            (if (gi("copies") > 1) " × ${gi("copies")}" else "") + (if (n > shown) " (first $shown shown)" else "") +
            " · watermark / header / footer are not shown here"
    }

    private fun drawWm(c: Canvas, sw: Float, sh: Float, total: Int, name: String) {
        val t = fill(gs("wm"), 0, total, name)
        if (t.isEmpty()) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textSize = 100f }
        val w1 = p.measureText(t) / 100f
        val diag = Math.hypot(sw.toDouble(), sh.toDouble()).toFloat()
        val size = maxOf(8f, minOf(150f, diag * 0.7f / (if (w1 > 0f) w1 else 1f)))
        p.textSize = size; p.color = Color.argb(71, 128, 128, 128); p.textAlign = Paint.Align.CENTER
        c.save()
        c.translate(sw / 2, sh / 2)
        c.rotate(-Math.toDegrees(Math.atan2(sh.toDouble(), sw.toDouble())).toFloat())
        c.drawText(t, 0f, -(p.ascent() + p.descent()) / 2f, p)
        c.restore()
    }

    /** Header / footer text "left | centre | right" (one part = centred). */
    private fun drawZone(c: Canvas, txt: String, y: Float, sw: Float, fs: Float, s0: Int, total: Int, name: String) {
        if (txt.isEmpty()) return
        val pr = txt.split('|')
        val z = if (pr.size == 1) listOf("", pr[0], "") else listOf(pr[0], pr.getOrElse(1) { "" }, pr.getOrElse(2) { "" })
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF333333.toInt(); textSize = fs }
        val xs = floatArrayOf(28f, sw / 2, sw - 28f)
        val al = arrayOf(Paint.Align.LEFT, Paint.Align.CENTER, Paint.Align.RIGHT)
        for (i in 0 until 3) {
            val t = fill(z[i].trim(), s0, total, name)
            if (t.isEmpty()) continue
            p.textAlign = al[i]
            c.drawText(t, xs[i], y, p)
        }
    }
}
