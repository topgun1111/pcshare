// JVM self-test for core/PsRenderGeom.kt + PsLayoutJson.resolve (Print space part 6a).
//   kotlinc -cp orgjson.jar app/src/main/java/com/lanshare/app/core/SheetLayout.kt app/src/main/java/com/lanshare/app/core/PsLayoutJson.kt \
//     app/src/main/java/com/lanshare/app/core/PsRenderGeom.kt tools/psrender_geom_selftest/Main.kt -include-runtime -d /tmp/prg.jar
//   java -cp /tmp/prg.jar:orgjson.jar MainKt        (expects ALL OK)
import com.lanshare.app.core.PsLayoutJson
import com.lanshare.app.core.PsRenderGeom
import com.lanshare.app.core.SheetLayout
import com.lanshare.app.core.SheetLayout.Fit
import com.lanshare.app.core.SheetLayout.Grid
import com.lanshare.app.core.SheetLayout.Orient
import com.lanshare.app.core.SheetLayout.Pic
import com.lanshare.app.core.SheetLayout.Spec
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.system.exitProcess

private var bad = 0
private fun check(name: String, ok: Boolean) {
    println((if (ok) "ok   " else "FAIL ") + name)
    if (!ok) bad++
}

/** screen coordinates, y down: turn clockwise by deg, then mirror left-right if flip (the order Thumbs.render and PrintSpaceRender use) */
private fun apply(o: IntArray, x: Int, y: Int): Pair<Int, Int> {
    var px = x
    var py = y
    repeat(o[0] / 90) { val nx = -py; val ny = px; px = nx; py = ny }   // 90 deg clockwise with y down: (x, y) -> (-y, x)
    if (o[1] == 1) px = -px
    return Pair(px, py)
}

fun main() {
    // ---- sample / needPx
    check("sample 4000x3000 need 500 -> 8", PsRenderGeom.sample(4000, 3000, 500) == 8)
    check("sample 4000x3000 need 3000 -> 1", PsRenderGeom.sample(4000, 3000, 3000) == 1)
    check("sample small picture -> 1", PsRenderGeom.sample(800, 600, 1000) == 1)
    check("sample portrait uses the long side", PsRenderGeom.sample(3000, 4000, 500) == 8)
    check("needPx A4 at 200 dpi", PsRenderGeom.needPx(595.0, 421.0, 200 / 72.0) == 1653)
    check("needPx is capped (A3 at 300 dpi)", PsRenderGeom.needPx(842.0, 595.0, 300 / 72.0) == PsRenderGeom.MAXPX)
    check("needPx has a floor", PsRenderGeom.needPx(1.0, 1.0, 0.1) == 16)

    // ---- draw: the slot box centre, clip, turn
    val box = SheetLayout.Box(10.0, 20.0, 100.0, 200.0)
    val d = PsRenderGeom.draw(box, 80.0, 120.0, -90)
    check("draw centre / clip", d.cx == 60f && d.cy == 120f && d.left == 10f && d.top == 20f && d.right == 110f && d.bottom == 220f)
    check("draw turn is normalised (-90 -> 270)", d.rot == 270 && PsRenderGeom.draw(box, 1.0, 1.0, 450).rot == 90)
    check("draw never has a zero size", PsRenderGeom.draw(box, 0.0, 0.0, 0).let { it.dw >= 1f && it.dh >= 1f })
    check("page size is whole points", PsRenderGeom.pageSize(595.0, 842.0) == Pair(595, 842) && PsRenderGeom.pageSize(0.2, 0.2) == Pair(1, 1))
    check("preview size clamps the width", PsRenderGeom.previewSize(595.0, 842.0, 50).first == 120 && PsRenderGeom.previewSize(595.0, 842.0, 5000).first == 1100)
    check("preview height follows the sheet", PsRenderGeom.previewSize(595.0, 842.0, 595).second == 842)

    // ---- autoDpi
    val one = listOf(Pair(567.0, 814.0))
    check("autoDpi keeps the wish when it fits", PsRenderGeom.autoDpi(200, one, 400L shl 20) == 200)
    check("autoDpi clamps the wish", PsRenderGeom.autoDpi(1000, one, 1L shl 40) == PsRenderGeom.MAX_DPI && PsRenderGeom.autoDpi(10, one, 1L shl 40) == PsRenderGeom.MIN_DPI)
    val many = List(120) { Pair(567.0, 814.0) }   // 120 full A4 photos
    val budget = 400L shl 20
    val low = PsRenderGeom.autoDpi(200, many, budget)
    check("autoDpi lowers a big job ($low dpi)", low < 200 && low >= PsRenderGeom.MIN_DPI)
    fun raw(dpi: Int): Double = many.sumOf { (a, b) -> a * dpi / 72.0 * b * dpi / 72.0 * 3.0 }
    check("autoDpi result fits the budget and one more dpi would not", raw(low) <= budget && (low == PsRenderGeom.MIN_DPI || raw(low + 1) > budget))
    check("autoDpi never goes below the floor", PsRenderGeom.autoDpi(200, List(5000) { Pair(567.0, 814.0) }, 1L shl 20) == PsRenderGeom.MIN_DPI)

    // ---- exif table (same as Thumbs.render)
    val e = (1..8).map { PsRenderGeom.exif(it) }
    check("exif 1 and unknown = no change", e[0].toList() == listOf(0, 0) && PsRenderGeom.exif(0).toList() == listOf(0, 0) && PsRenderGeom.exif(99).toList() == listOf(0, 0))
    check("exif 2 mirror", apply(e[1], 1, 2) == Pair(-1, 2))
    check("exif 3 turn 180", apply(e[2], 1, 2) == Pair(-1, -2))
    check("exif 4 vertical flip", apply(e[3], 1, 2) == Pair(1, -2))
    check("exif 5 transpose (x, y) -> (y, x)", apply(e[4], 1, 2) == Pair(2, 1))
    check("exif 6 turn 90 clockwise", apply(e[5], 1, 2) == Pair(-2, 1))
    check("exif 7 transverse (x, y) -> (-y, -x)", apply(e[6], 1, 2) == Pair(-2, -1))
    check("exif 8 turn 270 clockwise", apply(e[7], 1, 2) == Pair(2, -1))
    check("exif: 5..8 swap width / height like PsLayoutApi", (1..8).all { (PsRenderGeom.exif(it)[0] % 180 == 90) == (it in 5..8) })

    // ---- renderer assumptions about the layout model: box centre = cell centre, picture box = pic * k, FILL covers the box
    val pics = listOf(Pic(4000, 3000), Pic(3000, 4000), Pic(1600, 1200, rot = 90), Pic(2000, 2000), Pic(4000, 3000))
    for (fit in listOf(Fit.FIT, Fit.FILL)) {
        val spec = Spec(grid = Grid(2, 2), orient = Orient.PORTRAIT, fit = fit)
        val plan = SheetLayout.autoFill(spec, pics, SheetLayout.Mode.FRONT_ONLY)
        val lay = SheetLayout.compose(spec, pics, plan)
        val boxes = SheetLayout.slotBoxes(spec, plan.portrait)
        var ok = true
        var covers = true
        for (pg in lay.pages) for (c in pg.cells) {
            val dd = PsRenderGeom.draw(boxes[c.slot], pics[c.pic].w * c.k, pics[c.pic].h * c.k, c.rot)
            if (abs(dd.cx - c.cx) > 1e-3 || abs(dd.cy - c.cy) > 1e-3) ok = false
            val turned = dd.rot % 180 != 0
            val vw = if (turned) dd.dh else dd.dw     // size as seen on the sheet
            val vh = if (turned) dd.dw else dd.dh
            if (fit == Fit.FILL && (vw < dd.bw - 0.01f || vh < dd.bh - 0.01f)) covers = false
            if (fit == Fit.FIT && (vw > dd.bw + 0.01f || vh > dd.bh + 0.01f)) covers = false   // FIT stays inside the box
        }
        check("$fit: draw centre = cell centre", ok)
        check("$fit: " + (if (fit == Fit.FILL) "picture covers the slot box" else "picture stays inside the slot box"), covers)
    }

    // ---- PsLayoutJson.resolve = op "view" (same plan rules, same pages)
    val pl = (1..5).map { PsLayoutJson.Pict("p$it", 4000, 3000, 0, if (it == 2) 2 else 1) }
    val req = JSONObject().put("spec", JSONObject().put("cols", 2).put("rows", 2).put("orient", "portrait"))
    val viewAns = PsLayoutJson.handle(JSONObject(req.toString()).put("op", "view"), pl)
    val res = PsLayoutJson.resolve(req, pl)
    val lj = PsLayoutJson.layoutJson(res.spec, res.pics, res.plan, res.layout)
    check("resolve layout = handle(view) layout", lj.toString() == viewAns.getJSONObject("layout").toString())
    check("resolve: 6 copies on 4-up = 2 sheets", res.layout.pages.size == 2 && res.plan.sheets.size == 2)
    val fillAns = PsLayoutJson.handle(JSONObject(req.toString()).put("op", "fill").put("mode", "both"), pl)
    val req2 = JSONObject(req.toString()).put("plan", fillAns.getJSONObject("plan"))
    val res2 = PsLayoutJson.resolve(req2, pl)
    check("resolve takes the plan of the request (both sides: front + back pages)", res2.layout.pages.map { it.side.name } == listOf("FRONT", "BACK") &&
        PsLayoutJson.planJson(res2.plan, pl).toString() == fillAns.getJSONObject("plan").toString())
    check("resolve with no pictures = no pages", PsLayoutJson.resolve(req, emptyList()).layout.pages.isEmpty())
    var refused = false
    try { PsLayoutJson.resolve(JSONObject().put("spec", JSONObject().put("cols", 8).put("rows", 8).put("margin", 72).put("gap", 40).put("paper", "A5")), pl) }
    catch (ex: IllegalArgumentException) { refused = ex.message?.contains("Too many") == true }
    check("resolve refuses an impossible spec like the editor", refused)
    var gone = true
    val plan3 = JSONObject().put("portrait", true).put("sheets", JSONArray().put(JSONObject().put("f", JSONArray(listOf("p1", "zzz", null, "p3"))).put("b", JSONArray(listOf(null, null, null, null)))))
    val res3 = PsLayoutJson.resolve(JSONObject(req.toString()).put("plan", plan3), pl)
    gone = res3.layout.pages.size == 1 && res3.layout.pages[0].cells.map { it.slot } == listOf(0, 3)
    check("resolve: an id that left the print space just empties its slot", gone)

    println(if (bad == 0) "ALL OK" else "$bad FAILED")
    if (bad != 0) exitProcess(1)
}
