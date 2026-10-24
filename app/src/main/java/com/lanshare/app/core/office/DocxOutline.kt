package com.lanshare.app.core.office

/** Part 12: outline of a Word-type document = its headings in document order (top-level paragraphs and table cells). Pure Kotlin. */
class OutlineItem(val id: Int, val level: Int, val text: String)

object DocxOutline {
    private const val MAX = 500

    /** Numbers the headings (sets [DPara.hid], which DocxHtml writes as `id="lsh<hid>"`) and returns them. Safe to call again on the same doc. */
    fun build(doc: DDoc): List<OutlineItem> {
        val out = ArrayList<OutlineItem>()
        fun walk(bs: List<DBlock>, depth: Int) {
            for (b in bs) {
                if (out.size >= MAX) return
                if (b is DPara) {
                    b.hid = -1
                    if (b.heading <= 0) continue
                    val t = StringBuilder()
                    for (r in b.runs) if (r.kind == DRun.TEXT && r.rev != 2) t.append(r.text)
                    val s = t.toString().replace('\n', ' ').replace('\t', ' ').trim()
                    if (s.isEmpty()) continue
                    b.hid = out.size
                    out.add(OutlineItem(b.hid, minOf(b.heading, 6), if (s.length > 90) s.substring(0, 89) + "\u2026" else s))
                } else if (b is DTable && depth < 2) {
                    for (row in b.rows) for (c in row.cells) walk(c.blocks, depth + 1)
                }
            }
        }
        walk(doc.body, 0)
        return out
    }
}
