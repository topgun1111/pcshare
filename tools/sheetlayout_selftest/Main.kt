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
    val af = SheetLayout.addFree(SheetLayout.Plan(true, emptyList()), fs, p4, 0, Side.FRONT, 2, null, null)
    val ac = SheetLayout.compose(fs, p4, af).pages[0].free[0]
    check("addFree on an empty plan makes a sheet, item fits half the sheet", af.sheets.size == 1 && ac.dw <= 595.0 * 0.5 + 1e-6 && ac.dh <= 842.0 * 0.5 + 1e-6 && Math.abs(ac.dh / ac.dw - 1.5) < 1e-9)
    val af2 = SheetLayout.addFree(af, fs, p4, 0, Side.FRONT, 2, 0.25, 0.75)
    check("addFree at a point, on top", af2.sheets[0].ff.size == 2 && af2.sheets[0].ff[1].cx == 0.25 && af2.sheets[0].ff[1].cy == 0.75)

    println(if (bad == 0) "ALL OK" else "$bad FAILED")
    exitProcess(if (bad == 0) 0 else 1)
}
