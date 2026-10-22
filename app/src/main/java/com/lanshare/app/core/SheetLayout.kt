package com.lanshare.app.core

import kotlin.math.max
import kotlin.math.min

/**
 * Print space, part 4 of 10: sheet layout model.
 *
 * Pure Kotlin on purpose (no android.*, no org.json) so it compiles and runs on the JVM, like core/office.
 * Nothing here touches files or bitmaps; it only answers "which picture goes where on which side of which sheet".
 *
 * Units are PDF points (1/72 inch), origin top-left, y grows downwards.
 *
 * Reading frame: slot numbers of a side are counted row by row in the way that side is READ (after turning
 * the sheet over, the back is read upright too). [backSlot] tells which back slot lies physically behind a
 * front slot for a given flip edge. Printers with a duplex option do the real flipping themselves, so the
 * renderer (part 6) can put the back page into the PDF exactly as it is read.
 *
 * Geometry matches pvSheets() in ui.html and images_to_sheets() in pcprint.py for single-sided FIT / FILL
 * (same paper sizes, grid presets, default margin 14 / gap 8, auto turn by 90 deg, auto portrait / landscape).
 */
object SheetLayout {

    enum class Flip { LONG, SHORT }
    enum class Fit { FIT, FILL }
    enum class Orient { AUTO, PORTRAIT, LANDSCAPE }
    enum class Side { FRONT, BACK }

    /** FRONT_ONLY: one side. BOTH_SIDES: fill the front, then the back, sheet by sheet.
     *  PAIRS: pictures 0+1, 2+3, ... are (front, behind it), e.g. both sides of an ID card. */
    enum class Mode { FRONT_ONLY, BOTH_SIDES, PAIRS }

    data class Paper(val name: String, val w: Double, val h: Double)

    val PAPERS: List<Paper> = listOf(
        Paper("A3", 842.0, 1191.0), Paper("A4", 595.0, 842.0), Paper("A5", 420.0, 595.0),
        Paper("Letter", 612.0, 792.0), Paper("Legal", 612.0, 1008.0)
    )

    /** Unknown or empty names give A4 (same as ui.html). */
    fun paper(name: String?): Paper = PAPERS.firstOrNull { p -> p.name.equals(name, ignoreCase = true) } ?: PAPERS[1]

    /** Grid in PORTRAIT form; a landscape sheet uses [turned]. */
    data class Grid(val cols: Int, val rows: Int) {
        val per: Int get() = cols * rows
        fun turned(): Grid = Grid(rows, cols)
    }

    /** Presets 1 / 2 / 4 / 6 / 9 pictures per sheet (portrait form, same as PV_GRID in ui.html). */
    val PRESETS: Map<Int, Grid> = mapOf(1 to Grid(1, 1), 2 to Grid(1, 2), 4 to Grid(2, 2), 6 to Grid(2, 3), 9 to Grid(3, 3))

    const val MAX_AXIS = 8
    const val MAX_ENTRIES = 5000
    const val MIN_CELL = 20.0

    /** A picture as it LOOKS (EXIF turn already applied), plus the manual turn and the number of copies. */
    data class Pic(val w: Int, val h: Int, val rot: Int = 0, val copies: Int = 1)

    data class Spec(
        val paper: String = "A4",
        val orient: Orient = Orient.AUTO,
        val grid: Grid = Grid(1, 1),
        val margin: Double = 14.0,
        val gap: Double = 8.0,
        val fit: Fit = Fit.FIT,
        val flip: Flip = Flip.LONG,
        val autoRot: Boolean = true,
        val reverse: Boolean = false,
        /** Free placement: the grid is ignored and every side carries [Free] items placed anywhere (move / turn / scale). */
        val free: Boolean = false
    ) {
        companion object {
            fun preset(n: Int): Grid = PRESETS[n] ?: Grid(1, 1)
        }
    }

    /**
     * Free placement: one picture put anywhere on a side. Fractions of the sheet so the layout survives a paper change:
     * [cx] / [cy] = centre as a fraction of the sheet width / height, [w] = drawn width of the UNTURNED picture as a fraction of the sheet
     * width (height follows the picture's proportions), [rot] = extra clockwise turn in degrees (any value, on top of the picture's own turn).
     * The order of a list is the stacking order (last = on top). Pictures may overlap and hang over the edge (they are clipped by the sheet).
     */
    data class Free(val pic: Int, val cx: Double, val cy: Double, val w: Double, val rot: Double = 0.0)

    const val FREE_MIN_W = 0.03
    const val FREE_MAX_W = 3.0
    const val MAX_FREE = 60

    /** One sheet. front / back have one entry per slot: index into the picture list, or null = empty. [ff] / [fb] = free items of the front / back (spec.free). */
    data class Sheet(val front: List<Int?>, val back: List<Int?>, val ff: List<Free> = emptyList(), val fb: List<Free> = emptyList()) {
        fun free(s: Side): List<Free> = if (s == Side.FRONT) ff else fb
        fun withFree(s: Side, l: List<Free>): Sheet = if (s == Side.FRONT) copy(ff = l) else copy(fb = l)
        fun side(s: Side): List<Int?> = if (s == Side.FRONT) front else back
        fun with(s: Side, slot: Int, pic: Int?): Sheet {
            val l = side(s).toMutableList()
            if (slot !in l.indices) return this
            l[slot] = pic
            return if (s == Side.FRONT) copy(front = l) else copy(back = l)
        }
    }

    /** Sheets plus the orientation they were built for (slot numbers depend on it). */
    data class Plan(val portrait: Boolean, val sheets: List<Sheet>)

    data class SlotRef(val sheet: Int, val side: Side, val slot: Int)

    /** One placed picture. x / y = top-left of the drawn box, which is the cell box in FILL (cropped) and the picture box in FIT. */
    data class Cell(
        val slot: Int, val pic: Int, val rot: Int,
        val k: Double, val fk: Double,
        val w: Double, val h: Double, val cx: Double, val cy: Double,
        val cw: Double, val ch: Double
    ) {
        val x: Double get() = cx - w / 2
        val y: Double get() = cy - h / 2
    }

    /** A free item placed on a page, in points: draws the UNTURNED picture [dw] x [dh] centred on ([cx], [cy]) turned by [rot] degrees (picture turn included). */
    data class FreeCell(val pic: Int, val cx: Double, val cy: Double, val dw: Double, val dh: Double, val rot: Double)

    data class Page(val sheet: Int, val side: Side, val cells: List<Cell>, val free: List<FreeCell> = emptyList())

    /** pages are in print order: sheet 0 front, sheet 0 back, sheet 1 front ... (back pages only when any sheet has a back). */
    data class Layout(
        val portrait: Boolean, val sw: Double, val sh: Double, val grid: Grid,
        val pages: List<Page>, val score: Double
    )

    // ---------------------------------------------------------------- spec

    /** Clamps everything to safe values (margin 0-72 pt, gap 0-40 pt, grid 1-8 per axis). */
    fun clean(s: Spec): Spec = s.copy(
        grid = Grid(s.grid.cols.coerceIn(1, MAX_AXIS), s.grid.rows.coerceIn(1, MAX_AXIS)),
        margin = s.margin.coerceIn(0.0, 72.0),
        gap = s.gap.coerceIn(0.0, 40.0)
    )

    fun gridFor(s: Spec, portrait: Boolean): Grid = if (portrait) s.grid else s.grid.turned()

    /** Returns null when the spec is usable, else a short reason for a toast. */
    fun validate(s0: Spec): String? {
        val s = clean(s0)
        val p = paper(s.paper)
        val orients = when (s.orient) {
            Orient.PORTRAIT -> listOf(true)
            Orient.LANDSCAPE -> listOf(false)
            Orient.AUTO -> listOf(true, false)
        }
        for (portrait in orients) {
            val g = gridFor(s, portrait)
            val sw = if (portrait) p.w else p.h
            val sh = if (portrait) p.h else p.w
            val cw = (sw - 2 * s.margin - s.gap * (g.cols - 1)) / g.cols
            val ch = (sh - 2 * s.margin - s.gap * (g.rows - 1)) / g.rows
            if (cw < MIN_CELL || ch < MIN_CELL) return "Too many pictures for this margin / gap"
        }
        return null
    }

    // ---------------------------------------------------------------- flip

    /**
     * Back slot that lies physically behind a front slot.
     * Long edge on a portrait sheet (book) mirrors the columns; short edge (calendar) mirrors the rows.
     * On a landscape sheet the physical long edge is the horizontal one, so the two swap.
     */
    fun backSlot(slot: Int, g: Grid, flip: Flip, portrait: Boolean): Int {
        val c = slot % g.cols
        val r = slot / g.cols
        val mirrorCols = (flip == Flip.LONG) == portrait
        return if (mirrorCols) r * g.cols + (g.cols - 1 - c) else (g.rows - 1 - r) * g.cols + c
    }

    /** Same, for a plan and spec. */
    fun backOf(plan: Plan, spec: Spec, frontSlot: Int): Int {
        val s = clean(spec)
        return backSlot(frontSlot, gridFor(s, plan.portrait), s.flip, plan.portrait)
    }

    // ---------------------------------------------------------------- filling

    private fun pad(l: List<Int>, n: Int): List<Int?> = List(n) { i -> l.getOrNull(i) }
    private fun blank(n: Int): List<Int?> = List(n) { null }
    private fun copiesOf(p: Pic): Int = p.copies.coerceIn(1, 99)

    /** Picture indices in print order, one entry per copy. */
    private fun expand(pics: List<Pic>, reverse: Boolean): List<Int> {
        val order = pics.indices.toList().let { l -> if (reverse) l.reversed() else l }
        val out = ArrayList<Int>()
        for (i in order) {
            repeat(copiesOf(pics[i])) { if (out.size < MAX_ENTRIES) out.add(i) }
        }
        return out
    }

    private fun frontOnly(seq: List<Int>, per: Int): List<Sheet> =
        seq.chunked(per).map { c -> Sheet(pad(c, per), blank(per)) }

    /** Fills sheets automatically. AUTO orientation is decided here (once, for all sheets, like pvSheets). */
    fun autoFill(spec0: Spec, pics: List<Pic>, mode: Mode): Plan {
        val s = clean(spec0)
        if (pics.isEmpty()) return Plan(s.orient != Orient.LANDSCAPE, emptyList())
        val seq = expand(pics, s.reverse && mode != Mode.PAIRS)
        val portrait = when (s.orient) {
            Orient.PORTRAIT -> true
            Orient.LANDSCAPE -> false
            Orient.AUTO -> chooseOrient(s, pics, seq)
        }
        val g = gridFor(s, portrait)
        val per = g.per
        val sheets: List<Sheet> = when (mode) {
            Mode.FRONT_ONLY -> frontOnly(seq, per)
            Mode.BOTH_SIDES -> seq.chunked(per * 2).map { c -> Sheet(pad(c.take(per), per), pad(c.drop(per), per)) }
            Mode.PAIRS -> {
                // the copies of the FRONT picture decide how often the pair repeats
                val pairs = ArrayList<Pair<Int, Int?>>()
                var i = 0
                while (i < pics.size) {
                    val back = if (i + 1 < pics.size) i + 1 else null
                    repeat(copiesOf(pics[i])) { if (pairs.size < MAX_ENTRIES) pairs.add(Pair(i, back)) }
                    i += 2
                }
                pairs.chunked(per).map { c ->
                    val f = MutableList<Int?>(per) { null }
                    val b = MutableList<Int?>(per) { null }
                    c.forEachIndexed { j, pr ->
                        f[j] = pr.first
                        b[backSlot(j, g, s.flip, portrait)] = pr.second
                    }
                    Sheet(f, b)
                }
            }
        }
        return Plan(portrait, sheets)
    }

    /**
     * Refills after the grid, paper or orientation changed: keeps the order of what is placed (sheet, front, back)
     * and drops the gaps. Pairs and hand-made gaps are NOT kept; call [autoFill] with PAIRS again for those.
     */
    fun reflow(spec0: Spec, pics: List<Pic>, plan: Plan, duplex: Boolean): Plan {
        val s = clean(spec0)
        val flat = ArrayList<Int>()
        for (sh in plan.sheets) {
            for (e in sh.front) if (e != null && e in pics.indices) flat.add(e)
            for (e in sh.back) if (e != null && e in pics.indices) flat.add(e)
        }
        if (flat.isEmpty()) return Plan(plan.portrait, emptyList())
        val portrait = when (s.orient) {
            Orient.PORTRAIT -> true
            Orient.LANDSCAPE -> false
            Orient.AUTO -> plan.portrait
        }
        val per = gridFor(s, portrait).per
        val sheets = if (duplex) flat.chunked(per * 2).map { c -> Sheet(pad(c.take(per), per), pad(c.drop(per), per)) }
        else frontOnly(flat, per)
        return Plan(portrait, sheets)
    }

    // ---------------------------------------------------------------- editing (immutable)

    fun assign(plan: Plan, at: SlotRef, pic: Int?): Plan {
        if (at.sheet !in plan.sheets.indices) return plan
        val l = plan.sheets.toMutableList()
        l[at.sheet] = l[at.sheet].with(at.side, at.slot, pic)
        return plan.copy(sheets = l)
    }

    fun swap(plan: Plan, a: SlotRef, b: SlotRef): Plan {
        if (a.sheet !in plan.sheets.indices || b.sheet !in plan.sheets.indices) return plan
        val sa = plan.sheets[a.sheet].side(a.side)
        val sb = plan.sheets[b.sheet].side(b.side)
        if (a.slot !in sa.indices || b.slot !in sb.indices) return plan
        val pa = sa[a.slot]
        val pb = sb[b.slot]
        return assign(assign(plan, a, pb), b, pa)
    }

    /** Puts [pic] on the back, behind the given front slot (honours the flip edge). */
    fun placeBehind(plan: Plan, spec: Spec, sheet: Int, frontSlot: Int, pic: Int?): Plan =
        assign(plan, SlotRef(sheet, Side.BACK, backOf(plan, spec, frontSlot)), pic)

    fun addSheet(plan: Plan, spec: Spec): Plan {
        val per = gridFor(clean(spec), plan.portrait).per
        return plan.copy(sheets = plan.sheets + Sheet(blank(per), blank(per)))
    }

    /** Drops EMPTY sheets at the end only (a gap in the middle is a choice). */
    fun trim(plan: Plan): Plan {
        var n = plan.sheets.size
        while (n > 0 && isBlank(plan.sheets[n - 1])) n--
        return plan.copy(sheets = plan.sheets.take(n))
    }

    /** Removes one sheet (front and back). */
    fun removeSheet(plan: Plan, sheet: Int): Plan =
        if (sheet !in plan.sheets.indices) plan else plan.copy(sheets = plan.sheets.filterIndexed { i, _ -> i != sheet })

    private fun isBlank(sh: Sheet): Boolean = sh.front.all { e -> e == null } && sh.back.all { e -> e == null } && sh.ff.isEmpty() && sh.fb.isEmpty()

    fun placedCount(plan: Plan): Int = plan.sheets.sumOf { sh -> sh.front.count { e -> e != null } + sh.back.count { e -> e != null } + sh.ff.size + sh.fb.size }

    fun hasBack(plan: Plan): Boolean = plan.sheets.any { sh -> sh.back.any { e -> e != null } || sh.fb.isNotEmpty() }

    // ---------------------------------------------------------------- geometry

    private fun norm(r: Int): Int = (((r % 360) + 360) % 360 / 90) * 90

    private class Fitted(val rot: Int, val fk: Double, val dw: Double, val dh: Double)

    /** Best of "as is" and "turned by 90" (when autoRot), like images_to_sheets(). Ties keep the first. */
    private fun bestTurn(p: Pic, cw: Double, ch: Double, autoRot: Boolean): Fitted {
        var b: Fitted? = null
        val extra = if (autoRot) intArrayOf(0, 90) else intArrayOf(0)
        for (ex in extra) {
            val r = (norm(p.rot) + ex) % 360
            val turned = r % 180 != 0
            val w = max(1, p.w).toDouble()
            val h = max(1, p.h).toDouble()
            val dw = if (turned) h else w
            val dh = if (turned) w else h
            val k = min(cw / dw, ch / dh)
            if (b == null || k > b.fk * 1.0001) b = Fitted(r, k, dw, dh)
        }
        return b!!
    }

    private fun cellFor(slot: Int, picIdx: Int, p: Pic, g: Grid, s: Spec, sw: Double, sh: Double): Cell {
        val cw = max(1.0, (sw - 2 * s.margin - s.gap * (g.cols - 1)) / g.cols)
        val ch = max(1.0, (sh - 2 * s.margin - s.gap * (g.rows - 1)) / g.rows)
        val b = bestTurn(p, cw, ch, s.autoRot)
        val fill = s.fit == Fit.FILL
        val kk = if (fill) max(cw / b.dw, ch / b.dh) else b.fk
        val col = slot % g.cols
        val row = slot / g.cols
        return Cell(
            slot = slot, pic = picIdx, rot = b.rot, k = kk, fk = b.fk,
            w = if (fill) cw else b.dw * kk, h = if (fill) ch else b.dh * kk,
            cx = s.margin + col * (cw + s.gap) + cw / 2, cy = s.margin + row * (ch + s.gap) + ch / 2,
            cw = cw, ch = ch
        )
    }

    class Box(val x: Double, val y: Double, val w: Double, val h: Double)

    /** Cell boxes of ONE side of a sheet, EMPTY slots included (slot order = row by row). The editor draws its drop targets from these. */
    fun slotBoxes(spec0: Spec, portrait: Boolean): List<Box> {
        val s = clean(spec0)
        val pp = paper(s.paper)
        val sw = if (portrait) pp.w else pp.h
        val sh = if (portrait) pp.h else pp.w
        val g = gridFor(s, portrait)
        val cw = max(1.0, (sw - 2 * s.margin - s.gap * (g.cols - 1)) / g.cols)
        val ch = max(1.0, (sh - 2 * s.margin - s.gap * (g.rows - 1)) / g.rows)
        return List(g.per) { slot ->
            Box(s.margin + (slot % g.cols) * (cw + s.gap), s.margin + (slot / g.cols) * (ch + s.gap), cw, ch)
        }
    }

    /** Builds the pages of a plan. Entries pointing outside the picture list are skipped. */
    fun compose(spec0: Spec, pics: List<Pic>, plan: Plan): Layout {
        val s = clean(spec0)
        val pp = paper(s.paper)
        val portrait = plan.portrait
        val sw = if (portrait) pp.w else pp.h
        val sh = if (portrait) pp.h else pp.w
        val g = gridFor(s, portrait)
        val anyBack = hasBack(plan)
        var score = 0.0
        val pages = ArrayList<Page>()
        plan.sheets.forEachIndexed { si, sheet ->
            for (side in Side.values()) {
                if (side == Side.BACK && !anyBack) continue
                val cells = ArrayList<Cell>()
                if (!s.free) sheet.side(side).forEachIndexed { slot, e ->
                    if (e != null && e in pics.indices && slot < g.per) {
                        val c = cellFor(slot, e, pics[e], g, s, sw, sh)
                        score += c.fk * c.fk * max(1, pics[e].w).toDouble() * max(1, pics[e].h).toDouble()
                        cells.add(c)
                    }
                }
                val fr = ArrayList<FreeCell>()
                for (f in sheet.free(side)) {
                    if (f.pic !in pics.indices || fr.size >= MAX_FREE) continue
                    val fc = freeCell(f, pics[f.pic], sw, sh)
                    score += (fc.dw * fc.dh)
                    fr.add(fc)
                }
                pages.add(Page(si, side, cells, fr))
            }
        }
        return Layout(portrait, sw, sh, g, pages, score)
    }

    /** Free item -> points. The picture's own turn (multiples of 90) is added to the item's turn; a quarter turn swaps width and height of the LOOK, not of the drawing. */
    fun freeCell(f: Free, p: Pic, sw: Double, sh: Double): FreeCell {
        val pw = max(1, p.w).toDouble()
        val ph = max(1, p.h).toDouble()
        val dw = f.w.coerceIn(FREE_MIN_W, FREE_MAX_W) * sw
        val dh = dw * ph / pw
        return FreeCell(f.pic, f.cx.coerceIn(-1.0, 2.0) * sw, f.cy.coerceIn(-1.0, 2.0) * sh, dw, dh, (norm(p.rot) + f.rot) % 360.0)
    }

    /** Replaces the free items of one side (the editor sends the whole side after a gesture). Sheets are added when [sheet] is the next one. */
    fun setFree(plan: Plan, spec: Spec, sheet: Int, side: Side, items: List<Free>): Plan {
        if (sheet !in plan.sheets.indices) return plan
        val l = plan.sheets.toMutableList()
        l[sheet] = l[sheet].withFree(side, items.take(MAX_FREE))
        return plan.copy(sheets = l)
    }

    /**
     * Puts picture [pic] on a side as a free item, as big as fits in half the sheet (both ways), at ([cx], [cy]) (fractions) or - when null - near the centre,
     * each new item a little off the last so they do not hide each other. A plan with no sheet gets one. Same item can be added many times.
     */
    fun addFree(plan0: Plan, spec: Spec, pics: List<Pic>, sheet: Int, side: Side, pic: Int, cx: Double?, cy: Double?): Plan {
        if (pic !in pics.indices) return plan0
        val plan = if (plan0.sheets.isEmpty()) addSheet(plan0, spec) else plan0
        if (sheet !in plan.sheets.indices) return plan
        val pp = paper(clean(spec).paper)
        val sw = if (plan.portrait) pp.w else pp.h
        val sh = if (plan.portrait) pp.h else pp.w
        val p = pics[pic]
        val pw = max(1, p.w).toDouble()
        val ph = max(1, p.h).toDouble()
        val turned = norm(p.rot) % 180 != 0
        // unit item: width = whole sheet width (dw = sw), height = sw * ph / pw; the footprint swaps for a quarter turn
        val fw = if (turned) sw * ph / pw else sw
        val fh = if (turned) sw else sw * ph / pw
        val k = min(0.5 * sw / fw, 0.5 * sh / fh).coerceIn(FREE_MIN_W, FREE_MAX_W)
        val cur = plan.sheets[sheet].free(side)
        val off = 0.04 * (cur.size % 5)
        val it = Free(pic, cx ?: (0.5 + off - 0.08), cy ?: (0.5 + off - 0.08), k, 0.0)
        return setFree(plan, spec, sheet, side, cur + it)
    }

    /**
     * Grid -> free: every placed picture becomes a free item at the place it has now (FIT size / FILL box), so the person can start from
     * a grid and then move things. Back slots keep the way they are READ. Result has the same orientation; set spec.free afterwards.
     */
    fun gridToFree(spec0: Spec, pics: List<Pic>, plan: Plan): Plan {
        val s = clean(spec0).copy(free = false, fit = Fit.FIT)   // FILL crops inside the cell; a free item is not cropped, so it starts at its FIT size
        val lay = compose(s, pics, plan)
        val sheets = plan.sheets.mapIndexed { i, sh ->
            fun conv(side: Side): List<Free> {
                val pg = lay.pages.firstOrNull { it.sheet == i && it.side == side } ?: return emptyList()
                return pg.cells.map { c ->
                    val pw = max(1, pics[c.pic].w).toDouble()
                    // turn the cell shows = c.rot; item turn = c.rot - picture turn
                    val own = norm(pics[c.pic].rot)
                    val w = pw * c.k
                    Free(c.pic, c.cx / lay.sw, c.cy / lay.sh, w / lay.sw, ((c.rot - own) % 360 + 360) % 360.0)
                }
            }
            sh.copy(ff = conv(Side.FRONT), fb = conv(Side.BACK))
        }
        return plan.copy(sheets = sheets)
    }

    private fun chooseOrient(s: Spec, pics: List<Pic>, seq: List<Int>): Boolean {
        val per = s.grid.per
        fun scoreOf(portrait: Boolean): Double =
            compose(s, pics, Plan(portrait, frontOnly(seq, per))).score
        val a = scoreOf(true)
        val b = scoreOf(false)
        return !(b > a * 1.0001)
    }
}
