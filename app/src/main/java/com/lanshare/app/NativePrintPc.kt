package com.lanshare.app

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import com.lanshare.app.core.Jobs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Native replacement for ui.html `printOptions` (the FinePrint-style PC print dialog) - PART B, item 1 of HANDOVER_NATIVE_LIST.md 2r.
 *
 * Same option keys, defaults, presets and "send only what differs from the default" rule as ui.html (PRN_DEF / PRN_BUILTIN /
 * `k.onclick`), so pcprint.py and [Jobs.PRINT_KEYS] see exactly what the WebView dialog sent:
 *  printer, copies, duplex, color, nup (+ border, booklet), pages (all/odd/even) + range + reverse, paper, fit + custom scale,
 *  margin, align + autorot, watermark (wm, wm_under), header / footer (hdr, ftr), presets (built-in + own, long-press to delete).
 * Pictures only selected = "picture mode" like ui.html: one shared set of sheets (sheet=1), no booklet / range / odd-even / scale / position.
 * Live preview strip ([PrintPv], B item 2) on top; in picture mode tap a sheet picture (or a tile) to turn that picture 90 degrees (rots).
 * [show] takes the files from a [PrintSet] (default: the current selection; print intake from other apps passes its own).
 *
 * NOT compiled / NOT device-tested.
 */
class NativePrintPc(
    private val act: Activity,
    private val ctl: FsController,
    private val c: NlTheme.Cols,
    private val sp: SharedPreferences
) {
    private companion object {
        val GREEN = 0xFF4CAF50.toInt()
        /** ui.html PRN_DEF, every value as a string. A value equal to its default (or empty) is not sent. */
        val DEF = linkedMapOf(
            "printer" to "", "copies" to "1", "duplex" to "", "color" to "", "nup" to "1", "booklet" to "0", "border" to "0",
            "pages" to "", "range" to "", "reverse" to "0", "paper" to "", "fit" to "shrink", "margin" to "", "scale" to "",
            "align" to "", "autorot" to "0", "wm" to "", "wm_under" to "0", "hdr" to "", "ftr" to "", "noauto" to "0"
        )
        val FLAGS = setOf("booklet", "border", "reverse", "autorot", "noauto", "wm_under")
        /** Not remembered between prints (ui.html resets them on open): the page range, the watermark and the header / footer. */
        val SESSION = setOf("printer", "range", "wm", "wm_under", "hdr", "ftr")
        val BUILTIN = linkedMapOf<String, Map<String, String>>(
            "Normal" to emptyMap(),
            "Save paper" to mapOf("nup" to "2", "duplex" to "long", "color" to "mono"),
            "Booklet" to mapOf("booklet" to "1", "duplex" to "short"),
            "Draft" to mapOf("color" to "mono", "nup" to "2")
        )
        val PICS = setOf("png", "jpg", "jpeg", "bmp", "gif", "tif", "tiff", "webp", "heic", "heif", "avif", "ico", "wbmp", "dng", "jfif", "jpe", "jfi")
        val OFFICE = setOf("doc", "docx", "rtf", "odt", "xls", "xlsx", "csv", "ods", "ppt", "pptx", "odp")
    }

    private val dn: Float = act.resources.displayMetrics.density
    private fun dp(v: Int) = Math.round(v * dn)

    private fun tv(t: String, size: Float, col: Int, bold: Boolean = false): TextView = TextView(act).apply {
        text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(col)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun chip(label: String): TextView = tv(label, 13f, c.fg, true).apply {
        gravity = Gravity.CENTER; setPadding(dp(14), 0, dp(14), 0); minWidth = dp(40); isClickable = true
    }

    private fun paint(b: TextView, on: Boolean) {
        b.setTextColor(if (on) c.onac else c.fg)
        b.background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(if (on) c.ac else c.cont) }
    }

    private fun mine(): JSONObject = try { JSONObject(sp.getString("pcPresets", "") ?: "") } catch (_: Throwable) { JSONObject() }

    // ---------------------------------------------------------------- dialog
    /** [devId] = the PC (LANShare peer or SMB share id), [name] = its display name. The files are the current selection of [ctl]. */
    fun show(devId: String, name: String, set: PrintSet = PrintSet(ctl.dev, ctl.path, ctl.selFiles()) { ctl.clearSel() }, wifi: Boolean = false) {
        val files = set.files
        if (files.isEmpty()) return
        val exts = files.map { it.name.substringAfterLast('.', "").lowercase() }
        val imgMode = exts.all { it in PICS }          // only pictures: laid out together on shared sheets (ui.html imgMode)
        val officeSel = exts.any { it in OFFICE }      // Word / Excel / PowerPoint: needs a converter on the PC for layout options

        val optKey = if (wifi) "wifiopts" else "pcopts"   // a Wi-Fi printer remembers its own last choice
        val st = HashMap<String, String>(DEF)
        try {
            val prev = JSONObject(sp.getString(optKey, "") ?: "")
            for (k in DEF.keys) if (k !in SESSION && prev.has(k)) st[k] = prev.optString(k, DEF[k]!!)
        } catch (_: Throwable) { }
        st["printer"] = if (wifi) "" else (sp.getString("pcprinter:$devId", "") ?: "")
        if (imgMode) { st["booklet"] = "0"; st["noauto"] = "0" }

        var dialog: AlertDialog? = null
        val redraw = ArrayList<() -> Unit>()
        val rots = IntArray(files.size)        // picture mode: manual turn of every picture (0 / 90 / 180 / 270)
        var officeOk = false                   // the PC can convert Word / Excel / PowerPoint (status answer)
        var pv: PrintPv? = null
        fun refresh() { redraw.forEach { f -> f() }; pv?.redraw() }

        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val info = tv("Checking printer\u2026", 12f, c.mut)
        col.addView(info)
        // live preview of the layout (pages per sheet, booklet, page selection, watermark, header / footer, sides, colour)
        pv = PrintPv(act, c, set, devId, false, st, rots, imgMode, { refresh() }, { officeOk }, wifi)
        col.addView(pv!!.view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        fun section(title: String, vararg kids: View): View {
            val w = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, 0) }
            if (title.isNotEmpty()) w.addView(tv(title, 13f, c.mut, true))
            for (k in kids) w.addView(k, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            col.addView(w)
            return w
        }

        fun seg(key: String, opts: List<Pair<String, String>>): View {
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            for ((v, label) in opts) {
                val b = chip(label)
                b.setOnClickListener { st[key] = v; refresh() }
                redraw.add { paint(b, st[key] == v) }
                row.addView(b, LinearLayout.LayoutParams(-2, dp(36)).apply { rightMargin = dp(6) })
            }
            return HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(row) }
        }

        /** On/off row bound to a "0"/"1" key; [inv] = the switch shows the opposite of the key (e.g. "Auto-turn" = not noauto). */
        fun sw(key: String, label: String, inv: Boolean = false): View {
            val s = Switch(act)
            s.setOnCheckedChangeListener { _, on ->
                st[key] = if (on != inv) "1" else "0"
                if (key == "noauto" && st[key] == "0") rots.fill(0)   // auto-turn on again: forget the manual turns (ui.html)
                refresh()
            }
            redraw.add { val want = (st[key] == "1") != inv; if (s.isChecked != want) s.isChecked = want }
            return LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(tv(label, 14f, c.fg), LinearLayout.LayoutParams(0, -2, 1f))
                addView(s)
            }
        }

        fun field(key: String, hint: String, numeric: Boolean = false): EditText = EditText(act).apply {
            this.hint = hint; setSingleLine(); setTextColor(c.fg); setHintTextColor(c.mut)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { st[key] = s?.toString() ?: "" }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
            })
            redraw.add { if (this.text.toString() != (st[key] ?: "")) setText(st[key] ?: "") }
        }

        // ---- presets (built-in + own; long-press an own preset to delete it)
        val presetRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        fun applyPreset(p: Map<String, String>) {
            val keep = st["printer"] ?: ""
            st.putAll(DEF); st.putAll(p); st["printer"] = keep   // a preset never changes the printer; range / watermark / header start empty
            refresh()
        }
        lateinit var drawPresets: () -> Unit
        fun savePreset() {
            val et = EditText(act).apply { hint = "Preset name"; setSingleLine(); setTextColor(c.fg); setHintTextColor(c.mut) }
            val box = LinearLayout(act).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et, LinearLayout.LayoutParams(-1, -2)) }
            AlertDialog.Builder(act).setTitle("Save preset")
                .setMessage("Saves the options below under a name (press and hold a preset to delete it).")
                .setView(box).setNegativeButton("Cancel", null)
                .setPositiveButton("Save") { _, _ ->
                    val n = et.text.toString().trim().take(24)
                    if (n.isEmpty()) return@setPositiveButton
                    val o = JSONObject()
                    for ((k, v) in st) if (k != "printer" && k != "range" && k in DEF) o.put(k, v)
                    val m = mine(); m.put(n, o)
                    sp.edit().putString("pcPresets", m.toString()).apply()
                    drawPresets()
                }.show()
        }
        fun deletePreset(n: String) {
            AlertDialog.Builder(act).setTitle("Delete preset \"$n\"?").setNegativeButton("Cancel", null)
                .setPositiveButton("Delete") { _, _ ->
                    val m = mine(); m.remove(n)
                    sp.edit().putString("pcPresets", m.toString()).apply()
                    drawPresets()
                }.show()
        }
        drawPresets = {
            presetRow.removeAllViews()
            fun add(label: String, onTap: () -> Unit, onHold: (() -> Unit)? = null) {
                val b = chip(label); paint(b, false)
                b.setOnClickListener { onTap() }
                if (onHold != null) b.setOnLongClickListener { onHold(); true }
                presetRow.addView(b, LinearLayout.LayoutParams(-2, dp(36)).apply { rightMargin = dp(6) })
            }
            for ((n, p) in BUILTIN) add(n, { applyPreset(p) })
            val m = mine()
            for (n in m.keys()) {
                val o = m.optJSONObject(n) ?: continue
                val p = HashMap<String, String>()
                for (k in DEF.keys) if (o.has(k)) p[k] = o.optString(k)
                add(n, { applyPreset(p) }, { deletePreset(n) })
            }
            add("+ Save", { savePreset() })
        }
        drawPresets()
        section("Presets", HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(presetRow) })

        // ---- printer chooser (filled from the PC's answer; hidden when it lists none)
        val names = ArrayList<String>()
        val spAdapter = ArrayAdapter<String>(act, android.R.layout.simple_spinner_dropdown_item, ArrayList<String>())
        val spin = Spinner(act).apply { adapter = spAdapter }
        val printerSec = section("Printer", spin)
        printerSec.visibility = View.GONE
        val hint = tv("", 12f, c.mut).apply { visibility = View.GONE; setPadding(0, dp(8), 0, 0) }

        var gen = 0
        var sidesSec: View? = null; var colourSec: View? = null
        fun status() {
            if (wifi) {   // a printer on this Wi-Fi: status over IPP, no PC service; hide what the printer cannot do
                val gw = ++gen
                info.text = "Checking printer\u2026"; info.setTextColor(c.mut)
                Thread {
                    val r: JSONObject? = try { Jobs.printerStatus(devId) } catch (_: Throwable) { null }
                    act.runOnUiThread {
                        if (gw != gen || dialog?.isShowing != true) return@runOnUiThread
                        if (r == null) { info.text = "Printer status unavailable"; info.setTextColor(c.mut); return@runOnUiThread }
                        if (!r.optBoolean("ok", false)) {
                            info.text = "Printer not reachable - is it on and on this Wi-Fi?"; info.setTextColor(c.warn); return@runOnUiThread
                        }
                        val problem = if (r.isNull("problem")) "" else r.optString("problem", "")
                        if (problem.isNotEmpty()) { info.text = name + " \u00b7 " + problem; info.setTextColor(c.warn) }
                        else { info.text = name + " \u00b7 ready \u00b7 Wi-Fi"; info.setTextColor(GREEN) }
                        if (r.has("duplex") && !r.isNull("duplex") && !r.optBoolean("duplex", true)) sidesSec?.visibility = View.GONE
                        if (r.has("color") && !r.isNull("color") && !r.optBoolean("color", true)) colourSec?.visibility = View.GONE
                    }
                }.also { it.isDaemon = true }.start()
                return
            }
            val g = ++gen
            info.text = "Checking printer\u2026"; info.setTextColor(c.mut)
            val chosen = st["printer"] ?: ""
            Thread {
                val r: JSONObject? = try { Jobs.printerStatus(devId, chosen.ifEmpty { null }) } catch (_: Throwable) { null }
                act.runOnUiThread {
                    if (g != gen || dialog?.isShowing != true) return@runOnUiThread
                    if (r == null) { info.text = "Printer status unavailable"; info.setTextColor(c.mut); return@runOnUiThread }
                    if (!r.optBoolean("ok", false)) {
                        info.text = "Print service not reachable on this PC"; info.setTextColor(c.warn); return@runOnUiThread
                    }
                    val arr = r.optJSONArray("printers")
                    if (arr != null && arr.length() > 0) {
                        printerSec.visibility = View.VISIBLE
                        if (names.isEmpty()) {
                            val def = if (r.isNull("default")) "" else r.optString("default")
                            for (k in 0 until arr.length()) names.add(arr.optString(k))
                            spAdapter.clear(); spAdapter.add("Default printer")
                            for (n in names) spAdapter.add(if (n == def) "$n (default)" else n)
                            val at = names.indexOf(st["printer"] ?: "")
                            if (at < 0) st["printer"] = ""
                            spin.setSelection(if (at >= 0) at + 1 else 0)
                        }
                    }
                    val pr = if (r.isNull("printer")) "" else r.optString("printer", "")
                    val problem = if (r.isNull("problem")) "" else r.optString("problem", "")
                    when {
                        pr.isEmpty() -> { info.text = "No default printer set on the PC"; info.setTextColor(c.warn) }
                        problem.isNotEmpty() -> { info.text = "$pr \u00b7 $problem"; info.setTextColor(c.warn) }
                        else -> { info.text = "$pr \u00b7 ready"; info.setTextColor(GREEN) }
                    }
                    officeOk = !(r.isNull("office") || r.optString("office").isEmpty())
                    pv?.redraw()
                    val notes = ArrayList<String>()
                    if (r.has("pypdf") && !r.optBoolean("pypdf", true)) notes.add("Layout needs the pypdf package on the PC - run pcprint.py again (it installs it) if they fail.")
                    if (r.isNull("engine") || r.optString("engine").isEmpty()) notes.add("SumatraPDF is missing on the PC: duplex, colour and paper size are ignored.")
                    if (officeSel && (r.isNull("office") || r.optString("office").isEmpty()))
                        notes.add("Word / Excel / PowerPoint files are printed by the PC's own app: only printer and copies apply. Install LibreOffice (or Microsoft Office) on the PC and run the new pcprint.py to unlock layout options.")
                    hint.visibility = if (notes.isEmpty()) View.GONE else View.VISIBLE
                    hint.text = notes.joinToString(" ")
                }
            }.also { it.isDaemon = true }.start()
        }
        spin.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, rowId: Long) {
                val n = if (pos <= 0) "" else names.getOrElse(pos - 1) { "" }
                if (n == (st["printer"] ?: "")) return          // also swallows the selection set while filling the list
                st["printer"] = n
                status()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // ---- copies
        val cnt = tv("1", 16f, c.fg, true).apply { gravity = Gravity.CENTER; minWidth = dp(40) }
        val minus = chip("\u2212"); val plus = chip("+")
        minus.setOnClickListener { st["copies"] = maxOf(1, (st["copies"]?.toIntOrNull() ?: 1) - 1).toString(); refresh() }
        plus.setOnClickListener { st["copies"] = minOf(99, (st["copies"]?.toIntOrNull() ?: 1) + 1).toString(); refresh() }
        redraw.add { cnt.text = st["copies"] ?: "1" }
        paint(minus, false); paint(plus, false)
        section("Copies", LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(minus, LinearLayout.LayoutParams(-2, dp(36))); addView(cnt); addView(plus, LinearLayout.LayoutParams(-2, dp(36)))
        })

        sidesSec = section("Sides", seg("duplex", listOf("" to "Printer default", "off" to "One-sided", "long" to "Long edge", "short" to "Short edge")))
        colourSec = section("Colour", seg("color", listOf("" to "Default", "color" to "Colour", "mono" to "Black & white")))
        if (imgMode) {
            section("Pictures per sheet", seg("nup", listOf("1" to "1", "2" to "2", "4" to "4", "6" to "6", "9" to "9")), sw("border", "Border around each picture"))
            section("Order", sw("reverse", "Reverse order"))
            // one tile per picture: tap = turn it 90 degrees clockwise (the sheets above show the result)
            val tiles = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            files.forEachIndexed { i, f ->
                val iv = android.widget.ImageView(act).apply { scaleType = android.widget.ImageView.ScaleType.FIT_CENTER; contentDescription = "Turn " + f.name }
                val lb = tv("\u21bb", 12f, c.fg, true).apply { gravity = Gravity.CENTER }
                val tile = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; isClickable = true
                    background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(c.cont) }
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                    addView(iv, LinearLayout.LayoutParams(dp(64), dp(64)))
                    addView(lb, LinearLayout.LayoutParams(-2, -2))
                    setOnClickListener { pv?.turn(i) }
                }
                pv?.thumb(i)?.let { iv.setImageBitmap(it) }
                redraw.add { iv.rotation = rots[i].toFloat(); lb.text = if (rots[i] != 0) rots[i].toString() + "\u00b0" else "\u21bb" }
                tiles.addView(tile, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
            }
            pv?.onThumb = { i, bm -> (tiles.getChildAt(i) as? LinearLayout)?.getChildAt(0)?.let { (it as android.widget.ImageView).setImageBitmap(bm) } }
            section("Turn pictures (tap = 90\u00b0 clockwise)", HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(tiles) },
                sw("noauto", "Auto-turn pictures to fill the sheet", inv = true))
        } else {
            section("Pages per sheet", seg("nup", listOf("1" to "1", "2" to "2", "4" to "4", "6" to "6", "9" to "9")),
                sw("border", "Border around each page"), sw("booklet", "Booklet (folded, two-sided on short edge)"))
            section("Pages", seg("pages", listOf("" to "All", "odd" to "Odd only", "even" to "Even only")),
                field("range", "Page range, e.g. 1-3,5,8-"), sw("reverse", "Reverse order"))
        }
        section("Paper", seg("paper", listOf("" to "Auto", "A4" to "A4", "Letter" to "Letter", "A3" to "A3", "A5" to "A5", "Legal" to "Legal")))
        if (imgMode) {
            section("Scaling", seg("fit", listOf("shrink" to "Fit whole picture", "fill" to "Fill page (crop)")))
        } else {
            section("Scaling", seg("fit", listOf("shrink" to "Shrink to fit", "fit" to "Fit", "noscale" to "Actual size", "fill" to "Fill (crop)")),
                field("scale", "Custom scale in %, e.g. 90 (overrides the above)", numeric = true))
        }
        section("Margins", seg("margin", listOf("" to "Default", "0" to "None", "14" to "Small", "28" to "Normal", "42" to "Large")))
        col.addView(tv("None = edge to edge; most printers cannot print the last 3-5 mm of the paper.", 12f, c.mut).apply { setPadding(0, dp(4), 0, 0) })
        if (!imgMode) {
            section("Position", seg("align", listOf("" to "Centre", "top" to "Top", "topleft" to "Top left")), sw("autorot", "Turn pages to fit the sheet"))
            section("Watermark", field("wm", "Text across every page, e.g. DRAFT"), sw("wm_under", "Behind the page content"))
            section("Header and footer", field("hdr", "Header: left | centre | right"), field("ftr", "Footer: left | centre | right"))
            col.addView(tv("Header / footer and watermark can use {page} {pages} {date} {time} {file}.", 12f, c.mut).apply { setPadding(0, dp(4), 0, 0) })
        }
        col.addView(tv(
            if (wifi) "The layout is done on this phone and sent to the printer as a PDF. Word / Excel / PowerPoint files are converted first by a PC running pcprint.py."
            else "Layout and fitting work for PDF, image and text files; other files get printer and copies only.",
            12f, c.mut).apply { setPadding(0, dp(12), 0, 0) })
        col.addView(hint)

        // ---- submit: only what differs from the defaults is sent (ui.html `k.onclick`)
        fun submit() {
            val out = JSONObject()
            val keep = JSONObject()
            for ((k, def) in DEF) {
                val v = st[k] ?: def
                if (k !in SESSION) keep.put(k, v)
                when {
                    k in FLAGS -> if (v == "1") out.put(k, 1)
                    k == "copies" || k == "nup" -> { val n = v.trim().toIntOrNull(); if (n != null && v != def) out.put(k, n) }
                    k == "scale" -> { val n = v.trim().toIntOrNull(); if (n != null && n in 10..500) out.put(k, n.toString()) }
                    k == "margin" -> if (v.isNotEmpty()) out.put(k, v)        // "0" = None must survive
                    else -> { val t = v.trim(); if (t.isNotEmpty() && v != def) out.put(k, t) }
                }
            }
            if (imgMode) {
                out.put("sheet", 1)
                out.put("rots", JSONArray(rots.toList()))
                for (k in listOf("booklet", "range", "pages", "scale", "align", "autorot")) out.remove(k)
            } else if (st["booklet"] == "1" && (st["duplex"] ?: "").isEmpty()) out.put("duplex", "short")
            val ed = sp.edit().putString(optKey, keep.toString())
            if (!wifi) ed.putString("pcprinter:$devId", st["printer"] ?: "")
            ed.apply()
            ctl.printPaths(set.dev, set.paths(), devId, out) { set.after() }
        }

        val dlg = AlertDialog.Builder(act).setTitle("Print on $name")
            .setView(ScrollView(act).apply { addView(col) })
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Print") { _, _ -> submit() }
            .create()
        dialog = dlg
        dlg.setOnDismissListener { pv?.dispose() }
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dlg.show()
        refresh()
        status()
    }
}
