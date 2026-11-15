package com.takipli.oynat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.takipli.oynat.core.parseUpdateAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * In-app update from GitHub Releases. CI publishes signed release APKs named `TakipliOynat-Android-<engine>-<X.Y.Z>-b<N>.apk`
 * (main pushes = prerelease, `v*` tags = release; private repositories only). This object lists the releases, picks the newest
 * APK of THIS build flavor, and downloads it (checking size and, when GitHub gives one, SHA-256) to cache/updates. The system
 * installer then does the real security check: it only accepts an APK signed with the same key as the installed app.
 * A private repository needs a read-only fine-grained token (Contents: read); it is kept in the app's private preferences.
 */
object Updater {
    class Found(val label: String, val code: Int, val name: String, val apiUrl: String, val size: Long, val sha256: String?)

    private const val PREFS = "updater"
    private val REPO_RE = Regex("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$")
    private val HOSTS = listOf("github.com", "githubusercontent.com", "amazonaws.com")

    fun repo(ctx: Context): String =
        (ctx.getSharedPreferences(PREFS, 0).getString("repo", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.UPDATE_REPO).trim()

    fun token(ctx: Context): String = ctx.getSharedPreferences(PREFS, 0).getString("token", "").orEmpty()

    fun save(ctx: Context, repo: String, token: String) {
        ctx.getSharedPreferences(PREFS, 0).edit().putString("repo", repo.trim()).putString("token", token.trim()).apply()
    }

    fun repoValid(repo: String) = REPO_RE.matches(repo.trim())

    private fun hostOk(u: URL) = u.protocol == "https" && HOSTS.any { u.host == it || u.host.endsWith(".$it") }

    private fun open(u: URL, token: String, accept: String): HttpURLConnection =
        (u.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = false // the redirect target (object storage) must NOT get our Authorization header
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "TakipliOynat-Android")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (token.isNotBlank() && u.host == "api.github.com") setRequestProperty("Authorization", "Bearer $token")
        }

    /** The newest release APK of this flavor if it is newer than the installed build; null = up to date. */
    suspend fun check(ctx: Context): Found? = withContext(Dispatchers.IO) {
        val repo = repo(ctx)
        require(repoValid(repo)) { "repository" }
        val c = open(URL("https://api.github.com/repos/$repo/releases?per_page=20"), token(ctx), "application/vnd.github+json")
        val body = try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
        val releases = JSONArray(body)
        var best: Found? = null
        for (i in 0 until releases.length()) {
            val r = releases.getJSONObject(i)
            if (r.optBoolean("draft")) continue
            val assets = r.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                val (label, code) = parseUpdateAsset(a.optString("name"), BuildConfig.ENGINE) ?: continue
                val cur = best
                if (cur == null || code > cur.code) {
                    best = Found(label, code, a.getString("name"), a.getString("url"), a.optLong("size"),
                        a.optString("digest").removePrefix("sha256:").takeIf { it.length == 64 })
                }
            }
        }
        best?.takeIf { it.code > BuildConfig.VERSION_CODE }
    }

    /** Downloads to cache/updates/<name> (old files there are removed first). `onProgress(done, total)`; total 0 = unknown. */
    suspend fun download(ctx: Context, f: Found, onProgress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").also { it.mkdirs(); it.listFiles()?.forEach { x -> x.delete() } }
        val out = File(dir, f.name)
        val part = File(dir, f.name + ".part")
        var conn: HttpURLConnection? = null
        try {
            var url = URL(f.apiUrl)
            var hops = 0
            while (true) {
                require(hostOk(url)) { "blocked host ${url.host}" }
                val c = open(url, token(ctx), "application/octet-stream")
                conn = c
                val rc = c.responseCode
                if (rc in 300..399) {
                    val loc = c.getHeaderField("Location") ?: throw IOException("redirect without Location")
                    c.disconnect()
                    url = URL(url, loc)
                    if (++hops > 5) throw IOException("too many redirects")
                    continue
                }
                if (rc !in 200..299) throw IOException("HTTP $rc")
                break
            }
            val c = conn!!
            val total = c.contentLengthLong.takeIf { it > 0 } ?: f.size
            val md = MessageDigest.getInstance("SHA-256")
            c.inputStream.use { input ->
                part.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var n = 0L
                    while (true) {
                        ensureActive()
                        val r = input.read(buf)
                        if (r < 0) break
                        o.write(buf, 0, r); md.update(buf, 0, r); n += r
                        onProgress(n, total)
                    }
                }
            }
            if (f.size > 0 && part.length() != f.size) throw IOException("size mismatch")
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (f.sha256 != null && !hex.equals(f.sha256, ignoreCase = true)) throw IOException("checksum mismatch")
            check(part.renameTo(out)) { "cannot save the APK" }
            out
        } finally {
            conn?.disconnect()
            part.delete()
        }
    }

    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallSettings(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
