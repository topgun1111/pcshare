package com.lanshare.app

import android.graphics.Typeface
import android.os.Build
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
 *
 * Reader features (HANDOVER.md 7.5):
 *  - consecutive text lines form one wrapped paragraph (a line ending in two spaces or `\` stays a hard break), list items take lazy continuation lines
 *  - nested lists by indentation (any indent width), nested quotes (`> >`), setext headings (`===` / `---`), `#` headings without eating a trailing `C#`
 *  - YAML front matter shown as a "yaml" code card, `<!-- comments -->` hidden
 *  - `\*` style escapes, `***bold italic***`, reference links (`[text][ref]` + `[ref]: url`), `[![badge](img)](url)`
 *  - simple HTML: `<br>`, `<a href>`, `<img>` (shown as a tappable label); other known tags (`<kbd>`, `<sup>`, `<p>` ...) are dropped, their text stays
 *  - every heading gets a GitHub-style slug ([Head]) so `[x](#section)` links can scroll and the editor can show an outline
 */
object MdRender {
    private const val MAX_CHARS = 600_000
    private val FL = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
    // groups: 1 ***bold italic***, 2 `code`, 3 **bold**, 4 __bold__, 5 ~~strike~~, 6+7 ![alt](url), 8+9 [text](url) (text may hold one image),
    // 10+11 [text][ref], 12 *italic*, 13 _italic_, 14 <https://auto>, 15 bare https://url, 16+17 <a href>text</a>, 18 <img ...>, 19 other simple html tag
    private val INLINE = Regex(
        """\*\*\*(?!\s)(.+?)(?<!\s)\*\*\*""" +
        """|`([^`\n]+)`""" +
        """|\*\*(.+?)\*\*""" +
        """|__(.+?)__""" +
        """|~~(.+?)~~""" +
        """|!\[([^\]\n]*)\]\(([^)\s]+)[^)]*\)""" +
        """|\[((?:!\[[^\]\n]*\]\([^)\n]*\)|[^\]\n])+)\]\(([^)\s]+)[^)]*\)""" +
        """|\[([^\]\n]+)\]\[([^\]\n]*)\]""" +
        """|\*(?!\s)([^*\n]+?)(?<!\s)\*""" +
        """|(?<!\w)_(?!\s)([^_\n]+?)(?<!\s)_(?!\w)""" +
        """|<(https?://[^>\s]+)>""" +
        """|(https?://[^\s<>\]]+)""" +
        """|<a\s[^>]*?href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""" +
        """|(<img\s[^>]*>)""" +
        """|(</?(?:br|hr|p|div|span|kbd|sup|sub|details|summary|center|b|i|u|em|strong|small|mark|ins|del|font|picture|source|table|thead|tbody|tr|td|th|ul|ol|li|h[1-6])(?:\s[^>]*)?/?>)""",
        RegexOption.IGNORE_CASE
    )
    private val HEAD = Regex("^\\s{0,3}(#{1,6})\\s+(.*?)\\s*$")
    private val HEAD_TAIL = Regex("\\s+#+$")
    private val SET1 = Regex("^\\s{0,3}=+\\s*$")
    private val SET2 = Regex("^\\s{0,3}-{2,}\\s*$")
    private val HR = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val LIST = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$")
    private val SEP = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")
    private val REFDEF = Regex("""^\s{0,3}\[([^\]^][^\]]*)\]:\s*(\S+)(?:\s+(?:"[^"]*"|'[^']*'|\([^)]*\)))?\s*$""")
    private val FM_KEY = Regex("^[A-Za-z_][\\w-]*\\s*:")
    private val ESC = Regex("""\\([\\`*_{}\[\]()#+\-.!|~<>])""")
    private val IMG_IN_LINK = Regex("""!\[([^\]\n]*)\]\([^)\n]*\)""")
    private val SRC = Regex("""src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val ALT = Regex("""alt\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val SIZES = floatArrayOf(1.7f, 1.45f, 1.25f, 1.12f, 1.05f, 1.0f)
    private const val IMG = "\uD83D\uDDBC "

    sealed class Block {
        /** [heads]: headings inside this block, [Head.offset] = character offset into [text]. */
        class Text(val text: CharSequence, val heads: List<Head> = emptyList()) : Block()
        class Code(val lang: String, val code: String) : Block()
        /** [align]: 0 left, 1 centre, 2 right. Every row has exactly [head].size cells. */
        class Table(val head: List<CharSequence>, val rows: List<List<CharSequence>>, val align: List<Int>) : Block()
    }

    class Head(val level: Int, val title: String, val slug: String, val offset: Int)

    private class Ctx(val muted: Int, val accent: Int, val codeBg: Int, val px: Float, val refs: Map<String, String>, val onLink: (String) -> Unit)

    private class Link(val url: String, val color: Int, val cb: (String) -> Unit) : ClickableSpan() {
        override fun onClick(w: View) = cb(url)
        override fun updateDrawState(ds: TextPaint) { ds.color = color; ds.isUnderlineText = true }
    }

    /** GitHub-style anchor of a heading text (also used to normalise `#fragment` links). */
    fun slug(t: String): String {
        val b = StringBuilder()
        for (ch in t.trim().lowercase()) {
            if (ch.isLetterOrDigit() || ch == '_' || ch == '-') b.append(ch) else if (ch == ' ') b.append('-')
        }
        return b.toString()
    }

    private fun quoteSpan(color: Int, px: Float): QuoteSpan =
        if (Build.VERSION.SDK_INT >= 28) QuoteSpan(color, (3 * px).toInt(), (8 * px).toInt()) else QuoteSpan(color)

    fun blocks(src: String, textColor: Int, night: Boolean, density: Float, onLink: (String) -> Unit): List<Block> {
        val cut = src.length > MAX_CHARS
        val lines = (if (cut) src.substring(0, MAX_CHARS) else src).replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val refs = HashMap<String, String>()
        for (ln in lines) {
            val m = REFDEF.matchEntire(ln) ?: continue
            refs[m.groupValues[1].trim().lowercase()] = m.groupValues[2].trim('<', '>')
        }
        val c = Ctx(ColorUtils.setAlphaComponent(textColor, 150), if (night) 0xFFFFB77C.toInt() else 0xFFC25100.toInt(),
            ColorUtils.setAlphaComponent(textColor, 32), density, refs, onLink)
        val out = ArrayList<Block>()
        var sb = SpannableStringBuilder()
        val heads = ArrayList<Head>()
        val slugs = HashMap<String, Int>()
        val ind = ArrayList<Int>()          // indentation widths of the open list levels
        var plainEnd = -1                   // sb.length right after a plain text line: the next plain line continues that paragraph
        var contEnd = -1                    // sb.length right after a list item: a following plain line continues the item
        var prevHard = false                // the previous plain line ended in a hard break
        var itemSpan: LeadingMarginSpan.Standard? = null
        var itemSt = 0
        fun flush() {
            while (sb.isNotEmpty() && sb[sb.length - 1] == '\n') sb.delete(sb.length - 1, sb.length)
            var lead = 0
            while (lead < sb.length && sb[lead] == '\n') lead++
            if (lead > 0) sb.delete(0, lead)
            if (sb.isNotEmpty()) {
                val n = sb.length
                out.add(Block.Text(sb, heads.map { Head(it.level, it.title, it.slug, (it.offset - lead).coerceIn(0, n)) }))
            }
            heads.clear()
            sb = SpannableStringBuilder()
            plainEnd = -1; contEnd = -1
        }
        fun heading(lvl: Int, raw: String) {
            gap(sb)
            val st = sb.length
            inline(sb, raw, c)
            sb.setSpan(StyleSpan(Typeface.BOLD), st, sb.length, FL)
            sb.setSpan(RelativeSizeSpan(SIZES[lvl - 1]), st, sb.length, FL)
            if (lvl <= 2) sb.setSpan(ForegroundColorSpan(c.accent), st, sb.length, FL)
            val title = sb.substring(st, sb.length).trim()
            val base = slug(title)
            val n = slugs[base] ?: 0
            slugs[base] = n + 1
            heads.add(Head(lvl, title, if (n == 0) base else "$base-$n", st))
            sb.append('\n')
            plainEnd = -1; contEnd = -1; ind.clear()
        }
        var inCode = false
        var fenceCh = '`'
        var lang = ""
        val code = StringBuilder()
        var inCom = false
        var i = 0
        // YAML front matter
        if (lines.size > 2 && lines[0].trim() == "---") {
            var j = 1
            while (j < lines.size && j < 80 && lines[j].trim() != "---" && lines[j].trim() != "...") j++
            if (j < lines.size && j < 80 && j > 1 && FM_KEY.containsMatchIn(lines[1])) {
                out.add(Block.Code("yaml", lines.subList(1, j).joinToString("\n")))
                i = j + 1
            }
        }
        while (i < lines.size) {
            val orig = lines[i]
            if (inCode) {
                val tt = orig.trim()
                if (tt.length >= 3 && tt.all { it == fenceCh }) {
                    inCode = false
                    out.add(Block.Code(lang, code.toString().trimEnd('\n')))
                    code.setLength(0)
                } else code.append(orig).append('\n')
                i++; continue
            }
            // HTML comments are hidden (they may span several lines)
            var line = orig
            var hadCom = false
            if (inCom) {
                hadCom = true
                val e = line.indexOf("-->")
                if (e < 0) { i++; continue }
                line = line.substring(e + 3); inCom = false
            }
            while (true) {
                val s = line.indexOf("<!--")
                if (s < 0) break
                hadCom = true
                val e = line.indexOf("-->", s + 4)
                if (e < 0) { line = line.substring(0, s); inCom = true; break }
                line = line.substring(0, s) + line.substring(e + 3)
            }
            val t = line.trim()
            if (hadCom && t.isEmpty()) { i++; continue }
            if (t.startsWith("```") || t.startsWith("~~~")) {
                flush(); ind.clear(); inCode = true; code.setLength(0)
                fenceCh = t[0]
                lang = t.trimStart('`', '~').trim().substringBefore(' ').lowercase()
                i++; continue
            }
            if (t.isEmpty()) { gap(sb); i++; continue }
            if (REFDEF.matches(line)) { i++; continue }
            // table: header row + separator row
            if (t.contains('|') && i + 1 < lines.size && lines[i + 1].contains('|') && lines[i + 1].contains('-') && SEP.matches(lines[i + 1])) {
                flush(); ind.clear()
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
                heading(hm.groupValues[1].length, HEAD_TAIL.replace(hm.groupValues[2], ""))
                i++; continue
            }
            if (HR.matches(line)) {
                val st = sb.length
                sb.append("\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n")
                sb.setSpan(ForegroundColorSpan(c.muted), st, sb.length, FL)
                plainEnd = -1; contEnd = -1; ind.clear()
                i++; continue
            }
            if (t.startsWith(">")) {
                var rest = t
                var depth = 0
                while (rest.startsWith(">")) { depth++; rest = rest.substring(1).trimStart() }
                val st = sb.length
                val qh = HEAD.matchEntire(rest)
                val ql = LIST.matchEntire(rest)
                if (qh != null) {
                    val s2 = sb.length
                    inline(sb, HEAD_TAIL.replace(qh.groupValues[2], ""), c)
                    sb.setSpan(StyleSpan(Typeface.BOLD), s2, sb.length, FL)
                } else if (ql != null) {
                    val (bullet, body) = listParts(ql, 0)
                    sb.append(bullet).append(' ')
                    inline(sb, body, c)
                } else inline(sb, rest, c)
                sb.append('\n')
                repeat(depth.coerceAtMost(4)) { sb.setSpan(quoteSpan(c.accent, c.px), st, sb.length, FL) }
                sb.setSpan(ForegroundColorSpan(c.muted), st, sb.length, FL)
                plainEnd = -1; contEnd = -1; ind.clear()
                i++; continue
            }
            val l = LIST.matchEntire(line)
            if (l != null) {
                val w = l.groupValues[1].replace("\t", "    ").length
                while (ind.isNotEmpty() && w < ind.last()) ind.removeAt(ind.size - 1)
                if (ind.isEmpty() || w > ind.last()) ind.add(w)
                val lvl = (ind.size - 1).coerceAtMost(6)
                val (bullet, body) = listParts(l, lvl)
                val st = sb.length
                sb.append(bullet).append(' ')
                inline(sb, body, c)
                sb.append('\n')
                val span = LeadingMarginSpan.Standard(((6 + lvl * 16) * c.px).toInt(), ((6 + lvl * 16 + 18) * c.px).toInt())
                sb.setSpan(span, st, sb.length, FL)
                itemSpan = span; itemSt = st
                contEnd = sb.length; plainEnd = -1; prevHard = false
                i++; continue
            }
            // plain text. A line followed by ==== / ---- is a setext heading.
            val joined = plainEnd > 0 && plainEnd == sb.length
            val lazy = contEnd > 0 && contEnd == sb.length
            val nxt = lines.getOrNull(i + 1)
            if (!joined && !lazy && nxt != null) {
                if (SET1.matches(nxt)) { heading(1, t); i += 2; continue }
                if (SET2.matches(nxt)) { heading(2, t); i += 2; continue }
            }
            val hard = line.endsWith("  ") || line.endsWith("\\")
            var body = line.trimEnd()
            if (line.endsWith("\\")) body = body.dropLast(1)
            if (lazy && !prevHard) {
                // lazy continuation of the list item above
                sb.replace(sb.length - 1, sb.length, " ")
                inline(sb, body.trim(), c)
                sb.append('\n')
                itemSpan?.let { sb.setSpan(it, itemSt, sb.length, FL) }
                contEnd = sb.length; prevHard = hard
            } else if (joined && !prevHard) {
                sb.replace(sb.length - 1, sb.length, " ")
                inline(sb, body.trim(), c)
                sb.append('\n')
                plainEnd = sb.length; prevHard = hard
            } else {
                ind.clear()
                inline(sb, if (joined || lazy) body.trim() else body, c)
                sb.append('\n')
                plainEnd = sb.length; contEnd = -1; prevHard = hard
            }
            i++
        }
        if (inCode) out.add(Block.Code(lang, code.toString().trimEnd('\n')))   // unclosed fence: still shown as code
        if (cut) sb.append("\n\u2026 preview shows the first ${MAX_CHARS / 1000} thousand characters")
        flush()
        return out
    }

    private fun listParts(l: MatchResult, lvl: Int): Pair<String, String> {
        val mark = l.groupValues[2]
        var body = l.groupValues[3]
        var bullet = if (mark[0].isDigit()) mark else when (lvl % 3) { 0 -> "\u2022"; 1 -> "\u25E6"; else -> "\u25AA" }
        if (body.startsWith("[ ] ")) { bullet = "\u2610"; body = body.substring(4) }
        else if (body.startsWith("[x] ") || body.startsWith("[X] ")) { bullet = "\u2611"; body = body.substring(4) }
        return bullet to body
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

    // `\*` style escapes travel through the inline parser as one private-use char (U+E000 + code) and are turned back when text is appended
    private fun enc(s: String): String =
        if (s.indexOf('\\') < 0) s else ESC.replace(s) { (0xE000 + it.groupValues[1][0].code).toChar().toString() }

    private fun dec(s: String, keepSlash: Boolean = false): String {
        var has = false
        for (ch in s) if (ch.code in 0xE000..0xE0FF) { has = true; break }
        if (!has) return s
        val b = StringBuilder(s.length)
        for (ch in s) {
            if (ch.code in 0xE000..0xE0FF) { if (keepSlash) b.append('\\'); b.append((ch.code - 0xE000).toChar()) } else b.append(ch)
        }
        return b.toString()
    }

    private fun inline(sb: SpannableStringBuilder, s: String, c: Ctx) = inl(sb, enc(s), c)

    private fun inl(sb: SpannableStringBuilder, s: String, c: Ctx) {
        var pos = 0
        for (m in INLINE.findAll(s)) {
            if (m.range.first > pos) sb.append(dec(s.substring(pos, m.range.first)))
            val g = m.groups
            val st = sb.length
            var end = m.range.last + 1
            when {
                g[1] != null -> {
                    inl(sb, g[1]!!.value, c)
                    sb.setSpan(StyleSpan(Typeface.BOLD_ITALIC), st, sb.length, FL)
                }
                g[2] != null -> {
                    sb.append(dec(g[2]!!.value, true))
                    sb.setSpan(android.text.style.TypefaceSpan("monospace"), st, sb.length, FL)
                    sb.setSpan(BackgroundColorSpan(c.codeBg), st, sb.length, FL)
                    sb.setSpan(RelativeSizeSpan(0.92f), st, sb.length, FL)
                }
                g[3] != null || g[4] != null -> {
                    inl(sb, (g[3] ?: g[4])!!.value, c)
                    sb.setSpan(StyleSpan(Typeface.BOLD), st, sb.length, FL)
                }
                g[5] != null -> {
                    inl(sb, g[5]!!.value, c)
                    sb.setSpan(StrikethroughSpan(), st, sb.length, FL)
                }
                g[7] != null -> {   // image: shown as a tappable label (no network, no decoding)
                    val alt = dec(g[6]?.value.orEmpty())
                    sb.append(IMG).append(if (alt.isEmpty()) "image" else alt)
                    sb.setSpan(Link(dec(g[7]!!.value), c.muted, c.onLink), st, sb.length, FL)
                }
                g[9] != null -> {
                    // a badge `[![alt](img)](url)` becomes one label that opens the outer link
                    val txt = IMG_IN_LINK.replace(g[8]!!.value) { IMG + it.groupValues[1].ifEmpty { "image" } }
                    inl(sb, txt, c)
                    sb.setSpan(Link(dec(g[9]!!.value), c.accent, c.onLink), st, sb.length, FL)
                }
                g[10] != null -> {   // [text][ref]
                    val key = dec(g[11]?.value.orEmpty().ifEmpty { g[10]!!.value }).trim().lowercase()
                    val url = c.refs[key]
                    if (url == null) sb.append(dec(m.value))
                    else {
                        inl(sb, g[10]!!.value, c)
                        sb.setSpan(Link(url, c.accent, c.onLink), st, sb.length, FL)
                    }
                }
                g[12] != null || g[13] != null -> {
                    inl(sb, (g[12] ?: g[13])!!.value, c)
                    sb.setSpan(StyleSpan(Typeface.ITALIC), st, sb.length, FL)
                }
                g[14] != null -> {
                    sb.append(g[14]!!.value)
                    sb.setSpan(Link(g[14]!!.value, c.accent, c.onLink), st, sb.length, FL)
                }
                g[15] != null -> {   // bare URL: trailing punctuation is not part of it
                    val raw = g[15]!!.value
                    val u = raw.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '\'', '"')
                    sb.append(dec(u))
                    sb.setSpan(Link(dec(u), c.accent, c.onLink), st, sb.length, FL)
                    end = m.range.first + u.length
                }
                g[16] != null -> {   // <a href="url">text</a>
                    inl(sb, g[17]!!.value, c)
                    if (sb.length == st) sb.append(dec(g[16]!!.value))
                    sb.setSpan(Link(dec(g[16]!!.value), c.accent, c.onLink), st, sb.length, FL)
                }
                g[18] != null -> {   // <img src alt>
                    val tag = g[18]!!.value
                    val srcUrl = SRC.find(tag)?.groupValues?.get(1)
                    val alt = ALT.find(tag)?.groupValues?.get(1).orEmpty()
                    sb.append(IMG).append(if (alt.isEmpty()) "image" else dec(alt))
                    if (srcUrl != null) sb.setSpan(Link(dec(srcUrl), c.muted, c.onLink), st, sb.length, FL)
                }
                else -> {   // other simple html tag: <br> is a line break, the rest is dropped
                    if (g[19]!!.value.startsWith("<br", ignoreCase = true)) sb.append('\n')
                }
            }
            pos = end
        }
        if (pos < s.length) sb.append(dec(s.substring(pos)))
    }
}
