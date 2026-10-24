package com.lanshare.app.core.usearch

/** Universal Search, Part 9: export of a results list as CSV or plain text (no android.*), tested by `exportTests`. */
object UsExport {
    class ExRow(val name: String, val path: String, val dir: Boolean, val size: Long, val mtimeSec: Long, val snippet: String = "", val line: Int = 0)

    const val MAX_ROWS = 5000

    private fun csvCell(s: String): String {
        // leading = + - @ would run as a formula in a spreadsheet: neutralise with a quote
        val t = if (s.isNotEmpty() && s[0] in "=+-@") "'$s" else s
        return if (t.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + t.replace("\"", "\"\"") + "\"" else t
    }

    /** UTC "yyyy-MM-dd HH:mm" without java.time (minSdk 24). Empty for t <= 0. */
    fun date(sec: Long): String {
        if (sec <= 0) return ""
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")); c.timeInMillis = sec * 1000
        fun p(n: Int) = if (n < 10) "0$n" else "$n"
        return "" + c.get(java.util.Calendar.YEAR) + "-" + p(c.get(java.util.Calendar.MONTH) + 1) + "-" + p(c.get(java.util.Calendar.DAY_OF_MONTH)) +
            " " + p(c.get(java.util.Calendar.HOUR_OF_DAY)) + ":" + p(c.get(java.util.Calendar.MINUTE))
    }

    fun csv(rows: List<ExRow>): String {
        val sb = StringBuilder("name,path,type,size,modified_utc,line,snippet\r\n")
        for (r in rows.take(MAX_ROWS)) {
            sb.append(csvCell(r.name)).append(',').append(csvCell(r.path)).append(',').append(if (r.dir) "folder" else "file").append(',')
                .append(if (r.dir) "" else r.size.toString()).append(',').append(date(r.mtimeSec)).append(',')
                .append(if (r.line > 0) r.line.toString() else "").append(',').append(csvCell(r.snippet)).append("\r\n")
        }
        return sb.toString()
    }

    /** One path per line (folders end with "/"). */
    fun text(rows: List<ExRow>): String {
        val sb = StringBuilder()
        for (r in rows.take(MAX_ROWS)) { sb.append(r.path); if (r.dir && !r.path.endsWith("/")) sb.append('/'); sb.append('\n') }
        return sb.toString()
    }
}
