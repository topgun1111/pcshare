package com.lanshare.app

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.PathParser
import org.json.JSONObject

/** A 24x24 Material path drawn at [px] x [px]. Shared by the sheets and the native search row. */
class NlGlyph(c: Context, private val path: Path?, col: Int, private val px: Int) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col; style = Paint.Style.FILL }
    override fun onMeasure(w: Int, h: Int) { setMeasuredDimension(px, px) }
    override fun onDraw(cv: Canvas) {
        val pa = path ?: return
        val s = px / 24f
        cv.save(); cv.scale(s, s); cv.drawPath(pa, p); cv.restore()
    }
    companion object {
        fun parse(d: String): Path? = try { if (d.isEmpty()) null else PathParser.createPathFromPathData(d) } catch (_: Throwable) { null }
    }
}

/** Radio ring (22 dp, 2 px border, 12 dp dot) = ui.html .rd */
private class NlRadio(c: Context, private val on: Boolean, private val ring: Int, private val tl: Int, private val d: Float) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onMeasure(w: Int, h: Int) { val s = Math.round(22f * d); setMeasuredDimension(s, s) }
    override fun onDraw(cv: Canvas) {
        val r = 11f * d
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.color = if (on) tl else ring
        cv.drawCircle(r, r, r - d, p)
        if (on) { p.style = Paint.Style.FILL; p.color = tl; cv.drawCircle(r, r, 6f * d, p) }
    }
}

/**
 * Native twins of ui.html openSort() (centered dialog) and openView() (bottom sheet).
 * ui.html sends the current state as JSON; every choice goes back through [onPref] as
 * {sort,asc} / {view} / {thumb} / {aa} / {hid}. Kill switch: localStorage ls_nls='0' (page keeps its own HTML sheets).
 */
class NlSheets(private val act: Activity, private val onPref: (JSONObject) -> Unit) {
    private val d = UiScale.dens(act)
    private fun dp(v: Float) = Math.round(v * d)
    private var dlg: Dialog? = null

    /** Last sort labels the page sent ({none:"No Sort",name:"Name",...}); the instant path in NativeList uses them for the header. */
    var labels: JSONObject? = null
        private set

    private class St(val j: JSONObject) {
        val pal = NlPal(j.getJSONObject("p"))
        var view: String = j.optString("view", "list")
        var thumb: String = j.optString("thumb", "s")
        var sort: String = j.optString("sort", "name")
        var asc: Boolean = j.optInt("asc", 1) == 1
        var hid: Boolean = j.optInt("hid") == 1
        var aa = false
        val lb: JSONObject = j.optJSONObject("lb") ?: JSONObject()
        val ic: JSONObject = j.optJSONObject("ic") ?: JSONObject()
        fun label(k: String): String = lb.optString(k, k)
    }

    fun close() { try { dlg?.dismiss() } catch (_: Throwable) { }; dlg = null }

    private fun tv(t: String, sp: Float, col: Int, medium: Boolean = false): TextView = TextView(act).apply {
        text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(col)
        if (medium) typeface = Fnt.med()
    }

    private fun ripple(v: View) {
        val tvv = TypedValue()
        if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, tvv, true)) v.setBackgroundResource(tvv.resourceId)
    }

    private fun newDialog(bottom: Boolean): Dialog {
        val dl = Dialog(act)
        dl.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dl.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.42f)
            setGravity(if (bottom) Gravity.BOTTOM else Gravity.CENTER)
            setLayout(if (bottom) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dl.setCanceledOnTouchOutside(true)
        return dl
    }

    private fun round(col: Int, r: Float) = GradientDrawable().apply { setColor(col); cornerRadius = r * d }

    // ---------------------------------------------------------------- Sort By
    fun showSort(json: String) {
        val s = try { St(JSONObject(json)) } catch (_: Throwable) { return }
        labels = s.lb
        sortFor(s)
    }

    private fun sortFor(s: St) {
        close()
        val p = s.pal
        val dl = newDialog(false)
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(24f), 0, dp(8f))
            background = round(p.card, 28f)
        }
        card.addView(tv("Sort By", 20f, p.fg, true).apply { setPadding(dp(24f), 0, dp(24f), dp(8f)) })
        fun opt(k: String, asc: Boolean) {
            val on = s.sort == k && (k == "none" || s.asc == asc)
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(54f); setPadding(dp(24f), dp(6f), dp(24f), dp(6f))
            }
            ripple(row)
            row.addView(NlRadio(act, on, p.mut, p.tl, d))
            val lab = s.label(k) + (if (k == "none") "" else if (asc) " \u25B2" else " \u25BC")
            row.addView(tv(lab, 20f, p.fg, on).apply { setPadding(dp(16f), 0, 0, 0) })
            row.setOnClickListener { dl.dismiss(); onPref(JSONObject().put("sort", k).put("asc", if (asc) 1 else 0)) }
            card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        opt("none", true)
        for (k in listOf("name", "size", "date", "type")) { opt(k, true); opt(k, false) }
        val cancel = tv("CANCEL", 14f, p.tl, true).apply {
            letterSpacing = 0.08f; setPadding(dp(16f), dp(12f), dp(16f), dp(12f)); ripple(this)
            setOnClickListener { dl.dismiss() }
        }
        card.addView(cancel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END; setMargins(0, dp(8f), dp(16f), 0) })
        val wrap = LinearLayout(act).apply { setPadding(dp(24f), 0, dp(24f), 0) }
        wrap.addView(card, LinearLayout.LayoutParams(dp(360f), ViewGroup.LayoutParams.WRAP_CONTENT))
        dl.setContentView(wrap)
        dlg = dl
        dl.show()
    }

    // ---------------------------------------------------------------- View options sheet
    fun showView(json: String) {
        val s = try { St(JSONObject(json)) } catch (_: Throwable) { return }
        labels = s.lb
        close()
        val p = s.pal
        val dl = newDialog(true)
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12f), 0, dp(12f), dp(8f)) }
        val scroll = ScrollView(act).apply { setBackgroundColor(p.card); addView(col) }

        fun hr() = View(act).apply { setBackgroundColor(p.bd) }.also { col.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)) }
        fun head(t: String) { col.addView(tv(t, 16f, p.tl, true).apply { setPadding(dp(4f), dp(14f), dp(4f), dp(6f)) }) }

        fun cbRow(label: String, on: Boolean, f: (Boolean) -> Unit) {
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(56f); setPadding(dp(4f), dp(6f), dp(4f), dp(6f)) }
            ripple(row)
            row.addView(tv(label, 18f, p.fg), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val cb = CheckBox(act).apply { isChecked = on; buttonTintList = ColorStateList.valueOf(p.tl); isClickable = false; isFocusable = false }
            row.addView(cb)
            row.setOnClickListener { f(!on) }
            col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        fun radioRow(items: List<Triple<String, Boolean, () -> Unit>>, iconOf: (Int) -> View) {
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6f), 0, dp(6f)) }
            items.forEachIndexed { n, item ->
                val cell = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4f), 0, dp(4f), 0); contentDescription = item.first }
                ripple(cell)
                cell.addView(NlRadio(act, item.second, p.mut, p.tl, d))
                cell.addView(iconOf(n), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(14f), 0, 0, 0) })
                val act1 = item.third
                cell.setOnClickListener { act1() }
                row.addView(cell, LinearLayout.LayoutParams(0, dp(56f), 1f))
            }
            col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        fun draw() {
            col.removeAllViews()
            cbRow("Apply to all folders", s.aa) { v -> s.aa = v; onPref(JSONObject().put("aa", if (v) 1 else 0)); draw() }
            hr(); head("View")
            val views = listOf("list" to "vlist", "grid" to "vgrid", "compact" to "vcomp")
            radioRow(views.map { (k, _) -> Triple(k.replaceFirstChar { c -> c.uppercase() }, s.view == k, { s.view = k; onPref(JSONObject().put("view", k)); draw() }) }) { n ->
                NlGlyph(act, NlGlyph.parse(s.ic.optString(views[n].second)), p.fg, dp(24f))
            }
            val thumbs = listOf("s" to 22f, "l" to 32f)
            radioRow(thumbs.map { (k, _) -> Triple(if (k == "s") "Small thumbnails" else "Large thumbnails", s.thumb == k, { s.thumb = k; onPref(JSONObject().put("thumb", k)); draw() }) }) { n ->
                // the 24 box path scaled to 22 / 32 dp, like the page does
                NlGlyph(act, NlGlyph.parse(s.ic.optString("img")), p.fg, dp(thumbs[n].second))
            }
            hr(); head("Sort")
            val sb = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(56f); setPadding(dp(4f), dp(6f), dp(4f), dp(6f)) }
            ripple(sb)
            sb.addView(tv(s.label(s.sort) + (if (s.sort == "none") "" else if (s.asc) " \u25B2" else " \u25BC"), 18f, p.fg))
            sb.setOnClickListener { dl.dismiss(); sortFor(s) }
            col.addView(sb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            hr(); head("Others")
            cbRow("Show hidden files", s.hid) { v -> s.hid = v; onPref(JSONObject().put("hid", if (v) 1 else 0)); draw() }
        }
        draw()
        dl.setContentView(scroll)
        dlg = dl
        dl.show()
        dl.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
