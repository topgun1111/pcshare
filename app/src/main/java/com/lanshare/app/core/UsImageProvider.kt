package com.lanshare.app.core

import com.lanshare.app.core.usearch.ImgHit
import com.lanshare.app.core.usearch.ImgPlan
import com.lanshare.app.core.usearch.ImgQuery

/** Universal Search Part 7: adapter from an [ImgPlan] to [ImgSearch] (text = OCR words, visual = CLIP meaning). ImgSearch keeps owning vectors, OCR text and discovery (D7). Paths come back REAL. */
object UsImageProvider {
    class Out(val hits: List<ImgHit>, val notes: List<String>, val modelMissing: Boolean)

    private fun paths(j: org.json.JSONObject): List<String> {
        val a = j.optJSONArray("results") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("path")?.takeIf { p -> p.isNotEmpty() } }
    }

    fun run(plan: ImgPlan, threshold: Float): Out {
        val notes = ArrayList<String>(plan.notes)
        val root = try { Core.local.root.path } catch (_: Throwable) { return Out(emptyList(), notes + "Picture search: storage is not ready", false) }
        var ocr: List<String> = emptyList()
        if (plan.wantsOcr) {
            ocr = try {
                ImgQuery.intersect(plan.ocr.map { paths(ImgSearch.text(it.text, it.whole)) }, plan.ocrNot.map { paths(ImgSearch.text(it.text, it.whole)) })
            } catch (e: BadReq) { notes.add("ocr: " + (e.message ?: "failed")); emptyList() }
        }
        var ai: List<Pair<String, Float>> = emptyList()
        var missing = false
        if (plan.wantsAi) {
            if (ImgSearch.engine == null) { missing = true; notes.add("ai: the picture model is not installed (Settings > Image search > install); showing text matches only") }
            else ai = try {
                val a = ImgSearch.visual(plan.ai, threshold = threshold).optJSONArray("results")
                if (a == null) emptyList() else (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { it.optString("path") to it.optDouble("score", 0.0).toFloat() } }
            } catch (e: BadReq) { notes.add("ai: " + (e.message ?: "failed")); emptyList() }
        }
        val merged = ImgQuery.merge(ocr, ai).map { ImgHit(ImgQuery.toReal(root, it.path), it.score, it.badge) }
        if (plan.wantsOcr && ImgSearch.status().optInt("withText", 1) == 0 && ocr.isEmpty()) notes.add("ocr: no picture has been read yet (Settings > Image search > scan with text)")
        if (plan.wantsAi && !missing && ai.isEmpty() && ImgSearch.status().optInt("indexed", 1) == 0) notes.add("ai: no picture has been analysed yet (Settings > Image search > scan)")
        return Out(merged, notes, missing)
    }
}
