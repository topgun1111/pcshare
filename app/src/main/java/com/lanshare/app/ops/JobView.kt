package com.lanshare.app.ops

import com.lanshare.app.core.Jobs

/**
 * Read-only view of the app's background jobs ([Jobs.all]: copy / move / send / extract / zip / delete / print).
 * The jobs themselves keep running in the core (process wide, not in an Activity); this object only turns them into
 * rows for the native overlay ([JobOverlay]) and for the foreground notification ([com.lanshare.app.LanShareService]).
 */
object JobView {
    data class Row(
        val id: String,
        val label: String,
        val pct: Int,              // 0..100, -1 = indeterminate
        val state: String,         // run | done | cancel | error
        val detail: String,
        val cancellable: Boolean
    )

    private const val LINGER_OK_MS = 3_000L
    private const val LINGER_ERR_MS = 10_000L

    private val order = HashMap<String, Int>()      // first-seen order (the job map has none)
    private var seq = 0
    private val dismissed = HashSet<String>()       // "Clear" pressed: finished rows stay hidden

    @Synchronized fun rows(): List<Row> {
        val now = System.currentTimeMillis()
        val out = ArrayList<Pair<Int, Row>>()
        for ((key, j) in Jobs.all.entries.toList().map { it.key to it.value }) {
            val st = j.state
            if (st != "run") {
                val e = j.end
                if (e <= 0L || key in dismissed) continue
                if (now - e > (if (st == "error") LINGER_ERR_MS else LINGER_OK_MS)) continue
            }
            val n = order.getOrPut(key) { seq++ }
            val total = j.total
            val pct = when {
                st == "done" -> 100
                total > 0L -> ((j.done * 100L) / total).toInt().coerceIn(0, 100)
                else -> -1
            }
            val detail = when {
                st == "error" -> j.error ?: ""
                j.ask != null -> "Waiting for your answer - open LANShare"
                else -> buildString {
                    if (j.filesTotal > 0) append(j.files).append('/').append(j.filesTotal)
                    if (j.cur.isNotEmpty()) { if (isNotEmpty()) append("  "); append(j.cur) }
                }
            }
            out.add(n to Row(key, j.label, pct, st, detail, st == "run" && !j.cancel))
        }
        if (order.size > 200) order.keys.retainAll(Jobs.all.keys)
        return out.sortedBy { it.first }.map { it.second }
    }

    fun running(): List<Row> = rows().filter { it.state == "run" }

    /** Cancel = the same flag the page's Cancel button sets (POST /api/cancel); the job loops check it. */
    fun cancel(id: String) { Jobs.all[id]?.cancel = true }

    @Synchronized fun clearFinished() {
        for ((k, j) in Jobs.all) if (j.state != "run") dismissed.add(k)
    }

    /** Average progress of the running jobs, -1 when one of them is indeterminate or nothing runs. */
    fun overallPct(r: List<Row>): Int {
        val run = r.filter { it.state == "run" }
        if (run.isEmpty() || run.any { it.pct < 0 }) return -1
        return run.sumOf { it.pct } / run.size
    }
}
