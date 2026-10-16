package com.lanshare.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.ListView
import com.lanshare.app.core.SCat
import com.lanshare.app.core.SNode

/**
 * Storage Analyzer, part 5 = the size list. A plain [ListView] (no dependencies) showing the kids of one [SNode] folder, biggest first
 * (the scan already sorts them): category colour chip, name, a line with counts / category, size, % of the folder, and a bar whose
 * length is the share of the folder. The "N small files" node is a row too. Tap = [onTap] + selected row highlight.
 * Same data and colours as [StorageTreemap] (it shares [StorageTreemap.CAT_COL]); drill-in / breadcrumb are in StorageActivity (part 6).
 * Part 7: [setFilter] (one file category), [setFlat] (Largest files tab, rows show the folder), [select].
 * NOT compiled / NOT device-tested.
 */
class StorageList(ctx: Context) : ListView(ctx) {
    /** Called with the tapped node (file, folder or the synthetic "N small files" node). */
    var onTap: ((SNode) -> Unit)? = null

    var colBg = Color.WHITE
    var colFg = 0xFF161616.toInt()
    var colDim = 0xFF5F5F5F.toInt()
    var colAcc = 0xFFC25100.toInt()
    var colLine = 0xFFDDDDDD.toInt()

    private var kids: Array<SNode> = SNode.NONE     // rows shown (after the category filter)
    private var parentSize = 0L                     // 100 % of the share bar / percent column
    private var selNode: SNode? = null

    // part 7: source of the rows, category filter, flat mode (Largest files tab)
    private var src: Array<SNode> = SNode.NONE
    private var srcRoot: SNode? = null
    private var flatTotal = 0L
    private var flat = false
    private var cat = -1                            // SCat index to show only, -1 = all

    /** Currently selected node (null = none). */
    val selected: SNode? get() = selNode

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val adapterImpl = object : BaseAdapter() {
        override fun getCount() = kids.size
        override fun getItem(position: Int): Any = kids[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = (convertView as? RowView) ?: RowView(context)
            v.bind(kids[position], kids[position] === selNode)
            return v
        }
    }

    init {
        contentDescription = "Size list"
        divider = null
        dividerHeight = 0
        isVerticalScrollBarEnabled = true
        setAdapter(adapterImpl)
        setOnItemClickListener { _, _, pos, _ ->
            if (pos in kids.indices) {
                selNode = kids[pos]
                adapterImpl.notifyDataSetChanged()
                onTap?.invoke(kids[pos])
            }
        }
    }

    /** The folder whose kids are listed (null = empty list). The list scrolls back to the top. */
    fun setRoot(r: SNode?) {
        flat = false; srcRoot = r; src = r?.kids ?: SNode.NONE
        selNode = null
        rebuild()
    }

    /** Part 7, Largest files tab: [files] (biggest first, any folders) as a flat list, percent = share of [total]. Rows show the folder path. */
    fun setFlat(files: List<SNode>, total: Long) {
        flat = true; srcRoot = null; src = files.toTypedArray(); flatTotal = total
        selNode = null
        rebuild()
    }

    /** Part 7: show only files of category [c] (SCat index) and the folders that contain some; -1 = everything. Folder rows then show their bytes of that category. */
    fun setFilter(c: Int) {
        if (c == cat) return
        cat = c; selNode = null
        rebuild()
    }

    /** Part 7: highlight [n] (matched by identity, or by name for a file of the Largest list that is a twin of a kept node) and scroll to it. */
    fun select(n: SNode?) {
        var at = -1
        if (n != null) for (i in kids.indices) { val k = kids[i]; if (k === n || (!k.dir && !n.dir && !k.agg && k.parent === n.parent && k.name == n.name && k.size == n.size)) { at = i; break } }
        selNode = if (at >= 0) kids[at] else null
        adapterImpl.notifyDataSetChanged()
        if (at >= 0) setSelection(at)
    }

    /** Bytes the row stands for: its size, or (category filter on) its bytes of that category. */
    private fun rowSize(n: SNode): Long {
        if (cat < 0) return n.size
        if (n.dir) return n.cats?.let { if (cat < it.size) it[cat] else 0L } ?: 0L
        return if (n.cat.coerceIn(0, SCat.COUNT - 1) == cat) n.size else 0L
    }

    private fun rebuild() {
        setBackgroundColor(colBg)
        if (cat < 0) {
            kids = src
            parentSize = if (flat) flatTotal else srcRoot?.size ?: 0L
        } else {
            val l = ArrayList<SNode>()
            for (k in src) if (!k.agg && rowSize(k) > 0) l.add(k)
            if (!flat) l.sortByDescending { rowSize(it) }
            kids = l.toTypedArray()
            parentSize = if (flat) flatTotal else srcRoot?.let { r -> r.cats?.let { if (cat < it.size) it[cat] else 0L } } ?: 0L
        }
        adapterImpl.notifyDataSetChanged()
        setSelection(0)
    }

    /** Category with the most bytes below a folder (OTHER when it has no files). */
    private fun domCat(k: SNode): Int {
        val c = k.cats ?: return SCat.OTHER
        var b = 0; var bv = -1L
        for (i in 0 until Math.min(c.size, SCat.COUNT)) if (c[i] > bv) { bv = c[i]; b = i }
        return b
    }

    /** One row, drawn by hand (one view, no child views: cheap for a few hundred rows). */
    private inner class RowView(ctx: Context) : View(ctx) {
        private var nd: SNode? = null
        private var sel = false
        private val fill = Paint().apply { style = Paint.Style.FILL }
        private val tp = Paint(Paint.ANTI_ALIAS_FLAG)

        init { layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH()) }

        private fun rowH() = (dp(56f) * Math.max(1f, resources.configuration.fontScale)).toInt()

        fun bind(n: SNode, selected: Boolean) {
            nd = n; sel = selected
            layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH())
            invalidate()
        }

        override fun onMeasure(wSpec: Int, hSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(wSpec), rowH())
        }

        override fun onDraw(canvas: Canvas) {
            val n = nd ?: return
            val w = width.toFloat(); val h = height.toFloat()
            val fs = Math.max(1f, resources.configuration.fontScale)
            val padX = dp(14f)
            if (sel) { fill.color = mix(colBg, colAcc, 0.16f); canvas.drawRect(0f, 0f, w, h, fill) }

            val cat = if (n.agg) SCat.OTHER else if (n.dir) (if (this@StorageList.cat >= 0) this@StorageList.cat else domCat(n)) else n.cat.coerceIn(0, SCat.COUNT - 1)
            val chipCol = if (n.agg) mix(colBg, colFg, 0.30f) else StorageTreemap.CAT_COL[cat]

            // chip: file = filled square, folder = darker square with a lighter inner square (reads as "box")
            val cs = dp(12f); val cx = padX; val cy = dp(12f)
            fill.color = if (n.dir && !n.agg) mix(chipCol, 0xFF000000.toInt(), 0.28f) else chipCol
            canvas.drawRoundRect(cx, cy, cx + cs, cy + cs, dp(2f), dp(2f), fill)
            if (n.dir && !n.agg) { fill.color = chipCol; canvas.drawRect(cx + dp(3f), cy + dp(3f), cx + cs - dp(3f), cy + cs - dp(3f), fill) }

            // right column: size (bold) + % of the folder
            val sz = rowSize(n)
            val pct = if (parentSize > 0) sz * 100.0 / parentSize else 0.0
            val sizeTxt = NlModel.fmt(sz)
            val pctTxt = if (pct < 0.1 && sz > 0) "<0.1%" else String.format(java.util.Locale.US, "%.1f%%", pct)
            tp.typeface = Typeface.DEFAULT_BOLD; tp.textSize = dp(14f) * fs; tp.textAlign = Paint.Align.RIGHT
            val sizeW = tp.measureText(sizeTxt)
            tp.typeface = Typeface.DEFAULT; tp.textSize = dp(12f) * fs
            val pctW = tp.measureText(pctTxt)
            val rightW = Math.max(sizeW, pctW)
            val line1 = dp(8f) + dp(14f) * fs          // baseline of the first line
            val line2 = line1 + dp(4f) + dp(12f) * fs  // baseline of the second line

            tp.typeface = Typeface.DEFAULT_BOLD; tp.textSize = dp(14f) * fs; tp.color = colFg
            canvas.drawText(sizeTxt, w - padX, line1, tp)
            tp.typeface = Typeface.DEFAULT; tp.textSize = dp(12f) * fs; tp.color = colDim
            canvas.drawText(pctTxt, w - padX, line2, tp)

            // left column: name + info line
            tp.textAlign = Paint.Align.LEFT
            val tx = cx + cs + dp(10f)
            val maxW = w - padX - rightW - dp(12f) - tx
            tp.typeface = if (n.dir && !n.agg) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            tp.textSize = dp(14f) * fs; tp.color = colFg
            val name = if (n.agg) "Small files (" + n.files + ")" else n.name
            canvas.drawText(fit(name, maxW), tx, line1, tp)
            tp.typeface = Typeface.DEFAULT; tp.textSize = dp(12f) * fs; tp.color = colDim
            canvas.drawText(fit(info(n), maxW), tx, line2, tp)

            // share bar along the bottom
            val bh = dp(4f); val by = h - dp(8f) - bh
            val bx0 = padX; val bx1 = w - padX
            fill.color = colLine
            canvas.drawRoundRect(bx0, by, bx1, by + bh, bh / 2, bh / 2, fill)
            val frac = if (parentSize > 0) (sz.toDouble() / parentSize).coerceIn(0.0, 1.0) else 0.0
            val fw = Math.max(if (sz > 0) dp(2f) else 0f, ((bx1 - bx0) * frac).toFloat())
            if (fw > 0f) { fill.color = chipCol; canvas.drawRoundRect(bx0, by, bx0 + fw, by + bh, bh / 2, bh / 2, fill) }
        }

        private fun info(n: SNode): String = when {
            n.agg -> "small files kept together"
            n.dir -> {
                val sb = StringBuilder()
                sb.append(n.files).append(if (n.files == 1L) " file" else " files")
                if (n.folders > 0) sb.append(" · ").append(n.folders).append(if (n.folders == 1L) " folder" else " folders")
                if (n.denied) sb.append(" · not readable")
                if (this@StorageList.cat >= 0) sb.append(" · folder total ").append(NlModel.fmt(n.size))
                sb.toString()
            }
            flat -> LABELS[n.cat.coerceIn(0, SCat.COUNT - 1)] + " · " + folderOf(n)
            else -> LABELS[n.cat.coerceIn(0, SCat.COUNT - 1)]
        }

        /** Folder of a file, as a scan-relative path (Largest files rows). */
        private fun folderOf(n: SNode): String {
            val p = n.path(); val i = p.lastIndexOf('/')
            return if (i <= 0) "/" else p.substring(0, i)
        }

        /** [s] cut to [maxW] pixels with an ellipsis (uses the current paint). */
        private fun fit(s: String, maxW: Float): String {
            if (s.isEmpty() || maxW <= 0f) return ""
            val cnt = tp.breakText(s, true, maxW, null)
            if (cnt >= s.length) return s
            return if (cnt <= 1) s.substring(0, cnt) else s.substring(0, cnt - 1) + "…"
        }

        private fun mix(a: Int, b: Int, t: Float): Int {
            val u = 1f - t
            return Color.rgb(
                (Color.red(a) * u + Color.red(b) * t).toInt(),
                (Color.green(a) * u + Color.green(b) * t).toInt(),
                (Color.blue(a) * u + Color.blue(b) * t).toInt())
        }
    }

    companion object {
        /** Category names, SCat order: other img vid aud doc arc app. */
        private val LABELS = arrayOf("Other", "Images", "Video", "Audio", "Documents", "Archives", "Apps")
    }
}
