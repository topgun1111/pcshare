package com.lanshare.app.core

import org.json.JSONObject
import java.io.File

/**
 * Registry of unfinished copies ("name.ext.lspart" next to the target) and of copy jobs that stopped with something left to do.
 *
 * A partial file may only be continued when it belongs to the SAME version of the source: [tag] = "size:mtime" of the source file.
 * The tag lives here (filesDir/partials.json), not in a sidecar file, so no extra files clutter the user's folders.
 * Keys: absolute path of the .lspart for this phone, "smb|<device id>|<share path>" for SMB shares.
 * Entries older than [MAX_DAYS] are dropped on start (and their .lspart on this phone is deleted).
 */
object Partials {
    const val MAX_DAYS = 7
    private var file: File? = null
    private val tags = LinkedHashMap<String, Pair<String, Long>>()   // key -> (tag, last touched ms)
    private val specs = LinkedHashMap<String, JSONObject>()          // resume id -> spec of an interrupted copy / move job (see Jobs.ResumeSpec)

    @Synchronized fun init(dir: File) {
        file = File(dir, "partials.json")
        tags.clear(); specs.clear()
        try {
            val f = file!!
            val o = JSONObject(f.readText())
            o.optJSONObject("tags")?.let { t -> for (k in t.keys()) { val e = t.getJSONObject(k); tags[k] = e.getString("tag") to e.optLong("t") } }
            o.optJSONObject("jobs")?.let { j -> for (k in j.keys()) specs[k] = j.getJSONObject(k) }
        } catch (_: Exception) {}
    }

    private fun save() {
        val f = file ?: return
        try {
            val t = JSONObject(); for ((k, v) in tags) t.put(k, JSONObject().put("tag", v.first).put("t", v.second))
            val j = JSONObject(); for ((k, v) in specs) j.put(k, v)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(JSONObject().put("tags", t).put("jobs", j).toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (_: Exception) {}
    }

    @Synchronized fun put(key: String, tag: String) { tags[key] = tag to System.currentTimeMillis(); save() }
    @Synchronized fun matches(key: String, tag: String) = tags[key]?.first == tag
    @Synchronized fun tagOf(key: String): String? = tags[key]?.first
    @Synchronized fun forget(key: String) { if (tags.remove(key) != null) save() }

    // ---- interrupted jobs (copy / move that ended with an error and still have files to finish)
    @Synchronized fun putJob(id: String, spec: JSONObject) {
        spec.put("t", System.currentTimeMillis())
        specs.remove(id); specs[id] = spec
        while (specs.size > 20) specs.remove(specs.keys.first())
        save()
    }
    @Synchronized fun job(id: String): JSONObject? = specs[id]
    @Synchronized fun dropJob(id: String) { if (specs.remove(id) != null) save() }
    @Synchronized fun jobs(): List<Pair<String, JSONObject>> = specs.entries.map { it.key to it.value }.reversed()

    /** On start: forget (and delete, on this phone) what is older than [MAX_DAYS]; also drops registry entries whose local file is gone. */
    @Synchronized fun purge() {
        val now = System.currentTimeMillis()
        val old = now - MAX_DAYS * 86_400_000L
        var changed = false
        val it = tags.entries.iterator()
        while (it.hasNext()) {
            val (k, v) = it.next()
            val local = k.startsWith("/")
            val gone = local && !File(k).exists()
            if (v.second < old || gone) {
                if (local && !gone) try { File(k).delete() } catch (_: Exception) {}
                it.remove(); changed = true
            }
        }
        val j = specs.entries.iterator()
        while (j.hasNext()) if (j.next().value.optLong("t") < old) { j.remove(); changed = true }
        if (changed) save()
    }
}
