package com.lanshare.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Native home of everything ui.html kept in the WebView's localStorage: view / sort per folder and global, hidden files, theme,
 * favourites, quick folders, history, drawer tab, split screen.
 *
 * (The WebView app and its localStorage migration no longer exist.)
 *
 * NOT compiled / NOT device-tested.
 */
object Prefs {
    private var sp: SharedPreferences? = null
    fun init(c: Context) { if (sp == null) sp = c.applicationContext.getSharedPreferences("lanshare_ui", Context.MODE_PRIVATE) }
    private fun s(): SharedPreferences = sp ?: throw IllegalStateException("Prefs.init(context) first")

    // ---------------------------------------------------------------- simple values
    var hidden: Boolean
        get() = s().getString("ls_hidden", "0") == "1"
        set(v) { s().edit().putString("ls_hidden", if (v) "1" else "0").apply() }
    /** light | dark | auto */
    var theme: String
        get() = s().getString("ls_theme", "light").let { if (it == "dark" || it == "auto") it else "light" }
        set(v) { s().edit().putString("ls_theme", v).apply() }
    var drawerTab: Int
        get() = s().getString("ls_dtab", "0")?.toIntOrNull() ?: 0
        set(v) { s().edit().putString("ls_dtab", v.toString()).apply() }
    var dual: Boolean
        get() = s().getString("ls_dual", "0") == "1"
        set(v) { s().edit().putString("ls_dual", if (v) "1" else "0").apply() }
    /** Last known "70% used" of this phone's storage: lets a cold start draw the pill at once. */
    var used: Int?
        get() = s().getString("ls_used", null)?.toIntOrNull()
        set(v) { s().edit().putString("ls_used", v?.toString() ?: "").apply() }

    // ---------------------------------------------------------------- view / sort (global default + per-folder overrides)
    class ViewPref(val view: String, val sort: String, val asc: Boolean, val thumb: String) {
        fun toJson(): JSONObject = JSONObject().put("view", view).put("sort", sort).put("asc", asc).put("thumb", thumb)
    }

    private val VIEWS = setOf("list", "compact", "grid")
    private val SORTS = setOf("name", "date", "size", "type", "none")

    private fun parse(o: JSONObject?, d: ViewPref): ViewPref {
        if (o == null) return d
        val v = o.optString("view", d.view).let { if (it in VIEWS) it else d.view }
        val so = o.optString("sort", d.sort).let { if (it in SORTS) it else d.sort }
        return ViewPref(v, so, o.optBoolean("asc", d.asc), if (o.optString("thumb") == "l") "l" else "s")
    }

    private fun global(): ViewPref {
        val p = s()
        val d = ViewPref(p.getString("ls_view", "list") ?: "list", p.getString("ls_sort", "name") ?: "name", p.getString("ls_asc", "1") != "0", "s")
        val g = try { JSONObject(p.getString("ls_g", "") ?: "") } catch (_: Throwable) { null }
        return parse(g, parse(null, d))
    }

    private fun pf(): JSONObject = try { JSONObject(s().getString("ls_pf", "") ?: "") } catch (_: Throwable) { JSONObject() }

    /** View of one folder: its own choice, else the global one; a folder that is mostly photos / videos opens as big thumbnails unless the user chose anything. */
    fun viewFor(dev: String, path: String, mostlyMedia: Boolean): ViewPref {
        val own = pf().optJSONObject("$dev|$path")
        val g = global()
        if (own != null) return parse(own, g)
        if (mostlyMedia && !s().contains("ls_g")) return ViewPref("grid", g.sort, g.asc, "l")
        return g
    }

    /** [all] = "Apply to all folders": becomes the global default and clears every per-folder override (ui.html setPref). */
    @Synchronized fun setView(dev: String, path: String, v: ViewPref, all: Boolean) {
        val e = s().edit()
        if (all) { e.putString("ls_g", v.toJson().toString()); e.putString("ls_pf", "{}") }
        else {
            val o = pf(); val k = "$dev|$path"
            o.remove(k); o.put(k, v.toJson())
            if (o.length() > 200) o.keys().asSequence().firstOrNull()?.let { o.remove(it) }
            e.putString("ls_pf", o.toString())
            if (!s().contains("ls_g")) e.putString("ls_g", global().toJson().toString())
        }
        e.apply()
    }

    // ---------------------------------------------------------------- places: favourites, quick folders, history
    class Loc(val dev: String, val path: String, val name: String, val dn: String = "") {
        fun same(o: Loc) = dev == o.dev && path == o.path
        fun toJson(): JSONObject = JSONObject().put("dev", dev).put("path", path).put("name", name).put("dn", dn)
    }

    private fun locs(key: String): List<Loc>? {
        val a = try { JSONArray(s().getString(key, "") ?: "") } catch (_: Throwable) { return null }
        val out = ArrayList<Loc>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val p = o.optString("path", "/")
            out.add(Loc(o.optString("dev", "local"), p, o.optString("name").ifEmpty { baseName(p) }, o.optString("dn")))
        }
        return out
    }
    private fun saveLocs(key: String, l: List<Loc>) { s().edit().putString(key, JSONArray().also { a -> l.forEach { a.put(it.toJson()) } }.toString()).apply() }

    fun baseName(path: String): String = path.trimEnd('/').substringAfterLast('/').removeSuffix("!")

    fun favs(): List<Loc> = locs("ls_fav") ?: emptyList()
    fun isFav(dev: String, path: String) = favs().any { it.dev == dev && it.path == path }
    /** [devName] = display name of a non-local device (shown under the favourite). */
    fun toggleFav(dev: String, path: String, devName: String) {
        val l = favs().toMutableList()
        val me = Loc(dev, path, if (path == "/") (if (dev == "local") "Main storage" else devName) else baseName(path), if (dev == "local") "" else devName)
        if (!l.removeAll { it.same(me) }) l.add(me)
        saveLocs("ls_fav", l)
    }

    /** Quick folders; the first run (or an unreadable list) gives ui.html's QF0. */
    fun quick(): List<Loc> = locs("ls_qf") ?: listOf("Download", "DCIM", "Movies", "Pictures", "Music", "Documents").map { Loc("local", "/$it", it) }
    fun setQuick(l: List<Loc>) = saveLocs("ls_qf", l)

    fun history(): List<Loc> = locs("ls_his") ?: emptyList()
    /** ui.html histPush: newest first, no duplicates, 40 entries, the phone's root is not recorded. */
    fun histPush(dev: String, path: String) {
        if (dev == "local" && path == "/") return
        val l = history().toMutableList()
        val me = Loc(dev, path, baseName(path))
        if (l.firstOrNull()?.same(me) == true) return
        l.removeAll { it.same(me) }; l.add(0, me)
        while (l.size > 40) l.removeAt(l.size - 1)
        saveLocs("ls_his", l)
    }
    fun removeHistory(dev: String, path: String) { saveLocs("ls_his", history().filterNot { it.dev == dev && it.path == path }) }
    fun clearHistory() { s().edit().remove("ls_his").apply() }
}
