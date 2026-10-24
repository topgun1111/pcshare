// JVM self-test for core/usearch (no Android needed).  Build & run:
//   kotlinc app/src/main/java/com/lanshare/app/core/usearch/*.kt tools/usearch_selftest/Main.kt -include-runtime -d /tmp/ust.jar
//   java -jar /tmp/ust.jar
// Prints "ok <name>" per passed check and "FAIL <name> ..." per failed one; "info ..." lines are measurements only.
import com.lanshare.app.core.usearch.*
import java.io.File
import java.io.RandomAccessFile

var fails = 0
var oks = 0

fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) { oks++; println("ok   $name") } else { fails++; println("FAIL $name $detail") }
}

fun eq(name: String, got: Any?, want: Any?) = check(name, got == want, "got='$got' want='$want'")

fun allPaths(ix: NameIndex): List<String> {
    val l = ArrayList<String>()
    ix.forEachLive { l.add(ix.pathOf(it)) }
    l.sort()
    return l
}

fun sameIndex(a: NameIndex, b: NameIndex): Boolean {
    if (a.slotCount() != b.slotCount() || a.count() != b.count() || a.dirCount() != b.dirCount() || a.epoch() != b.epoch()) return false
    for (i in 0 until a.slotCount()) {
        if (a.isLive(i) != b.isLive(i)) return false
        if (!a.isLive(i)) continue
        if (a.nameOf(i) != b.nameOf(i) || a.parentOf(i) != b.parentOf(i) || a.isDir(i) != b.isDir(i) ||
            a.sizeOf(i) != b.sizeOf(i) || a.mtimeOf(i) != b.mtimeOf(i) || a.foldedOf(i) != b.foldedOf(i)) return false
    }
    return true
}

fun hits(ix: NameIndex, needle: String): Int {
    var n = 0
    ix.scanRange(0, ix.slotCount(), Fold.fold(needle).toByteArray(Charsets.UTF_8)) { n++ }
    return n
}

fun foldTests() {
    eq("fold ASCII upper", Fold.fold("ABC.Txt"), "abc.txt")
    eq("fold dotted capital I", Fold.fold("\u0130STANBUL"), "istanbul")
    eq("fold I and dotless", Fold.fold("I\u0131I"), "iii")
    eq("fold strips Turkish letters", Fold.fold("\u00C7ALI\u015EMA \u011E\u00DC\u00D6"), "calisma guo")
    eq("fold keeps diacritics when asked", Fold.fold("\u00C7ALI\u015EMA", true), "\u00E7ali\u015Fma")
    eq("fold circumflex", Fold.fold("K\u00C2\u011EIT"), "kagit")
    eq("fold keeps length", Fold.fold("\u0130\u0131\u00C7\u015E").length, 4)
    eq("fold plain lower is identity", Fold.fold("already.lower-1"), "already.lower-1")
    eq("fold empty", Fold.fold(""), "")
}

fun memoryTests() {
    val ix = NameIndex(4)
    val r = ix.addRoot("/r")
    val a = ix.add(r, "A", true, 0, 100)
    val b = ix.add(a, "B", true, 0, 101)
    val c = ix.add(b, "c.txt", false, 42, 102)
    val tr = ix.add(r, "\u00C7ali\u015Fma", true, 0, 103)
    val ist = ix.add(tr, "\u0130stanbul.TXT", false, 7, 104)
    val ig = ix.add(r, "\u0131\u011Fd\u0131r.txt", false, 8, 105)
    eq("path of nested file", ix.pathOf(c), "/r/A/B/c.txt")
    eq("find existing path", ix.find("/r/A/B/c.txt"), c)
    eq("find missing path", ix.find("/r/A/X"), NameIndex.NONE)
    eq("find root itself", ix.find("/r"), r)
    eq("find outside any root", ix.find("/other/x"), NameIndex.NONE)
    eq("counts", listOf(ix.count(), ix.dirCount(), ix.slotCount()), listOf(7, 4, 7))
    eq("name keeps original text", ix.nameOf(ist), "\u0130stanbul.TXT")
    eq("folded name", ix.foldedOf(ist), "istanbul.txt")
    eq("folded Turkish folder", ix.foldedOf(tr), "calisma")
    eq("scan finds ASCII query in Turkish name", hits(ix, "cal"), 1)
    eq("scan finds typed dotted capital", hits(ix, "\u0130STAN"), 1)
    eq("scan finds igdir via dotless i", hits(ix, "igdir"), 1)
    eq("scan empty needle matches all", hits(ix, ""), 7)
    eq("scan no match", hits(ix, "zzz"), 0)
    eq("scan chunk range", run { var n = 0; ix.scanRange(0, 3, ByteArray(0)) { n++ }; n }, 3)

    // rename a folder with children
    check("rename returns true", ix.rename(a, "Z"))
    eq("rename updates descendants' path", ix.pathOf(c), "/r/Z/B/c.txt")
    eq("find new path", ix.find("/r/Z/B/c.txt"), c)
    eq("old path gone", ix.find("/r/A/B/c.txt"), NameIndex.NONE)
    eq("folded name follows rename", ix.foldedOf(a), "z")

    // move
    check("move folder up", ix.move(b, r))
    eq("moved path", ix.pathOf(c), "/r/B/c.txt")
    val p = ix.add(r, "P", true, 0, 1)
    val q = ix.add(p, "Q", true, 0, 1)
    check("move into own subtree refused", !ix.move(p, q))
    check("move onto a file refused", !ix.move(p, c))
    check("move root refused", !ix.move(r, p))
    check("move with new name", ix.move(q, r, "Q2"))
    eq("moved and renamed path", ix.pathOf(q), "/r/Q2")

    // remove
    val slotsBefore = ix.slotCount()
    val liveBefore = ix.count()
    eq("remove subtree count", ix.remove(tr), 2)
    eq("live count after remove", ix.count(), liveBefore - 2)
    eq("removed path not found", ix.find("/r/\u00C7ali\u015Fma/\u0130stanbul.TXT"), NameIndex.NONE)
    check("removed id not live", !ix.isLive(ist))
    eq("remove twice is harmless", ix.remove(tr), 0)
    val nid = ix.add(r, "new.txt", false, 1, 1)
    eq("ids are not reused", nid, slotsBefore)
    eq("dead scan skipped", hits(ix, "stanbul"), 0)

    // save / load with dead slots
    val f = File.createTempFile("usnames", ".bin")
    try {
        val bytes = IndexStore.save(ix, f)
        check("save wrote bytes", bytes > 40, "bytes=$bytes")
        check("no tmp file left", !File(f.path + ".tmp").exists())
        val back = IndexStore.load(f)
        check("load returns an index", back != null)
        if (back != null) {
            check("loaded index equals saved (ids, dead slots, fields)", sameIndex(ix, back))
            eq("loaded paths equal", allPaths(back), allPaths(ix))
            eq("loaded find works", back.find("/r/B/c.txt"), c)
            eq("loaded scan works", hits(back, "new.t"), 1)
        }
        // corruption
        val full = f.readBytes()
        val t1 = File.createTempFile("ustrunc", ".bin"); t1.writeBytes(full.copyOf(full.size / 2))
        check("truncated file -> null", IndexStore.load(t1) == null)
        val t2 = File.createTempFile("ustrail", ".bin"); t2.writeBytes(full.copyOf(full.size - 8))
        check("missing trailer -> null", IndexStore.load(t2) == null)
        val flipped = full.copyOf(); flipped[flipped.size / 2] = (flipped[flipped.size / 2].toInt() xor 0x55).toByte()
        val t3 = File.createTempFile("usflip", ".bin"); t3.writeBytes(flipped)
        check("flipped byte -> null", IndexStore.load(t3) == null)
        val t4 = File.createTempFile("usempty", ".bin")
        check("empty file -> null", IndexStore.load(t4) == null)
        check("missing file -> null", IndexStore.load(File(f.path + ".nope")) == null)
        val wrongVer = full.copyOf(); wrongVer[7] = 9
        val t5 = File.createTempFile("usver", ".bin"); t5.writeBytes(wrongVer)
        check("other version -> null", IndexStore.load(t5) == null)
        t1.delete(); t2.delete(); t3.delete(); t4.delete(); t5.delete()
    } finally { f.delete() }

    // compact
    val before = allPaths(ix)
    val ep = ix.epoch()
    val map = ix.compact()
    eq("compact keeps all live paths", allPaths(ix), before)
    eq("compact drops dead slots", ix.slotCount(), ix.count())
    eq("compact bumps epoch", ix.epoch(), ep + 1)
    eq("compact map marks dead as -1", map[ist], NameIndex.NONE)
    check("compact map maps live ids", map[c] >= 0 && ix.pathOf(map[c]) == "/r/B/c.txt")
    check("find works after compact", ix.find("/r/Q2") != NameIndex.NONE)

    // removeChildren / rescan primitive
    val r2 = ix.find("/r")
    val kept = ix.removeChildren(r2)
    check("removeChildren removes", kept > 0 && ix.count() == 1)
    check("removeChildren keeps the folder", ix.isLive(r2))
}

fun mk(dir: File, name: String): File = File(dir, name).also { it.mkdirs() }

fun walkerTests() {
    val tmp = java.nio.file.Files.createTempDirectory("ust").toFile()
    try {
        val root = File(tmp, "vol"); root.mkdirs()
        // generated tree: 100 x 20 folders x 50 files = 100000 files, 2100 folders
        val kinds = arrayOf("rapor", "foto", "ders", "not", "film")
        val t0 = System.nanoTime()
        for (i in 0 until 100) {
            for (j in 0 until 20) {
                val d = File(root, "d%03d/s%02d".format(i, j)); d.mkdirs()
                for (k in 0 until 50) File(d, "f${k}_${kinds[k % 5]}.txt").createNewFile()
            }
        }
        println("info tree built in ${(System.nanoTime() - t0) / 1_000_000} ms")
        File(mk(root, "DCIM/Camera"), "IMG_0001.jpg").writeBytes(ByteArray(1234))
        File(mk(root, "Download"), "rapor.pdf").writeBytes(ByteArray(10))
        File(mk(root, "Android/data/com.x"), "secret.txt").createNewFile()
        File(mk(root, "Android/obb/com.x"), "big.obb").createNewFile()
        File(mk(root, ".thumbnails"), "t1.jpg").createNewFile()
        File(mk(root, "Excluded"), "skipme.txt").createNewFile()
        var extraFiles = 2L   // IMG_0001.jpg, rapor.pdf
        var extraDirs = 6L    // DCIM, Camera, Download, Android, data, obb
        var loop = false
        try { java.nio.file.Files.createSymbolicLink(File(root, "loop").toPath(), root.toPath()); loop = true; extraDirs++ } catch (_: Throwable) { }
        var turk = false
        try {
            val td = File(root, "\u00C7al\u0131\u015Fma_\u0130\u015F")
            val madeDir = td.mkdirs()   // the name may be mangled on a JVM without UTF-8 file names; whatever got created is still walked and counted
            if (madeDir) extraDirs++
            if (madeDir && File(td, "\u0131\u011Fd\u0131r.txt").createNewFile()) extraFiles++
            val names = td.list()
            turk = names != null && names.contains("\u0131\u011Fd\u0131r.txt")
        } catch (_: Throwable) { }
        println("info symlink created=$loop turkish names on disk=$turk (sun.jnu.encoding=${System.getProperty("sun.jnu.encoding")})")

        val ix = NameIndex(1024)
        val w = Walker(ix, DefaultRules(listOf(File(root, "Excluded").path)))
        val t1 = System.nanoTime()
        val rid = w.scanVolume(root)
        val ms = maxOf(1L, (System.nanoTime() - t1) / 1_000_000)
        val total = ix.count()
        println("info walk: $total entries in $ms ms = ${total * 1000L / ms} entries/s; heap arrays ${ix.approxBytes() / 1024} KB = ${ix.approxBytes() / total} bytes/entry")
        eq("walker file count", w.state.files, 100000L + extraFiles)
        eq("walker folder count", w.state.dirs, 2100L + extraDirs)
        eq("index count = root + folders + files", total.toLong(), 1L + w.state.dirs + w.state.files)
        eq("root entry path", ix.pathOf(rid), root.path)
        check("find deep file", ix.find(root.path + "/d042/s07/f13_not.txt") != NameIndex.NONE)
        check("file size recorded", ix.sizeOf(ix.find(root.path + "/DCIM/Camera/IMG_0001.jpg")) == 1234L)
        check("file mtime recorded", ix.mtimeOf(ix.find(root.path + "/Download/rapor.pdf")) > 1_000_000_000L)
        check("folder flag", ix.isDir(ix.find(root.path + "/DCIM/Camera")) && !ix.isDir(ix.find(root.path + "/Download/rapor.pdf")))
        check("Android/data listed", ix.find(root.path + "/Android/data") != NameIndex.NONE)
        eq("Android/data not entered", ix.find(root.path + "/Android/data/com.x"), NameIndex.NONE)
        eq("Android/obb not entered", ix.find(root.path + "/Android/obb/com.x"), NameIndex.NONE)
        eq(".thumbnails omitted", ix.find(root.path + "/.thumbnails"), NameIndex.NONE)
        eq("user-excluded folder omitted", ix.find(root.path + "/Excluded"), NameIndex.NONE)
        if (loop) {
            check("symlinked folder listed", ix.find(root.path + "/loop") != NameIndex.NONE)
            eq("symlinked folder not followed", ix.find(root.path + "/loop/DCIM"), NameIndex.NONE)
        } else println("info symlink checks skipped")
        if (turk) check("Turkish file name on disk indexed", ix.find(root.path + "/\u00C7al\u0131\u015Fma_\u0130\u015F/\u0131\u011Fd\u0131r.txt") != NameIndex.NONE)
        else println("info Turkish disk check skipped")
        eq("scan: _rapor files", hits(ix, "_rapor"), 20000)
        eq("scan: rapor.pdf also found by 'rapor'", hits(ix, "rapor"), 20001)

        var best = Long.MAX_VALUE
        for (rep in 0 until 5) { val s = System.nanoTime(); hits(ix, "f7_film"); best = minOf(best, System.nanoTime() - s) }
        println("info scan of ${ix.slotCount()} names: best of 5 = ${best / 1000} us")

        // save / load of the big index
        val f = File(tmp, "names.bin")
        val s0 = System.nanoTime()
        val bytes = IndexStore.save(ix, f)
        val s1 = System.nanoTime()
        val back = IndexStore.load(f)
        val s2 = System.nanoTime()
        println("info names.bin: $bytes bytes = ${bytes / total} bytes/entry, save ${(s1 - s0) / 1_000_000} ms, load ${(s2 - s1) / 1_000_000} ms")
        check("big index loads", back != null)
        if (back != null) check("big index round trip identical", sameIndex(ix, back))
        RandomAccessFile(f, "rw").use { it.setLength(it.length() - 1) }
        check("big index cut by one byte -> null", IndexStore.load(f) == null)

        // rescan one folder
        val camera = ix.find(root.path + "/DCIM/Camera")
        File(root, "DCIM/Camera/IMG_0002.jpg").writeBytes(ByteArray(5))
        File(root, "DCIM/Camera/IMG_0001.jpg").delete()
        w.rescanDir(camera, File(root, "DCIM/Camera"), "DCIM/Camera")
        check("rescanDir sees the new file", ix.find(root.path + "/DCIM/Camera/IMG_0002.jpg") != NameIndex.NONE)
        eq("rescanDir drops the deleted file", ix.find(root.path + "/DCIM/Camera/IMG_0001.jpg"), NameIndex.NONE)

        // rescan the volume: same result, no duplicates
        val before = ix.count()
        w.scanVolume(root)
        eq("second volume scan keeps the count", ix.count(), before)

        // cancel
        val ix2 = NameIndex(1024)
        val w2 = Walker(ix2)
        w2.state.cancel()
        w2.scanVolume(root)
        check("cancelled walk stops early", ix2.count() < 10)

        // unreadable folder (a non-root user is expected; as root this only prints 0)
        val locked = mk(root, "locked"); locked.setReadable(false)
        val w3 = Walker(NameIndex(16))
        w3.scanVolume(locked)
        println("info unreadable folders counted: ${w3.state.unreadable}")
        locked.setReadable(true)
    } finally {
        tmp.deleteRecursively()
    }
}

// ------------------------------------------------------------------ Part 2: query engine
val UTC: java.util.TimeZone = java.util.TimeZone.getTimeZone("UTC")
const val NOW = 1792929600L                  // 2026-10-25 12:00:00 UTC, a Sunday
val pctx = ParseContext(NOW, UTC)

fun fixtureTests(args: Array<String>) {
    val cands = listOfNotNull(args.firstOrNull(), "tools/usearch_selftest/query_fixture.txt", "../tools/usearch_selftest/query_fixture.txt")
    val f = cands.map { File(it) }.firstOrNull { it.isFile }
    if (f == null) { check("fixture file found", false, cands.toString()); return }
    var n = 0
    for ((ln, raw) in f.readLines(Charsets.UTF_8).withIndex()) {
        if (raw.isBlank() || raw.startsWith("#")) continue
        val tab = raw.indexOf('\t')
        if (tab < 0) { check("fixture line ${ln + 1} has a tab", false, raw); continue }
        val qs = raw.substring(0, tab)
        val want = raw.substring(tab + 1)
        val got = try { QueryParser.parse(qs, pctx).dump() } catch (e: Exception) { "EXC $e" }
        n++
        eq("parse [$qs]", got, want)
    }
    check("fixture has 60+ lines", n >= 60, "n=$n")
}

fun relNames(ix: NameIndex, r: SearchResult): List<String> = r.ids.map { ix.pathOf(it).removePrefix("/r").removePrefix("/") }

fun srch(ix: NameIndex, q: String, opt: MatchOptions = MatchOptions(), sort: SortSpec = SortSpec(), off: Int = 0, lim: Int = 1000): SearchResult =
    Search.run(ix, QueryParser.parse(q, pctx), opt, sort, off, lim)

fun setOf2(ix: NameIndex, q: String, opt: MatchOptions = MatchOptions()): Set<String> = relNames(ix, srch(ix, q, opt)).toSet()

fun matcherTests() {
    val ix = NameIndex(8)
    val old = 1735689600L
    val r = ix.addRoot("/r")
    val docs = ix.add(r, "Docs", true, 0, old)
    val pics = ix.add(r, "Pictures", true, 0, old)
    val hol = ix.add(pics, "Holiday", true, 0, old)
    val cal = ix.add(r, "\u00C7ali\u015Fma", true, 0, old)
    val dl = ix.add(r, "Downloads", true, 0, old)
    ix.add(docs, "Report.docx", false, 3L * 1048576, 1792497600L)
    ix.add(docs, "report-final.TXT", false, 1000, old)
    val notes = ix.add(docs, "notes.txt", false, 1000, old)
    ix.add(docs, "old report.txt", false, 1000, old)
    ix.add(docs, "xreport.txt", false, 1000, old)
    ix.add(pics, "IMG_001.jpg", false, 2L * 1048576, 1792908000L)
    ix.add(pics, "IMG_002.PNG", false, 1000, old)
    ix.add(hol, "beach.jpg", false, 900, old)
    ix.add(hol, "beach (copy).jpg", false, 800, old)
    ix.add(cal, "\u0130stanbul Notlar\u0131.txt", false, 1000, old)
    ix.add(cal, "\u0131\u011Fd\u0131r.txt", false, 1000, old)
    ix.add(dl, "setup.exe", false, 500L * 1048576, old)
    ix.add(dl, "archive.zip", false, 1000, old)
    ix.add(r, "README.md", false, 1000, old)

    eq("report finds 4, case-insensitive", relNames(ix, srch(ix, "report")).size, 4)
    eq("report best order: exact, prefix, word start, substring",
        relNames(ix, srch(ix, "report")), listOf("Docs/Report.docx", "Docs/report-final.TXT", "Docs/old report.txt", "Docs/xreport.txt"))
    eq("case:report", setOf2(ix, "case:report"), setOf("Docs/report-final.TXT", "Docs/old report.txt", "Docs/xreport.txt"))
    eq("match case option", setOf2(ix, "Report", MatchOptions(matchCase = true)), setOf("Docs/Report.docx"))
    eq("ext:txt", srch(ix, "ext:txt").total, 6)
    eq("ext:jpg;png", srch(ix, "ext:jpg;png").total, 4)
    eq("kind:image beach", srch(ix, "kind:image beach").total, 2)
    eq("kind:folder counts the root too", srch(ix, "kind:folder").total, 6)
    eq("size:>100mb", setOf2(ix, "size:>100mb"), setOf("Downloads/setup.exe"))
    eq("size:1mb..5mb", setOf2(ix, "size:1mb..5mb"), setOf("Docs/Report.docx", "Pictures/IMG_001.jpg"))
    eq("size never matches folders", srch(ix, "size:<1gb kind:folder").total, 0)
    eq("ext:jpg !beach", setOf2(ix, "ext:jpg !beach"), setOf("Pictures/IMG_001.jpg"))
    eq("Turkish capital I name found by ASCII", srch(ix, "istanbul").total, 1)
    eq("dotless i and g-breve folded", srch(ix, "igdir").total, 1)
    eq("c-cedilla folder found by plain letters", relNames(ix, srch(ix, "calisma")), listOf("\u00C7ali\u015Fma"))
    eq("diac: typed with the letters finds it", srch(ix, "diac:\u00E7ali\u015Fma").total, 1)
    eq("diac: plain letters do not match any more", srch(ix, "diac:calisma").total, 0)
    eq("diacritics option", srch(ix, "calisma", MatchOptions(diacritics = true)).total, 0)
    eq("wildcard *.txt", srch(ix, "*.txt").total, 6)
    eq("wildcard report* anchors at the start", setOf2(ix, "report*"), setOf("Docs/Report.docx", "Docs/report-final.TXT"))
    eq("wildcard img_00?.jpg", setOf2(ix, "img_00?.jpg"), setOf("Pictures/IMG_001.jpg"))
    eq("regex term", setOf2(ix, "re:^img_\\d+\\.(jpg|png)$"), setOf("Pictures/IMG_001.jpg", "Pictures/IMG_002.PNG"))
    eq("regex option", setOf2(ix, "^readme", MatchOptions(regex = true)), setOf("README.md"))
    eq("phrase with space and brackets", srch(ix, "\"beach (copy)\"").total, 1)
    eq("whole word beach", srch(ix, "ww:beach").total, 2)
    eq("whole word each fails", srch(ix, "ww:each").total, 0)
    eq("substring each", srch(ix, "each").total, 2)
    eq("whole word option", srch(ix, "each", MatchOptions(wholeWord = true)).total, 0)
    eq("OR", setOf2(ix, "setup | readme"), setOf("Downloads/setup.exe", "README.md"))
    eq("term with slash matches the path", srch(ix, "holiday/beach").total, 2)
    eq("plain term looks at the name only", srch(ix, "pictures").total, 1)
    eq("match path option", srch(ix, "pictures", MatchOptions(matchPath = true)).total, 6)
    eq("in: relative to the volume root", srch(ix, "in:/Pictures").total, 6)
    eq("in: absolute path", srch(ix, "in:/r/Docs").total, 6)
    eq("in: with a word", setOf2(ix, "in:/Pictures beach"), setOf("Pictures/Holiday/beach.jpg", "Pictures/Holiday/beach (copy).jpg"))
    eq("depth:1 folders", srch(ix, "depth:1 kind:folder").total, 4)
    eq("depth:<2 folders", srch(ix, "depth:<2 kind:folder").total, 5)
    eq("path:holiday ext:jpg", srch(ix, "path:holiday ext:jpg").total, 2)
    eq("dm:2026-10", setOf2(ix, "dm:2026-10"), setOf("Docs/Report.docx", "Pictures/IMG_001.jpg"))
    eq("dm:today", setOf2(ix, "dm:today"), setOf("Pictures/IMG_001.jpg"))
    eq("dm:thisweek", srch(ix, "dm:thisweek").total, 2)
    eq("dm:yesterday", srch(ix, "dm:yesterday").total, 0)
    eq("dm:2025", srch(ix, "dm:2025 ext:zip").total, 1)

    eq("sort NAME", relNames(ix, srch(ix, "ext:jpg", sort = SortSpec(SortKey.NAME))),
        listOf("Pictures/Holiday/beach (copy).jpg", "Pictures/Holiday/beach.jpg", "Pictures/IMG_001.jpg"))
    eq("sort NAME reversed", relNames(ix, srch(ix, "ext:jpg", sort = SortSpec(SortKey.NAME, reverse = true))).first(), "Pictures/IMG_001.jpg")
    eq("sort SIZE big first", relNames(ix, srch(ix, "ext:jpg", sort = SortSpec(SortKey.SIZE))),
        listOf("Pictures/IMG_001.jpg", "Pictures/Holiday/beach.jpg", "Pictures/Holiday/beach (copy).jpg"))
    eq("sort SIZE reversed", relNames(ix, srch(ix, "ext:jpg", sort = SortSpec(SortKey.SIZE, reverse = true))).first(), "Pictures/Holiday/beach (copy).jpg")
    eq("sort DATE newest first", relNames(ix, srch(ix, "ext:jpg", sort = SortSpec(SortKey.DATE))).first(), "Pictures/IMG_001.jpg")
    eq("sort PATH", relNames(ix, srch(ix, "ext:jpg", sort = SortSpec(SortKey.PATH))).first(), "Pictures/Holiday/beach (copy).jpg")
    eq("beach exact name first", relNames(ix, srch(ix, "beach")).first(), "Pictures/Holiday/beach.jpg")
    eq("foldersFirst", relNames(ix, srch(ix, "holiday | beach", sort = SortSpec(SortKey.NAME, foldersFirst = true))).first(), "Pictures/Holiday")

    val pg = srch(ix, "ext:txt", off = 1, lim = 2)
    eq("paging keeps the total", pg.total, 6)
    eq("paging page size", pg.ids.size, 2)
    val first3 = srch(ix, "ext:txt", sort = SortSpec(SortKey.NAME), lim = 3).ids.toList()
    val page2 = srch(ix, "ext:txt", sort = SortSpec(SortKey.NAME), off = 1, lim = 2).ids.toList()
    eq("paging is a slice of the sorted list", page2, first3.subList(1, 3))
    val cnt = srch(ix, "ext:txt", lim = 0)
    eq("limit 0 counts only", listOf(cnt.total, cnt.ids.size), listOf(6, 0))

    eq("bad regex matches nothing and says so", srch(ix, "re:(ab").total, 0)
    check("bad regex adds a note", srch(ix, "re:(ab").notes.isNotEmpty())
    check("bad regex from the option adds a note", srch(ix, "(ab", MatchOptions(regex = true)).notes.isNotEmpty())
    eq("scope-only query has no name hits", srch(ix, "ai:dog").total, 0)
    eq("empty query has no hits", srch(ix, "").total, 0)
    eq("only NOT matches everything else", srch(ix, "!ext:txt").total, ix.count() - 6)

    val tok = CancelToken(); tok.cancel()
    val cr = Search.run(ix, QueryParser.parse("report", pctx), MatchOptions(), SortSpec(), 0, 10, tok)
    check("cancelled search reports incomplete", !cr.complete)
    check("finished search reports complete", srch(ix, "report").complete)

    eq("before remove", srch(ix, "notes.txt").total, 1)
    ix.remove(notes)
    eq("removed entry is not found", srch(ix, "notes.txt").total, 0)
    ix.rename(ix.find("/r/Docs/xreport.txt"), "yreport.txt")
    eq("renamed entry found under the new name", setOf2(ix, "yreport"), setOf("Docs/yreport.txt"))
    eq("renamed entry gone under the old name", srch(ix, "xreport").total, 0)
    val mv = ix.move(ix.find("/r/Docs/old report.txt"), dl)
    check("move ok", mv)
    eq("moved entry has the new path", setOf2(ix, "old report"), setOf("Downloads/old report.txt"))

    // plainWords and filters on the Query
    eq("plainWords", QueryParser.parse("foo bar ext:jpg !baz *.x", pctx).plainWords(), listOf("foo", "bar"))
    eq("filters of a query", QueryParser.parse("foo ext:jpg", pctx).filters.size, 1)
    check("isEmpty", QueryParser.parse("  ", pctx).isEmpty)

    // dates against the real Calendar of the JVM zone: the keyword must contain 'now'
    val real = ParseContext(NOW, java.util.TimeZone.getTimeZone("Europe/Istanbul"))
    val d = QueryParser.parseDate("today", real)
    check("today contains now in another zone", d != null && d.ranges[0][0] <= NOW && NOW < d.ranges[0][1])
    check("today is 24 hours in Istanbul", d != null && d.ranges[0][1] - d.ranges[0][0] == 86400L)
}

fun randomQueryTests() {
    val ix = NameIndex(8)
    val r = ix.addRoot("/r")
    val a = ix.add(r, "abc", true, 0, 5)
    ix.add(a, "a.txt", false, 7, 5)
    ix.add(a, "B c.jpg", false, 9, 6)
    val alphabet = "ab \"|!:*?.<>=;-/\\()[]ext:kind:size:dm:re:case:ww:content:ai:in:depth:path:1mb09"
    var seed = 12345L
    fun rnd(n: Int): Int { seed = (seed * 6364136223846793005L + 1442695040888963407L); return ((seed ushr 33) % n).toInt() }
    var bad = 0
    var last = ""
    for (i in 0 until 3000) {
        val sb = StringBuilder()
        val len = 1 + rnd(14)
        for (k in 0 until len) sb.append(alphabet[rnd(alphabet.length)])
        last = sb.toString()
        try {
            val q = QueryParser.parse(last, pctx)
            q.dump()
            Search.run(ix, q, MatchOptions(), SortSpec(SortKey.values()[rnd(5)]), 0, 10)
        } catch (e: Exception) { bad++; if (bad == 1) println("info first failing query: [$last] $e") }
    }
    check("3000 random queries never throw", bad == 0, "bad=$bad")
}

fun perfTests() {
    val ix = NameIndex(1 shl 17)
    val r = ix.addRoot("/r")
    var seed = 99L
    fun rnd(n: Int): Int { seed = (seed * 6364136223846793005L + 1442695040888963407L); return ((seed ushr 33) % n).toInt() }
    val exts = arrayOf("jpg", "png", "txt", "mp4", "docx", "zip", "kt")
    val syl = arrayOf("ab", "ka", "to", "re", "mi", "su", "la", "ne", "\u00E7a", "\u015Fu", "\u0131r", "go")
    val names = ArrayList<String>()
    for (d in 0 until 1000) {
        val dir = ix.add(r, "d" + d + "_" + syl[rnd(syl.size)], true, 0, 1000L + d)
        for (f in 0 until 100) {
            val sb = StringBuilder()
            for (k in 0 until 2 + rnd(3)) sb.append(syl[rnd(syl.size)])
            sb.append('_').append(f).append('.').append(exts[rnd(exts.size)])
            val nm = if (rnd(7) == 0) sb.toString().uppercase() else sb.toString()
            ix.add(dir, nm, false, (rnd(5000000)).toLong(), 2000L + rnd(100000))
            names.add(nm)
        }
    }
    eq("perf tree size", ix.count(), 1 + 1000 + 100000)
    fun brute(needle: String): Int { val nd = Fold.fold(needle); var n = 0; for (x in names) if (Fold.fold(x).contains(nd)) n++; return n }
    for (needle in listOf("ab", "kato", "mi_5", "sur", "go")) {
        val t0 = System.nanoTime()
        val res = Search.run(ix, QueryParser.parse(needle, pctx), MatchOptions(), SortSpec(), 0, 50)
        val ms = (System.nanoTime() - t0) / 1000000L
        println("info query [$needle]: ${res.total} hits of ${ix.count()} entries in $ms ms (first page of 50, best match)")
        val want = brute(needle)   // file names; directory names (d<n>_<syl>) can match too: counted next
        var dirHits = 0
        ix.forEachLive { id -> if (ix.isDir(id) && id != r && ix.foldedOf(id).contains(Fold.fold(needle))) dirHits++ }
        eq("perf brute-force count [$needle]", res.total, want + dirHits)
        check("perf [$needle] stays far below a second", ms < 5000, "ms=$ms")
    }
    val t1 = System.nanoTime()
    val ex = Search.run(ix, QueryParser.parse("ext:jpg size:>1mb dm:1970", pctx), MatchOptions(), SortSpec(SortKey.SIZE), 0, 50)
    println("info query [ext:jpg size:>1mb dm:1970]: ${ex.total} hits in ${(System.nanoTime() - t1) / 1000000L} ms (no prefilter, size sort)")
    check("perf filter-only query runs", ex.total >= 0)
    val t2 = System.nanoTime()
    val rx = Search.run(ix, QueryParser.parse("re:^ab.*\\.txt$", pctx), MatchOptions(), SortSpec(SortKey.NAME), 0, 50)
    println("info query [regex]: ${rx.total} hits in ${(System.nanoTime() - t2) / 1000000L} ms")
    val t3 = System.nanoTime()
    val ps = Search.run(ix, QueryParser.parse("a", pctx), MatchOptions(matchPath = true), SortSpec(SortKey.PATH), 0, 50)
    println("info query [match path, path sort]: ${ps.total} hits in ${(System.nanoTime() - t3) / 1000000L} ms")
}

// ---------- Part 3: freshness (Delta, UserRules, HiddenFilter) ----------
fun touchOld(f: File, secsAgo: Long = 100) { f.setLastModified(System.currentTimeMillis() - secsAgo * 1000) }

fun deltaTests() {
    val tmp = java.nio.file.Files.createTempDirectory("ustd").toFile()
    try {
        val root = File(tmp, "vol"); root.mkdirs()
        File(mk(root, "A/sub"), "one.txt").writeText("1")
        File(mk(root, "A/sub"), "two.txt").writeText("22")
        File(mk(root, "B"), "keep.txt").writeText("k")
        File(mk(root, "Docs"), "n.txt").writeText("n")
        File(mk(root, ".LANShare Bin/x1"), "gone.txt").writeText("g")
        File(mk(root, "Excl/deep"), "e.txt").writeText("e")
        // make every folder old, so the racy-timestamp rule does not hide the effect of the changes below
        root.walkTopDown().filter { it.isDirectory }.forEach { touchOld(it) }
        val rules = UserRules(exclude = listOf(File(root, "Excl").path), omitNames = setOf(".thumbnails", ".cache", ".LANShare Bin"))
        val ix = NameIndex(64)
        Walker(ix, rules).scanVolume(root)
        val p = { s: String -> root.path + "/" + s }
        check("delta: bin folder and excluded folder are left out", ix.find(p(".LANShare Bin")) == NameIndex.NONE && ix.find(p("Excl")) == NameIndex.NONE)
        check("delta: normal files are in", ix.find(p("A/sub/one.txt")) != NameIndex.NONE && ix.find(p("B/keep.txt")) != NameIndex.NONE)

        // nothing changed: the delta walk touches no folder
        var st = Delta.syncVolume(ix, root, rules)
        eq("delta: unchanged tree -> 0 changed folders", st.dirsChanged, 0)
        check("delta: unchanged tree -> folders were checked", st.dirsChecked >= 4)

        // 1) new file in A/sub, deleted file, changed size: syncDir of the parent
        File(File(root, "A/sub"), "three.txt").writeText("333")
        File(File(root, "A/sub"), "one.txt").delete()
        File(File(root, "A/sub"), "two.txt").writeText("22222222")
        val before = ix.count()
        check("syncDir reports a change", Delta.syncDir(ix, File(root, "A/sub"), rules))
        check("syncDir: new file found", ix.find(p("A/sub/three.txt")) != NameIndex.NONE)
        check("syncDir: deleted file gone", ix.find(p("A/sub/one.txt")) == NameIndex.NONE)
        eq("syncDir: size updated", ix.sizeOf(ix.find(p("A/sub/two.txt"))), 8L)
        eq("syncDir: count after +1 -1", ix.count(), before)
        check("syncDir on an unchanged folder reports no change", !Delta.syncDir(ix, File(root, "A/sub"), rules))

        // 2) rename a file and a folder, new nested folder with files
        File(root, "B/keep.txt").renameTo(File(root, "B/kept.txt"))
        File(root, "Docs").renameTo(File(root, "Papers"))
        File(mk(root, "New/x/y"), "deep.txt").writeText("d")
        File(mk(root, "New/x/y"), "deep2.txt").writeText("d")
        for (d in listOf("B", "", "New")) touchOld(File(root, d), 50)   // folder times move on a real phone; make it explicit
        touchOld(File(root, "Papers"), 50)
        st = Delta.syncVolume(ix, root, rules)
        check("delta walk: renamed file", ix.find(p("B/kept.txt")) != NameIndex.NONE && ix.find(p("B/keep.txt")) == NameIndex.NONE)
        check("delta walk: renamed folder keeps its child", ix.find(p("Papers/n.txt")) != NameIndex.NONE && ix.find(p("Docs")) == NameIndex.NONE)
        check("delta walk: new nested folders walked completely", ix.find(p("New/x/y/deep2.txt")) != NameIndex.NONE)
        // B (rename) and the volume root (Docs->Papers, New added) are re-listed; New and Papers are walked completely inside the root's syncDir, so they are not counted again
        check("delta walk: some folders changed", st.dirsChanged >= 2)

        // 3) delete a whole folder tree
        File(root, "New").deleteRecursively()
        touchOld(root, 20)
        Delta.syncVolume(ix, root, rules)
        check("delta walk: deleted tree removed", ix.find(p("New")) == NameIndex.NONE && ix.find(p("New/x/y/deep.txt")) == NameIndex.NONE)

        // 4) file becomes a folder
        File(root, "B/kept.txt").delete(); File(root, "B/kept.txt").mkdirs(); File(File(root, "B/kept.txt"), "in.txt").writeText("i")
        Delta.syncDir(ix, File(root, "B"), rules)
        check("syncDir: file turned into folder", ix.isDir(ix.find(p("B/kept.txt"))))
        Delta.syncDir(ix, File(root, "B/kept.txt"), rules)
        check("syncDir: folder content found", ix.find(p("B/kept.txt/in.txt")) != NameIndex.NONE)

        // 5) syncDir on a path that is not indexed yet climbs to the nearest indexed folder
        File(mk(root, "Late/a/b"), "z.txt").writeText("z")
        Delta.syncDir(ix, File(root, "Late/a/b"), rules)
        check("syncDir: unindexed folder found through its ancestor", ix.find(p("Late/a/b/z.txt")) != NameIndex.NONE)

        // 6) excluded / bin content created later never enters
        File(File(root, "Excl/deep"), "more.txt").writeText("m")
        File(File(root, ".LANShare Bin/x1"), "more.txt").writeText("m")
        Delta.syncDir(ix, root, rules)
        check("syncDir: excluded and bin stay out", ix.find(p("Excl")) == NameIndex.NONE && ix.find(p(".LANShare Bin")) == NameIndex.NONE)

        // 7) volume root survives when its folder is missing; a missing sub-folder is removed
        File(root, "Late").deleteRecursively()
        Delta.syncDir(ix, File(root, "Late/a"), rules)
        check("syncDir: vanished folder removed (found via its parent)", ix.find(p("Late/a")) == NameIndex.NONE)
        check("syncDir: unknown path outside every volume is ignored", !Delta.syncDir(ix, File(tmp, "elsewhere/x"), rules))

        Delta.syncDir(ix, root, rules)   // the now-empty "Late" folder itself goes with its parent's listing
        // 8) consistency: the delta-maintained index equals a fresh full scan
        val fresh = NameIndex(64); Walker(fresh, rules).scanVolume(root)
        eq("delta index == fresh scan (paths)", allPaths(ix), allPaths(fresh))

        // 9) racy timestamp: a folder just changed is stored one second early
        val hot = mk(root, "Hot"); File(hot, "h.txt").writeText("h")
        val ix2 = NameIndex(16); Walker(ix2, rules).scanVolume(root)
        check("racy folder stamp is older than its real time", ix2.mtimeOf(ix2.find(p("Hot"))) < hot.lastModified() / 1000)
    } finally { tmp.deleteRecursively() }
}

fun rulesTests() {
    val inc = UserRules(include = listOf("/s/DCIM", "/s/Docs/work"), exclude = listOf("/s/DCIM/private"))
    eq("include: inside folder kept", inc.skip("/s/DCIM/Camera", "DCIM/Camera", "Camera", true), Skip.NONE)
    eq("include: file inside kept", inc.skip("/s/DCIM/a.jpg", "DCIM/a.jpg", "a.jpg", false), Skip.NONE)
    eq("include: ancestor folder is listed", inc.skip("/s/Docs", "Docs", "Docs", true), Skip.NONE)
    eq("include: file in ancestor folder left out", inc.skip("/s/Docs/x.txt", "Docs/x.txt", "x.txt", false), Skip.OMIT)
    eq("include: unrelated folder left out", inc.skip("/s/Music", "Music", "Music", true), Skip.OMIT)
    eq("include: unrelated file at the root left out", inc.skip("/s/readme.txt", "readme.txt", "readme.txt", false), Skip.OMIT)
    eq("exclude wins over include", inc.skip("/s/DCIM/private", "DCIM/private", "private", true), Skip.OMIT)
    eq("exclude covers children", inc.skip("/s/DCIM/private/x", "DCIM/private/x", "x", true), Skip.OMIT)
    eq("include: sibling with the same prefix is not inside", inc.skip("/s/DCIM2", "DCIM2", "DCIM2", true), Skip.OMIT)
    val none = UserRules()
    eq("no include: everything", none.skip("/s/Music", "Music", "Music", true), Skip.NONE)
    eq("default omit name", none.skip("/s/a/.thumbnails", "a/.thumbnails", ".thumbnails", true), Skip.OMIT)
    eq("android/data listed, not entered", none.skip("/s/Android/data", "Android/data", "data", true), Skip.NO_DESCEND)
    val h = HiddenFilter("/storage/emulated/0", setOf("/Secret", "/DCIM/private/a.jpg"))
    check("hidden: folder itself", h.isHidden("/storage/emulated/0/Secret"))
    check("hidden: below a hidden folder", h.isHidden("/storage/emulated/0/Secret/x/y.txt"))
    check("hidden: single file", h.isHidden("/storage/emulated/0/DCIM/private/a.jpg"))
    check("hidden: sibling file is visible", !h.isHidden("/storage/emulated/0/DCIM/private/b.jpg"))
    check("hidden: similar name is visible", !h.isHidden("/storage/emulated/0/Secret2/x"))
    check("hidden: other volume is never hidden", !h.isHidden("/storage/ABCD-1234/Secret/x"))
    check("hidden: empty list", !HiddenFilter("/r", emptySet()).isHidden("/r/a"))
}

// ---------- Part 5: text-file content (TextExtractor, ContentMatch) ----------
fun contentTests() {
    val tmp = java.nio.file.Files.createTempDirectory("usct").toFile()
    try {
        val tr = "\u0130stanbul \u00e7al\u0131\u015f\u0131r, \u011fe\u00e7 \u00f6\u011fle \u00fc\u015f\u00fcd\u00fc"
        fun w(name: String, b: ByteArray): File { val f = File(tmp, name); f.writeBytes(b); return f }
        val u8 = TextExtractor.extract(w("a.txt", tr.toByteArray(Charsets.UTF_8)), "txt", 1L shl 20)
        eq("extract: utf-8 text", u8?.text, tr); eq("extract: utf-8 charset", u8?.charset, "UTF-8")
        val bom = TextExtractor.extract(w("b.txt", byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + tr.toByteArray(Charsets.UTF_8)), "txt", 1L shl 20)
        eq("extract: utf-8 BOM stripped", bom?.text, tr)
        val u16 = TextExtractor.extract(w("c.txt", byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + tr.toByteArray(Charsets.UTF_16LE)), "txt", 1L shl 20)
        eq("extract: utf-16 LE with BOM", u16?.text, tr)
        val u16n = TextExtractor.extract(w("d.txt", "hello world, plain ascii in utf-16".toByteArray(Charsets.UTF_16LE)), "txt", 1L shl 20)
        eq("extract: utf-16 LE without BOM", u16n?.text, "hello world, plain ascii in utf-16")
        val cp = try { java.nio.charset.Charset.forName("windows-1254") } catch (e: Exception) { null }
        if (cp != null) {
            val c1254 = TextExtractor.extract(w("e.txt", tr.toByteArray(cp)), "txt", 1L shl 20)
            eq("extract: windows-1254 Turkish letters", c1254?.text, tr); eq("extract: windows-1254 charset name", c1254?.charset, cp.name())
        } else println("info windows-1254 not available on this JVM, cp1254 checks skipped")
        check("extract: binary (NUL bytes) -> null", TextExtractor.extract(w("f.txt", ByteArray(200) { if (it % 10 == 0) 0 else (it % 7 + 1).toByte() }), "txt", 1L shl 20) == null)
        check("extract: over the size cap -> null", TextExtractor.extract(w("g.txt", ByteArray(5000) { 'a'.code.toByte() }), "txt", 1000L) == null)
        check("extract: empty file -> null", TextExtractor.extract(w("h.txt", ByteArray(0)), "txt", 1000L) == null)
        eq("extract: CRLF -> LF", TextExtractor.decode("a\r\nb\rc".toByteArray(), "txt")?.text, "a\nb\nc")
        val html = TextExtractor.decode("<html><head><style>p{color:red}</style><script>var x='secret';</script></head><body><!-- c --><p>Merhaba &amp; <b>d\u00fcnya</b></p></body></html>".toByteArray(Charsets.UTF_8), "html")?.text ?: ""
        check("extract: html text kept", html.contains("Merhaba & ") && html.contains("d\u00fcnya"))
        check("extract: html script / style / tags / comments gone", !html.contains("secret") && !html.contains("color") && !html.contains("<") && !html.contains(" c "))
        eq("extract: same bytes give the same text (deterministic)", TextExtractor.decode(tr.toByteArray(), "txt")?.text, TextExtractor.decode(tr.toByteArray(), "txt")?.text)

        // matching
        val doc = Fold.fold("Merhaba d\u00fcnya\nKedi sever s\u00fct\nTOPLANTI notlar\u0131 yar\u0131n\nkediler de")
        fun terms(vararg n: QNode, opt: MatchOptions = MatchOptions()): List<CTerm> = ContentMatch.compile(n.toList(), opt, ArrayList())
        val h1 = ContentMatch.find(doc, terms(QText("KED\u0130")))
        check("content: Turkish fold finds 'Kedi'", h1 != null); eq("content: line of first match", h1?.line, 2)
        eq("content: word on line 3 (diacritics ignored)", ContentMatch.find(doc, terms(QText("notlar\u0131")))?.line, 3)
        check("content: contains matches inside a word", ContentMatch.find(doc, terms(QText("ed"))) != null)
        check("content: whole word rejects 'ed'", ContentMatch.find(doc, terms(QText("ed"), opt = MatchOptions(wholeWord = true))) == null)
        eq("content: whole word skips 'kediler', finds 'kedi'", ContentMatch.find(doc, terms(QText("kedi"), opt = MatchOptions(wholeWord = true)))?.line, 2)
        check("content: all words must be present", ContentMatch.find(doc, terms(QText("kedi"), QText("toplanti"))) != null && ContentMatch.find(doc, terms(QText("kedi"), QText("araba"))) == null)
        check("content: NOT word rejects", ContentMatch.find(doc, terms(QText("kedi"), QNot(QText("sut")))) == null)
        check("content: NOT word absent is fine", ContentMatch.find(doc, terms(QText("kedi"), QNot(QText("araba")))) != null)
        check("content: phrase", ContentMatch.find(doc, terms(QText("kedi sever", phrase = true))) != null)
        check("content: wildcard", ContentMatch.find(doc, terms(QText("kedi*sut"))) != null && ContentMatch.find(doc, terms(QText("merh?ba"))) != null)
        check("content: regex", ContentMatch.find(doc, terms(QText("to[a-z]+ti", regex = true))) != null)
        val notes = ArrayList<String>()
        check("content: invalid regex is dropped with a note", ContentMatch.compile(listOf(QText("(", regex = true)), MatchOptions(), notes).isEmpty() && notes.isNotEmpty())
        eq("content: SQL prefilter needles are plain positive words", ContentMatch.needles(terms(QText("Kedi"), QNot(QText("x")), QText("a*b"))), listOf("kedi"))
        val sn = ContentMatch.snippet("x".repeat(200) + "TARGET" + "y".repeat(200), 200, 6)
        check("content: snippet holds the match and is short", sn.contains("TARGET") && sn.length < 140)
    } finally { tmp.deleteRecursively() }
}

/** Part 7: ocr: / ai: planning and merging (pure; ImgSearch itself needs Android and is checked on the device). */
fun imgTests() {
    fun pl(q: String, opt: MatchOptions = MatchOptions()) = ImgQuery.plan(QueryParser.parse(q), opt)
    val a = pl("ocr:fatura ocr:toplam")
    eq("img: two ocr words are two terms", a.ocr.map { it.text }, listOf("fatura", "toplam")); check("img: no ai part", !a.wantsAi && a.wantsOcr)
    val b = pl("ocr:fatura !ocr:iptal")
    eq("img: ocr NOT term kept apart", b.ocrNot.map { it.text }, listOf("iptal"))
    val c = pl("ai:red car ai:night")
    eq("img: ai words join into one sentence", c.ai, "red car night"); check("img: ai only wants no ocr", c.wantsAi && !c.wantsOcr)
    val d = pl("ai:\"red car\" !ai:blue")
    eq("img: ai phrase text", d.ai, "red car"); check("img: ai NOT ignored with a note", d.notes.any { it.startsWith("ai:") })
    val e = pl("ocr:fat*")
    check("img: ocr wildcard gets a note", e.notes.any { it.contains("wildcards") })
    check("img: whole-word option reaches the term", pl("ocr:kedi", MatchOptions(wholeWord = true)).ocr[0].whole && !pl("ocr:kedi").ocr[0].whole)
    check("img: only NOT ocr term -> no terms + note", pl("!ocr:iptal").let { !it.wantsOcr && it.notes.isNotEmpty() })
    check("img: plain name query has no picture part", pl("kedi").let { !it.wantsOcr && !it.wantsAi })
    eq("img: intersect keeps AND", ImgQuery.intersect(listOf(listOf("/a", "/b", "/c"), listOf("/c", "/a")), emptyList()), listOf("/a", "/c"))
    eq("img: intersect removes NOT hits", ImgQuery.intersect(listOf(listOf("/a", "/b")), listOf(listOf("/b"))), listOf("/a"))
    eq("img: intersect of nothing is empty", ImgQuery.intersect(emptyList(), emptyList()), emptyList<String>())
    eq("img: intersect drops duplicates", ImgQuery.intersect(listOf(listOf("/a", "/a")), emptyList()), listOf("/a"))
    val m = ImgQuery.merge(listOf("/a", "/b", "/a"), listOf("/b" to 0.31f, "/c" to 0.28f, "/c" to 0.2f))
    eq("img: merge order OCR first then AI", m.map { it.path }, listOf("/a", "/b", "/c"))
    eq("img: merge badges", m.map { it.badge }, listOf("OCR", "OCR + AI", "AI"))
    check("img: both-engine hit keeps the AI score", m[1].score == 0.31f && m[0].score == 1f)
    eq("img: label OCR", ImgQuery.label(m[0]), "OCR"); eq("img: label AI shows score", ImgQuery.label(m[2]), "AI 0.28")
    eq("img: toReal", ImgQuery.toReal("/storage/emulated/0/", "/DCIM/a.jpg"), "/storage/emulated/0/DCIM/a.jpg")
    eq("img: toReal without leading slash", ImgQuery.toReal("/r", "x.png"), "/r/x.png")
    check("img: threshold clamp", ImgQuery.clampThreshold(0f) == 0.05f && ImgQuery.clampThreshold(9f) == 0.60f && ImgQuery.clampThreshold(Float.NaN) == 0.20f && ImgQuery.clampThreshold(0.3f) == 0.3f)
    check("img: extensions", "jpg" in ImgQuery.EXTS && "txt" !in ImgQuery.EXTS)
}

/** Part 6: Office document text out of the office_selftest fixtures (run from the repo root). */
fun docTests() {
    val D = File("tools/office_selftest")
    check("doc: fixtures found", File(D, "sample.docx").isFile, D.absolutePath)
    val DE = com.lanshare.app.core.office.DocExtractor
    fun ex(n: String) = DE.extract(File(D, n))
    val want = listOf("sample.docx" to "merged", "sample.xlsx" to "total", "sample.pptx" to "alpha", "sample.odt" to "bold and plain", "sample.ods" to "inline", "sample.odp" to "opening")
    for ((n, w) in want) {
        val t = ex(n)
        check("doc: $n readable", t != null)
        check("doc: $n has '$w'", t != null && Fold.fold(t).contains(w), "text=" + (t ?: "null").take(80).replace('\n', '|'))
    }
    for (n in listOf("sample.doc", "sample.xls")) check("doc: $n readable (legacy)", ex(n) != null)
    val px = ex("sample.pptx") ?: ""; val xx = ex("sample.xlsx") ?: ""
    check("doc: pptx match is inside a slide", DE.sectionAt(px, px.indexOf("Alpha")).startsWith("Slide "), DE.sectionAt(px, px.indexOf("Alpha")))
    check("doc: xlsx match is inside a sheet", DE.sectionAt(xx, xx.indexOf("Total")).startsWith("Sheet "), DE.sectionAt(xx, xx.indexOf("Total")))
    val syn = "## Sheet1\nA\tB\n## Data\nx\ty"
    eq("doc: sectionAt sheet", DE.sectionAt(syn, syn.indexOf("x")), "Sheet Data")
    eq("doc: sectionAt first sheet", DE.sectionAt(syn, syn.indexOf("A")), "Sheet Sheet1")
    val sl = "Slide 1\nfoo\nSlide 2 (hidden)\nbar"
    eq("doc: sectionAt slide", DE.sectionAt(sl, sl.indexOf("bar")), "Slide 2")
    eq("doc: sectionAt slide 1", DE.sectionAt(sl, sl.indexOf("foo")), "Slide 1")
    eq("doc: sectionAt none (Word)", DE.sectionAt("a\nb\nc", 4), "")
    eq("doc: sectionAt empty / past end", DE.sectionAt("", 5), "")
    val tmp = java.nio.file.Files.createTempDirectory("usdt").toFile()
    try {
        val src = File(D, "sample.docx").readBytes()
        fun w(n: String, b: ByteArray) = File(tmp, n).also { it.writeBytes(b) }
        check("doc: random bytes -> null, no throw", DE.extract(w("r.docx", ByteArray(500) { (it * 31 + 7).toByte() })) == null)
        check("doc: truncated zip -> null, no throw", DE.extract(w("t.docx", src.copyOf(src.size / 3))) == null)
        check("doc: empty file -> null", DE.extract(w("e.xlsx", ByteArray(0))) == null)
        check("doc: not an office extension -> null", DE.extract(w("a.txt", src)) == null)
        check("doc: size cap -> null", DE.extract(File(D, "sample.docx"), 10L) == null)
        check("doc: missing file -> null", DE.extract(File(tmp, "nope.docx")) == null)
        check("doc: next file still works after bad ones", ex("sample.docx") != null)
    } finally { tmp.deleteRecursively() }
}


fun exportTests() {
    val rows = listOf(
        UsExport.ExRow("a,b.txt", "/sdcard/a,b.txt", false, 1234, 0, "say \"hi\"", 7),
        UsExport.ExRow("=cmd", "/sdcard/=cmd", false, 5, 86400L * 365),
        UsExport.ExRow("Docs", "/sdcard/Docs", true, 0, 0))
    val c = UsExport.csv(rows).split("\r\n")
    eq("export: csv header", c[0], "name,path,type,size,modified_utc,line,snippet")
    eq("export: csv quoting + line", c[1], "\"a,b.txt\",\"/sdcard/a,b.txt\",file,1234,,7,\"say \"\"hi\"\"\"")
    check("export: formula neutralised", c[2].startsWith("'=cmd,"), c[2])
    check("export: date utc", c[2].contains(",1971-01-01 00:00,"), c[2])
    eq("export: folder row", c[3], "Docs,/sdcard/Docs,folder,,,,")
    eq("export: text", UsExport.text(rows), "/sdcard/a,b.txt\n/sdcard/=cmd\n/sdcard/Docs/\n")
    check("export: capped", UsExport.text(List(UsExport.MAX_ROWS + 10) { UsExport.ExRow("x", "/x$it", false, 1, 1) }).lines().size == UsExport.MAX_ROWS + 1)
    eq("export: empty date", UsExport.date(0), "")
}

fun arcTests() {
    eq("arc: strip", ArcNames.strip("  ARC: Rapor 2026 "), "Rapor 2026")
    check("arc: plain text is not an archive query", ArcNames.strip("report") == null && ArcNames.strip("arc") == null)
    eq("arc: words folded", ArcNames.words("\u0130\u015Eler  \u00C7ay"), listOf("isler", "cay"))
    check("arc: all words, folded path", ArcNames.matches("Belgeler/\u015Eubat/RAPOR.docx", ArcNames.words("subat rapor")))
    check("arc: missing word fails", !ArcNames.matches("a/b.txt", ArcNames.words("b zzz")))
    check("arc: empty query matches nothing", !ArcNames.matches("a/b.txt", emptyList()))
    eq("arc: leaf", ArcNames.leaf("x/y/z.txt"), "z.txt")
}

fun stressTests() {
    val ix = NameIndex(16)
    val root = ix.addRoot("/r")
    val long = "L" + "x".repeat(3000) + "end.txt"
    val emoji = "pic \uD83D\uDE00 beach \uD83C\uDFD6.jpg"
    val a = ix.add(root, long, false, 1, 1)
    val b = ix.add(root, emoji, false, 2, 2)
    eq("stress: long name round trip", ix.nameOf(a), long)
    eq("stress: emoji name round trip", ix.nameOf(b), emoji)
    check("stress: long name found", srch(ix, "xxxend").total == 1)
    check("stress: emoji name found", srch(ix, "beach").total == 1)
    eq("stress: emoji path", ix.pathOf(b), "/r/" + emoji)
    var many = 0
    for (i in 0 until 20000) { ix.add(root, "f$i.bin", false, i.toLong(), 1); many++ }
    check("stress: 20k adds searchable", srch(ix, "f19999", lim = 5).total == 1)
}

fun viewTests() {
    // history
    var h = UsHistory.push(emptyList(), "invoice")
    h = UsHistory.push(h, "Beach")
    h = UsHistory.push(h, "INVOICE")
    eq("history: newest first, case-merged", h, listOf("INVOICE", "Beach"))
    eq("history: blank / 1 char ignored", UsHistory.push(h, " a "), h)
    var big = emptyList<String>()
    for (i in 0 until 50) big = UsHistory.push(big, "query $i")
    eq("history: capped", big.size, UsHistory.MAX_RECENT)
    eq("history: first is newest", big[0], "query 49")
    val tricky = listOf("a\\b", "line1\nline2", "tab\there", "plain")
    eq("history: encode / decode round trip", UsHistory.decode(UsHistory.encode(tricky)), tricky)
    eq("history: decode null / empty", UsHistory.decode(null).size + UsHistory.decode("").size, 0)
    eq("history: remove ignores case", UsHistory.remove(h, "beach"), listOf("INVOICE"))
    // saved
    var sv = UsHistory.save(emptyList(), "Receipts", "ocr:receipt dm:thisyear")
    sv = UsHistory.save(sv, "Big videos", "kind:video size:>1gb")
    sv = UsHistory.save(sv, "receipts", "ocr:receipt")
    eq("saved: same name replaced", sv.map { it.name + "=" + it.query }, listOf("receipts=ocr:receipt", "Big videos=kind:video size:>1gb"))
    eq("saved: no name = query", UsHistory.save(emptyList(), " ", "x y")[0].name, "x y")
    eq("saved: blank query ignored", UsHistory.save(sv, "n", "  ").size, sv.size)
    val odd = listOf(UsHistory.Saved("a\tb", "q\nr"), UsHistory.Saved("n", "in:/x y"))
    val back = UsHistory.decodeSaved(UsHistory.encodeSaved(odd))
    eq("saved: round trip", back.map { it.name + "|" + it.query }, odd.map { it.name + "|" + it.query })
    check("saved: garbage lines skipped", UsHistory.decodeSaved("nolinehere\n\tq\nok\tq2").map { it.name } == listOf("ok"))
    // sections
    eq("sections: blank = one", UsSections.plan("  ", true, true, true).size, 1)
    eq("sections: everywhere off = one", UsSections.plan("beach", false, true, true).map { it.id }, listOf("names"))
    eq("sections: all four", UsSections.plan("beach", true, true, true).map { it.id }, listOf("names", "content", "ocr", "ai"))
    eq("sections: content switch off", UsSections.plan("beach", true, false, true).map { it.id }, listOf("names", "ocr", "ai"))
    eq("sections: pictures switch off", UsSections.plan("beach", true, true, false).map { it.id }, listOf("names", "content"))
    eq("sections: own engine = one", UsSections.plan("ai:cat", true, true, true).map { it.query }, listOf("ai:cat"))
    check("sections: hasEngine", UsSections.hasEngine("a !content:b") && !UsSections.hasEngine("contents beach"))
    eq("sections: queries", UsSections.plan("tax ext:pdf", true, true, true).map { it.query }, listOf("tax ext:pdf", "content: tax ext:pdf", "ocr: tax ext:pdf", "ai: tax ext:pdf"))
    eq("sections: title", UsSections.title(UsSections.Sec("names", "Names", "x"), 1234, true), "Names  \u00B7  1234+")
    val q1 = QueryParser.parse("content: tax ext:pdf")
    check("sections: content query parses to content + filter", q1.content != null && q1.name == null && q1.filters.size == 1)
    val q2 = QueryParser.parse("ocr: receipt")
    check("sections: ocr query parses", q2.ocr != null && q2.content == null)
    // scope
    val base = QueryParser.parse("beach")
    val sc = UsSections.scope(base, "/r/Pictures")
    check("scope: name gets AND in-filter", sc.name is QAnd && sc.filters.size == 1 && sc.filters[0] is InFilter)
    check("scope: empty dir = same query", UsSections.scope(base, " ") === base)
    val sc2 = UsSections.scope(QueryParser.parse("content: tax"), "/r/Docs")
    check("scope: content query keeps content, name = filter only", sc2.content != null && sc2.name is QFilter && sc2.filters.size == 1)
    check("scope: path with spaces stays one filter", (UsSections.scope(QueryParser.parse("x"), "/r/My Pics").filters[0] as InFilter).dir == "/r/My Pics")
}

fun main(args: Array<String>) {
    foldTests()
    memoryTests()
    walkerTests()
    rulesTests()
    deltaTests()
    contentTests()
    docTests()
    imgTests()
    viewTests()
    exportTests()
    stressTests()
    arcTests()
    fixtureTests(args)
    matcherTests()
    randomQueryTests()
    perfTests()
    println("done: ok=$oks FAIL=$fails")
    if (fails > 0) System.exit(1)
}
