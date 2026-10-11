package com.lanshare.app

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.view.View
import androidx.core.graphics.ColorUtils

/**
 * Light Markdown -> blocks for the text editor's preview (TextEditorActivity). No library, no WebView.
 * Running text (headings, bold / italic / strike, `code`, bullet / numbered / task lists, quotes, rules, clickable links) is merged into [Block.Text];
 * fenced code ([Block.Code], syntax-highlighted by the editor via [CodeHl]) and tables ([Block.Table], a real grid) are separate blocks
 * so the editor can give them their own horizontally scrolling cards.
 */
object MdRender {
    private const val MAX_CHARS = 600_000
    private val FL = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
    // groups: 1 `code`, 2 **bold**, 3 __bold__, 4 ~~strike~~, 5+6 ![alt](url), 7+8 [text](url), 9 *italic*, 10 _italic_, 11 <https://auto>, 12 bare https://url
    private val INLINE = Regex(
        """`([^`\n]+)`""" +
        """|\*\*(.+?)\*\*""" +
        """|__(.+?)__""" +
        """|~~(.+?)~~""" +
        """|!\[([^\]\n]*)\]\(([^)\s]+)[^)]*\)""" +
        """|\[([^\]\n]+)\]\(([^)\s]+)[^)]*\)""" +
        """|\*(?!\s)([^*\n]+?)(?<!\s)\*""" +
        """|(?<!\w)_(?!\s)([^_\n]+?)(?<!\s)_(?!\w)""" +
        """|<(https?://[^>\s]+)>""" +
        """|(https?://[^\s<>\]]+)"""
    )
    private val HEAD = Regex("^\\s{0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val HR = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val QUOTE = Regex("^\\s*>\\s?(.*)$")
    private val LIST = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$")
    private val SEP = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")
    private val SIZES = floatArrayOf(1.7f, 1.45f, 1.25f, 1.12f, 1.05f, 1.0f)

    sealed class Block {
        class Text(val text: CharSequence) : Block()
        class Code(val lang: String, val code: String) : Block()
        /** [align]: 0 left, 1 centre, 2 right. Every row has exactly [head].size cells. */
        class Table(val head: List<CharSequence>, val rows: List<List<CharSequence>>, val align: List<Int>) : Block()
    }

    private class Ctx(val muted: Int, val accent: Int, val codeBg: Int, val px: Float, val onLink: (String) -> Unit)

    private class Link(val url: String, val color: Int, val cb: (String) -> Unit) : ClickableSpan() {
        override fun onClick(w: View) = cb(url)
        override fun updateDrawState(ds: TextPaint) { ds.color = color; ds.isUnderlineText = true }
    }

    fun blocks(src: String, textColor: Int, night: Boolean, density: Float, onLink: (String) -> Unit): List<Block> {
        val cut = src.length > MAX_CHARS
        val lines = (if (cut) src.substring(0, MAX_CHARS) else src).replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val c = Ctx(ColorUtils.setAlphaComponent(textColor, 150), if (night) 0xFFFFB77C.toInt() else 0xFFC25100.toInt(),
            ColorUtils.setAlphaComponent(textColor, 32), density, onLink)
        val out = ArrayList<Block>()
        var sb = SpannableStringBuilder()
        fun flush() {
            while (sb.isNotEmpty() && sb[sb.length - 1] == '\n') sb.delete(sb.length - 1, sb.length)
            while (sb.isNotEmpty() && sb[0] == '\n') sb.delete(0, 1)
            if (sb.isNotEmpty()) out.add(Block.Text(sb))
            sb = SpannableStringBuilder()
        }
        var inCode = false
        var lang = ""
        val code = StringBuilder()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            if (t.startsWith("```") || t.startsWith("~~~")) {
                if (!inCode) {
                    flush(); inCode = true; code.setLength(0)
                    lang = t.trimStart('`', '~').trim().substringBefore(' ').lowercase()
                } else {
                    inCode = false
                    out.add(Block.Code(lang, code.toString().trimEnd('\n')))
                    code.setLength(0)
                }
                i++; continue
            }
            if (inCode) { code.append(line).append('\n'); i++; continue }
            if (t.isEmpty()) { gap(sb); i++; continue }
            // table: header row + separator row
            if (t.contains('|') && i + 1 < lines.size && lines[i + 1].contains('|') && lines[i + 1].contains('-') && SEP.matches(lines[i + 1])) {
                flush()
                val head = cells(line)
                val align = cells(lines[i + 1]).map { if (it.startsWith(":") && it.endsWith(":")) 1 else if (it.endsWith(":")) 2 else 0 }
                val raw = ArrayList<List<String>>()
                var j = i + 2
                while (j < lines.size && lines[j].trim().isNotEmpty() && lines[j].contains('|')) { raw.add(cells(lines[j])); j++ }
                var cols = head.size
                for (r in raw) if (r.size > cols) cols = r.size
                fun cell(s: String): CharSequence { val b = SpannableStringBuilder(); inline(b, s, c); return b }
                val h = List(cols) { k -> cell(head.getOrElse(k) { "" }) }
                val rows = raw.map { r -> List(cols) { k -> cell(r.getOrElse(k) { "" }) } }
                out.add(Block.Table(h, rows, List(cols) { k -> align.getOrElse(k) { 0 } }))
                i = j; continue
            }
            val hm = HEAD.matchEntire(line)
            if (hm != null) {
                gap(sb)
                val lvl = hm.groupValues[1].length
                val st = sb.length
                inline(sb, hm.groupValues[2], c)
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
        if (inCode) out.add(Block.Code(lang, code.toString().trimEnd('\n')))   // unclosed fence: still shown as code
        if (cut) sb.append("\n\u2026 preview shows the first ${MAX_CHARS / 1000} thousand characters")
        flush()
        return out
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
            var end = m.range.last + 1
            when {
                g[1] != null -> {
                    sb.append(g[1]!!.value)
                    sb.setSpan(android.text.style.TypefaceSpan("monospace"), st, sb.length, FL)
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
                g[6] != null -> {   // image: shown as a tappable label (no network, no decoding)
                    val alt = g[5]?.value.orEmpty()
                    sb.append("\uD83D\uDDBC ").append(if (alt.isEmpty()) "image" else alt)
                    sb.setSpan(Link(g[6]!!.value, c.muted, c.onLink), st, sb.length, FL)
                }
                g[7] != null -> {
                    inline(sb, g[7]!!.value, c)
                    sb.setSpan(Link(g[8]!!.value, c.accent, c.onLink), st, sb.length, FL)
                }
                g[9] != null || g[10] != null -> {
                    inline(sb, (g[9] ?: g[10])!!.value, c)
                    sb.setSpan(StyleSpan(Typeface.ITALIC), st, sb.length, FL)
                }
                g[11] != null -> {
                    sb.append(g[11]!!.value)
                    sb.setSpan(Link(g[11]!!.value, c.accent, c.onLink), st, sb.length, FL)
                }
                else -> {   // bare URL: trailing punctuation is not part of it
                    val raw = g[12]!!.value
                    val u = raw.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '\'', '"')
                    sb.append(u)
                    sb.setSpan(Link(u, c.accent, c.onLink), st, sb.length, FL)
                    end = m.range.first + u.length
                }
            }
            pos = end
        }
        if (pos < s.length) sb.append(s, pos, s.length)
    }
}
