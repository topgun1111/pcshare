// End-to-end pipeline on REAL files (no mocks): tree -> Walker -> NameIndex -> IndexStore -> Search (names, kinds, filters, sort, paging)
// -> content extraction (txt in 4 encodings, office fixtures) -> ContentMatch -> Delta freshness -> persistence again -> export.
// Run from the repo root (needs tools/office_selftest/*.docx etc.).
import com.lanshare.app.core.usearch.*
import com.lanshare.app.core.office.DocExtractor
import java.io.File

fun e2eTests() {
    sec("E2E pipeline")
    val tmp = java.nio.file.Files.createTempDirectory("usee").toFile()
    try {
        val root = File(tmp, "vol"); root.mkdirs()
        val OFF = File("tools/office_selftest")
        check("e2e: office fixtures present", File(OFF, "sample.docx").isFile && File(OFF, "sample.xlsx").isFile && File(OFF, "sample.pptx").isFile && File(OFF, "sample.odt").isFile, OFF.absolutePath)
        // ---------------------------------------------------------------- 1. the tree
        val tr1 = "Toplant\u0131 notlar\u0131: \u0130stanbul \u00e7al\u0131\u015f\u0131r\nikinci sat\u0131r kedi sever s\u00fct\nson sat\u0131r"
        val cp = try { java.nio.charset.Charset.forName("windows-1254") } catch (_: Exception) { null }
        File(mk(root, "Notes"), "gunluk.txt").writeBytes(tr1.toByteArray(Charsets.UTF_8))
        if (cp != null) File(File(root, "Notes"), "eski.txt").writeBytes(tr1.toByteArray(cp))
        File(File(root, "Notes"), "utf16.txt").writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + tr1.toByteArray(Charsets.UTF_16LE))
        File(File(root, "Notes"), "binary.txt").writeBytes(ByteArray(300) { if (it % 7 == 0) 0 else (it % 90 + 33).toByte() })
        File(File(root, "Notes"), "huge.log").writeBytes(ByteArray(2 * 1024 * 1024) { 'a'.code.toByte() })
        File(File(root, "Notes"), "empty.txt").createNewFile()
        for (n in listOf("sample.docx", "sample.xlsx", "sample.pptx", "sample.odt")) if (File(OFF, n).isFile) File(mk(root, "Docs"), n).writeBytes(File(OFF, n).readBytes())
        File(mk(root, "Pics"), "IMG_0001.jpg").writeBytes(ByteArray(1234))
        File(File(root, "Pics"), "IMG_0002.png").writeBytes(ByteArray(40))
        File(File(root, "Pics"), "clip.mp4").writeBytes(ByteArray(50))
        File(mk(root, "Archives"), "backup.zip").writeBytes(ByteArray(60))
        File(mk(root, "Download"), "rapor.pdf").writeBytes(ByteArray(10))
        File(mk(root, "\u00dcnicode Klas\u00f6r"), "\u00e7al\u0131\u015fma \u015fubat.txt").writeText("x")
        val emoji = "plaj \uD83C\uDFD6 \uD83D\uDE00.jpg"
        File(File(root, "Pics"), emoji).writeBytes(ByteArray(5))
        File(File(root, "Notes"), "L" + "y".repeat(200) + ".txt").writeText("long")
        File(mk(root, ".thumbnails"), "t1.jpg").writeBytes(ByteArray(3))
        root.walkTopDown().filter { it.isDirectory }.forEach { touchOld(it) }
        val rules = UserRules(omitNames = setOf(".thumbnails", ".cache"))

        // ---------------------------------------------------------------- 2. walk
        val ix = NameIndex(64)
        Walker(ix, rules).scanVolume(root)
        val P = { s: String -> root.path + "/" + s }
        val expectFiles = root.walkTopDown().filter { it.isFile && !it.path.contains("/.thumbnails") }.count()
        val expectDirs = root.walkTopDown().filter { it.isDirectory && it != root && !it.path.contains("/.thumbnails") }.count()
        var fileCount = 0; ix.forEachLive { if (!ix.isDir(it)) fileCount++ }
        eq("e2e: every real file is indexed", fileCount, expectFiles.toInt())
        eq("e2e: every real folder is indexed", ix.dirCount() - 1, expectDirs.toInt())   // minus the volume root itself
        check("e2e: omitted folder is not indexed", ix.find(P(".thumbnails")) == NameIndex.NONE && ix.find(P(".thumbnails/t1.jpg")) == NameIndex.NONE)
        eq("e2e: size stored", ix.sizeOf(ix.find(P("Pics/IMG_0001.jpg"))), 1234L)
        eq("e2e: emoji name round trip", ix.nameOf(ix.find(P("Pics/$emoji"))), emoji)
        check("e2e: folder flag", ix.isDir(ix.find(P("Docs"))) && !ix.isDir(ix.find(P("Docs/sample.docx"))))

        // ---------------------------------------------------------------- 3. persistence
        val store = File(tmp, "names.bin")
        IndexStore.save(ix, store)
        val ix2 = IndexStore.load(store)
        check("e2e: names.bin loads", ix2 != null)
        check("e2e: loaded index is identical", ix2 != null && sameIndex(ix, ix2))
        val trunc = File(tmp, "trunc.bin"); trunc.writeBytes(store.readBytes().copyOf((store.length() * 2 / 3).toInt()))
        val ixT = try { IndexStore.load(trunc) } catch (_: Throwable) { null }
        check("e2e: a truncated file never claims more entries than the full one", ixT == null || ixT.count() <= ix.count())

        // ---------------------------------------------------------------- 4. name search vs brute force on the real tree
        fun rel(p: String) = p.removePrefix(root.path).removePrefix("/")
        fun names(q: String, sort: SortSpec = SortSpec(), off: Int = 0, lim: Int = 1000, opt: MatchOptions = MatchOptions()) =
            srch(ix, q, opt, sort, off, lim).ids.map { rel(ix.pathOf(it)) }
        eq("e2e: ext:txt = brute force", names("ext:txt").toSet(), root.walkTopDown().filter { it.isFile && it.extension == "txt" && !it.path.contains("/.thumbnails") }.map { rel(it.path) }.toSet())
        eq("e2e: kind:image finds jpg + png (emoji jpg too)", names("kind:image").map { it.substringAfterLast('/') }.toSet(), setOf("IMG_0001.jpg", "IMG_0002.png", emoji))
        eq("e2e: kind:video", names("kind:video"), listOf("Pics/clip.mp4"))
        eq("e2e: kind:archive", names("kind:archive"), listOf("Archives/backup.zip"))
        check("e2e: kind:folder returns only folders", names("kind:folder").isNotEmpty() && names("kind:folder").all { ix.isDir(ix.find(P(it))) })
        check("e2e: kind:doc has the four office fixtures", names("kind:doc").map { it.substringAfterLast('/') }.containsAll(listOf("sample.docx", "sample.xlsx", "sample.pptx", "sample.odt")))
        eq("e2e: size:>1kb = brute force", names("size:>1kb").toSet(), root.walkTopDown().filter { it.isFile && it.length() > 1024 && !it.path.contains("/.thumbnails") }.map { rel(it.path) }.toSet())
        check("e2e: size:>1mb finds only the big log", names("size:>1mb") == listOf("Notes/huge.log"))
        eq("e2e: Turkish fold: 'calisma subat' finds the dotless / cedilla name", names("calisma subat"), listOf("\u00dcnicode Klas\u00f6r/\u00e7al\u0131\u015fma \u015fubat.txt"))
        eq("e2e: Turkish fold: capital dotted U finds the folder", names("UNICODE"), listOf("\u00dcnicode Klas\u00f6r"))
        check("e2e: wildcard", names("img_000?.*").size == 2)
        check("e2e: OR", names("clip.mp4 | backup.zip").size == 2)
        check("e2e: NOT", names("IMG_ !png").map { it.substringAfterLast('/') } == listOf("IMG_0001.jpg"))
        check("e2e: phrase", names("\"rapor.pdf\"") == listOf("Download/rapor.pdf"))
        eq("e2e: in: limits to one folder", names("in:${P("Pics")} kind:file").size, 4)
        check("e2e: path: filter", names("path:Download").contains("Download/rapor.pdf"))
        val byName = names("kind:file", SortSpec(SortKey.NAME))
        check("e2e: sort NAME: first <= last", byName.size > 1 && Fold.fold(byName.first().substringAfterLast('/')) <= Fold.fold(byName.last().substringAfterLast('/')))
        val bySize = srch(ix, "kind:file", sort = SortSpec(SortKey.SIZE)).ids.map { ix.sizeOf(it) }
        check("e2e: sort SIZE is by size (monotonic)", bySize == bySize.sortedDescending() || bySize == bySize.sorted())
        val all = names("kind:file", SortSpec(SortKey.PATH))
        eq("e2e: paging: two pages = whole list", names("kind:file", SortSpec(SortKey.PATH), 0, 5) + names("kind:file", SortSpec(SortKey.PATH), 5, 1000), all)
        eq("e2e: total counts all hits even when paged", srch(ix, "kind:file", lim = 3).total, all.size)
        check("e2e: reverse order flips", names("kind:file", SortSpec(SortKey.PATH, reverse = true)) == all.reversed())
        eq("e2e: case-sensitive option", names("img_0001", opt = MatchOptions(matchCase = true)).size, 0)
        eq("e2e: case-insensitive default", names("img_0001").size, 1)
        eq("e2e: regex option", names("^img_000[12]\\.", opt = MatchOptions(regex = true)).size, 2)
        check("e2e: garbage query does not throw", try { srch(ix, "(((( ||| \"unclosed re: !!"); true } catch (_: Throwable) { false })
        check("e2e: empty query returns no crash", try { srch(ix, ""); true } catch (_: Throwable) { false })
        val tok = CancelToken(); tok.cancel()
        check("e2e: a cancelled search returns without throwing", try { Search.run(ix, QueryParser.parse("kind:file"), MatchOptions(), SortSpec(), 0, 10, tok); true } catch (_: Throwable) { false })

        // ---------------------------------------------------------------- 5. content pipeline (what IndexManager.doContent does, without SQLite)
        val store2 = HashMap<String, String>()
        val textExt = TextExtractor.DEFAULT_EXTS
        val docExt = setOf("docx", "xlsx", "pptx", "odt", "ods", "odp", "doc", "xls")
        ix.forEachLive { id ->
            if (ix.isDir(id)) return@forEachLive
            val f = File(ix.pathOf(id)); val ext = f.extension.lowercase()
            val t: String? = if (ext in docExt) DocExtractor.extract(f) else if (ext in textExt || ext == "log") TextExtractor.extract(f, ext, 1L shl 20)?.text else null
            if (t != null && t.isNotBlank()) store2[rel(f.path)] = Fold.fold(t)
        }
        fun opt(vararg n: String) = ContentMatch.compile(n.map { QText(it) }, MatchOptions(), ArrayList())
        fun found(word: String): Set<String> = store2.filter { ContentMatch.find(it.value, opt(word)) != null }.keys
        check("e2e: utf-8 text indexed", store2.containsKey("Notes/gunluk.txt"))
        if (cp != null) eq("e2e: windows-1254 file gives the same searchable text", store2["Notes/eski.txt"], store2["Notes/gunluk.txt"])
        eq("e2e: utf-16 file gives the same searchable text", store2["Notes/utf16.txt"], store2["Notes/gunluk.txt"])
        check("e2e: binary 'txt' is not stored", !store2.containsKey("Notes/binary.txt"))
        check("e2e: file over the cap is not stored", !store2.containsKey("Notes/huge.log"))
        check("e2e: empty file is not stored", !store2.containsKey("Notes/empty.txt"))
        check("e2e: Turkish word found regardless of dots (KEDI / kedi)", found("KED\u0130").contains("Notes/gunluk.txt"))
        check("e2e: word found in all text encodings", found("istanbul").containsAll(listOf("Notes/gunluk.txt", "Notes/utf16.txt")))
        eq("e2e: line number of 'kedi sever'", ContentMatch.find(store2["Notes/gunluk.txt"]!!, ContentMatch.compile(listOf(QText("kedi sever", phrase = true)), MatchOptions(), ArrayList()))?.line, 2)
        for ((n, w) in listOf("Docs/sample.docx" to "merged", "Docs/sample.xlsx" to "total", "Docs/sample.pptx" to "alpha", "Docs/sample.odt" to "bold and plain"))
            check("e2e: '$w' found inside $n", found(w.split(' ')[0]).contains(n), "stored=" + store2.keys.toString())
        check("e2e: a word that exists nowhere finds nothing", found("zzqxwv").isEmpty())
        val snip = store2["Notes/gunluk.txt"]!!.let { t -> ContentMatch.find(t, opt("kedi"))?.snip ?: "" }
        check("e2e: snippet holds the match", snip.contains("kedi"))

        // ---------------------------------------------------------------- 6. freshness: change the tree, delta, search again
        File(File(root, "Notes"), "yeni dosya.txt").writeText("new file")
        File(File(root, "Pics"), "clip.mp4").delete()
        File(root, "Archives").renameTo(File(root, "Arsiv"))
        val st = Delta.syncVolume(ix, root, rules)
        check("e2e: delta saw changed folders", st.dirsChanged >= 1)
        check("e2e: new file is searchable", names("yeni dosya") == listOf("Notes/yeni dosya.txt"))
        check("e2e: deleted file is gone", names("clip.mp4").isEmpty())
        eq("e2e: renamed folder: old path gone, new path with its child", listOf(ix.find(P("Archives/backup.zip")) == NameIndex.NONE, ix.find(P("Arsiv/backup.zip")) != NameIndex.NONE), listOf(true, true))
        eq("e2e: kind:archive follows the rename", names("kind:archive"), listOf("Arsiv/backup.zip"))
        IndexStore.save(ix, store)
        val ix3 = IndexStore.load(store)
        check("e2e: index after delta persists and reloads identical", ix3 != null && sameIndex(ix, ix3))

        // ---------------------------------------------------------------- 7. screen helpers on real results
        val rows = srch(ix, "kind:image", sort = SortSpec(SortKey.NAME)).ids.map { UsExport.ExRow(ix.nameOf(it), ix.pathOf(it), ix.isDir(it), ix.sizeOf(it), ix.mtimeOf(it)) }
        val csv = UsExport.csv(rows).trim().split("\r\n")
        eq("e2e: csv has header + one line per result", csv.size, rows.size + 1)
        check("e2e: csv quotes names with commas / quotes", UsExport.csv(listOf(UsExport.ExRow("a,\"b\".txt", "/x", false, 1, 1))).contains("\"a,\"\"b\"\".txt\""))
        val plan = UsSections.plan("kedi", true, true, true)
        check("e2e: 'Search everywhere' plans the engines", plan.size >= 2 && plan.first().query.isNotEmpty())
        eq("e2e: an engine query stays one section", UsSections.plan("content:kedi", true, true, true).size, 1)
        check("e2e: archive query helper", ArcNames.strip("arc:rapor") == "rapor" && ArcNames.matches("x/Rapor 2026.docx", ArcNames.words("rapor")))
        val h = UsHistory.push(UsHistory.push(emptyList(), "kedi"), "plaj")
        eq("e2e: history round trip", UsHistory.decode(UsHistory.encode(h)), h)
    } finally { tmp.deleteRecursively() }
}
