package com.lanshare.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Text inbox: every text / link sent to or received from another device, oldest first.
 * Persisted in <filesDir>/inbox.json (written to a temp file, then renamed), capped by count and total size.
 */
object Notes {
    class Note(val id: Long, val ts: Long, val out: Boolean, val peer: String, val text: String)

    private const val MAX_ITEMS = 300
    private const val MAX_TEXT = 100_000
    private const val MAX_TOTAL = 2_000_000
    private val items = ArrayList<Note>()
    private var loaded = false
    private var nextId = 1L

    /** Called (on any thread) after the list changed. */
    val listeners = CopyOnWriteArrayList<() -> Unit>()
    /** The inbox screen is on top: an incoming text needs no heads-up notification. */
    @Volatile var visible = false

    private fun file(): File? = Cfg.dir?.let { File(it, "inbox.json") }

    private fun ensure() {
        if (loaded) return
        val f = file() ?: return   // config not loaded yet: try again on the next call
        loaded = true
        try {
            if (f.isFile) {
                val a = JSONArray(f.readText())
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val id = o.optLong("id", nextId)
                    items.add(Note(id, o.optLong("ts"), o.optBoolean("out"), o.optString("peer"), o.optString("text")))
                    if (id >= nextId) nextId = id + 1
                }
            }
        } catch (_: Exception) {}
    }

    private fun save() {
        val f = file() ?: return
        try {
            val a = JSONArray()
            for (n in items) a.put(JSONObject().put("id", n.id).put("ts", n.ts).put("out", n.out).put("peer", n.peer).put("text", n.text))
            val tmp = File(f.path + ".tmp")
            tmp.writeText(a.toString())
            if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) f.writeText(a.toString()) }
        } catch (_: Exception) {}
    }

    private fun fire() { for (l in listeners) try { l() } catch (_: Throwable) {} }

    /** Adds one entry ([out] = sent by this device). The same text twice within 2 s from the same device is kept once. */
    fun add(out: Boolean, peer: String, text: String): Note? {
        if (text.isBlank()) return null
        val t = text.take(MAX_TEXT)
        val n: Note
        synchronized(this) {
            ensure()
            val now = System.currentTimeMillis()
            val last = items.lastOrNull()
            if (last != null && last.out == out && last.peer == peer && last.text == t && now - last.ts < 2000) return last
            n = Note(nextId++, now, out, peer, t)
            items.add(n)
            var total = items.sumOf { it.text.length }
            while (items.size > MAX_ITEMS || (total > MAX_TOTAL && items.size > 1)) { total -= items.removeAt(0).text.length }
            save()
        }
        fire()
        return n
    }

    @Synchronized fun all(): List<Note> { ensure(); return ArrayList(items) }

    fun remove(id: Long) {
        synchronized(this) { ensure(); if (!items.removeAll { it.id == id }) return; save() }
        fire()
    }

    fun clear() {
        synchronized(this) { ensure(); items.clear(); save() }
        fire()
    }
}
