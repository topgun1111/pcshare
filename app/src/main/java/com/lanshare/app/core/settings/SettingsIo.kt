package com.lanshare.app.core.settings

import android.content.Context
import com.lanshare.app.UiScale
import com.lanshare.app.core.Cfg
import com.lanshare.app.core.settings.SettingsSchema.Entry
import com.lanshare.app.core.settings.SettingsSchema.Src
import com.lanshare.app.core.settings.SettingsSchema.T
import org.json.JSONArray
import org.json.JSONObject

/**
 * Settings export / import - the Android part: reads every registered setting from lanshare.json, the SharedPreferences files
 * and the page's localStorage snapshot, and writes them back. Format, registry and compatibility rules: [SettingsSchema].
 * Nothing is written unless [SettingsSchema.parse] accepted the file (all-or-nothing: a refused file changes nothing).
 */
object SettingsIo {
    /** Page keys whose native copy lives in SharedPreferences "ui_prefs" (the page restores them from there when its localStorage is empty). */
    private val WEB_MIRRORED = setOf("ls_g", "ls_pf")
    private const val UI_PREFS = "ui_prefs"

    // ------------------------------------------------------------------ export

    /**
     * [webJson] = {key: string | null} for every key in [SettingsSchema.WEB_KEYS] (read by the page from its localStorage).
     * [secrets] = include saved-server passwords and the Phone Explorer PIN (off by default: the file is plain text).
     */
    fun export(c: Context, webJson: String, secrets: Boolean): String {
        val web = try { JSONObject(webJson) } catch (_: Exception) { JSONObject() }
        val values = HashMap<String, Any?>()
        for (x in SettingsSchema.ALL) values[x.id] = if (x.secret && !secrets && x.type != T.OBJLIST) null else read(c, x, web, secrets)
        val pi = c.packageManager.getPackageInfo(c.packageName, 0)
        @Suppress("DEPRECATION") val code = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        return SettingsSchema.build(values, pi.versionName ?: "?", code, Cfg.name, System.currentTimeMillis(), secrets)
    }

    private fun read(c: Context, x: Entry, web: JSONObject, secrets: Boolean): Any? {
        val raw: Any? = when (x.src) {
            Src.CFG -> Cfg.rawGet(x.key)
            Src.PREF -> { val sp = c.getSharedPreferences(x.file, Context.MODE_PRIVATE); if (sp.contains(x.key)) sp.all[x.key] else null }
            Src.WEB -> {
                val w = if (web.isNull(x.key)) null else web.optString(x.key)
                w ?: if (x.key in WEB_MIRRORED) c.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE).getString(x.key, null)?.takeIf { it.isNotEmpty() } else null
            }
        }
        val v = norm(x, raw) ?: return null
        if (x.src == Src.CFG && x.type == T.OBJLIST && x.secret && !secrets) return stripPasswords(v as JSONArray)
        return v
    }

    /** Stored value -> the JSON type the registry expects; a value of the wrong stored type is left out (= default) instead of exported as garbage. */
    private fun norm(x: Entry, raw: Any?): Any? {
        if (raw == null) return null
        return when (x.type) {
            T.BOOL -> raw as? Boolean
            T.INT -> (raw as? Number)?.takeIf { it.toDouble() == Math.rint(it.toDouble()) }?.toInt()
            T.FLOAT -> (raw as? Number)?.toString()?.toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }
            T.STR -> raw as? String
            T.STRLIST -> (raw as? JSONArray)?.let { JSONArray(it.toString()) }
            T.OBJLIST -> (raw as? JSONArray)?.let { JSONArray(it.toString()) }
        }
    }

    private fun stripPasswords(a: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until a.length()) { val o = a.optJSONObject(i) ?: continue; out.put(JSONObject(o.toString()).apply { remove("password") }) }
        return out
    }

    // ------------------------------------------------------------------ import

    class Applied(val count: Int, /** key -> value or null (remove), for the page's localStorage */ val web: JSONObject, val keptPasswords: Boolean)

    /** [p] must come from [SettingsSchema.parse] (already validated). Applies every setting; returns what the page has to write into its localStorage. */
    fun apply(c: Context, p: SettingsSchema.Parsed): Applied {
        val secrets = p.meta.secrets
        val web = JSONObject()
        var n = 0
        // --- lanshare.json
        val keepName = p.values["cfg/name_custom"] == true   // an automatic name (phone model) belongs to the phone it came from, not to this one
        for (x in SettingsSchema.ALL) if (x.src == Src.CFG && x.id in p.values) {   // only the fields the gate accepted; fields missing from the file keep their current value
            if (x.key == "name" && !keepName) continue
            var v = p.values[x.id]
            if (x.secret && x.type == T.OBJLIST) v = (v as? JSONArray)?.let { mergePasswords(it, Cfg.rawGet(x.key) as? JSONArray, secrets) }
            Cfg.rawPut(x.key, v); n++
        }
        Cfg.save()
        // --- SharedPreferences, one commit per file
        val files = SettingsSchema.ALL.filter { it.src == Src.PREF && it.id in p.values }.groupBy { it.file }
        for ((file, list) in files) {
            val ed = c.getSharedPreferences(file, Context.MODE_PRIVATE).edit()
            for (x in list) {
                if (x.secret && !secrets) continue   // the PIN stays as it is on this phone when the file carries none
                val v = p.values[x.id]
                if (v == null) ed.remove(x.key) else when (x.type) {
                    T.BOOL -> ed.putBoolean(x.key, v as Boolean)
                    T.INT -> ed.putInt(x.key, (v as Number).toInt())
                    T.FLOAT -> ed.putFloat(x.key, (v as Number).toFloat())
                    T.STR -> ed.putString(x.key, v as String)
                    else -> {}
                }
                n++
            }
            ed.commit()
        }
        UiScale.tf = UiScale.text(c) / 100f
        // --- page (localStorage is written by the page itself; its native mirrors are written here)
        val mir = c.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE).edit()
        for (x in SettingsSchema.ALL) if (x.src == Src.WEB && x.id in p.values) {
            val v = p.values[x.id] as String?
            web.put(x.key, v ?: JSONObject.NULL); n++
            if (x.key in WEB_MIRRORED) { if (v.isNullOrEmpty()) mir.remove(x.key) else mir.putString(x.key, v) }
        }
        mir.commit()
        return Applied(n, web, !secrets)
    }

    /** A file without passwords must not wipe the ones saved on this phone: entries with the same id keep their local password. */
    private fun mergePasswords(imp: JSONArray, local: JSONArray?, secrets: Boolean): JSONArray {
        if (secrets) return imp
        val pw = HashMap<String, String>()
        if (local != null) for (i in 0 until local.length()) { val o = local.optJSONObject(i) ?: continue; val id = o.optString("id"); if (id.isNotEmpty()) pw[id] = o.optString("password", "") }
        val out = JSONArray()
        for (i in 0 until imp.length()) {
            val o = imp.optJSONObject(i) ?: continue
            val keep = pw[o.optString("id")]
            if (keep != null) o.put("password", keep)
            out.put(o)
        }
        return out
    }

    /** Summary for the confirm dialog: what is imported, what is skipped (incompatible) and what stays as it is. */
    fun describe(p: SettingsSchema.Parsed): String {
        val m = p.meta
        val d = if (m.created > 0) java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(m.created)) else "?"
        val sb = StringBuilder()
        sb.append("From \"${m.device.ifEmpty { "?" }}\", exported $d by LANShare ${m.versionName}.\n")
        val n = p.values.keys.count { id ->
            val x = SettingsSchema.BY_ID.getValue(id)
            !(x.secret && !m.secrets && x.type != T.OBJLIST) && !(id == "cfg/name" && p.values["cfg/name_custom"] != true)   // left alone by apply(): PIN without passwords, an automatic device name
        }
        sb.append("$n setting${if (n == 1) "" else "s"} will be imported.\n")
        if (p.skipped.isNotEmpty()) sb.append("\n\u26a0 ${p.skipped.size} not compatible with this version, skipped:\n").append(list(p.skipped.map { it.label + " (" + it.reason + ")" }))
        if (p.untouched.isNotEmpty()) sb.append("\n${p.untouched.size} not in the file, unchanged on this phone:\n").append(list(p.untouched))
        sb.append("\n").append(if (m.secrets) "The file contains passwords and the Phone Explorer PIN." else "Passwords and the Phone Explorer PIN are not in the file: the ones saved on this phone stay.")
        sb.append("\n\nThe settings listed as imported replace the current ones and the app restarts its screen.")
        return sb.toString()
    }

    private fun list(l: List<String>): String = if (l.size <= 8) l.joinToString("\n") { "\u2022 $it" } + "\n" else l.take(8).joinToString("\n") { "\u2022 $it" } + "\n\u2022 and ${l.size - 8} more\n"
}
