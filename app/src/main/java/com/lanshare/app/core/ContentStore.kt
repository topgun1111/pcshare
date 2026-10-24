package com.lanshare.app.core

import android.database.sqlite.SQLiteDatabase
import com.lanshare.app.core.usearch.CHit
import java.io.File

/**
 * Text-file content store (Universal Search Part 5, decision D3). One SQLite table, key = absolute path (NameIndex ids change after a full rebuild).
 * `txt` is the folded text (Fold.fold, diacritics dropped), at most [MAX_CHARS] characters per file (a CursorWindow holds about 2 MB per row).
 * No FTS: Contains-matching needs a substring scan, so search = SQL instr() prefilter on all plain words, then ContentMatch verifies. Real cost: a scan of
 * the whole store per query (budget setting keeps it bounded, default 300 MB of text).
 */
class ContentStore(file: File) {
    companion object { const val MAX_CHARS = 600_000 }

    class Found(val path: String, val mtime: Long, val size: Long, val hit: CHit, val label: String = "")

    private val db: SQLiteDatabase

    init {
        file.parentFile?.mkdirs()
        db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("CREATE TABLE IF NOT EXISTS docs(path TEXT PRIMARY KEY, mtime INTEGER, size INTEGER, txt TEXT)")
        try { db.execSQL("ALTER TABLE docs ADD COLUMN kind TEXT DEFAULT 't'") } catch (_: Throwable) {}   // Part 6: 't' text file, 'd' Office document (old rows = 't')
    }

    /** [mtime, size] of the stored row or null. */
    fun stamp(path: String): LongArray? {
        db.rawQuery("SELECT mtime, size FROM docs WHERE path=?", arrayOf(path)).use { c -> return if (c.moveToFirst()) longArrayOf(c.getLong(0), c.getLong(1)) else null }
    }

    fun upsert(path: String, mtime: Long, size: Long, txt: String, kind: String = "t") {
        db.execSQL("INSERT OR REPLACE INTO docs(path, mtime, size, txt, kind) VALUES(?,?,?,?,?)", arrayOf<Any>(path, mtime, size, if (txt.length > MAX_CHARS) txt.substring(0, MAX_CHARS) else txt, kind))
    }

    fun delete(path: String) { db.execSQL("DELETE FROM docs WHERE path=?", arrayOf<Any>(path)) }

    fun begin() { db.beginTransaction() }
    fun commit() { if (db.inTransaction()) { db.setTransactionSuccessful(); db.endTransaction() } }

    fun count(): Int = db.rawQuery("SELECT COUNT(*) FROM docs WHERE txt != ''", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    fun chars(): Long = db.rawQuery("SELECT IFNULL(SUM(LENGTH(txt)), 0) FROM docs", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    fun allPaths(): List<String> {
        val l = ArrayList<String>()
        db.rawQuery("SELECT path FROM docs", null).use { while (it.moveToNext()) l.add(it.getString(0)) }
        return l
    }

    fun clear() { db.execSQL("DELETE FROM docs") }

    /** Scans rows whose text contains every plain needle; [verify] decides (whole word, regex, NOT). Stops at [max] hits, at [deadlineMs] or when [cancelled]. */
    fun search(needles: List<String>, verify: (String) -> CHit?, max: Int, deadlineMs: Long, cancelled: () -> Boolean): Pair<List<Found>, Boolean> {
        val sb = StringBuilder("SELECT path, mtime, size, txt, kind FROM docs WHERE txt != ''")
        for (n in needles) sb.append(" AND instr(txt, ?) > 0")
        val out = ArrayList<Found>()
        var complete = true
        db.rawQuery(sb.toString(), needles.toTypedArray()).use { c ->
            var k = 0
            while (c.moveToNext()) {
                if ((++k and 63) == 0 && (cancelled() || System.currentTimeMillis() > deadlineMs)) { complete = false; break }
                val h = verify(c.getString(3)) ?: continue
                out.add(Found(c.getString(0), c.getLong(1), c.getLong(2), h, if (c.getString(4) == "d") com.lanshare.app.core.office.DocExtractor.sectionAt(c.getString(3), h.pos) else ""))
                if (out.size >= max) { complete = false; break }
            }
        }
        return Pair(out, complete)
    }

    fun close() { try { db.close() } catch (_: Throwable) {} }
}
