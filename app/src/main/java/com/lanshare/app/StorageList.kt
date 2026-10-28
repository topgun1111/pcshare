package com.lanshare.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
 * Part 9a: multi-select (long-press a row = select mode, tap toggles a tick box, [checkedNodes], [endMulti]) for the batch delete.
 * Tree view (WizTree style): folders expand in place under their own row (chevron at the left, indented children, % / bar relative to the parent folder).
 * Tap the chevron = expand / collapse; tap the row = [onTap] as before.
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
    private var parentSize = 0L                     // 100 % of the share bar / percent column (top level)
    // tree view: rows are the visible nodes in depth-first order; [depths] / [psz] (parent size = 100 %) run parallel to [kids]
    private val expanded = java.util.HashSet<SNode>()
    private var depths = IntArray(0)
    private var psz = LongArray(0)
    private var treeOn = false
    private var topList = ArrayList<SNode>()
    private var selNode: SNode? = null

    // part 7: source of the rows, category filter, flat mode (Largest files tab)
    private var src: Array<SNode> = SNode.NONE
    private var srcRoot: SNode? = null
    private var flatTotal = 0L
    private var flat = false
    private var cat = -1                            // SCat index to show only, -1 = all

    /** Currently selected node (null = none). */
    val selected: SNode? get() = selNode

    // part 9a: multi-select (batch delete). Long-press starts it, a tap toggles a row, [onMulti] tells the screen to repaint its bar.
    var onMulti: (() -> Unit)? = null
    private val checked = java.util.LinkedHashSet<SNode>()
    /** True while the list is in select mode (taps toggle ticks instead of opening / describing). */
    var multi = false
        private set
    /** Part 9b: false on another volume (delete is only offered on the main storage), so a long-press does nothing. */
    var canSelect = true

    /** Ticked nodes in the order they were ticked (copy). */
    fun checkedNodes(): List<SNode> = ArrayList(checked)

    /** Leave select mode and drop all ticks. */
    fun endMulti() {
        if (!multi && checked.isEmpty()) return
        multi = false; checked.clear()
        adapterImpl.notifyDataSetChanged()
        onMulti?.invoke()
    }

    private fun toggle(n: SNode) {
        if (n.agg) return                       // the "small files" node cannot be deleted from here
        if (!checked.remove(n)) checked.add(n)
        if (checked.isEmpty()) multi = false    // last tick removed: select mode ends
        adapterImpl.notifyDataSetChanged()
        onMulti?.invoke()
    }

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val adapterImpl = object : BaseAdapter() {
        override fun getCount() = kids.size
        override fun getItem(position: Int): Any = kids[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = (convertView as? RowView) ?: RowView(context)
            v.bind(kids[position], kids[position] === selNode, multi, checked.contains(kids[position]), depths[position], psz[position])
            return v
        }
    }

    init {
        contentDescription = "Size list"
        divider = null
        dividerHeight = 0
        isVerticalScrollBarEnabled = true
        setAdapter(adapterImpl)
        setOnItemClickListener { _, view, pos, _ ->
            if (pos in kids.indices) {
                if ((view as? RowView)?.hitChevron() == true) { toggleExpand(kids[pos]); return@setOnItemClickListener }   // chevron: expand / collapse in place
                if (multi) { toggle(kids[pos]); return@setOnItemClickListener }   // select mode: a tap ticks / unticks
                selNode = kids[pos]
                adapterImpl.notifyDataSetChanged()
                onTap?.invoke(kids[pos])
            }
        }
        setOnItemLongClickListener { _, _, pos, _ ->
            if (canSelect && pos in kids.indices && !kids[pos].agg) {
                if (!multi) { multi = true; selNode = null }
                if (!checked.contains(kids[pos])) toggle(kids[pos]) else adapterImpl.notifyDataSetChanged()
                true
            } else false
        }
    }

    /** The folder whose kids are listed (null = empty list). The list scrolls back to the top. */
    fun setRoot(r: SNode?) {
        flat = false; srcRoot = r; src = r?.kids ?: SNode.NONE
        selNode = null; expanded.clear()
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

    /** Sort order of the rows: 0 size (largest first, the scan's own order), 1 name, 2 file count, 3 type, 4 size (smallest first). */
    private var sort = 0
    private var query = ""

    fun setSort(m: Int) {
        if (m == sort) return
        sort = m; selNode = null
        rebuild()
    }

    /** Only rows whose name contains [q] (case-insensitive; empty = all). The "small files" row is hidden while a query is active. */
    fun setQuery(q: String) {
        if (q == query) return
        query = q; selNode = null
        rebuild()
    }

    private fun typeKey(n: SNode) = if (n.dir) 0 else 1 + n.cat.coerceIn(0, SCat.COUNT - 1)

    /** Rows of [arr] after the category filter / name query, in the chosen sort order. */
    private fun prepare(arr: Array<SNode>): ArrayList<SNode> {
        val l = ArrayList<SNode>(arr.size)
        for (k in arr) {
            if (cat >= 0 && (k.agg || rowSize(k) <= 0)) continue
            if (query.isNotEmpty() && (k.agg || !k.name.contains(query, ignoreCase = true))) continue
            l.add(k)
        }
        when (sort) {
            1 -> l.sortWith(compareBy<SNode>({ it.agg }, { it.name.lowercase() }))
            2 -> l.sortWith(compareBy<SNode>({ it.agg }, { -(if (it.dir) it.files else 1L) }, { -rowSize(it) }))
            3 -> l.sortWith(compareBy<SNode>({ it.agg }, { typeKey(it) }, { -rowSize(it) }))
            4 -> l.sortWith(compareBy<SNode>({ it.agg }, { rowSize(it) }))
            else -> if (cat >= 0 && !flat) l.sortByDescending { rowSize(it) }
        }
        return l
    }

    private fun isExpandable(n: SNode) = n.dir && !n.agg && n.kids.isNotEmpty()

    /** Visible rows = top level + the kids of every expanded folder below it (depth first). Ticks and scroll position stay. */
    private fun flatten() {
        treeOn = !flat && query.isEmpty()
        val outN = ArrayList<SNode>(topList.size)
        val outD = ArrayList<Int>(topList.size)
        val outP = ArrayList<Long>(topList.size)
        fun add(list: List<SNode>, depth: Int, p: Long) {
            for (n in list) {
                outN.add(n); outD.add(depth); outP.add(p)
                if (treeOn && expanded.contains(n) && isExpandable(n)) add(prepare(n.kids), depth + 1, rowSize(n))
            }
        }
        add(topList, 0, parentSize)
        kids = outN.toTypedArray()
        depths = IntArray(outD.size) { outD[it] }
        psz = LongArray(outP.size) { outP[it] }
    }

    private fun toggleExpand(n: SNode) {
        if (!isExpandable(n)) return
        if (!expanded.remove(n)) expanded.add(n)
        flatten()
        adapterImpl.notifyDataSetChanged()
    }

    private fun rebuild() {
        setBackgroundColor(colBg)
        if (multi || checked.isNotEmpty()) { multi = false; checked.clear(); onMulti?.invoke() }   // new rows: ticks are void
        topList = prepare(src)
        parentSize = if (cat < 0) (if (flat) flatTotal else srcRoot?.size ?: 0L)
        else (if (flat) flatTotal else srcRoot?.let { r -> r.cats?.let { if (cat < it.size) it[cat] else 0L } } ?: 0L)
        flatten()
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
        private var inMulti = false
        private var tick = false
        private var depth = 0
        private var parentSz = 0L
        private var downX = 0f
        private val fill = Paint().apply { style = Paint.Style.FILL }
        private val tp = Paint(Paint.ANTI_ALIAS_FLAG)

        init { layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH()) }

        private fun rowH() = (dp(56f) * Math.max(1f, resources.configuration.fontScale)).toInt()

        override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
            if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) downX = e.x
            return super.onTouchEvent(e)
        }

        /** True when the last touch landed on this row's chevron (a folder that can be expanded). */
        fun hitChevron(): Boolean {
            val n = nd ?: return false
            if (!treeOn || !isExpandable(n)) return false
            val x0 = dp(14f) + (if (inMulti) dp(30f) else 0f) + dp(16f) * depth
            return downX >= x0 - dp(6f) && downX <= x0 + dp(34f)
        }

        fun bind(n: SNode, selected: Boolean, multiMode: Boolean, ticked: Boolean, dep: Int, parentBytes: Long) {
            nd = n; sel = selected; inMulti = multiMode; tick = ticked; depth = dep; parentSz = parentBytes
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
            if (sel || tick) { fill.color = mix(colBg, colAcc, if (tick) 0.22f else 0.16f); canvas.drawRect(0f, 0f, w, h, fill) }
            // part 9a: tick box at the left in select mode (the "small files" row has none)
            val shift = if (inMulti) dp(30f) else 0f
            if (inMulti && !n.agg) {
                val bx = padX; val by0 = (h - dp(20f)) / 2f; val bs = dp(20f)
                if (tick) {
                    fill.color = colAcc; canvas.drawRoundRect(bx, by0, bx + bs, by0 + bs, dp(6f), dp(6f), fill)
                    tp.style = Paint.Style.STROKE; tp.strokeWidth = dp(2.2f); tp.color = Color.WHITE; tp.strokeCap = Paint.Cap.ROUND
                    canvas.drawLine(bx + bs * 0.24f, by0 + bs * 0.54f, bx + bs * 0.43f, by0 + bs * 0.72f, tp)
                    canvas.drawLine(bx + bs * 0.43f, by0 + bs * 0.72f, bx + bs * 0.78f, by0 + bs * 0.30f, tp)
                    tp.style = Paint.Style.FILL; tp.strokeWidth = 0f
                } else {
                    tp.style = Paint.Style.STROKE; tp.strokeWidth = dp(1.6f); tp.color = colDim
                    canvas.drawRoundRect(bx + 1f, by0 + 1f, bx + bs - 1f, by0 + bs - 1f, dp(4f), dp(4f), tp)
                    tp.style = Paint.Style.FILL; tp.strokeWidth = 0f
                }
            }

            val cat = if (n.agg) SCat.OTHER else if (n.dir) (if (this@StorageList.cat >= 0) this@StorageList.cat else domCat(n)) else n.cat.coerceIn(0, SCat.COUNT - 1)
            val chipCol = if (n.agg) mix(colBg, colFg, 0.30f) else StorageTreemap.CAT_COL[cat]

            // chip: file = filled square, folder = darker square with a lighter inner square (reads as "box")
            val indent = if (treeOn) dp(16f) * depth + dp(20f) else 0f
            val cs = dp(12f); val cx = padX + shift + indent; val cy = dp(12f)
            if (treeOn && isExpandable(n)) {      // chevron: right = collapsed, down = expanded
                val x0 = padX + shift + dp(16f) * depth; val yc = cy + cs / 2f
                val path = Path()
                if (expanded.contains(n)) { path.moveTo(x0 + dp(3f), yc - dp(3f)); path.lineTo(x0 + dp(13f), yc - dp(3f)); path.lineTo(x0 + dp(8f), yc + dp(4f)) }
                else { path.moveTo(x0 + dp(5f), yc - dp(5f)); path.lineTo(x0 + dp(5f), yc + dp(5f)); path.lineTo(x0 + dp(12f), yc) }
                path.close()
                fill.color = colDim; canvas.drawPath(path, fill)
            }
            fill.color = if (n.dir && !n.agg) mix(chipCol, 0xFF000000.toInt(), 0.28f) else chipCol
            canvas.drawRoundRect(cx, cy, cx + cs, cy + cs, dp(2f), dp(2f), fill)
            if (n.dir && !n.agg) { fill.color = chipCol; canvas.drawRect(cx + dp(3f), cy + dp(3f), cx + cs - dp(3f), cy + cs - dp(3f), fill) }

            // right column: size (bold) + % of the folder
            val sz = rowSize(n)
            val pct = if (parentSz > 0) sz * 100.0 / parentSz else 0.0
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
            val bx0 = if (treeOn) cx else padX; val bx1 = w - padX
            fill.color = colLine
            canvas.drawRoundRect(bx0, by, bx1, by + bh, bh / 2, bh / 2, fill)
            val frac = if (parentSz > 0) (sz.toDouble() / parentSz).coerceIn(0.0, 1.0) else 0.0
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
        /** Sort menu entries, index = [setSort] mode. */
        val SORT_LABELS = arrayOf("Size (largest)", "Name (A\u2013Z)", "Files (most)", "Type", "Size (smallest)")
        /** Category names, SCat order: other img vid aud doc arc app. */
        val LABELS = arrayOf("Other", "Images", "Video", "Audio", "Documents", "Archives", "Apps")
    }
}
