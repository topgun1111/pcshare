package com.lanshare.app

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors

/**
 * Accuracy wrapper around any [OcrEngine] (same contract, boxes are always in pixels of the bitmap that was passed in).
 *  - big pictures (long side >= [BIG]): read once as a whole (large text, reading order) AND as overlapping tiles at 1:1 scale
 *    (small print that ML Kit loses when it shrinks the whole picture). Where both found the same word the tile reading wins;
 *    large text stays with the whole-picture pass; pieces of one row are joined; order follows the whole-picture pass.
 *  - small pictures (long side < [SMALL]): enlarged 2x first, boxes scaled back.
 *  - everything in between: passed straight through.
 *  - [recognizeHi]: when the ORIGINAL file is much bigger than the bitmap (downscaled to fit memory), the tiles are cut from the
 *    original at full resolution ([HiRes]) instead of from the bitmap; their boxes are scaled back to bitmap pixels.
 * Callbacks arrive on the main thread (the base engine's), the merge is cheap (a few hundred words).
 */
class TiledOcr(private val base: OcrEngine) : OcrEngine {
    @Volatile private var closed = false
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "ocr-tiles").also { it.isDaemon = true } }   // cuts tiles out of the original file
    @Volatile private var openHi: HiRes? = null

    private class Tile(val x: Int, val y: Int, val w: Int, val h: Int, val core: RectF)
    private class Keyed(val key: Int, val line: OcrLine)

    override fun recognize(bm: Bitmap, cb: (Result<List<OcrLine>>) -> Unit) = recognizeHi(bm, null, cb)

    /** Like [recognize]; [original] = the picture file [bm] was decoded from (same orientation rules), or null. */
    fun recognizeHi(bm: Bitmap, original: File?, cb: (Result<List<OcrLine>>) -> Unit) {
        val side = maxOf(bm.width, bm.height)
        when {
            side >= BIG -> whole(bm, original, cb)
            side < SMALL -> enlarged(bm, cb)
            else -> base.recognize(bm, cb)
        }
    }

    override fun close() {
        closed = true
        io.execute { openHi?.close(); openHi = null }
        io.shutdown()
        base.close()
    }

    // ---------------------------------------------------------------- small pictures
    private fun enlarged(bm: Bitmap, cb: (Result<List<OcrLine>>) -> Unit) {
        val up = try { Bitmap.createScaledBitmap(bm, bm.width * 2, bm.height * 2, true) } catch (e: Throwable) { null }
        if (up == null) { base.recognize(bm, cb); return }
        base.recognize(up) { res ->
            try { up.recycle() } catch (_: Throwable) {}
            cb(res.map { ls -> ls.map { l -> xf(l, 0.5f) } })
        }
    }

    private fun xq(q: FloatArray?, f: Float, dx: Float, dy: Float): FloatArray? = q?.let { a -> FloatArray(8) { i -> a[i] * f + (if (i % 2 == 0) dx else dy) } }
    private fun word(w: OcrWord, f: Float, dx: Float, dy: Float) =
        OcrWord(w.text, RectF(w.box.left * f + dx, w.box.top * f + dy, w.box.right * f + dx, w.box.bottom * f + dy), xq(w.quad, f, dx, dy))
    /** Line scaled by [f] (box, words and tilted corners). */
    private fun xf(l: OcrLine, f: Float) = OcrLine(l.text, RectF(l.box.left * f, l.box.top * f, l.box.right * f, l.box.bottom * f), l.words.map { w -> word(w, f, 0f, 0f) })

    // ---------------------------------------------------------------- big pictures
    private fun whole(bm: Bitmap, original: File?, cb: (Result<List<OcrLine>>) -> Unit) {
        base.recognize(bm) { res ->
            if (closed) return@recognize
            val full = res.getOrNull()
            if (full == null) { cb(res); return@recognize }          // the base engine failed (model missing ...): report it as it is
            if (original == null) { tiles(bm, full, null, cb); return@recognize }
            io.execute {
                val hi = try { HiRes.open(original) } catch (e: Throwable) { null }
                main.post {
                    if (closed) { hi?.let { h -> closeHi(h) }; return@post }
                    tiles(bm, full, hi, cb)
                }
            }
        }
    }

    /** Closes a decoder on the tile thread (it may still be cutting); directly when that thread is gone. */
    private fun closeHi(h: HiRes) { try { io.execute { h.close() } } catch (e: Throwable) { h.close() } }

    /** Tiles from the original ([hi0], full resolution) when it is clearly bigger than [bm], else from [bm] itself. */
    private fun tiles(bm: Bitmap, full: List<OcrLine>, hi0: HiRes?, cb: (Result<List<OcrLine>>) -> Unit) {
        val hi = if (hi0 != null && maxOf(hi0.w, hi0.h) >= maxOf(bm.width, bm.height) * 1.25f) hi0 else { hi0?.let { h -> closeHi(h) }; null }
        openHi = hi
        val sw = hi?.w ?: bm.width
        val sh = hi?.h ?: bm.height
        val f = bm.width.toFloat() / sw                           // tile space -> bitmap pixels (1 without the original)
        val ts = tilesOf(sw, sh)
        val got = ArrayList<OcrLine>()
        fun cut(t: Tile, k: (Bitmap?) -> Unit) {
            if (hi == null) { k(try { Bitmap.createBitmap(bm, t.x, t.y, t.w, t.h).takeIf { it !== bm } } catch (e: Throwable) { null }); return }
            try {
                io.execute {
                    val c = try { hi.crop(t.x, t.y, t.w, t.h) } catch (e: Throwable) { null }
                    main.post { k(c) }
                }
            } catch (e: Throwable) { k(null) }       // executor already shut down (closed)
        }
        nextTile(ts, 0, sw, sh, { t, k -> cut(t, k) }, got) {
            if (hi != null) { if (openHi === hi) openHi = null; closeHi(hi) }
            val tl = if (hi == null) got else got.map { l -> xf(l, f) }
            val merged = try { merge(full, tl) } catch (e: Throwable) { full }
            cb(Result.success(if (merged.isEmpty()) full else merged))
        }
    }

    /** One tile after the other (the engine reads one picture at a time); a tile that cannot be cut (memory) is skipped, the whole-picture pass covers it. */
    private fun nextTile(ts: List<Tile>, i: Int, sw: Int, sh: Int, cut: (Tile, (Bitmap?) -> Unit) -> Unit, got: ArrayList<OcrLine>, done: () -> Unit) {
        if (closed) return
        if (i >= ts.size) { done(); return }
        val t = ts[i]
        cut(t) { crop ->
            if (closed) { try { crop?.recycle() } catch (_: Throwable) {} }
            else if (crop == null) nextTile(ts, i + 1, sw, sh, cut, got, done)
            else base.recognize(crop) { res ->
                try { crop.recycle() } catch (_: Throwable) {}
                res.getOrNull()?.let { got.addAll(own(it, t, sw, sh)) }
                nextTile(ts, i + 1, sw, sh, cut, got, done)
            }
        }
    }

    /** Start + core range along one axis: tiles of [TILE] px overlapping by about [OVER]; the core is where a tile "owns" the words (middle of each overlap). */
    private fun axis(n: Int): List<IntArray> {
        if (n <= TILE) return listOf(intArrayOf(0, 0, n))
        val starts = ArrayList<Int>()
        var s = 0
        while (true) {
            if (s + TILE >= n) { starts.add(n - TILE); break }
            starts.add(s)
            s += TILE - OVER
        }
        val out = ArrayList<IntArray>()
        for (i in starts.indices) {
            val from = if (i == 0) 0 else (starts[i] + starts[i - 1] + TILE) / 2
            val to = if (i == starts.size - 1) n else (starts[i + 1] + starts[i] + TILE) / 2
            out.add(intArrayOf(starts[i], from, to))
        }
        return out
    }

    private fun tilesOf(w: Int, h: Int): List<Tile> {
        val out = ArrayList<Tile>()
        for (y in axis(h)) for (x in axis(w))
            out.add(Tile(x[0], y[0], minOf(TILE, w), minOf(TILE, h), RectF(x[1].toFloat(), y[1].toFloat(), x[2].toFloat(), y[2].toFloat())))
        return out
    }

    /** Words of one tile in bitmap coordinates: only those whose centre lies in the tile's core and that do not touch a cut edge of the tile (cut words are garbage). */
    private fun own(ls: List<OcrLine>, t: Tile, bw: Int, bh: Int): List<OcrLine> {
        val out = ArrayList<OcrLine>()
        for (l in ls) {
            val ws = ArrayList<OcrWord>()
            for (w in l.words) {
                val b = w.box
                if (t.x > 0 && b.left <= EDGE) continue
                if (t.y > 0 && b.top <= EDGE) continue
                if (t.x + t.w < bw && b.right >= t.w - EDGE) continue
                if (t.y + t.h < bh && b.bottom >= t.h - EDGE) continue
                val g = word(w, 1f, t.x.toFloat(), t.y.toFloat())
                if (!t.core.contains(g.box.centerX(), g.box.centerY())) continue
                ws.add(g)
            }
            if (ws.isNotEmpty()) out.add(lineOf(ws))
        }
        return out
    }

    // ---------------------------------------------------------------- merge
    private fun lineOf(ws: List<OcrWord>): OcrLine {
        val r = RectF(ws[0].box)
        for (w in ws) r.union(w.box)
        return OcrLine(ws.joinToString(" ") { it.text }, r, ws)
    }

    private fun same(a: RectF, b: RectF): Boolean {
        val iw = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val ih = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (iw <= 0f || ih <= 0f) return false
        val small = minOf(a.width() * a.height(), b.width() * b.height())
        return small > 0f && iw * ih >= 0.4f * small
    }

    private fun sameRow(a: OcrLine, b: OcrLine): Boolean {
        val ra = a.box
        val rb = b.box
        val ov = minOf(ra.bottom, rb.bottom) - maxOf(ra.top, rb.top)
        val hs = minOf(ra.height(), rb.height())
        if (hs <= 0f || ov < 0.6f * hs) return false
        val gap = maxOf(ra.left, rb.left) - minOf(ra.right, rb.right)
        return gap <= 0.8f * maxOf(ra.height(), rb.height())
    }

    private fun joinRows(l: ArrayList<OcrLine>) {
        var i = 0
        while (i < l.size) {
            var j = i + 1
            while (j < l.size) {
                if (sameRow(l[i], l[j])) {
                    l[i] = lineOf((l[i].words + l[j].words).sortedBy { it.box.centerX() })
                    l.removeAt(j)
                    j = i + 1
                } else j++
            }
            i++
        }
    }

    private fun merge(full: List<OcrLine>, tileLines: List<OcrLine>): List<OcrLine> {
        // 1) large text belongs to the whole-picture pass: tile words centred inside a big whole-picture line are dropped
        val big = full.filter { it.box.height() >= BIGTEXT }
        val tw = ArrayList<OcrWord>()
        val kept = ArrayList<OcrLine>()
        for (l in tileLines) {
            val ws = l.words.filter { w -> big.none { b -> b.box.contains(w.box.centerX(), w.box.centerY()) } }
            if (ws.isNotEmpty()) { kept.add(if (ws.size == l.words.size) l else lineOf(ws)); tw.addAll(ws) }
        }
        // 2) whole-picture words that a tile read at the same place are dropped: the tile reading wins
        val all = ArrayList<OcrLine>()
        for (l in full) {
            val ws = l.words.filter { w -> tw.none { t -> same(w.box, t.box) } }
            if (ws.isNotEmpty()) all.add(if (ws.size == l.words.size) l else lineOf(ws))
        }
        all.addAll(kept)
        // 3) pieces of one row (tile borders, left-overs of a whole-picture line) become one line again
        joinRows(all)
        // 4) reading order follows the whole-picture pass: each line goes after the whole-picture line nearest to it (vertical distance counts more), then top / left
        if (full.isEmpty()) return all.sortedWith(compareBy<OcrLine>({ it.box.top }, { it.box.left }))
        val keyed = all.map { l ->
            var bi = 0
            var bd = Float.MAX_VALUE
            for ((i, f) in full.withIndex()) {
                val dx = f.box.centerX() - l.box.centerX()
                val dy = f.box.centerY() - l.box.centerY()
                val d = dx * dx + 16f * dy * dy
                if (d < bd) { bd = d; bi = i }
            }
            Keyed(bi, l)
        }
        return keyed.sortedWith(compareBy<Keyed>({ it.key }, { it.line.box.top }, { it.line.box.left })).map { it.line }
    }

    private companion object {
        const val BIG = 2400          // long side from which tiles are used
        const val SMALL = 900         // long side below which the picture is enlarged 2x
        const val TILE = 1792
        const val OVER = 256
        const val EDGE = 4f           // a word this close to a cut edge of a tile is ignored
        const val BIGTEXT = 96f       // whole-picture lines at least this tall are left alone
    }
}
