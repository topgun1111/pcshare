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
     *  PAIRS: pictures 0+1, 2+3, ... are (front, behind it), e.g. both sides of an ID card.
     *  FRONT_PLUS_ONE: the front takes the whole grid (e.g. 2 pictures), the back takes ONE picture on the whole sheet (see [Spec.backOne]). */
    enum class Mode { FRONT_ONLY, BOTH_SIDES, PAIRS, FRONT_PLUS_ONE }

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
        val free: Boolean = false,
        /** Part 14a: printer safe margin in pt (the strip along every paper edge a printer cannot print). Free placement keeps pictures inside it (the editor clamps; 14c repeats the clamp here). 0 = off. */
        val safe: Double = 14.0,
        /** Part 14b: free placement keeps the pictures of one side apart (no overlap). The page enforces it while editing; 14c repeats it here. */
        val keep: Boolean = true,
        /** Part 14b: clear space between two pictures in pt (0-40) when [keep] is on. */
        val keepGap: Double = 6.0,
        /** "N + 1": the back side of every sheet is ONE picture on the whole sheet, whatever the front grid is (e.g. 2 on the front, 1 on the back). */
        val backOne: Boolean = false
    ) {
        companion object {
            fun preset(n: Int): Grid = PRESETS[n] ?: Grid(1, 1)
        }
    }

    /**
     * Free placement: one picture put anywhere on a side. Fractions of the sheet so the layout survives a paper change:
     * [cx] / [cy] = centre as a fraction of the sheet width / height, [w] = drawn width of the UNTURNED picture as a fraction of the sheet
     * width (height follows the picture's proportions), [rot] = extra clockwise turn in degrees (any value, on top of the picture's own turn).
     * The order of a list is the stacking order (last = on top). Part 14c: the server keeps them inside the safe rectangle ([clampFree]) and, with Keep apart on, apart ([freeHit]).
     */
    data class Free(val pic: Int, val cx: Double, val cy: Double, val w: Double, val rot: Double = 0.0, val st: Double = 1.0)

    const val FREE_MIN_W = 0.03

    const val FREE_MIN_ST = 0.2

    const val FREE_MAX_ST = 5.0
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

    /** Clamps everything to safe values (margin 0-72 pt, gap 0-40 pt, safe 0-72 pt, grid 1-8 per axis). */
    fun clean(s: Spec): Spec = s.copy(
        grid = Grid(s.grid.cols.coerceIn(1, MAX_AXIS), s.grid.rows.coerceIn(1, MAX_AXIS)),
        margin = s.margin.coerceIn(0.0, 72.0),
        gap = s.gap.coerceIn(0.0, 40.0),
        safe = s.safe.coerceIn(0.0, 72.0),
        keepGap = s.keepGap.coerceIn(0.0, 40.0)
    )

    fun gridFor(s: Spec, portrait: Boolean): Grid = if (portrait) s.grid else s.grid.turned()

    /** Grid of one SIDE: the back is a single big slot when [Spec.backOne] is on, else the same grid as the front. */
    fun gridFor(s: Spec, portrait: Boolean, side: Side): Grid =
        if (side == Side.BACK && s.backOne) Grid(1, 1) else gridFor(s, portrait)

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
        if (s.backOne) return 0   // one big slot on the back: every front slot lies (partly) behind it
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
            // front grid full, then ONE picture for the back (back slot 0); callers set spec.backOne for this mode
            Mode.FRONT_PLUS_ONE -> seq.chunked(per + 1).map { c -> Sheet(pad(c.take(per), per), pad(c.drop(per), per)) }
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
        val sheets = if (duplex && s.backOne) flat.chunked(per + 1).map { c -> Sheet(pad(c.take(per), per), pad(c.drop(per), per)) }
        else if (duplex) flat.chunked(per * 2).map { c -> Sheet(pad(c.take(per), per), pad(c.drop(per), per)) }
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
    fun slotBoxes(spec0: Spec, portrait: Boolean, side: Side = Side.FRONT): List<Box> {
        val s = clean(spec0)
        val pp = paper(s.paper)
        val sw = if (portrait) pp.w else pp.h
        val sh = if (portrait) pp.h else pp.w
        val g = gridFor(s, portrait, side)
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
                val gs = gridFor(s, portrait, side)   // the back can be one big slot (spec.backOne)
                if (!s.free) sheet.side(side).forEachIndexed { slot, e ->
                    if (e != null && e in pics.indices && slot < gs.per) {
                        val c = cellFor(slot, e, pics[e], gs, s, sw, sh)
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
        val dh = dw * ph / pw / stOf(f)
        return FreeCell(f.pic, f.cx.coerceIn(-1.0, 2.0) * sw, f.cy.coerceIn(-1.0, 2.0) * sh, dw, dh, (norm(p.rot) + f.rot) % 360.0)
    }

    // ------------------------------------------------------------------ part 14c: safe margin + keep apart (mirrors fClamp / fHit / fSpot / fPack in ui.html)

    /** Safe rectangle as fractions of the sheet [l, r, t, b]: the paper edge minus [safe] pt, never narrower than 10 % of the sheet (same as fSafeR). */
    fun safeRect(safe: Double, sw: Double, sh: Double): DoubleArray {
        val s = max(0.0, safe)
        val px = min(0.45, s / sw)
        val py = min(0.45, s / sh)
        return doubleArrayOf(px, 1 - px, py, 1 - py)
    }

    /** Aspect of the drawing (height / width), same as FreeCell.dh / dw. */
    private fun aspect(p: Pic): Double = max(1, p.h).toDouble() / max(1, p.w).toDouble()

    /** Side-handle stretch of a free item (width x st at the same height; 1 = picture's own proportions). */
    fun stOf(f: Free): Double = if (f.st.isFinite()) f.st.coerceIn(FREE_MIN_ST, FREE_MAX_ST) else 1.0

    /** Turned bounding box of a free item as fractions of the sheet [bw, bh] (fBox). */
    fun freeBox(f: Free, p: Pic, sw: Double, sh: Double): DoubleArray {
        val t = Math.toRadians(norm(p.rot) + f.rot)
        val c = Math.abs(Math.cos(t))
        val s2 = Math.abs(Math.sin(t))
        val dw = f.w * sw
        val dh = dw * aspect(p) / stOf(f)
        return doubleArrayOf((c * dw + s2 * dh) / sw, (s2 * dw + c * dh) / sh)
    }

    /** Puts the TURNED box of [f] into the safe rectangle: shrinks first (shape kept, width >= FREE_MIN_W), then moves. Idempotent (same maths as fClamp). */
    fun clampFree(f0: Free, p: Pic, sw: Double, sh: Double, safe: Double): Free {
        val r = safeRect(safe, sw, sh)
        var f = f0.copy(st = stOf(f0), w = f0.w.coerceIn(FREE_MIN_W, FREE_MAX_W), cx = if (f0.cx.isFinite()) f0.cx else 0.5, cy = if (f0.cy.isFinite()) f0.cy else 0.5)
        var b = freeBox(f, p, sw, sh)
        val k = min(1.0, min((r[1] - r[0]) / max(b[0], 1e-9), (r[3] - r[2]) / max(b[1], 1e-9)))
        if (k < 1) {
            f = f.copy(w = max(FREE_MIN_W, f.w * k))
            b = freeBox(f, p, sw, sh)
        }
        val cx = if (b[0] >= r[1] - r[0]) (r[0] + r[1]) / 2 else min(r[1] - b[0] / 2, max(r[0] + b[0] / 2, f.cx))
        val cy = if (b[1] >= r[3] - r[2]) (r[2] + r[3]) / 2 else min(r[3] - b[1] / 2, max(r[2] + b[1] / 2, f.cy))
        return f.copy(cx = cx, cy = cy)
    }

    /** The 4 corners (tl, tr, br, bl) of the turned picture in pt. */
    fun freePoly(f: Free, p: Pic, sw: Double, sh: Double): Array<DoubleArray> {
        val th = Math.toRadians(norm(p.rot) + f.rot)
        val c = Math.cos(th)
        val s = Math.sin(th)
        val dw = f.w * sw
        val dh = dw * aspect(p) / stOf(f)
        val out = Array(4) { DoubleArray(2) }
        for (i in 0 until 4) {
            val x = (if (i == 1 || i == 2) 1 else -1) * dw / 2
            val y = (if (i >= 2) 1 else -1) * dh / 2
            out[i][0] = f.cx * sw + x * c - y * s
            out[i][1] = f.cy * sh + x * s + y * c
        }
        return out
    }

    private fun separate(a: Array<DoubleArray>, b: Array<DoubleArray>, slack: Double): Boolean {
        for (poly in arrayOf(a, b)) for (i in 0 until 2) {
            val p = poly[i]
            val q = poly[i + 1]
            val ex = q[0] - p[0]
            val ey = q[1] - p[1]
            val l = Math.hypot(ex, ey).let { if (it == 0.0) 1.0 else it }
            val nx = -ey / l
            val ny = ex / l
            var a0 = 1e18; var a1 = -1e18; var b0 = 1e18; var b1 = -1e18
            for (c in a) { val v = c[0] * nx + c[1] * ny; if (v < a0) a0 = v; if (v > a1) a1 = v }
            for (c in b) { val v = c[0] * nx + c[1] * ny; if (v < b0) b0 = v; if (v > b1) b1 = v }
            if (b0 - a1 >= -1e-6 - slack || a0 - b1 >= -1e-6 - slack) return true   // touching counts as separate
        }
        return false
    }

    private fun distSeg(p: DoubleArray, a: DoubleArray, b: DoubleArray): Double {
        val dx = b[0] - a[0]
        val dy = b[1] - a[1]
        val l2 = dx * dx + dy * dy
        val t = if (l2 != 0.0) max(0.0, min(1.0, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / l2)) else 0.0
        return Math.hypot(p[0] - a[0] - t * dx, p[1] - a[1] - t * dy)
    }

    private fun polyDist(a: Array<DoubleArray>, b: Array<DoubleArray>): Double {
        var d = 1e18
        for (i in 0 until 4) for (j in 0 until 4) d = min(d, min(distSeg(a[i], b[j], b[(j + 1) % 4]), distSeg(b[j], a[i], a[(i + 1) % 4])))
        return d
    }

    /**
     * True when the two turned pictures overlap or are closer than [gap] pt (touching with gap 0 is allowed). Same as fHit; [slack] (pt) makes it
     * forgiving: the page computes in screen pixels, so the wire check (setFree) uses a little slack and never refuses a state the page called legal.
     */
    fun freeHit(f: Free, pf: Pic, g: Free, pg: Pic, sw: Double, sh: Double, gap: Double, slack: Double = 0.0): Boolean {
        val d1 = Math.hypot(f.w * sw, f.w * aspect(pf) / stOf(f) * sw) / 2
        val d2 = Math.hypot(g.w * sw, g.w * aspect(pg) / stOf(g) * sw) / 2
        if (Math.hypot((f.cx - g.cx) * sw, (f.cy - g.cy) * sh) > d1 + d2 + gap + 1e-6) return false   // far apart (no slack needed: the bound is generous)
        val a = freePoly(f, pf, sw, sh)
        val b = freePoly(g, pg, sw, sh)
        return !separate(a, b, slack) || polyDist(a, b) < gap - 1e-6 - slack
    }

    /** Index pairs (i < j) of items of one side that overlap or are closer than [gap]; items whose picture is unknown are ignored. */
    fun freePairs(l: List<Free>, pics: List<Pic>, sw: Double, sh: Double, gap: Double, slack: Double = 0.0): Set<Long> {
        val out = HashSet<Long>()
        for (i in l.indices) {
            val pi = pics.getOrNull(l[i].pic) ?: continue
            for (j in i + 1 until l.size) {
                val pj = pics.getOrNull(l[j].pic) ?: continue
                if (freeHit(l[i], pi, l[j], pj, sw, sh, gap, slack)) out.add(i.toLong() * 100000L + j)
            }
        }
        return out
    }

    private fun anyHit(others: List<Free>, f: Free, p: Pic, pics: List<Pic>, sw: Double, sh: Double, gap: Double): Boolean {
        for (g in others) {
            val pg = pics.getOrNull(g.pic) ?: continue
            if (freeHit(f, p, g, pg, sw, sh, gap)) return true
        }
        return false
    }

    /**
     * First free place for [f] among [others] inside the safe rectangle: the biggest size (down to 6 % of f.w) that has a legal position,
     * nearest to ([tx], [ty]) (fractions). null = no room. Same search as fSpot.
     */
    fun freeSpot(others: List<Free>, f: Free, p: Pic, pics: List<Pic>, sw: Double, sh: Double, safe: Double, gap: Double, tx: Double, ty: Double): Free? {
        val r = safeRect(safe, sw, sh)
        var k = 1.0
        while (k >= 0.06) {
            val g0 = f.copy(w = (f.w * k).coerceIn(FREE_MIN_W, FREE_MAX_W))
            val b = freeBox(g0, p, sw, sh)
            val hx = b[0] / 2
            val hy = b[1] / 2
            if (!(hx * 2 > r[1] - r[0] + 1e-9 || hy * 2 > r[3] - r[2] + 1e-9)) {
                val x0 = r[0] + hx; val x1 = r[1] - hx; val y0 = r[2] + hy; val y1 = r[3] - hy
                val nx = max(1, Math.ceil((x1 - x0) / 0.02).toInt())
                val ny = max(1, Math.ceil((y1 - y0) / 0.02).toInt())
                val cand = ArrayList<DoubleArray>()
                for (i in 0..nx) for (j in 0..ny) {
                    val cx = x0 + (x1 - x0) * i / nx
                    val cy = y0 + (y1 - y0) * j / ny
                    cand.add(doubleArrayOf(cx, cy, (cx - tx) * (cx - tx) + (cy - ty) * (cy - ty)))
                }
                cand.sortBy { it[2] }
                for (c in cand) {
                    val g = g0.copy(cx = c[0], cy = c[1])
                    if (!anyHit(others, g, p, pics, sw, sh, gap)) return g
                }
            }
            k *= 0.85
        }
        return null
    }

    /**
     * Spreads the items of one side apart: shelf packing of the turned boxes inside the safe rectangle, all pictures shrunk by ONE common factor
     * until it fits (turn and shape kept), like fPack. null = does not fit.
     */
    fun packFree(l: List<Free>, pics: List<Pic>, sw: Double, sh: Double, safe: Double, gap: Double): List<Free>? {
        if (l.isEmpty()) return l
        if (l.any { pics.getOrNull(it.pic) == null }) return null
        val r = safeRect(safe, sw, sh)
        var s = 1.0
        while (s > 0.03) {
            val ws = l.map { max(FREE_MIN_W, it.w * s) }
            val bw = DoubleArray(l.size)
            val bh = DoubleArray(l.size)
            l.forEachIndexed { i, f -> val b = freeBox(f.copy(w = ws[i]), pics[f.pic], sw, sh); bw[i] = b[0] * sw; bh[i] = b[1] * sh }
            val order = l.indices.sortedByDescending { bh[it] }
            val pos = arrayOfNulls<Free>(l.size)
            val xl = r[0] * sw; val xr = r[1] * sw; val yt = r[2] * sh; val yb = r[3] * sh
            var x = xl; var y = yt; var rh = 0.0
            var ok = true
            for (i in order) {
                if (bw[i] > xr - xl + 1e-6) { ok = false; break }
                if (x + bw[i] > xr + 1e-6) { x = xl; y += rh + gap; rh = 0.0 }
                if (y + bh[i] > yb + 1e-6) { ok = false; break }
                pos[i] = l[i].copy(w = ws[i], cx = (x + bw[i] / 2) / sw, cy = (y + bh[i] / 2) / sh)
                x += bw[i] + gap
                rh = max(rh, bh[i])
            }
            if (ok) return pos.map { it!! }
            s *= 0.93
        }
        return null
    }

    private fun sheetWH(plan: Plan, spec: Spec): DoubleArray {
        val pp = paper(clean(spec).paper)
        return if (plan.portrait) doubleArrayOf(pp.w, pp.h) else doubleArrayOf(pp.h, pp.w)
    }

    /** pt of tolerance for the keep-apart check of a request from the page (0.05 pt = 0.02 mm). */
    const val WIRE_SLACK = 0.05

    /** keep apart is on: free placement AND spec.keep. */
    fun keepOn(spec: Spec): Boolean = spec.free && spec.keep

    /**
     * Replaces the free items of one side (the editor sends the whole side after a gesture). Sheets are added when [sheet] is the next one.
     * Part 14c: with [pics] every item is put into the safe rectangle (a hand-made request or an old plan cannot reach the strip the printer cannot print),
     * and with Keep apart on a side that has a pair overlapping NOW that did not overlap in the stored side is refused ("Pictures overlap").
     * Pairs that already overlapped (old layouts) are tolerated so they can be moved apart. Items of unknown pictures are kept as sent.
     */
    fun setFree(plan: Plan, spec: Spec, sheet: Int, side: Side, items: List<Free>, pics: List<Pic> = emptyList()): Plan {
        if (sheet !in plan.sheets.indices) return plan
        val s = clean(spec)
        val wh = sheetWH(plan, s)
        val list = items.take(MAX_FREE).map { f ->
            val p = pics.getOrNull(f.pic)
            if (p == null) f else clampFree(f, p, wh[0], wh[1], s.safe)
        }
        if (keepOn(s) && pics.isNotEmpty()) {
            val before = freePairs(plan.sheets[sheet].free(side), pics, wh[0], wh[1], s.keepGap, WIRE_SLACK)
            val now = freePairs(list, pics, wh[0], wh[1], s.keepGap, WIRE_SLACK)
            if (now.any { it !in before }) throw IllegalArgumentException("Pictures overlap (Keep apart is on)")
        }
        val l = plan.sheets.toMutableList()
        l[sheet] = l[sheet].withFree(side, list)
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
        val tx = cx ?: (0.5 + off - 0.08)
        val ty = cy ?: (0.5 + off - 0.08)
        var it = clampFree(Free(pic, tx, ty, k, 0.0), p, sw, sh, clean(spec).safe)
        // part 14c: Keep apart - the first free place (as big as fits, nearest to the wanted spot); no room = refused
        if (keepOn(clean(spec)) && anyHit(cur, it, p, pics, sw, sh, clean(spec).keepGap)) {
            it = freeSpot(cur, it, p, pics, sw, sh, clean(spec).safe, clean(spec).keepGap, it.cx, it.cy)
                ?: throw IllegalArgumentException("No room on this side for another picture")
        }
        return setFree(plan, spec, sheet, side, cur + it, pics)
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
        // part 14c: a grid margin smaller than the safe margin is pulled in; with Keep apart on, a side that touches / overlaps afterwards is packed
        val sp = clean(spec0)
        val wh = sheetWH(plan, sp)
        fun fix(l: List<Free>): List<Free> {
            val c = l.map { f -> pics.getOrNull(f.pic)?.let { clampFree(f, it, wh[0], wh[1], sp.safe) } ?: f }
            if (!sp.keep || c.size < 2 || freePairs(c, pics, wh[0], wh[1], sp.keepGap).isEmpty()) return c
            return packFree(c, pics, wh[0], wh[1], sp.safe, sp.keepGap) ?: c
        }
        return plan.copy(sheets = sheets.map { it.copy(ff = fix(it.ff), fb = fix(it.fb)) })
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
