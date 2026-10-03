package com.lanshare.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** <filesDir>/lanshare.json - same file and format the Python version used, so device id and SMB list survive the switch. */
object Cfg {
    private var file: File? = null
    /** Folder that holds lanshare.json (app filesDir); caches live below it. */
    val dir: File? @Synchronized get() = file?.absoluteFile?.parentFile
    private var obj = JSONObject()

    val id: String @Synchronized get() = obj.getString("id")
    var name: String
        @Synchronized get() = obj.optString("name", "")
        @Synchronized set(v) { obj.put("name", v) }
    var nameCustom: Boolean
        @Synchronized get() = obj.optBoolean("name_custom", false)
        @Synchronized set(v) { obj.put("name_custom", v) }

    @Synchronized fun smb(): JSONArray = obj.optJSONArray("smb") ?: JSONArray()
    @Synchronized fun setSmb(a: JSONArray) { obj.put("smb", a) }

    @Synchronized fun load(f: File, phoneModel: String, hostName: String) {
        file = f
        try { obj = JSONObject(f.readText()) } catch (_: Exception) { obj = JSONObject() }
        if (!obj.has("id")) obj.put("id", UUID.randomUUID().toString().replace("-", "").take(8))
        obj.remove("pin"); obj.remove("paired"); obj.remove("backup_ips")
        if (!obj.has("smb")) obj.put("smb", JSONArray())
        val fallback = if (hostName != "" && hostName != "localhost") hostName else "Phone-" + id.take(4)
        val cur = obj.optString("name", "")
        // use the phone model unless the user picked a name themselves (an old auto name is replaced)
        if (!nameCustom && (cur.isEmpty() || cur == fallback || cur.startsWith("Phone-")))
            obj.put("name", phoneModel.ifEmpty { cur }.ifEmpty { fallback })
        save()
    }

    @Synchronized fun save() {
        try { file?.writeText(obj.toString()) } catch (_: Exception) {}
    }
}
