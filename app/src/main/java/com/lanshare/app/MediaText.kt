package com.lanshare.app

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Native twin of the web UI's Details sheet (assets/ui.html, doDetails): turns the media fields produced by core/Media.kt
 * (container, tracks, tags, date taken) into plain text, and fetches them for a file that the viewers only know by URL.
 * Used by the picture viewer, the video player and the storage analyser.
 */
object MediaText {
    /** GET /api/stat for an item url of the app's own server (".../api/file?dev=..&path=.."). Blocking: call from a worker thread. */
    fun fetchStat(itemUrl: String): JSONObject? = try {
        val u = android.net.Uri.parse(itemUrl)
        val dev = u.getQueryParameter("dev") ?: "local"
        val path = u.getQueryParameter("path") ?: ""
        val url = itemUrl.substringBefore("/api/") + "/api/stat?dev=" + android.net.Uri.encode(dev) + "&path=" + android.net.Uri.encode(path)
        val c = URL(com.lanshare.app.core.RootShell.sign(url)).openConnection() as HttpURLConnection
        c.connectTimeout = 3000; c.readTimeout = 15000
        JSONObject(c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8))
    } catch (_: Throwable) { null }

    /** Media fields of a file on this phone, read directly (no HTTP). Blocking. */
    fun local(f: File): JSONObject? = try { com.lanshare.app.core.Media.info(f, f.name) } catch (_: Throwable) { null }

    private fun br(n: Long) = if (n >= 1_000_000) String.format(Locale.US, "%.2f", n / 1e6).trimEnd('0').trimEnd('.') + " Mb/s" else (n / 1000).toString() + " kb/s"
    private fun ch(n: Int) = when (n) { 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "$n channels" }

    /** "Date taken", Container, one block per track and Tags; empty string when the file has none of them. */
    fun format(o: JSONObject?): String {
        if (o == null) return ""
        val sb = StringBuilder()
        fun sec(t: String) { if (sb.isNotEmpty()) sb.append('\n'); sb.append('\n').append(t.uppercase(Locale.getDefault())).append('\n') }
        fun row(k: String, v: String?) { if (!v.isNullOrBlank()) sb.append(k).append(": ").append(v).append('\n') }
        val taken = o.optString("taken")
        if (taken.isNotEmpty()) { sb.append('\n'); row("Date taken", taken) }
        o.optJSONObject("container")?.let { c ->
            val fmt = c.optString("fmt"); val mime = c.optString("mime")
            sec("Container")
            row("Format", if (fmt.isNotEmpty() && mime.isNotEmpty() && fmt != mime) "$fmt ($mime)" else fmt.ifEmpty { mime })
            if (c.optLong("bitrate") > 0) row("Overall rate", br(c.optLong("bitrate")))
        }
        o.optJSONArray("tracks")?.let { ts ->
            val cnt = HashMap<String, Int>(); val seen = HashMap<String, Int>()
            for (i in 0 until ts.length()) { val k = ts.getJSONObject(i).optString("kind"); cnt[k] = (cnt[k] ?: 0) + 1 }
            val names = mapOf("video" to "Video", "audio" to "Audio", "text" to "Subtitles", "other" to "Other track")
            for (i in 0 until ts.length()) {
                val t = ts.getJSONObject(i); val k = t.optString("kind")
                seen[k] = (seen[k] ?: 0) + 1
                sec((names[k] ?: "Track") + if ((cnt[k] ?: 0) > 1) " " + seen[k] else "")
                row("Codec", t.optString("codec").ifEmpty { t.optString("mime") } + (t.optString("profile").let { if (it.isNotEmpty()) " · $it" else "" }))
                if (k == "video") {
                    if (t.optInt("w") > 0 && t.optInt("h") > 0) row("Resolution", "${t.optInt("w")} × ${t.optInt("h")}")
                    if (t.optDouble("fps") > 0) row("Frame rate", (Math.round(t.optDouble("fps") * 1000) / 1000.0).toString().removeSuffix(".0") + " fps")
                    row("HDR", t.optString("hdr"))
                }
                if (k == "audio") {
                    if (t.optInt("ch") > 0) row("Channels", ch(t.optInt("ch")))
                    if (t.optInt("rate") > 0) row("Sample rate", (Math.round(t.optInt("rate") / 10.0) / 100.0).toString().removeSuffix(".0") + " kHz")
                }
                if (t.optLong("bitrate") > 0) row("Bit rate", br(t.optLong("bitrate")))
                row("Language", t.optString("lang"))
            }
        }
        o.optJSONObject("tags")?.let { g ->
            val keys = listOf("title" to "Title", "artist" to "Artist", "albumartist" to "Album artist", "album" to "Album", "track" to "Track",
                "genre" to "Genre", "year" to "Year", "composer" to "Composer", "author" to "Author")
            if (keys.any { g.optString(it.first).isNotEmpty() }) { sec("Tags"); keys.forEach { row(it.second, g.optString(it.first)) } }
        }
        o.optJSONArray("sections")?.let { ss ->   // everything that is not photo / video / audio: core/FileInfo.kt
            for (i in 0 until ss.length()) {
                val s = ss.getJSONObject(i); val rs = s.optJSONArray("rows") ?: continue
                sec(s.optString("t"))
                for (k in 0 until rs.length()) { val r = rs.getJSONArray(k); row(r.optString(0), r.optString(1)) }
            }
        }
        return sb.toString().trimEnd()
    }
}
