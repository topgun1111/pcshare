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

    /** Remote (tailnet / other-subnet) device IPs that are re-probed on every scan; filled by "+ IP" and by Tailscale addresses peers advertise. */
    @Synchronized fun pins(): List<String> { val a = obj.optJSONArray("pins") ?: return emptyList(); return (0 until a.length()).map { a.optString(it) } }
    @Synchronized fun addPin(ip: String): Boolean {
        val cur = pins()
        if (ip in cur || cur.size >= 64) return false
        obj.put("pins", JSONArray(cur + ip)); save(); return true
    }

    /** Folders (virtual paths below the storage root, e.g. "/WhatsApp/Media") that the picture scan skips, with everything below them. */
    @Synchronized fun imgExcluded(): List<String> { val a = obj.optJSONArray("img_excl") ?: return emptyList(); return (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } }
    @Synchronized fun setImgExcluded(l: List<String>) { obj.put("img_excl", JSONArray(l)); save() }

    /** Folders (virtual paths) the picture scan is limited to; empty = scan everything (minus excluded). */
    @Synchronized fun imgIncluded(): List<String> { val a = obj.optJSONArray("img_incl") ?: return emptyList(); return (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } }
    @Synchronized fun setImgIncluded(l: List<String>) { obj.put("img_incl", JSONArray(l)); save() }

    /** Picture scan runs by itself when new pictures show up (default on; only once a first scan has been done). */
    @Synchronized fun imgAuto(): Boolean = obj.optBoolean("img_auto", true)
    @Synchronized fun setImgAuto(v: Boolean) { obj.put("img_auto", v); save() }
    /** Automatic picture scans only while the phone is charging (default off). */
    @Synchronized fun imgAutoCharging(): Boolean = obj.optBoolean("img_auto_chg", false)
    @Synchronized fun setImgAutoCharging(v: Boolean) { obj.put("img_auto_chg", v); save() }

    /** Wi-Fi printers this device has found (kept across restarts / sleep, so they are still offered when mDNS is silent with the screen off). */
    @Synchronized fun printerCache(): JSONArray = obj.optJSONArray("wifi_printers") ?: JSONArray()
    @Synchronized fun setPrinterCache(a: JSONArray) { obj.put("wifi_printers", a); save() }

    /** LANShare devices this one has seen (kept across restarts, so the Devices list is filled at once; liveLoop then verifies them). */
    @Synchronized fun peerCache(): JSONArray = obj.optJSONArray("peer_cache") ?: JSONArray()
    @Synchronized fun setPeerCache(a: JSONArray) { obj.put("peer_cache", a); save() }

    @Synchronized fun load(f: File, phoneModel: String, hostName: String) {
        file = f
        obj = readOr(f) ?: readOr(File(f.path + ".bak")) ?: JSONObject()   // a torn main file falls back to the last good copy
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

    private fun readOr(f: File): JSONObject? = try { if (f.isFile) JSONObject(f.readText()).takeIf { it.has("id") } else null } catch (_: Exception) { null }

    /** Write to a temp file, then rename: a crash or a killed process mid-write can no longer destroy the device id and the SMB logins. */
    @Synchronized fun save() {
        val f = file ?: return
        try {
            val tmp = File(f.path + ".tmp")
            tmp.writeText(obj.toString())
            if (f.isFile) try { f.copyTo(File(f.path + ".bak"), overwrite = true) } catch (_: Exception) {}
            if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) f.writeText(obj.toString()) }
        } catch (_: Exception) {}
    }
}
