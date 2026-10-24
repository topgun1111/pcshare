package com.lanshare.app.core.usearch

import java.util.IdentityHashMap
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Search-box toggles (chapter 16.5). Every field can be overridden per term by the case: ww: diac: prefixes.
 * matchCase = exact characters (diacritics included). diacritics = Turkish letters stay different from plain ones (default: forgiving).
 * matchPath = plain terms look at the whole path instead of the name. regex = every term is a regular expression.
 */
class MatchOptions(
    val matchCase: Boolean = false,
    val wholeWord: Boolean = false,
    val matchPath: Boolean = false,
    val diacritics: Boolean = false,
    val regex: Boolean = false
)

/** Per-entry scratch values, computed on first use, so a term that fails early never builds the path. One Ctx per search, one thread. */
internal class Ctx(val ix: NameIndex) {
    var id = -1
    private var nm: String? = null
    private var fo: String? = null
    private var ke: String? = null
    private var pa: String? = null
    private var pf0: String? = null
    private var pf1: String? = null
    private var dir = -1
    private var ex: String? = null

    fun reset(i: Int) { id = i; nm = null; fo = null; ke = null; pa = null; pf0 = null; pf1 = null; dir = -1; ex = null }

    fun name(): String { var v = nm; if (v == null) { v = ix.nameOf(id); nm = v }; return v }
    /** Folded name, diacritics stripped (the superset form stored in the index). */
    fun fold(): String { var v = fo; if (v == null) { v = ix.foldedOf(id); fo = v }; return v }
    /** Folded name, Turkish letters kept. */
    fun keep(): String { var v = ke; if (v == null) { v = Fold.fold(name(), true); ke = v }; return v }
    fun path(): String { var v = pa; if (v == null) { v = ix.pathOf(id); pa = v }; return v }
    fun pathFold(diac: Boolean): String {
        if (diac) { var v = pf1; if (v == null) { v = Fold.fold(path(), true); pf1 = v }; return v }
        var v = pf0; if (v == null) { v = Fold.fold(path(), false); pf0 = v }; return v
    }
    fun isDir(): Boolean { if (dir < 0) dir = if (ix.isDir(id)) 1 else 0; return dir == 1 }
    fun ext(): String { var v = ex; if (v == null) { v = Kinds.extOf(name()); ex = v }; return v }
    fun size(): Long = ix.sizeOf(id)
    fun mtime(): Long = ix.mtimeOf(id)

    /** 0 = a volume root, 1 = its children, and so on. */
    fun depth(): Int {
        var d = 0
        var x = ix.parentOf(id)
        while (x >= 0 && d < 4096) { d++; x = ix.parentOf(x) }
        return d
    }

    /** Length of the volume root's own path (so a path can be cut into a root-relative part). */
    fun rootLen(): Int {
        var x = id
        var g = 0
        while (g++ < 4096) { val p = ix.parentOf(x); if (p < 0) break; x = p }
        return ix.nameOf(x).trimEnd('/').length
    }
}

internal abstract class Pred {
    abstract val cost: Int
    abstract fun test(c: Ctx): Boolean
}

internal class TextPred(t: QText, opt: MatchOptions, notes: MutableList<String>) : Pred() {
    private val caseSens: Boolean = t.caseSens ?: opt.matchCase
    private val whole: Boolean = t.wholeWord ?: opt.wholeWord
    private val diac: Boolean = t.diacritics ?: opt.diacritics
    private val asRegex: Boolean = t.regex || opt.regex
    private val isWild: Boolean = !asRegex && t.wild
    private val onPath: Boolean = opt.matchPath || (!asRegex && (t.text.indexOf('/') >= 0 || t.text.indexOf('\\') >= 0))
    private val text: String = if (onPath && !asRegex) t.text.replace('\\', '/') else t.text
    private val needleStr: String = if (caseSens) text else Fold.fold(text, diac)
    private var kind = 0          // 0 substring, 1 wildcard, 2 regex, 3 never matches (bad regex)
    private var pat: Pattern? = null

    init {
        if (asRegex) {
            try {
                pat = Pattern.compile(text, if (caseSens) 0 else (Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE))
                kind = 2
            } catch (e: PatternSyntaxException) {
                kind = 3
                if (!t.regex) notes.add("bad regex: " + t.text)
            }
        } else if (isWild) {
            val src = needleStr
            val sb = StringBuilder()
            val lit = StringBuilder()
            for (ch in src) {
                if (ch == '*' || ch == '?') {
                    if (lit.isNotEmpty()) { sb.append(Pattern.quote(lit.toString())); lit.setLength(0) }
                    sb.append(if (ch == '*') ".*" else ".")
                } else lit.append(ch)
            }
            if (lit.isNotEmpty()) sb.append(Pattern.quote(lit.toString()))
            pat = Pattern.compile(sb.toString(), Pattern.DOTALL)
            kind = 1
        }
    }

    override val cost: Int get() = if (onPath) 4 else 1

    private fun hay(c: Ctx): String =
        if (onPath) (if (caseSens) c.path() else c.pathFold(diac))
        else if (caseSens) c.name() else if (diac) c.keep() else c.fold()

    override fun test(c: Ctx): Boolean {
        if (kind == 3) return false
        if (kind == 2) return pat!!.matcher(if (onPath) c.path() else c.name()).find()
        if (kind == 1) return pat!!.matcher(hay(c)).matches()
        val h = hay(c)
        return if (whole) containsWord(h, needleStr) else h.indexOf(needleStr) >= 0
    }

    /** Folded (diacritics stripped) text that every match must contain in its folded NAME; null when no such text exists. Feeds NameIndex.scanRange. */
    fun literal(): String? {
        if (onPath || kind == 2 || kind == 3) return null
        if (kind == 0) return Fold.fold(text, false)
        var best = ""
        for (part in text.split('*', '?')) if (part.length > best.length) best = part
        return if (best.isEmpty()) null else Fold.fold(best, false)
    }

    /** Plain term for ranking (folded like the index); null for wildcard / regex / path terms. */
    fun rankTerm(): String? = if (onPath || kind != 0) null else Fold.fold(text, false)

    private fun containsWord(h: String, nd: String): Boolean {
        if (nd.isEmpty()) return false
        var from = 0
        while (true) {
            val i = h.indexOf(nd, from)
            if (i < 0) return false
            val e = i + nd.length
            val before = i == 0 || !Character.isLetterOrDigit(h[i - 1]) || !Character.isLetterOrDigit(nd[0])
            val after = e >= h.length || !Character.isLetterOrDigit(h[e]) || !Character.isLetterOrDigit(nd[nd.length - 1])
            if (before && after) return true
            from = i + 1
        }
    }
}

internal class ExtPred(l: List<String>) : Pred() {
    private val set = HashSet<String>(l)
    override val cost: Int get() = 2
    override fun test(c: Ctx): Boolean = !c.isDir() && set.contains(c.ext())
}

internal class KindPred(private val kinds: List<Kind>) : Pred() {
    override val cost: Int get() = 2
    override fun test(c: Ctx): Boolean {
        for (k in kinds) if (Kinds.matches(k, c.isDir(), c.ext())) return true
        return false
    }
}

internal class SizePred(private val min: Long, private val max: Long) : Pred() {
    override val cost: Int get() = 2
    override fun test(c: Ctx): Boolean {
        if (c.isDir()) return false
        val s = c.size()
        return s >= min && s <= max
    }
}

internal class DatePred(private val ranges: List<LongArray>) : Pred() {
    override val cost: Int get() = 2
    override fun test(c: Ctx): Boolean {
        val m = c.mtime()
        for (r in ranges) if (m >= r[0] && m < r[1]) return true
        return false
    }
}

internal class DepthPred(private val min: Int, private val max: Int) : Pred() {
    override val cost: Int get() = 3
    override fun test(c: Ctx): Boolean { val d = c.depth(); return d >= min && d <= max }
}

internal class PathPred(text: String, private val diac: Boolean) : Pred() {
    private val needle = Fold.fold(text.replace('\\', '/'), diac)
    override val cost: Int get() = 4
    override fun test(c: Ctx): Boolean = c.pathFold(diac).indexOf(needle) >= 0
}

/** in:/DCIM - the entry is that folder or lies below it. The value is tried as an absolute path and as a path inside the volume root. */
internal class InPred(v: String) : Pred() {
    private val all: Boolean
    private val dir: String
    private val dirSlash: String
    init {
        var d = Fold.fold(v.replace('\\', '/'), false).trim().trimEnd('/')
        all = d.isEmpty()
        if (!d.startsWith("/")) d = "/" + d
        dir = d
        dirSlash = d + "/"
    }
    override val cost: Int get() = 4
    override fun test(c: Ctx): Boolean {
        if (all) return true
        val p = c.pathFold(false)
        if (p == dir || p.startsWith(dirSlash)) return true
        val rel = p.substring(minOf(p.length, c.rootLen()))
        return rel == dir || rel.startsWith(dirSlash)
    }
}

internal class NotPred(private val p: Pred) : Pred() {
    override val cost: Int get() = p.cost
    override fun test(c: Ctx): Boolean = !p.test(c)
}

internal class AndPred(private val list: List<Pred>) : Pred() {
    override val cost: Int get() = list.maxOf { it.cost }
    override fun test(c: Ctx): Boolean {
        for (p in list) if (!p.test(c)) return false
        return true
    }
}

internal class OrPred(private val list: List<Pred>) : Pred() {
    override val cost: Int get() = list.maxOf { it.cost }
    override fun test(c: Ctx): Boolean {
        for (p in list) if (p.test(c)) return true
        return false
    }
}

/**
 * A compiled name expression. Not thread-safe (it owns no state, but its Ctx must not be shared): Search.run builds one per search.
 * prefilter = UTF-8 bytes for NameIndex.scanRange (empty = scan everything); it is only set when every match must contain it.
 */
class Matcher(node: QNode?, val opt: MatchOptions) {
    val notes = ArrayList<String>()
    private val texts = IdentityHashMap<QText, TextPred>()
    private val root: Pred? = if (node == null) null else build(node)
    val prefilter: ByteArray
    private val rankTerms: List<String>

    init {
        val conj: List<QNode> = if (node == null) emptyList() else if (node is QAnd) node.list else listOf(node)
        var best = ""
        val ranks = ArrayList<String>()
        for (n in conj) {
            if (n is QText) {
                val p = texts[n] ?: continue
                val lit = p.literal()
                if (lit != null && lit.length > best.length) best = lit
                val rt = p.rankTerm()
                if (rt != null) ranks.add(rt)
            }
        }
        prefilter = best.toByteArray(Charsets.UTF_8)
        rankTerms = ranks
    }

    val hasQuery: Boolean get() = root != null

    private fun build(n: QNode): Pred = when (n) {
        is QText -> TextPred(n, opt, notes).also { texts[n] = it }
        is QFilter -> when (val f = n.f) {
            is ExtFilter -> ExtPred(f.exts)
            is KindFilter -> KindPred(f.kinds)
            is SizeFilter -> SizePred(f.min, f.max)
            is DateFilter -> DatePred(f.ranges)
            is PathFilter -> PathPred(f.text, opt.diacritics)
            is InFilter -> InPred(f.dir)
            is DepthFilter -> DepthPred(f.min, f.max)
        }
        is QNot -> NotPred(build(n.n))
        is QAnd -> AndPred(n.list.map { build(it) }.sortedBy { it.cost })
        is QOr -> OrPred(n.list.map { build(it) })
    }

    internal fun test(c: Ctx): Boolean { val r = root ?: return false; return r.test(c) }

    /** Smaller = better. Sum over the plain terms of Ranker.tier; 0 when the query has no plain term. */
    internal fun rank(c: Ctx): Int {
        if (rankTerms.isEmpty()) return 0
        val f = c.fold()
        var s = 0
        for (t in rankTerms) s += Ranker.tier(f, t)
        return s
    }
}
