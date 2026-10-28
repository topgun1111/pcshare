package com.lanshare.app.core.settings

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Settings export / import - the pure part (no Android classes, so it can be compiled and tested on a plain JVM).
 *
 * WHAT IS A SETTING: every choice the user can make, from the smallest (editor "Keep screen on") to the main ones (saved servers,
 * search index, interface size, theme). NOT settings (never exported): device id, caches (peer / printer lists, indexes), reading
 * positions, history, inbox texts, albums, update-check state. See HANDOVER.md chapter 17.
 *
 * REGISTRY: [ALL] lists EVERY exported setting exactly once. Adding a setting to the app = add a line here, bump [SCHEMA]
 * (otherwise old exports would silently look compatible only by key comparison - the key comparison below still refuses them).
 *
 * COMPATIBILITY RULE (owner, 2026-10-28): a file is imported ONLY when it is fully compatible with this app version, else it is
 * refused with a warning and nothing is changed. "Fully compatible" = schema between [MIN_COMPAT] and [SCHEMA], same format,
 * intact checksum, and EXACTLY the same set of setting ids as this version knows (no missing ones = file made by an older version
 * before those settings existed, no unknown ones = file made by a newer version), and every value has the right type / range.
 */
object SettingsSchema {
    const val FORMAT = "lanshare-settings"
    /** Version of the export layout + meaning of the settings. Bump when a setting is added, removed, renamed or changes meaning. */
    const val SCHEMA = 1
    /** Oldest schema whose files this version still imports (raise it to SCHEMA when an old value would be misread). */
    const val MIN_COMPAT = 1
    const val MAX_VALUE_CHARS = 1_500_000
    const val MAX_FILE_CHARS = 8_000_000

    enum class T { BOOL, INT, FLOAT, STR, STRLIST, OBJLIST }
    /** CFG = <filesDir>/lanshare.json, PREF = a SharedPreferences file, WEB = a localStorage key of the page (raw string). */
    enum class Src { CFG, PREF, WEB }

    class Entry(
        val src: Src, val file: String, val key: String, val type: T, val label: String,
        val secret: Boolean = false, val min: Double? = null, val max: Double? = null, val allowed: Set<String>? = null,
    ) {
        val id: String = when (src) { Src.CFG -> "cfg/$key"; Src.PREF -> "pref/$file/$key"; Src.WEB -> "web/$key" }
    }

    private val list = ArrayList<Entry>()
    private fun cfg(key: String, t: T, label: String, secret: Boolean = false, min: Double? = null, max: Double? = null) { list.add(Entry(Src.CFG, "", key, t, label, secret, min, max)) }
    private fun pref(file: String, key: String, t: T, label: String, secret: Boolean = false, min: Double? = null, max: Double? = null, allowed: Set<String>? = null) { list.add(Entry(Src.PREF, file, key, t, label, secret, min, max, allowed)) }
    private fun web(key: String, label: String) { list.add(Entry(Src.WEB, "", key, T.STR, label)) }

    init {
        // ---- main: devices, servers, search, pictures (lanshare.json)
        cfg("name", T.STR, "Device name"); cfg("name_custom", T.BOOL, "Device name chosen by hand")
        cfg("smb", T.OBJLIST, "Saved PCs (SMB)", secret = true); cfg("ftp", T.OBJLIST, "Saved FTP / SFTP servers", secret = true)
        cfg("pins", T.STRLIST, "Remote device IPs")
        cfg("img_excl", T.STRLIST, "Picture scan: excluded folders"); cfg("img_incl", T.STRLIST, "Picture scan: only these folders")
        cfg("img_auto", T.BOOL, "Picture scan runs by itself"); cfg("img_auto_chg", T.BOOL, "Picture scan only while charging")
        cfg("us_on", T.BOOL, "Search index on"); cfg("us_charge", T.BOOL, "Search index only while charging")
        cfg("us_c_on", T.BOOL, "Search inside text files"); cfg("us_c_max", T.INT, "Search: max file size (MB)", min = 1.0, max = 50.0)
        cfg("us_c_budget", T.INT, "Search: text budget (MB)", min = 20.0, max = 5000.0); cfg("us_c_ext", T.STR, "Search: file types")
        cfg("us_img", T.BOOL, "Search in pictures"); cfg("us_ai_th", T.FLOAT, "Search: picture similarity", min = 0.0, max = 1.0)
        cfg("us_incl", T.STRLIST, "Search index: only these folders"); cfg("us_excl", T.STRLIST, "Search index: excluded folders")
        cfg("root_on", T.BOOL, "Root access")
        cfg("def_target", T.STR, "Default device for text sharing"); cfg("auto_copy", T.BOOL, "Copy received text automatically")
        cfg("clip_sync", T.BOOL, "Clipboard sync"); cfg("print_verbose", T.BOOL, "Detailed print log")
        // ---- interface
        pref("ls_ui", "iface", T.INT, "Interface size", min = 80.0, max = 160.0); pref("ls_ui", "text", T.INT, "Text size", min = 80.0, max = 160.0)
        pref("ls_ui", "font", T.STR, "App font")
        pref("ops_overlay", "corner", T.INT, "Job bubble corner (native screens)", min = 0.0, max = 3.0)
        // ---- viewers and editors
        pref("viewer", "ocr_auto", T.INT, "Text recognition: when", min = 0.0, max = 2.0); pref("viewer", "ocr_engine", T.INT, "Text recognition: model", min = 0.0, max = 2.0)
        pref("office_view", "mode", T.STR, "Office view mode", allowed = setOf("auto", "exact", "simple"))
        pref("office_read", "on", T.BOOL, "Office: reading mode"); pref("office_read", "print", T.BOOL, "Office: print layout")
        pref("office_read", "px", T.INT, "Office: reading text size", min = 12.0, max = 32.0); pref("office_read", "theme", T.INT, "Office: reading theme", min = 0.0, max = 2.0)
        pref("ls_pdf_set", "night", T.BOOL, "PDF night mode"); pref("ls_player", "fullscreen", T.BOOL, "Video player full screen")
        pref("editor", "color", T.INT, "Text editor: color scheme", min = 0.0, max = 99.0); pref("editor", "size", T.FLOAT, "Text editor: text size", min = 1.0, max = 200.0)
        pref("editor", "mono", T.BOOL, "Text editor: monospace"); pref("editor", "wrap", T.BOOL, "Text editor: word wrap")
        pref("editor", "awake", T.BOOL, "Text editor: keep screen on"); pref("editor", "lines", T.BOOL, "Text editor: line numbers")
        pref("ls_print", "layout", T.INT, "Phone print: pictures per page", min = 0.0, max = 3.0)
        // ---- storage analyzer, search screen
        pref("storage_ui", "wiz", T.BOOL, "Storage analyzer: tree view"); pref("storage_ui", "wiztheme", T.STR, "Storage analyzer: tree colors")
        pref("us_ui", "sort", T.STR, "Search: sort"); pref("us_ui", "rev", T.BOOL, "Search: reverse order"); pref("us_ui", "ff", T.BOOL, "Search: folders first")
        pref("us_ui", "case", T.BOOL, "Search: match case"); pref("us_ui", "word", T.BOOL, "Search: whole words"); pref("us_ui", "path", T.BOOL, "Search: match path")
        pref("us_ui", "diac", T.BOOL, "Search: match accents"); pref("us_ui", "re", T.BOOL, "Search: regular expressions"); pref("us_ui", "every2", T.BOOL, "Search everywhere")
        pref("us_ui", "vmode", T.STR, "Search: result view", allowed = setOf("list", "compact", "grid")); pref("us_ui", "vthumb", T.STR, "Search: thumbnail size")
        pref("us_ui", "vcols", T.INT, "Search: grid columns", min = 0.0, max = 12.0); pref("us_ui", "saved", T.STR, "Search: saved searches")
        // ---- Phone Explorer (FTP share)
        pref("lanshare_pe", "pin", T.STR, "Phone Explorer PIN", secret = true); pref("lanshare_pe", "port", T.INT, "Phone Explorer port", min = 1024.0, max = 65535.0)
        pref("lanshare_pe", "readonly", T.BOOL, "Phone Explorer read-only"); pref("lanshare_pe", "tls", T.BOOL, "Phone Explorer FTPS")
        pref("lanshare_pe", "tls_req", T.BOOL, "Phone Explorer FTPS required"); pref("lanshare_pe", "autostart", T.BOOL, "Phone Explorer starts with the app")
        pref("lanshare_pe", "whitelist", T.STR, "Phone Explorer shared folders")
        // ---- the page (localStorage of ui.html; values are raw strings, many hold JSON)
        web("ls_sort", "Files: sort"); web("ls_asc", "Files: sort direction"); web("ls_view", "Files: view"); web("ls_theme", "Theme"); web("ls_hidden", "Show hidden files")
        web("ls_g", "Files: view for all folders"); web("ls_pf", "Files: view per folder"); web("ls_sortmig", "Files: sort migration done")
        web("ls_galsort", "Gallery: sort"); web("ls_galpc", "Gallery: columns"); web("ls_galhide", "Gallery: hide"); web("ls_galhid", "Gallery: hidden albums")
        web("ls_fsty", "Folder styles"); web("ls_qf", "Quick folders"); web("ls_dual", "Two-pane mode")
        web("ls_ismode", "Image search: mode"); web("ls_iswhole", "Image search: whole phone")
        web("ls_ps", "Print space on"); web("ls_ps_lay", "Print space: last layout"); web("ls_ps_presets", "Print space: presets")
        web("ls_nl", "Native list"); web("ls_nls", "Native sheets"); web("ls_nlq", "Native search bar"); web("ls_nlc", "Native top bar"); web("ls_nld", "Native drawer"); web("ls_nlh", "Native header")
        web("printTo", "Print: target PC"); web("printOpts", "Print: options"); web("printOptsBy", "Print: options per type"); web("printOptsLast", "Print: last options")
        web("jbc", "Job bubble corner (page)")
    }

    val ALL: List<Entry> = list
    val BY_ID: Map<String, Entry> = ALL.associateBy { it.id }
    val WEB_KEYS: List<String> = ALL.filter { it.src == Src.WEB }.map { it.key }

    init { check(BY_ID.size == ALL.size) { "duplicate setting id in SettingsSchema" } }

    class Meta(val schema: Int, val versionName: String, val versionCode: Long, val device: String, val created: Long, val secrets: Boolean)
    class Parsed(val meta: Meta, val values: Map<String, Any?>)

    /** kind: NOT_SETTINGS, DAMAGED, NEWER, OLDER, INVALID. reasons: plain sentences for the warning dialog. */
    sealed class Result {
        class Ok(val parsed: Parsed) : Result()
        class Refused(val kind: String, val reasons: List<String>) : Result()
    }

    // ------------------------------------------------------------------ build

    /** [values]: setting id -> value (Boolean / Int / Double / String / JSONArray) or null when the setting was never changed (= app default). Unknown ids are an error. */
    fun build(values: Map<String, Any?>, versionName: String, versionCode: Long, device: String, created: Long, secrets: Boolean): String {
        for (k in values.keys) require(k in BY_ID) { "unknown setting id $k" }
        val e = JSONObject()
        for (x in ALL) e.put(x.id, wrap(values[x.id]))
        val o = JSONObject()
        o.put("format", FORMAT); o.put("schema", SCHEMA)
        o.put("app", JSONObject().put("versionName", versionName).put("versionCode", versionCode))
        o.put("device", device); o.put("created", created); o.put("secrets", secrets); o.put("count", ALL.size)
        o.put("entries", e)
        o.put("checksum", checksum(e))
        return o.toString(1)
    }

    private fun wrap(v: Any?): Any = v ?: JSONObject.NULL

    // ------------------------------------------------------------------ check / parse

    fun parse(text: String): Result {
        if (text.length > MAX_FILE_CHARS) return Result.Refused("NOT_SETTINGS", listOf("The file is too large to be a settings export."))
        val o = try { JSONObject(text) } catch (_: Exception) { return Result.Refused("NOT_SETTINGS", listOf("This file is not a LANShare settings export.")) }
        if (o.optString("format") != FORMAT || !o.has("schema") || o.optJSONObject("entries") == null)
            return Result.Refused("NOT_SETTINGS", listOf("This file is not a LANShare settings export."))
        val schema = o.optInt("schema", -1)
        val app = o.optJSONObject("app")
        val ver = app?.optString("versionName")?.takeIf { it.isNotEmpty() } ?: "unknown version"
        val e = o.getJSONObject("entries")
        if (o.optString("checksum") != checksum(e))
            return Result.Refused("DAMAGED", listOf("The file is damaged or was edited by hand (checksum does not match). Export the settings again."))

        val reasons = ArrayList<String>(); var kind = ""
        fun refuse(k: String, r: String) { if (kind.isEmpty()) kind = k; reasons.add(r) }

        if (schema > SCHEMA) refuse("NEWER", "The file was made by a newer LANShare ($ver). This app does not know all of its settings. Update this app, then import again.")
        else if (schema < MIN_COMPAT) refuse("OLDER", "The file was made by an older LANShare ($ver) whose settings are not compatible with this version.")

        val fileIds = HashSet<String>(); val ki = e.keys(); while (ki.hasNext()) fileIds.add(ki.next())
        val missing = ALL.map { it.id }.filter { it !in fileIds }
        val unknown = fileIds.filter { it !in BY_ID }.sorted()
        if (missing.isNotEmpty()) refuse("OLDER", "The file comes from an older LANShare ($ver) and does not contain ${missing.size} setting${if (missing.size == 1) "" else "s"} that this version has: ${names(missing.map { BY_ID.getValue(it).label })}. Update LANShare on the device the file came from, export again, then import.")
        if (unknown.isNotEmpty()) refuse("NEWER", "The file has ${unknown.size} setting${if (unknown.size == 1) "" else "s"} this version does not know (${names(unknown.map { it.substringAfterLast('/') })}). It was made by a different LANShare version ($ver).")

        val vals = HashMap<String, Any?>()
        if (missing.isEmpty() && unknown.isEmpty()) for (x in ALL) {
            val raw = e.get(x.id)
            val v: Any? = if (raw === JSONObject.NULL) null else raw
            val bad = validate(x, v)
            if (bad != null) refuse("INVALID", "${x.label}: $bad") else vals[x.id] = v
        }
        if (reasons.isNotEmpty()) return Result.Refused(kind, reasons)
        val meta = Meta(schema, ver, app?.optLong("versionCode", 0L) ?: 0L, o.optString("device"), o.optLong("created", 0L), o.optBoolean("secrets", false))
        return Result.Ok(Parsed(meta, vals))
    }

    private fun names(l: List<String>): String = if (l.size <= 6) l.joinToString(", ") else l.take(6).joinToString(", ") + " and ${l.size - 6} more"

    /** null = fine. */
    fun validate(x: Entry, v: Any?): String? {
        if (v == null) return null
        fun num(): Double? = (v as? Number)?.toDouble()
        when (x.type) {
            T.BOOL -> if (v !is Boolean) return "expected on / off"
            T.INT -> { val d = num() ?: return "expected a whole number"; if (d != Math.rint(d) || v is Double && d.isNaN()) return "expected a whole number"
                if (x.min != null && d < x.min || x.max != null && d > x.max) return "value out of range" }
            T.FLOAT -> { val d = num() ?: return "expected a number"; if (d.isNaN() || d.isInfinite()) return "expected a number"
                if (x.min != null && d < x.min || x.max != null && d > x.max) return "value out of range" }
            T.STR -> { if (v !is String) return "expected text"; if (v.length > MAX_VALUE_CHARS) return "value too long"; if (x.allowed != null && v !in x.allowed) return "unknown option" }
            T.STRLIST -> { if (v !is JSONArray) return "expected a list"; for (i in 0 until v.length()) if (v.opt(i) !is String) return "expected a list of text" }
            T.OBJLIST -> { if (v !is JSONArray) return "expected a list"; for (i in 0 until v.length()) if (v.opt(i) !is JSONObject) return "expected a list of entries" }
        }
        return null
    }

    // ------------------------------------------------------------------ checksum (integrity only - not a signature)

    fun checksum(entries: JSONObject): String {
        val ids = ArrayList<String>(); val ki = entries.keys(); while (ki.hasNext()) ids.add(ki.next())
        ids.sort()
        val sb = StringBuilder()
        for (id in ids) sb.append(id).append('=').append(canon(entries.get(id))).append('\n')
        val d = MessageDigest.getInstance("SHA-256").digest(sb.toString().toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    /** JSON writers drop a trailing ".0" (16.0 is written as 16 and read back as an Int), so every integral number gets the same text. */
    private fun canonNum(d: Double): String = if (d == Math.rint(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()

    /** Same text for the same content, whatever key order the JSON library keeps. */
    private fun canon(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> { val ks = ArrayList<String>(); val ki = v.keys(); while (ki.hasNext()) ks.add(ki.next()); ks.sort(); ks.joinToString(",", "{", "}") { k -> JSONObject.quote(k) + ":" + canon(v.get(k)) } }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canon(v.get(it)) }
        is String -> JSONObject.quote(v)
        is Boolean -> v.toString()
        is Int, is Long -> v.toString()
        is Number -> canonNum(v.toString().toDoubleOrNull() ?: v.toDouble())   // Double, Float, and BigDecimal (the JVM org.json reads decimals as BigDecimal)
        else -> JSONObject.quote(v.toString())
    }
}
