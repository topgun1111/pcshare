package com.lanshare.app.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

/**
 * Print space part 5: wire format between the layout editor (ui.html) and [SheetLayout].
 *
 * Only org.json + SheetLayout, no android.*, so it runs on the JVM (tools/pslayout_selftest). The picture list
 * (ids, size as it LOOKS, manual turn, copies) is handed in by [PsLayoutApi]; the editor never sends sizes.
 *
 * The editor keeps `spec` + `plan` itself (it saves them in localStorage) and sends both with every call; the
 * answer carries the cleaned spec, the new plan and the composed layout. Plans use picture IDS (not list indices),
 * so removing a picture from the space later only empties its slots.
 *
 * Request  {op, spec{paper, orient, cols, rows, margin, gap, fit, flip, autoRot, reverse, free, safe, keep, keepGap}, plan{portrait, sheets:[{f:[id|null], b:[id|null]}]}, ...}
 *   op "view"                      compose only (no plan = fill the front sides)
 *   op "fill"   {mode}             FRONT_ONLY | BOTH_SIDES | PAIRS (auto-fill from the picture list, copies expanded)
 *   op "assign" {sheet, side, slot, id|null}
 *   op "swap"   {a:{sheet,side,slot}, b:{sheet,side,slot}}
 *   op "behind" {sheet, slot, id|null}   puts the picture on the back, behind the given FRONT slot
 *   op "addsheet" | "rmsheet" {sheet} | "trim"
 *   op "reflow" {duplex}           after grid / paper / orientation changed
 *   op "fset"  {sheet, side, items:[{id, cx, cy, w, rot, st?}]}   free placement (spec.free): replaces the free items of one side (part 14c: put inside the safe margin; with spec.keep a side that gets a NEW overlap is refused with "Pictures overlap"); cx / cy = centre and w = width of the
 *                                  unturned picture, all as fractions of the sheet (width / height / width); rot = clockwise degrees; list order = stacking order
 *   op "fadd"  {sheet, side, id, cx?, cy?}   puts a picture on a side as a free item (half the sheet big, at cx / cy or near the centre; added to the end = on top)
 *   op "tofree"                    turns the grid placement into free items and sets spec.free; "togrid" clears spec.free (free items stay in the plan, unused)
 * Plan sheets may carry `ff` / `fb` (free items of front / back, same item shape as op "fset"); page cells of a free layout are in `pages[].free` [{id, cx, cy, dw, dh, rot}] in pt
 *   (draw the UNTURNED picture dw x dh centred on cx, cy turned by rot; no clipping to a slot).
 * Answer   {ok, spec, plan, layout{portrait, sw, sh, cols, rows, slots[{x,y,w,h}], behind[], pages[{sheet, side, cells[{slot, id, rot, dw, dh}]}]}, placed{id:n}, sheets}
 *   cell: the picture is drawn dw x dh (unturned, pt), turned by rot degrees about the centre of the slot box, clipped to the slot box.
 *   behind[i] = back slot that lies physically behind front slot i (the mapping is its own inverse).
 * Errors are IllegalArgumentException with a text for a toast.
 */
object PsLayoutJson {
    const val MAX_SHEETS = 200

    class Pict(val id: String, val w: Int, val h: Int, val rot: Int, val copies: Int)

    // ------------------------------------------------------------------ parsing

    private fun num(o: JSONObject, k: String, d: Double): Double {
        val v = o.optDouble(k, d)
        return if (v.isNaN() || v.isInfinite()) d else v
    }

    fun specOf(o: JSONObject?): SheetLayout.Spec {
        val d = SheetLayout.Spec()
        if (o == null) return d
        return SheetLayout.Spec(
            paper = o.optString("paper", d.paper).ifEmpty { d.paper },
            orient = when (o.optString("orient").lowercase()) {
                "portrait" -> SheetLayout.Orient.PORTRAIT
                "landscape" -> SheetLayout.Orient.LANDSCAPE
                else -> SheetLayout.Orient.AUTO
            },
            grid = SheetLayout.Grid(o.optInt("cols", d.grid.cols), o.optInt("rows", d.grid.rows)),
            margin = num(o, "margin", d.margin),
            gap = num(o, "gap", d.gap),
            fit = if (o.optString("fit").lowercase() == "fill") SheetLayout.Fit.FILL else SheetLayout.Fit.FIT,
            flip = if (o.optString("flip").lowercase() == "short") SheetLayout.Flip.SHORT else SheetLayout.Flip.LONG,
            autoRot = o.optBoolean("autoRot", d.autoRot),
            reverse = o.optBoolean("reverse", d.reverse),
            free = o.optBoolean("free", d.free),
            safe = num(o, "safe", d.safe),
            keep = o.optBoolean("keep", d.keep),
            keepGap = num(o, "keepGap", d.keepGap)
        )
    }

    fun specJson(s: SheetLayout.Spec): JSONObject = JSONObject()
        .put("paper", SheetLayout.paper(s.paper).name)
        .put("orient", s.orient.name.lowercase())
        .put("cols", s.grid.cols).put("rows", s.grid.rows)
        .put("margin", s.margin).put("gap", s.gap)
        .put("fit", s.fit.name.lowercase()).put("flip", s.flip.name.lowercase())
        .put("autoRot", s.autoRot).put("reverse", s.reverse).put("free", s.free).put("safe", s.safe).put("keep", s.keep).put("keepGap", s.keepGap)

    private fun freeList(a: JSONArray?, idx: Map<String, Int>): List<SheetLayout.Free> {
        val out = ArrayList<SheetLayout.Free>()
        if (a == null) return out
        for (n in 0 until minOf(a.length(), SheetLayout.MAX_FREE)) {
            val o = a.optJSONObject(n) ?: continue
            val i = idx[o.optString("id", "")] ?: continue   // a picture that is gone from the space drops its free item
            out.add(SheetLayout.Free(i, num(o, "cx", 0.5), num(o, "cy", 0.5), num(o, "w", 0.4), num(o, "rot", 0.0), num(o, "st", 1.0)))
        }
        return out
    }

    private fun freeJson(l: List<SheetLayout.Free>, pics: List<Pict>): JSONArray {
        val a = JSONArray()
        for (f in l) if (f.pic in pics.indices) a.put(JSONObject().put("id", pics[f.pic].id).put("cx", r4(f.cx)).put("cy", r4(f.cy)).put("w", r4(f.w)).put("rot", r2(f.rot)).also { if (Math.abs(f.st - 1.0) > 1e-4) it.put("st", r4(f.st)) })
        return a
    }

    private fun r4(v: Double): Double = Math.round(v * 10000.0) / 10000.0
    private fun slotList(a: JSONArray?, per: Int, idx: Map<String, Int>): List<Int?> =
        List(per) { i ->
            val id = if (a == null || a.isNull(i)) "" else a.optString(i, "")
            if (id.isEmpty()) null else idx[id]   // an id that is gone from the space leaves an empty slot
        }

    /** Null when the request carries no plan. Slot lists are cut / padded to the slots of the spec's grid. */
    fun planOf(o: JSONObject?, spec: SheetLayout.Spec, idx: Map<String, Int>): SheetLayout.Plan? {
        if (o == null || !o.has("sheets")) return null
        val portrait = o.optBoolean("portrait", spec.orient != SheetLayout.Orient.LANDSCAPE)
        val per = SheetLayout.gridFor(spec, portrait).per
        val a = o.optJSONArray("sheets") ?: JSONArray()
        val sheets = ArrayList<SheetLayout.Sheet>()
        for (n in 0 until minOf(a.length(), MAX_SHEETS)) {
            val s = a.optJSONObject(n)
            sheets.add(SheetLayout.Sheet(slotList(s?.optJSONArray("f"), per, idx), slotList(s?.optJSONArray("b"), per, idx),
                freeList(s?.optJSONArray("ff"), idx), freeList(s?.optJSONArray("fb"), idx)))
        }
        return SheetLayout.Plan(portrait, sheets)
    }

    private fun ref(o: JSONObject?): SheetLayout.SlotRef {
        if (o == null) throw IllegalArgumentException("slot missing")
        return SheetLayout.SlotRef(o.getInt("sheet"), sideOf(o.optString("side")), o.getInt("slot"))
    }

    private fun sideOf(s: String): SheetLayout.Side = if (s.lowercase() == "back") SheetLayout.Side.BACK else SheetLayout.Side.FRONT

    private fun modeOf(s: String): SheetLayout.Mode = when (s.lowercase()) {
        "both", "both_sides" -> SheetLayout.Mode.BOTH_SIDES
        "pairs" -> SheetLayout.Mode.PAIRS
        else -> SheetLayout.Mode.FRONT_ONLY
    }

    // ------------------------------------------------------------------ answer

    private fun r2(v: Double): Double = Math.round(v * 100.0) / 100.0

    fun planJson(plan: SheetLayout.Plan, pics: List<Pict>): JSONObject {
        fun side(l: List<Int?>): JSONArray {
            val a = JSONArray()
            for (e in l) a.put(if (e != null && e in pics.indices) pics[e].id else JSONObject.NULL)
            return a
        }
        val sh = JSONArray()
        for (s in plan.sheets) {
            val o = JSONObject().put("f", side(s.front)).put("b", side(s.back))
            if (s.ff.isNotEmpty()) o.put("ff", freeJson(s.ff, pics))
            if (s.fb.isNotEmpty()) o.put("fb", freeJson(s.fb, pics))
            sh.put(o)
        }
        return JSONObject().put("portrait", plan.portrait).put("sheets", sh)
    }

    fun layoutJson(spec: SheetLayout.Spec, pics: List<Pict>, plan: SheetLayout.Plan, lay: SheetLayout.Layout): JSONObject {
        val g = lay.grid
        val slots = JSONArray()
        for (b in SheetLayout.slotBoxes(spec, plan.portrait)) slots.put(JSONObject().put("x", r2(b.x)).put("y", r2(b.y)).put("w", r2(b.w)).put("h", r2(b.h)))
        val behind = JSONArray()
        for (i in 0 until g.per) behind.put(SheetLayout.backSlot(i, g, spec.flip, plan.portrait))
        val pages = JSONArray()
        for (p in lay.pages) {
            val cells = JSONArray()
            for (c in p.cells) {
                val pic = pics[c.pic]
                cells.put(JSONObject().put("slot", c.slot).put("id", pic.id).put("rot", c.rot)
                    .put("dw", r2(max(1, pic.w) * c.k)).put("dh", r2(max(1, pic.h) * c.k)))
            }
            val fr = JSONArray()
            for (f in p.free) fr.put(JSONObject().put("id", pics[f.pic].id).put("cx", r2(f.cx)).put("cy", r2(f.cy)).put("dw", r2(f.dw)).put("dh", r2(f.dh)).put("rot", r2(f.rot)))
            pages.put(JSONObject().put("sheet", p.sheet).put("side", p.side.name.lowercase()).put("cells", cells).put("free", fr))
        }
        return JSONObject().put("portrait", lay.portrait).put("sw", r2(lay.sw)).put("sh", r2(lay.sh))
            .put("cols", g.cols).put("rows", g.rows).put("slots", slots).put("behind", behind).put("pages", pages)
    }

    private fun placedJson(plan: SheetLayout.Plan, pics: List<Pict>): JSONObject {
        val n = HashMap<Int, Int>()
        for (s in plan.sheets) {
            for (e in s.front + s.back) if (e != null && e in pics.indices) n[e] = (n[e] ?: 0) + 1
            for (f in s.ff + s.fb) if (f.pic in pics.indices) n[f.pic] = (n[f.pic] ?: 0) + 1
        }
        val o = JSONObject()
        for ((i, c) in n) o.put(pics[i].id, c)
        return o
    }

    // ------------------------------------------------------------------ request

    /** Cleaned spec, the picture list as the layout model wants it, id -> index, and the plan of the request (before any op). */
    private class Base(val spec: SheetLayout.Spec, val sp: List<SheetLayout.Pic>, val idx: Map<String, Int>, val plan: SheetLayout.Plan)

    private fun base(b: JSONObject, pics: List<Pict>): Base {
        val spec = SheetLayout.clean(specOf(b.optJSONObject("spec")))
        SheetLayout.validate(spec)?.let { throw IllegalArgumentException(it) }
        val sp = pics.map { p -> SheetLayout.Pic(p.w, p.h, p.rot, p.copies) }
        val idx = HashMap<String, Int>()
        pics.forEachIndexed { i, p -> idx[p.id] = i }
        var plan = planOf(b.optJSONObject("plan"), spec, idx) ?: SheetLayout.autoFill(spec, sp, SheetLayout.Mode.FRONT_ONLY)
        // the spec was switched to a fixed orientation the plan was not built for: refill (keeps the order)
        val mismatch = (spec.orient == SheetLayout.Orient.PORTRAIT && !plan.portrait) || (spec.orient == SheetLayout.Orient.LANDSCAPE && plan.portrait)
        if (mismatch && spec.free) plan = plan.copy(portrait = spec.orient == SheetLayout.Orient.PORTRAIT)   // free items are fractions of the sheet: they just follow the sheet
        else if (mismatch) plan = SheetLayout.reflow(spec, sp, plan, SheetLayout.hasBack(plan))
        return Base(spec, sp, idx, plan)
    }

    /** What the renderer (part 6) needs: the request's spec + plan composed into pages, no op applied. Same rules as op "view". */
    class Resolved(val spec: SheetLayout.Spec, val pics: List<Pict>, val plan: SheetLayout.Plan, val layout: SheetLayout.Layout)

    fun resolve(b: JSONObject, pics: List<Pict>): Resolved {
        val bs = base(b, pics)
        return Resolved(bs.spec, pics, bs.plan, SheetLayout.compose(bs.spec, bs.sp, bs.plan))
    }

    fun handle(b: JSONObject, pics: List<Pict>): JSONObject {
        val bs = base(b, pics)
        var spec = bs.spec
        val sp = bs.sp
        val idx = bs.idx
        fun need(id: String?): Int = idx[id ?: ""] ?: throw IllegalArgumentException("that picture is no longer in the print space")
        fun pick(o: JSONObject): Int? = if (o.isNull("id") || o.optString("id").isEmpty()) null else need(o.optString("id"))

        var plan = bs.plan
        when (val op = b.optString("op", "view")) {
            "view" -> {}
            "fill" -> plan = SheetLayout.autoFill(spec, sp, modeOf(b.optString("mode")))
            "assign" -> plan = SheetLayout.assign(plan, ref(b), pick(b))
            "swap" -> plan = SheetLayout.swap(plan, ref(b.optJSONObject("a")), ref(b.optJSONObject("b")))
            "behind" -> plan = SheetLayout.placeBehind(plan, spec, b.getInt("sheet"), b.getInt("slot"), pick(b))
            "addsheet" -> {
                if (plan.sheets.size >= MAX_SHEETS) throw IllegalArgumentException("Too many sheets (max $MAX_SHEETS)")
                plan = SheetLayout.addSheet(plan, spec)
            }
            "rmsheet" -> plan = SheetLayout.removeSheet(plan, b.getInt("sheet"))
            "trim" -> plan = SheetLayout.trim(plan)
            "fset" -> plan = SheetLayout.setFree(plan, spec, b.getInt("sheet"), sideOf(b.optString("side")), freeList(b.optJSONArray("items"), idx), sp)
            "fadd" -> plan = SheetLayout.addFree(plan, spec, sp, b.optInt("sheet", 0), sideOf(b.optString("side")), need(b.optString("id")),
                if (b.has("cx")) num(b, "cx", 0.5) else null, if (b.has("cy")) num(b, "cy", 0.5) else null)
            "tofree" -> {
                plan = SheetLayout.gridToFree(spec, sp, plan)
                spec = spec.copy(free = true)
            }
            "togrid" -> spec = spec.copy(free = false)
            "reflow" -> plan = SheetLayout.reflow(spec, sp, plan, b.optBoolean("duplex", SheetLayout.hasBack(plan)))
            else -> throw IllegalArgumentException("unknown layout operation: $op")
        }
        if (plan.sheets.size > MAX_SHEETS) plan = plan.copy(sheets = plan.sheets.take(MAX_SHEETS))
        val lay = SheetLayout.compose(spec, sp, plan)
        return JSONObject().put("ok", true).put("spec", specJson(spec)).put("plan", planJson(plan, pics))
            .put("layout", layoutJson(spec, pics, plan, lay)).put("placed", placedJson(plan, pics)).put("sheets", plan.sheets.size)
    }
}
