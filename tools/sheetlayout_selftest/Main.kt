// JVM self-test for core/SheetLayout.kt (Print space part 4). Same cases as tools/sheet_layout_mirror_test.py part 2.
//   kotlinc app/src/main/java/com/lanshare/app/core/SheetLayout.kt tools/sheetlayout_selftest/Main.kt -include-runtime -d /tmp/slt.jar
//   java -jar /tmp/slt.jar
import com.lanshare.app.core.SheetLayout
import com.lanshare.app.core.SheetLayout.Fit
import com.lanshare.app.core.SheetLayout.Flip
import com.lanshare.app.core.SheetLayout.Grid
import com.lanshare.app.core.SheetLayout.Mode
import com.lanshare.app.core.SheetLayout.Orient
import com.lanshare.app.core.SheetLayout.Pic
import com.lanshare.app.core.SheetLayout.Side
import com.lanshare.app.core.SheetLayout.SlotRef
import com.lanshare.app.core.SheetLayout.Spec
import kotlin.system.exitProcess

private var bad = 0
private fun check(name: String, ok: Boolean) {
    println((if (ok) "ok   " else "FAIL ") + name)
    if (!ok) bad++
}

fun main() {
    val g23 = Grid(2, 3)
    check("long edge portrait mirrors columns", (0..5).map { SheetLayout.backSlot(it, g23, Flip.LONG, true) } == listOf(1, 0, 3, 2, 5, 4))
    check("short edge portrait mirrors rows", (0..5).map { SheetLayout.backSlot(it, g23, Flip.SHORT, true) } == listOf(4, 5, 2, 3, 0, 1))
    val g32 = Grid(3, 2)
    check("long edge landscape mirrors rows", (0..5).map { SheetLayout.backSlot(it, g32, Flip.LONG, false) } == listOf(3, 4, 5, 0, 1, 2))
    check("short edge landscape mirrors columns", (0..5).map { SheetLayout.backSlot(it, g32, Flip.SHORT, false) } == listOf(2, 1, 0, 5, 4, 3))

    val p7 = List(7) { Pic(1000, 1500) }
    val s22 = Spec(orient = Orient.PORTRAIT, grid = Grid(2, 2))
    var pl = SheetLayout.autoFill(s22, p7, Mode.FRONT_ONLY)
    check("7 pics 4-up front only = 2 sheets, 4 + 3", pl.sheets.size == 2 && pl.sheets[1].front == listOf(4, 5, 6, null))
    var lay = SheetLayout.compose(s22, p7, pl)
    check("front only has no back pages", lay.pages.size == 2 && lay.pages.all { it.side == Side.FRONT })
    pl = SheetLayout.autoFill(s22, p7, Mode.BOTH_SIDES)
    check("both sides: front 4, back 3", pl.sheets.size == 1 && pl.sheets[0].back == listOf(4, 5, 6, null))
    lay = SheetLayout.compose(s22, p7, pl)
    check("both sides emits front + back page", lay.pages.map { it.side } == listOf(Side.FRONT, Side.BACK))

    val p4 = p7.take(4)
    pl = SheetLayout.autoFill(Spec(orient = Orient.PORTRAIT, grid = Grid(1, 2), flip = Flip.LONG), p4, Mode.PAIRS)
    check("pairs long edge 1x2", pl.sheets[0].front == listOf(0, 2) && pl.sheets[0].back == listOf(1, 3))
    pl = SheetLayout.autoFill(Spec(orient = Orient.PORTRAIT, grid = Grid(1, 2), flip = Flip.SHORT), p4, Mode.PAIRS)
    check("pairs short edge 1x2", pl.sheets[0].front == listOf(0, 2) && pl.sheets[0].back == listOf(3, 1))
    pl = SheetLayout.autoFill(Spec(orient = Orient.PORTRAIT, grid = Grid(2, 1), flip = Flip.LONG), p4, Mode.PAIRS)
    check("pairs long edge 2x1", pl.sheets[0].front == listOf(0, 2) && pl.sheets[0].back == listOf(3, 1))

    val q = listOf(Pic(1000, 1500, copies = 3), Pic(1000, 1500))
    val oneUp = Spec(orient = Orient.PORTRAIT)
    check("copies: 4 sheets", SheetLayout.autoFill(oneUp, q, Mode.FRONT_ONLY).sheets.map { it.front[0] } == listOf(0, 0, 0, 1))
    check("reverse: copies after reversed order", SheetLayout.autoFill(oneUp.copy(reverse = true), q, Mode.FRONT_ONLY).sheets.map { it.front[0] } == listOf(1, 0, 0, 0))

    val land = listOf(Pic(3000, 2000))
    check("auto orientation, auto-turn off: landscape photo -> landscape", !SheetLayout.autoFill(Spec(autoRot = false), land, Mode.FRONT_ONLY).portrait)
    check("auto orientation, auto-turn on: tie -> portrait", SheetLayout.autoFill(Spec(), land, Mode.FRONT_ONLY).portrait)

    val port = listOf(Pic(2000, 3000))
    var c = SheetLayout.compose(oneUp, port, SheetLayout.autoFill(oneUp, port, Mode.FRONT_ONLY)).pages[0].cells[0]
    check("A4 1-up 2:3 photo: height 814, scale 814/3000", Math.abs(c.h - 814.0) < 1e-6 && Math.abs(c.k - 814.0 / 3000.0) < 1e-9)
    val fill = oneUp.copy(fit = Fit.FILL)
    c = SheetLayout.compose(fill, port, SheetLayout.autoFill(fill, port, Mode.FRONT_ONLY)).pages[0].cells[0]
    check("fill: box = cell 567 x 814", c.w == 567.0 && c.h == 814.0 && c.k >= 567.0 / 2000.0 - 1e-9)
    check("empty list -> no sheets", SheetLayout.autoFill(Spec(), emptyList(), Mode.BOTH_SIDES).sheets.isEmpty())

    // editing
    pl = SheetLayout.autoFill(s22, p4, Mode.FRONT_ONLY)
    val sw = SheetLayout.swap(pl, SlotRef(0, Side.FRONT, 0), SlotRef(0, Side.FRONT, 3))
    check("swap", sw.sheets[0].front == listOf(3, 1, 2, 0))
    val pb = SheetLayout.placeBehind(pl, s22.copy(flip = Flip.SHORT), 0, 0, 2)
    check("placeBehind short edge: slot 0 -> back slot 2", pb.sheets[0].back == listOf(null, null, 2, null))
    check("trim drops empty tail only", SheetLayout.trim(SheetLayout.addSheet(pl, s22)).sheets.size == 1)
    check("validate: 8x8 on A5 with 72 pt margin and 40 pt gap is refused", SheetLayout.validate(Spec(paper = "A5", grid = Grid(8, 8), margin = 72.0, gap = 40.0)) != null)
    check("validate: defaults ok", SheetLayout.validate(Spec()) == null)

    // free placement
    val fs = s22.copy(free = true)
    val fp0 = SheetLayout.autoFill(s22, p4, Mode.FRONT_ONLY)
    val fpl = SheetLayout.setFree(fp0, fs, 0, Side.BACK, listOf(SheetLayout.Free(1, 0.5, 0.5, 0.5, 30.0)))
    check("free back item makes a back page", SheetLayout.hasBack(fpl))
    val fl = SheetLayout.compose(fs, p4, fpl)
    check("free layout: no grid cells, free item on the back page", fl.pages.size == 2 && fl.pages.all { it.cells.isEmpty() } && fl.pages[1].free.size == 1 && fl.pages[0].free.isEmpty())
    val fc = fl.pages[1].free[0]
    check("free item: centre, width, proportions, turn", Math.abs(fc.cx - fl.sw / 2) < 1e-6 && Math.abs(fc.dw - fl.sw * 0.5) < 1e-6 && Math.abs(fc.dh - fc.dw * 1.5) < 1e-6 && fc.rot == 30.0)
    val rp = SheetLayout.compose(fs, listOf(Pic(1000, 1500, rot = 90)), SheetLayout.Plan(true, listOf(SheetLayout.Sheet(listOf(null), listOf(null), listOf(SheetLayout.Free(0, 0.5, 0.5, 0.4, 15.0)), emptyList()))))
    check("free item adds the picture's own turn", rp.pages[0].free[0].rot == 105.0)
    check("trim keeps a sheet that only has free items", SheetLayout.trim(SheetLayout.Plan(true, listOf(SheetLayout.Sheet(listOf(null), listOf(null), listOf(SheetLayout.Free(0, 0.5, 0.5, 0.4)), emptyList())))).sheets.size == 1)
    val g2f = SheetLayout.gridToFree(s22, p4, SheetLayout.autoFill(s22, p4, Mode.FRONT_ONLY))
    val gl = SheetLayout.compose(s22, p4, g2f)
    val gf = SheetLayout.compose(fs, p4, g2f)
    check("grid -> free keeps place and size (4 items)", g2f.sheets[0].ff.size == 4 && gf.pages[0].free.size == 4 &&
        (0 until 4).all { i -> val a = gl.pages[0].cells[i]; val b = gf.pages[0].free[i]
            Math.abs(a.cx - b.cx) < 1e-6 && Math.abs(a.cy - b.cy) < 1e-6 && Math.abs(a.w - b.dw) < 1e-6 && a.rot.toDouble() == b.rot })
    check("free width is clamped", SheetLayout.compose(fs, p4, SheetLayout.Plan(true, listOf(SheetLayout.Sheet(listOf(null), listOf(null), listOf(SheetLayout.Free(0, 0.5, 0.5, 99.0)), emptyList())))).pages[0].free[0].dw <= 3.0 * 595.0 + 1e-6)
    val fsOpen = fs.copy(safe = 0.0, keep = false)   // part 14c: these two checks are about plain placement, safe margin / keep apart have their own checks below
    val af = SheetLayout.addFree(SheetLayout.Plan(true, emptyList()), fsOpen, p4, 0, Side.FRONT, 2, null, null)
    val ac = SheetLayout.compose(fs, p4, af).pages[0].free[0]
    check("addFree on an empty plan makes a sheet, item fits half the sheet", af.sheets.size == 1 && ac.dw <= 595.0 * 0.5 + 1e-6 && ac.dh <= 842.0 * 0.5 + 1e-6 && Math.abs(ac.dh / ac.dw - 1.5) < 1e-9)
    val af2 = SheetLayout.addFree(af, fsOpen, p4, 0, Side.FRONT, 2, 0.25, 0.75)
    check("addFree at a point, on top", af2.sheets[0].ff.size == 2 && af2.sheets[0].ff[1].cx == 0.25 && af2.sheets[0].ff[1].cy == 0.75)

    // ---- part 14c: safe margin + keep apart on the server (mirrors fClamp / fHit in ui.html; tools/psclamp_14c_mirror_test.py compares the maths with the page)
    run {
        val sw = 595.0
        val sh = 842.0
        val pics = listOf(Pic(1000, 1500), Pic(1500, 1000), Pic(1000, 1000, rot = 90))
        val sf = Spec(free = true, safe = 14.0, keep = true, keepGap = 6.0)
        fun inside(f: SheetLayout.Free, p: Pic, safe: Double): Boolean {
            val r = SheetLayout.safeRect(safe, sw, sh)
            val b = SheetLayout.freeBox(f, p, sw, sh)
            return f.cx - b[0] / 2 >= r[0] - 1e-9 && f.cx + b[0] / 2 <= r[1] + 1e-9 && f.cy - b[1] / 2 >= r[2] - 1e-9 && f.cy + b[1] / 2 <= r[3] + 1e-9
        }
        // clampFree: inside, idempotent, shape kept
        var allIn = true
        var idem = true
        var shape = true
        val rnd = java.util.Random(14)
        for (n in 0 until 3000) {
            val pi = rnd.nextInt(3)
            val f = SheetLayout.Free(pi, rnd.nextDouble() * 1.6 - 0.3, rnd.nextDouble() * 1.6 - 0.3, 0.03 + rnd.nextDouble() * 2.9, rnd.nextInt(360).toDouble())
            val c = SheetLayout.clampFree(f, pics[pi], sw, sh, 14.0)
            val c2 = SheetLayout.clampFree(c, pics[pi], sw, sh, 14.0)
            if (!inside(c, pics[pi], 14.0) && c.w > SheetLayout.FREE_MIN_W + 1e-12) allIn = false
            if (Math.abs(c2.cx - c.cx) > 1e-9 || Math.abs(c2.cy - c.cy) > 1e-9 || Math.abs(c2.w - c.w) > 1e-9) idem = false
            if (c.rot != f.rot || c.pic != f.pic) shape = false
        }
        check("clampFree puts every turned box inside the safe rectangle (3000 random items)", allIn)
        check("clampFree is idempotent", idem)
        check("clampFree keeps picture and turn", shape)
        val big = SheetLayout.clampFree(SheetLayout.Free(0, 0.5, 0.5, 3.0), pics[0], sw, sh, 14.0)
        check("a too big picture is shrunk first and centred", inside(big, pics[0], 14.0) && Math.abs(big.cx - 0.5) < 1e-9 && Math.abs(big.cy - 0.5) < 1e-9)
        val off = SheetLayout.clampFree(SheetLayout.Free(0, -0.4, 1.5, 0.2), pics[0], sw, sh, 14.0)
        check("an item off the paper moves in without changing size", off.w == 0.2 && inside(off, pics[0], 14.0))
        check("safe 0 allows the whole sheet (item at the corner stays put)", SheetLayout.clampFree(SheetLayout.Free(0, 0.1, 0.1, 0.2), pics[0], sw, sh, 0.0).let { Math.abs(it.cx - 0.1) < 1e-9 && Math.abs(it.cy - 0.1) < 1e-9 && it.w == 0.2 })

        // setFree normalises
        val empty = SheetLayout.Plan(true, listOf(SheetLayout.Sheet(listOf(null), listOf(null))))
        val wire = SheetLayout.setFree(empty, sf, 0, Side.FRONT, listOf(SheetLayout.Free(0, 1.4, -0.2, 0.3)), pics)
        check("setFree pulls a hand-made item out of the dead strip", wire.sheets[0].ff.size == 1 && inside(wire.sheets[0].ff[0], pics[0], 14.0))
        check("setFree without pictures keeps the old behaviour (no clamp)", SheetLayout.setFree(empty, sf, 0, Side.FRONT, listOf(SheetLayout.Free(0, 1.4, -0.2, 0.3))).sheets[0].ff[0].cx == 1.4)

        // keep apart: refuse a NEW overlap, tolerate an old one
        val two = SheetLayout.setFree(empty, sf, 0, Side.FRONT, listOf(SheetLayout.Free(1, 0.3, 0.2, 0.3), SheetLayout.Free(1, 0.7, 0.2, 0.3)), pics)
        check("two apart pictures are accepted", two.sheets[0].ff.size == 2)
        val refused = try { SheetLayout.setFree(two, sf, 0, Side.FRONT, listOf(SheetLayout.Free(1, 0.3, 0.2, 0.3), SheetLayout.Free(1, 0.4, 0.2, 0.3)), pics); false } catch (e: IllegalArgumentException) { e.message!!.contains("overlap") }
        check("a side that gets a new overlap is refused", refused)
        val openSpec = sf.copy(keep = false)
        val ov = SheetLayout.setFree(two, openSpec, 0, Side.FRONT, listOf(SheetLayout.Free(1, 0.3, 0.2, 0.3), SheetLayout.Free(1, 0.4, 0.2, 0.3)), pics)
        check("Keep apart off allows overlap", ov.sheets[0].ff.size == 2)
        val still = SheetLayout.setFree(ov, sf, 0, Side.FRONT, listOf(SheetLayout.Free(1, 0.3, 0.2, 0.3), SheetLayout.Free(1, 0.42, 0.2, 0.3)), pics)
        check("a pair that already overlapped may be sent again (so it can be moved apart)", still.sheets[0].ff.size == 2)
        check("touching with gap 0 is allowed", run {
            val g0 = sf.copy(keepGap = 0.0)
            val w = 0.3
            val dx = w   // the two pictures are 0.3 * 595 pt wide, so centres 0.3 apart touch
            SheetLayout.setFree(empty, g0, 0, Side.FRONT, listOf(SheetLayout.Free(1, 0.3, 0.2, w), SheetLayout.Free(1, 0.3 + dx, 0.2, w)), pics).sheets[0].ff.size == 2
        })

        // addFree with keep: lands in a free spot, as big as fits; a full side refuses
        var pl = empty
        for (n in 0 until 3) pl = SheetLayout.addFree(pl, sf, pics, 0, Side.FRONT, 0, null, null)
        val l3 = pl.sheets[0].ff
        check("addFree with Keep apart: three pictures, none overlapping, all inside", l3.size == 3 && SheetLayout.freePairs(l3, pics, sw, sh, 6.0).isEmpty() && l3.all { inside(it, pics[it.pic], 14.0) })
        val sf40 = sf.copy(keepGap = 40.0)
        val whole = SheetLayout.setFree(empty, sf40, 0, Side.FRONT, listOf(SheetLayout.Free(0, 0.5, 0.5, 3.0)), pics)   // shrinks to the full height of the frame; only a 24 pt strip is left on the sides
        var full = false
        try { SheetLayout.addFree(whole, sf40, pics, 0, Side.FRONT, 0, null, null) } catch (e: IllegalArgumentException) { full = e.message!!.contains("No room") }
        check("a side with no room left refuses another picture", full)

        // gridToFree honours safe + keep
        val tight = Spec(cols = 2, rows = 2, margin = 0.0, gap = 0.0, free = false)
        val gp = List(4) { Pic(1000, 1500) }
        val grid = SheetLayout.autoFill(tight, gp, Mode.FRONT_ONLY)
        val tf = SheetLayout.gridToFree(tight.copy(safe = 14.0, keep = true, keepGap = 6.0), gp, grid)
        val tl = tf.sheets[0].ff
        val wh = if (tf.portrait) doubleArrayOf(595.0, 842.0) else doubleArrayOf(842.0, 595.0)
        check("gridToFree: a grid with margin 0 is pulled into the safe frame and kept apart", tl.size == 4 && tl.all { f -> val r = SheetLayout.safeRect(14.0, wh[0], wh[1]); val b = SheetLayout.freeBox(f, gp[f.pic], wh[0], wh[1]); f.cx - b[0] / 2 >= r[0] - 1e-9 && f.cx + b[0] / 2 <= r[1] + 1e-9 && f.cy - b[1] / 2 >= r[2] - 1e-9 && f.cy + b[1] / 2 <= r[3] + 1e-9 } && SheetLayout.freePairs(tl, gp, wh[0], wh[1], 6.0).isEmpty())

        // packFree
        val pk = SheetLayout.packFree(List(5) { SheetLayout.Free(it % 3, 0.5, 0.5, 0.3, 0.0) }, pics, sw, sh, 14.0, 6.0)
        check("packFree spreads five pictures apart inside the frame", pk != null && SheetLayout.freePairs(pk, pics, sw, sh, 6.0).isEmpty() && pk.all { inside(it, pics[it.pic], 14.0) })

        // freeHit basics
        val a = SheetLayout.Free(1, 0.3, 0.2, 0.2)
        check("freeHit: same place hits, far apart does not", SheetLayout.freeHit(a, pics[1], a, pics[1], sw, sh, 0.0) && !SheetLayout.freeHit(a, pics[1], a.copy(cx = 0.8), pics[1], sw, sh, 6.0))
    }

    println(if (bad == 0) "ALL OK" else "$bad FAILED")
    exitProcess(if (bad == 0) 0 else 1)
}
