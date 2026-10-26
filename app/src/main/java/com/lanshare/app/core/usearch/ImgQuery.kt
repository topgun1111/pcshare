package com.lanshare.app.core.usearch

/**
 * Universal Search Part 7: pure (no Android) planning and merging for the picture sections of a query.
 * `ocr:` = words printed in a picture (each positive term must be found, NOT terms must not be); `ai:` = one sentence for the CLIP text tower.
 * [com.lanshare.app.core.UsImageProvider] feeds the plan to ImgSearch; ImgSearch itself is not changed.
 */
class OcrTerm(val text: String, val whole: Boolean)
class ImgPlan(val ocr: List<OcrTerm>, val ocrNot: List<OcrTerm>, val ai: String, val notes: List<String>) {
    val wantsOcr: Boolean get() = ocr.isNotEmpty()
    val wantsAi: Boolean get() = ai.isNotEmpty()
}
class ImgHit(val path: String, val score: Float, val badge: String)

object ImgQuery {
    const val DEFAULT_THRESHOLD = 0.25f
    const val MIN_THRESHOLD = 0.05f
    const val MAX_THRESHOLD = 0.60f
    val EXTS: Set<String> = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif")

    fun clampThreshold(t: Float): Float = if (t.isNaN()) DEFAULT_THRESHOLD else t.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)

    fun plan(q: Query, opt: MatchOptions): ImgPlan {
        val notes = ArrayList<String>()
        val pos = ArrayList<OcrTerm>(); val neg = ArrayList<OcrTerm>()
        var warn = false
        q.ocr?.terms?.forEach { n ->
            val not = n is QNot
            val t = (if (n is QNot) n.n else n) as? QText ?: return@forEach
            if (t.text.isBlank()) return@forEach
            if (!warn && (t.regex || t.wild || opt.regex)) { notes.add("ocr: wildcards and regular expressions are not supported, the text is matched as typed"); warn = true }
            val term = OcrTerm(t.text.trim(), t.wholeWord ?: opt.wholeWord)
            if (not) neg.add(term) else pos.add(term)
        }
        if (pos.isEmpty() && neg.isNotEmpty()) notes.add("ocr: needs at least one word that must be present")
        val sb = StringBuilder()
        var aiNot = false
        q.ai?.terms?.forEach { n ->
            if (n is QNot) { aiNot = true; return@forEach }
            val t = n as? QText ?: return@forEach
            val s = t.text.trim()
            if (s.isEmpty()) return@forEach
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(s)
        }
        if (aiNot) notes.add("ai: excluded words (!word) are ignored, describe what you want to see")
        return ImgPlan(if (pos.isEmpty()) emptyList() else pos, if (pos.isEmpty()) emptyList() else neg, sb.toString(), notes)
    }

    /** Intersection of per-term path lists minus every path of [not]; order of the first list is kept. Empty [lists] = empty. */
    fun intersect(lists: List<List<String>>, not: List<List<String>>): List<String> {
        if (lists.isEmpty()) return emptyList()
        val ban = HashSet<String>(); for (l in not) ban.addAll(l)
        var cur: List<String> = lists[0].distinct()
        for (i in 1 until lists.size) { val s = lists[i].toHashSet(); cur = cur.filter { it in s } }
        return cur.filter { it !in ban }
    }

    /** OCR hits first (exact words), then AI hits not already listed; a picture found by both keeps the "OCR + AI" badge and the AI score. */
    fun merge(ocr: List<String>, ai: List<Pair<String, Float>>): List<ImgHit> {
        val aiMap = LinkedHashMap<String, Float>(); for ((p, s) in ai) if (!aiMap.containsKey(p)) aiMap[p] = s
        val out = ArrayList<ImgHit>()
        val seen = HashSet<String>()
        for (p in ocr) { if (!seen.add(p)) continue; val s = aiMap[p]; out.add(if (s != null) ImgHit(p, s, "OCR + AI") else ImgHit(p, 1f, "OCR")) }
        for ((p, s) in aiMap) if (seen.add(p)) out.add(ImgHit(p, s, "AI"))
        return out
    }

    /** ImgSearch keeps virtual paths ("/DCIM/a.jpg"); the name index keeps real ones. [root] without trailing slash. */
    fun toReal(root: String, v: String): String = root.trimEnd('/') + (if (v.startsWith("/")) v else "/$v")

    fun label(h: ImgHit): String = if (h.badge == "OCR") "OCR" else h.badge + " " + String.format(java.util.Locale.ROOT, "%.2f", h.score)
}
