package com.lanshare.app.core

import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.random.Random

class GResp(val code: Int, val body: ByteArray, val headers: Map<String, String>)

/**
 * Google Drive account: sign-in happens in the phone's DEFAULT BROWSER (OAuth 2.0 authorization code + PKCE).
 * Google sends the browser back to http://127.0.0.1:<port>/oauth/google, which is this app's own local server (Routes.kt),
 * so no extra Android activity / manifest entry is needed. Tokens live in lanshare.json next to the SMB logins.
 */
object Gdrive {
    const val ID = "gdrive:main"

    /**
     * Optional built-in OAuth client ("Desktop app" type, Google Cloud Console > APIs & Services > Credentials).
     * Leave empty and every user pastes their own client ID in Settings > Cloud storage.
     */
    private const val BUILT_IN_CLIENT_ID = ""
    private const val BUILT_IN_CLIENT_SECRET = ""

    private const val SCOPE = "https://www.googleapis.com/auth/drive"
    private const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val REVOKE_URL = "https://oauth2.googleapis.com/revoke"
    private const val WAIT_MS = 10 * 60_000L

    private class Pending(val state: String, val verifier: String, val redirect: String, val started: Long) {
        @Volatile var status = "wait"      // wait | ok | err
        @Volatile var error: String? = null
    }

    @Volatile private var pending: Pending? = null
    @Volatile private var access: String? = null
    @Volatile private var accessExp = 0L
    /** Did the last request reach Google? Drives the dot next to "Google Drive" in the device list. */
    @Volatile var ok = true

    val fs: GDriveFs by lazy { GDriveFs() }

    private fun clientId(): String = Cfg.gdrive().optString("client_id").ifEmpty { BUILT_IN_CLIENT_ID }
    private fun clientSecret(): String = Cfg.gdrive().let { if (it.optString("client_id").isNotEmpty()) it.optString("client_secret") else BUILT_IN_CLIENT_SECRET }
    val connected: Boolean get() = Cfg.gdrive().optString("refresh").isNotEmpty()
    val email: String get() = Cfg.gdrive().optString("email")

    fun ep(): Endpoint {
        if (!connected) throw IOException("Google Drive is not signed in - open Settings > Cloud storage")
        return fs
    }

    /** The entry for /api/peers (null until the user has signed in). */
    fun peer(): JSONObject? = if (!connected) null else
        JSONObject().put("id", ID).put("name", "Google Drive").put("ip", "").put("ok", ok).put("seen", 0).put("cloud", true).put("via", "Cloud")

    // ---------------------------------------------------------------- status / settings (GET and POST /api/gdrive)
    fun status(): JSONObject {
        val p = pending
        if (p != null && p.status == "wait" && System.currentTimeMillis() - p.started > WAIT_MS) { p.status = "err"; p.error = "Sign-in took too long - try again" }
        val c = Cfg.gdrive()
        return JSONObject().put("configured", clientId().isNotEmpty()).put("builtin", BUILT_IN_CLIENT_ID.isNotEmpty())
            .put("connected", connected).put("email", c.optString("email")).put("client_id", c.optString("client_id"))
            .put("pending", p?.status ?: "").put("error", p?.error ?: "")
    }

    fun update(b: JSONObject): JSONObject {
        when (b.optString("op")) {
            "client" -> {
                val id = b.optString("id").trim()
                val secret = b.optString("secret").trim()
                if (id.isEmpty()) throw BadReq("enter the client ID")
                if (!id.endsWith(".apps.googleusercontent.com")) throw BadReq("that is not a Google client ID (it ends with .apps.googleusercontent.com)")
                val c = Cfg.gdrive()
                if (c.optString("client_id") != id || c.optString("client_secret") != secret) {   // other app registration = old tokens are useless
                    c.remove("refresh"); c.remove("email"); access = null; fs.clearCache()
                }
                c.put("client_id", id).put("client_secret", secret)
                Cfg.setGdrive(c); Cfg.save()
            }
            "login" -> return JSONObject().put("url", beginLogin())
            "cancel" -> pending = null
            "logout" -> logout()
            else -> throw BadReq("unknown op")
        }
        return status()
    }

    // ---------------------------------------------------------------- sign-in through the default browser
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Returns the Google address the UI opens in the default browser. */
    private fun beginLogin(): String {
        val cid = clientId()
        if (cid.isEmpty()) throw BadReq("enter a Google client ID first")
        val base = Core.url ?: throw IOException("the local server is not running yet")
        val rnd = SecureRandom()
        val vb = ByteArray(64).also { rnd.nextBytes(it) }
        val sb = ByteArray(18).also { rnd.nextBytes(it) }
        val verifier = b64(vb)
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val p = Pending(b64(sb), verifier, "$base/oauth/google", System.currentTimeMillis())
        pending = p
        return AUTH_URL + "?client_id=" + enc(cid) + "&redirect_uri=" + enc(p.redirect) + "&response_type=code&scope=" + enc(SCOPE) +
            "&state=" + enc(p.state) + "&code_challenge=" + enc(challenge) + "&code_challenge_method=S256" +
            "&access_type=offline&prompt=consent"
    }

    /** The browser lands here after the user said yes (or no) on Google's page. */
    fun callback(ex: Exchange) {
        val q = ex.query
        val p = pending
        fun page(good: Boolean, msg: String) = ex.reply(if (good) 200 else 400, html(good, msg).toByteArray(Charsets.UTF_8), "text/html; charset=utf-8")
        if (p == null || p.status != "wait" || q["state"].isNullOrEmpty() || q["state"] != p.state)
            return page(false, "This sign-in link is not valid any more. Start again in LANShare: Settings > Cloud storage.")
        val err = q["error"]
        if (!err.isNullOrEmpty()) {
            p.status = "err"
            p.error = if (err == "access_denied") "Sign-in was cancelled" else "Google said: $err"
            return page(false, p.error!!)
        }
        val code = q["code"]
        if (code.isNullOrEmpty()) { p.status = "err"; p.error = "Google did not send a sign-in code"; return page(false, p.error!!) }
        try {
            val form = linkedMapOf("client_id" to clientId(), "code" to code, "code_verifier" to p.verifier,
                "grant_type" to "authorization_code", "redirect_uri" to p.redirect)
            clientSecret().takeIf { it.isNotEmpty() }?.let { form["client_secret"] = it }
            val (st, text) = postForm(TOKEN_URL, form)
            if (st != 200) throw IOException(oauthError(text))
            val t = JSONObject(text)
            if (!t.optString("scope").split(' ').contains(SCOPE)) throw IOException("Drive access was not allowed - tick the Google Drive box on the permission page")
            val refresh = t.optString("refresh_token")
            if (refresh.isEmpty()) throw IOException("Google sent no long-term sign-in - remove LANShare under myaccount.google.com/permissions and try again")
            access = t.getString("access_token")
            accessExp = System.currentTimeMillis() + t.optLong("expires_in", 3600) * 1000
            val c = Cfg.gdrive()
            c.put("refresh", refresh)
            Cfg.setGdrive(c); Cfg.save()
            val who = try {
                JSONObject(String(call("GET", "https://www.googleapis.com/drive/v3/about?fields=user(emailAddress)").body, Charsets.UTF_8))
                    .optJSONObject("user")?.optString("emailAddress").orEmpty()
            } catch (_: Exception) { "" }
            c.put("email", who)
            Cfg.setGdrive(c); Cfg.save()
            fs.clearCache()
            p.status = "ok"
            page(true, if (who.isEmpty()) "Google Drive is connected." else "Google Drive is connected ($who).")
        } catch (e: Exception) {
            p.status = "err"; p.error = errText(e)
            page(false, errText(e))
        }
    }

    private fun logout() {
        val rt = Cfg.gdrive().optString("refresh")
        if (rt.isNotEmpty()) try { postForm(REVOKE_URL, mapOf("token" to rt)) } catch (_: Exception) {}   // best effort: the phone forgets the login either way
        val c = Cfg.gdrive()
        c.remove("refresh"); c.remove("email")
        Cfg.setGdrive(c); Cfg.save()
        access = null; accessExp = 0; pending = null
        fs.clearCache()
    }

    private fun html(good: Boolean, msg: String): String {
        val m = msg.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val back = "intent:#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;package=com.lanshare.app;end"
        return "<!doctype html><html><head><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\"><title>LANShare</title>" +
            "<style>body{font-family:system-ui,sans-serif;margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#f4f6fa;color:#1c2330}" +
            ".c{max-width:420px;margin:24px;padding:28px;background:#fff;border-radius:18px;box-shadow:0 4px 24px #0002;text-align:center}" +
            "h1{font-size:22px;margin:0 0 10px}p{font-size:15px;line-height:1.5;margin:8px 0}a.b{display:inline-block;margin-top:14px;padding:12px 22px;border-radius:12px;background:#2563eb;color:#fff;text-decoration:none;font-weight:600}</style></head>" +
            "<body><div class=c><h1>" + (if (good) "&#9989; Signed in" else "&#9888; Sign-in failed") + "</h1><p>" + m + "</p>" +
            "<p>You can close this tab and go back to LANShare.</p><a class=b href=\"" + back + "\">Open LANShare</a></div></body></html>"
    }

    // ---------------------------------------------------------------- tokens
    private fun oauthError(text: String): String = try {
        val o = JSONObject(text)
        (o.optString("error_description").ifEmpty { o.optString("error") }).ifEmpty { text.take(200) }
    } catch (_: Exception) { text.take(200) }

    private fun postForm(url: String, form: Map<String, String>): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 20_000; c.readTimeout = 30_000
            c.doOutput = true
            val body = form.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }.toByteArray(Charsets.UTF_8)
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val text = (if (code >= 400) c.errorStream else c.inputStream)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            return code to text
        } finally { c.disconnect() }
    }

    /** A valid access token; renews it with the stored refresh token when it is (almost) expired. */
    @Synchronized
    fun token(force: Boolean = false): String {
        val a = access
        if (!force && a != null && System.currentTimeMillis() < accessExp - 60_000) return a
        val rt = Cfg.gdrive().optString("refresh")
        if (rt.isEmpty()) throw Denied("Google Drive is not signed in - open Settings > Cloud storage")
        val form = linkedMapOf("client_id" to clientId(), "refresh_token" to rt, "grant_type" to "refresh_token")
        clientSecret().takeIf { it.isNotEmpty() }?.let { form["client_secret"] = it }
        val (st, text) = postForm(TOKEN_URL, form)
        if (st != 200) {
            val err = try { JSONObject(text).optString("error") } catch (_: Exception) { "" }
            if (err == "invalid_grant") {   // revoked, or a test-mode token that ran out (7 days)
                val c = Cfg.gdrive(); c.remove("refresh"); Cfg.setGdrive(c); Cfg.save()
                access = null; fs.clearCache()
                throw Denied("Google sign-in expired or was removed - sign in again in Settings > Cloud storage")
            }
            throw IOException("Google sign-in failed: " + oauthError(text))
        }
        val t = JSONObject(text)
        access = t.getString("access_token")
        accessExp = System.currentTimeMillis() + t.optLong("expires_in", 3600) * 1000
        return access!!
    }

    // ---------------------------------------------------------------- authorized HTTP
    private fun reasonOf(body: ByteArray): Pair<String, String> = try {
        val e = JSONObject(String(body, Charsets.UTF_8)).getJSONObject("error")
        (e.optJSONArray("errors")?.optJSONObject(0)?.optString("reason") ?: "") to e.optString("message")
    } catch (_: Exception) { "" to "" }

    private fun isRate(body: ByteArray) = reasonOf(body).first in setOf("rateLimitExceeded", "userRateLimitExceeded", "sharingRateLimitExceeded")

    /** Maps a Google error answer to the same exceptions the other devices use. */
    fun fail(code: Int, body: ByteArray): Nothing {
        val (reason, msg0) = reasonOf(body)
        val msg = msg0.ifEmpty { "HTTP $code" }
        when {
            code == 404 -> throw NotFound(if (msg0.isEmpty()) "Not found on Google Drive" else msg)
            reason == "storageQuotaExceeded" -> throw Full("Google Drive is full")
            code == 401 || code == 403 -> throw Denied(msg)
            code == 409 -> throw Exists(msg)
            else -> throw IOException("Google Drive: $msg")
        }
    }

    /**
     * One request. [raw] = hand back every status as it is (resumable uploads do their own retry logic); otherwise a 401 renews the token once,
     * 429 / 5xx / rate-limit answers are retried with back-off and all other errors become NotFound / Denied / Full / IOException.
     * HttpURLConnection has no PATCH, so PATCH goes out as POST + X-HTTP-Method-Override (Google honours it).
     */
    fun call(method: String, url: String, body: ByteArray? = null, headers: Map<String, String> = emptyMap(),
             bodyOff: Int = 0, bodyLen: Int = body?.size ?: 0, auth: Boolean = true, raw: Boolean = false, timeoutMs: Int = 60_000): GResp {
        var refreshed = false
        var attempt = 0
        while (true) {
            val c = URL(url).openConnection() as HttpURLConnection
            val resp: GResp = try {
                c.requestMethod = if (method == "PATCH") "POST" else method
                if (method == "PATCH") c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
                c.instanceFollowRedirects = false   // 308 = "resume incomplete" in resumable uploads, not a redirect
                c.connectTimeout = 20_000
                c.readTimeout = timeoutMs
                if (auth) c.setRequestProperty("Authorization", "Bearer " + token())
                for ((k, v) in headers) c.setRequestProperty(k, v)
                if (body != null || method == "POST" || method == "PUT" || method == "PATCH") {
                    c.doOutput = true
                    c.setFixedLengthStreamingMode(bodyLen)
                    c.outputStream.use { if (body != null) it.write(body, bodyOff, bodyLen) }
                }
                val code = c.responseCode
                val data = (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes() } ?: ByteArray(0)
                val hd = HashMap<String, String>()
                for ((k, v) in c.headerFields) if (k != null && v.isNotEmpty()) hd[k.lowercase()] = v[0]
                GResp(code, data, hd)
            } catch (e: IOException) {
                ok = false
                if (e is NotFound || e is Denied || raw || method != "GET" || ++attempt >= 4) throw e
                Thread.sleep(700L * attempt)
                continue
            } finally { c.disconnect() }
            ok = true
            if (raw) return resp
            val st = resp.code
            if (st == 401 && auth && !refreshed) { refreshed = true; token(force = true); continue }
            if ((st == 429 || st >= 500 || (st == 403 && isRate(resp.body))) && ++attempt < 5) {
                Thread.sleep((600L shl attempt) + Random.nextInt(300))
                continue
            }
            if (st >= 400) fail(st, resp.body)
            return resp
        }
    }

    /** Streaming GET (file download) with optional start offset; the caller closes the connection. */
    fun openStream(url: String, from: Long): HttpURLConnection {
        for (i in 0..1) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.setRequestProperty("Authorization", "Bearer " + token(force = i == 1))
            if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
            val code = try { c.responseCode } catch (e: IOException) { ok = false; c.disconnect(); throw e }
            ok = true
            if (code == 401 && i == 0) { c.disconnect(); continue }
            if (code != 200 && code != 206) {
                val b = c.errorStream?.use { it.readBytes() } ?: ByteArray(0)
                c.disconnect()
                fail(code, b)
            }
            if (from > 0 && code == 200) { c.disconnect(); throw IOException("Google Drive ignored the seek request") }
            return c
        }
        throw Denied("Google rejected the sign-in - sign in again in Settings > Cloud storage")
    }
}
