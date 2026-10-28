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

    /** Saved FTP / FTPS servers (see FtpFs.kt): [{id:"ftp:xxxxxx", host, user, password, name, ftps, dir}]. */
    @Synchronized fun ftp(): JSONArray = obj.optJSONArray("ftp") ?: JSONArray()
    @Synchronized fun setFtp(a: JSONArray) { obj.put("ftp", a) }

    /** Saved Google Drive accounts (see Gdrive.kt): [{id:"gd:xxxxxx", email, name}]. No tokens are stored. */
    @Synchronized fun gdrive(): JSONArray = obj.optJSONArray("gdrive") ?: JSONArray()
    @Synchronized fun setGdrive(a: JSONArray) { obj.put("gdrive", a) }

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

    /** Universal search (chapter 16 of HANDOVER.md): name index on / off (default on). */
    @Synchronized fun usOn(): Boolean = obj.optBoolean("us_on", true)
    @Synchronized fun setUsOn(v: Boolean) { obj.put("us_on", v); save() }
    /** Only index while charging (default off; below 20 % battery the work waits anyway). */
    @Synchronized fun usCharge(): Boolean = obj.optBoolean("us_charge", false)
    @Synchronized fun setUsCharge(v: Boolean) { obj.put("us_charge", v); save() }
    /** Text-file content index (Part 5): on / off, max size of one file (MB), total budget of stored text (MB), extension list (comma separated; empty = defaults). */
    @Synchronized fun usContent(): Boolean = obj.optBoolean("us_c_on", true)
    @Synchronized fun setUsContent(v: Boolean) { obj.put("us_c_on", v); save() }
    @Synchronized fun usContentMaxMb(): Int = obj.optInt("us_c_max", 5).coerceIn(1, 50)
    @Synchronized fun usContentBudgetMb(): Int = obj.optInt("us_c_budget", 300).coerceIn(20, 5000)
    @Synchronized fun setUsContentLimits(maxMb: Int, budgetMb: Int) { obj.put("us_c_max", maxMb.coerceIn(1, 50)); obj.put("us_c_budget", budgetMb.coerceIn(20, 5000)); save() }
    @Synchronized fun usContentExtsText(): String = obj.optString("us_c_ext", "")
    @Synchronized fun setUsContentExts(v: String) { obj.put("us_c_ext", v.trim()); save() }
    fun usContentExts(): Set<String> {
        val l = usContentExtsText().split(',', ';', ' ').map { it.trim().trimStart('.').lowercase() }.filter { it.isNotEmpty() }.toSet()
        return if (l.isEmpty()) com.lanshare.app.core.usearch.TextExtractor.DEFAULT_EXTS + com.lanshare.app.core.office.DocExtractor.EXTS else l
    }
    /** Folders (virtual paths below the storage root) the name index is limited to; empty = whole phone (minus excluded). */
    /** D10: picture sections (ocr: / ai:) in Universal Search; AI similarity threshold (default = ImgSearch.THRESHOLD). */
    @Synchronized fun usImg(): Boolean = obj.optBoolean("us_img", true)
    @Synchronized fun setUsImg(v: Boolean) { obj.put("us_img", v); save() }
    @Synchronized fun usAiThreshold(): Float = com.lanshare.app.core.usearch.ImgQuery.clampThreshold(obj.optDouble("us_ai_th", 0.25).toFloat())
    @Synchronized fun setUsAiThreshold(v: Float) { obj.put("us_ai_th", com.lanshare.app.core.usearch.ImgQuery.clampThreshold(v).toDouble()); save() }
    @Synchronized fun usIncluded(): List<String> { val a = obj.optJSONArray("us_incl") ?: return emptyList(); return (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } }
    @Synchronized fun setUsIncluded(l: List<String>) { obj.put("us_incl", JSONArray(l)); save() }
    /** Folders (virtual paths) the name index leaves out with everything below. */
    @Synchronized fun usExcluded(): List<String> { val a = obj.optJSONArray("us_excl") ?: return emptyList(); return (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } }
    @Synchronized fun setUsExcluded(l: List<String>) { obj.put("us_excl", JSONArray(l)); save() }

    /** Root support (su, see RootShell / RootFs): off until the user switches it on in Settings > Root access. */
    @Synchronized fun rootEnabled(): Boolean = obj.optBoolean("root_on", false)
    @Synchronized fun setRootEnabled(v: Boolean) { obj.put("root_on", v); save() }

    /** Text sharing: the device that "Send clipboard" (Quick Settings tile / notification), the share-sheet entry and the inbox send to without asking (peer id; "" = ask / the only online device). */
    @Synchronized fun defTarget(): String = obj.optString("def_target", "")
    @Synchronized fun setDefTarget(v: String) { obj.put("def_target", v); save() }
    /** Text that arrives from another device is put on this phone's clipboard at once (default off). Also the opt-in for silent clipboard-sync pushes. */
    @Synchronized fun autoCopy(): Boolean = obj.optBoolean("auto_copy", false)
    @Synchronized fun setAutoCopy(v: Boolean) { obj.put("auto_copy", v); save() }
    /** Copying text while a LANShare screen is open also sends it to the default device (default off). */
    @Synchronized fun clipSync(): Boolean = obj.optBoolean("clip_sync", false)
    @Synchronized fun setClipSync(v: Boolean) { obj.put("clip_sync", v); save() }

    /** Wi-Fi printers this device has found (kept across restarts / sleep, so they are still offered when mDNS is silent with the screen off). */
    @Synchronized fun printerCache(): JSONArray = obj.optJSONArray("wifi_printers") ?: JSONArray()
    @Synchronized fun setPrinterCache(a: JSONArray) { obj.put("wifi_printers", a); save() }

    /** LANShare devices this one has seen (kept across restarts, so the Devices list is filled at once; liveLoop then verifies them). */
    @Synchronized fun peerCache(): JSONArray = obj.optJSONArray("peer_cache") ?: JSONArray()
    @Synchronized fun setPeerCache(a: JSONArray) { obj.put("peer_cache", a); save() }

    @Synchronized fun printVerbose(): Boolean = obj.optBoolean("print_verbose", true)
    @Synchronized fun setPrintVerbose(v: Boolean) { obj.put("print_verbose", v); save() }

    /** Raw value of a config key (settings export); null when missing. */
    @Synchronized fun rawGet(key: String): Any? = if (obj.has(key) && !obj.isNull(key)) obj.opt(key) else null
    /** Raw write of a config key (settings import); null removes it. The caller calls [save]. */
    @Synchronized fun rawPut(key: String, v: Any?) { if (v == null) obj.remove(key) else obj.put(key, v) }

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
