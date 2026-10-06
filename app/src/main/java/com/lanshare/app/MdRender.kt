package com.lanshare.app

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import androidx.core.graphics.ColorUtils

/**
 * Light Markdown -> styled text for the text editor's preview (TextEditorActivity). No library, no WebView.
 * Headings, bold / italic / strike, `code`, fenced code, bullet / numbered / task lists, quotes, rules, links (styled, not clickable).
 * Tables are shown as one card per row (first cell = title, the other cells = "Header  value" lines): a real grid does not fit a phone.
 */
object MdRender {
    private const val MAX_CHARS = 600_000
    private val FL = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
    // groups: 1 `code`, 2 **bold**, 3 __bold__, 4 ~~strike~~, 5+6 [text](url), 7 *italic*, 8 _italic_
    private val INLINE = Regex("`([^`\\n]+)`|\\*\\*(.+?)\\*\\*|__(.+?)__|~~(.+?)~~|\\[([^\\]\\n]+)\\]\\(([^)\\s]+)\\)|\\*(?!\\s)([^*\\n]+?)(?<!\\s)\\*|(?<!\\w)_(?!\\s)([^_\\n]+?)(?<!\\s)_(?!\\w)")
    private val HEAD = Regex("^\\s{0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val HR = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val QUOTE = Regex("^\\s*>\\s?(.*)$")
    private val LIST = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$")
    private val SEP = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")
    private val SIZES = floatArrayOf(1.7f, 1.45f, 1.25f, 1.12f, 1.05f, 1.0f)

    private class Ctx(val muted: Int, val accent: Int, val codeBg: Int, val px: Float)

    fun render(src: String, textColor: Int, night: Boolean, density: Float): CharSequence {
        val cut = src.length > MAX_CHARS
        val lines = (if (cut) src.substring(0, MAX_CHARS) else src).replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val c = Ctx(ColorUtils.setAlphaComponent(textColor, 150), if (night) 0xFF8AB4F8.toInt() else 0xFF1A5FB4.toInt(),
            ColorUtils.setAlphaComponent(textColor, 32), density)
        val sb = SpannableStringBuilder()
        var inCode = false
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            if (t.startsWith("```") || t.startsWith("~~~")) { inCode = !inCode; if (!inCode) gap(sb); i++; continue }
            if (inCode) {
                val st = sb.length
                sb.append(if (line.isEmpty()) " " else line).append('\n')
                sb.setSpan(TypefaceSpan("monospace"), st, sb.length, FL)
                sb.setSpan(BackgroundColorSpan(c.codeBg), st, sb.length, FL)
                sb.setSpan(RelativeSizeSpan(0.9f), st, sb.length, FL)
                i++; continue
            }
            if (t.isEmpty()) { gap(sb); i++; continue }
            // table: header row + separator row
            if (t.contains('|') && i + 1 < lines.size && lines[i + 1].contains('|') && lines[i + 1].contains('-') && SEP.matches(lines[i + 1])) {
                val head = cells(line)
                var j = i + 2
                while (j < lines.size && lines[j].trim().isNotEmpty() && lines[j].contains('|')) {
                    val row = cells(lines[j])
                    val st0 = sb.length
                    if (row.isNotEmpty() && row[0].isNotEmpty()) {
                        val st = sb.length
                        inline(sb, row[0], c)
                        sb.setSpan(StyleSpan(Typeface.BOLD), st, sb.length, FL)
                        sb.setSpan(RelativeSizeSpan(1.05f), st, sb.length, FL)
                        sb.append('\n')
                    }
                    val dStart = sb.length
                    for (k in 1 until row.size) {
                        if (row[k].isEmpty()) continue
                        val ls = sb.length
                        sb.append(head.getOrElse(k) { "" })
                        sb.setSpan(StyleSpan(Typeface.BOLD), ls, sb.length, FL)
                        sb.setSpan(ForegroundColorSpan(c.muted), ls, sb.length, FL)
                        sb.append("  ")
                        inline(sb, row[k], c)
                        sb.append('\n')
                    }
                    if (sb.length > dStart) sb.setSpan(LeadingMarginSpan.Standard((12 * c.px).toInt()), dStart, sb.length, FL)
                    if (sb.length > st0) sb.append('\n')
                    j++
                }
                i = j; continue
            }
            val h = HEAD.matchEntire(line)
            if (h != null) {
                gap(sb)
                val lvl = h.groupValues[1].length
                val st = sb.length
                inline(sb, h.groupValues[2], c)
                sb.setSpan(StyleSpan(Typeface.BOLD), st, sb.length, FL)
                sb.setSpan(RelativeSizeSpan(SIZES[lvl - 1]), st, sb.length, FL)
                if (lvl <= 2) sb.setSpan(ForegroundColorSpan(c.accent), st, sb.length, FL)
                sb.append('\n')
                i++; continue
            }
            if (HR.matches(line)) {
                val st = sb.length
                sb.append("\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n")
                sb.setSpan(ForegroundColorSpan(c.muted), st, sb.length, FL)
                i++; continue
            }
            val q = QUOTE.matchEntire(line)
            if (q != null) {
                val st = sb.length
                inline(sb, q.groupValues[1], c)
                sb.append('\n')
                sb.setSpan(QuoteSpan(c.accent), st, sb.length, FL)
                sb.setSpan(ForegroundColorSpan(c.muted), st, sb.length, FL)
                i++; continue
            }
            val l = LIST.matchEntire(line)
            if (l != null) {
                val lvl = (l.groupValues[1].replace("\t", "  ").length / 2).coerceAtMost(6)
                val mark = l.groupValues[2]
                var body = l.groupValues[3]
                var bullet = if (mark[0].isDigit()) mark else if (lvl > 0) "\u25E6" else "\u2022"
                if (body.startsWith("[ ] ")) { bullet = "\u2610"; body = body.substring(4) }
                else if (body.startsWith("[x] ") || body.startsWith("[X] ")) { bullet = "\u2611"; body = body.substring(4) }
                val st = sb.length
                sb.append(bullet).append(' ')
                inline(sb, body, c)
                sb.append('\n')
                sb.setSpan(LeadingMarginSpan.Standard(((6 + lvl * 16) * c.px).toInt(), ((6 + lvl * 16 + 18) * c.px).toInt()), st, sb.length, FL)
                i++; continue
            }
            inline(sb, line.trimEnd(), c)
            sb.append('\n')
            i++
        }
        if (cut) sb.append("\n\u2026 preview shows the first ${MAX_CHARS / 1000} thousand characters")
        return sb
    }

    private fun gap(sb: SpannableStringBuilder) {
        val n = sb.length
        if (n == 0) return
        if (n >= 2 && sb[n - 1] == '\n' && sb[n - 2] == '\n') return
        sb.append('\n')
    }

    private fun cells(l: String): List<String> {
        var t = l.trim()
        if (t.startsWith("|")) t = t.substring(1)
        if (t.endsWith("|") && !t.endsWith("\\|")) t = t.dropLast(1)
        return t.replace("\\|", "\u0001").split('|').map { it.replace('\u0001', '|').trim() }
    }

    private fun inline(sb: SpannableStringBuilder, s: String, c: Ctx) {
        var pos = 0
        for (m in INLINE.findAll(s)) {
            if (m.range.first > pos) sb.append(s, pos, m.range.first)
            val g = m.groups
            val st = sb.length
            when {
                g[1] != null -> {
                    sb.append(g[1]!!.value)
                    sb.setSpan(TypefaceSpan("monospace"), st, sb.length, FL)
                    sb.setSpan(BackgroundColorSpan(c.codeBg), st, sb.length, FL)
                    sb.setSpan(RelativeSizeSpan(0.92f), st, sb.length, FL)
                }
                g[2] != null || g[3] != null -> {
                    inline(sb, (g[2] ?: g[3])!!.value, c)
                    sb.setSpan(StyleSpan(Typeface.BOLD), st, sb.length, FL)
                }
                g[4] != null -> {
                    inline(sb, g[4]!!.value, c)
                    sb.setSpan(StrikethroughSpan(), st, sb.length, FL)
                }
                g[5] != null -> {
                    inline(sb, g[5]!!.value, c)
                    sb.setSpan(ForegroundColorSpan(c.accent), st, sb.length, FL)
                    sb.setSpan(UnderlineSpan(), st, sb.length, FL)
                }
                else -> {
                    inline(sb, (g[7] ?: g[8])!!.value, c)
                    sb.setSpan(StyleSpan(Typeface.ITALIC), st, sb.length, FL)
                }
            }
            pos = m.range.last + 1
        }
        if (pos < s.length) sb.append(s, pos, s.length)
    }
}
