package com.lanshare.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Saved Google Drive accounts (HANDOVER chapter 17). Stored under "gdrive" in lanshare.json as [{id:"gd:xxxxxx", email, name}].
 * NO token is stored: [GAuth.token] asks Play Services for a fresh one (silent after the first consent).
 * Part 1 = sign-in + account list only. The browsable endpoint (GdriveFs) arrives in Part 2.
 */
object Gdrive {
    private const val MAX_ACCOUNTS = 5

    fun cfg(id: String): JSONObject? {
        val a = Cfg.gdrive()
        for (i in 0 until a.length()) if (a.getJSONObject(i).optString("id") == id) return a.getJSONObject(i)
        return null
    }

    fun byEmail(email: String): JSONObject? {
        val a = Cfg.gdrive()
        for (i in 0 until a.length()) if (a.getJSONObject(i).optString("email").equals(email, true)) return a.getJSONObject(i)
        return null
    }

    /** Saves the account (or refreshes the name of a known one). Returns its id. */
    fun add(email: String, name: String): String {
        if (email.isEmpty()) throw BadReq("Google did not tell which account signed in")
        byEmail(email)?.let { old ->
            if (name.isNotEmpty() && old.optString("name") != name) { old.put("name", name); Cfg.save() }
            return old.getString("id")
        }
        val cur = Cfg.gdrive()
        if (cur.length() >= MAX_ACCOUNTS) throw BadReq("At most $MAX_ACCOUNTS Google accounts: remove one first")
        val c = JSONObject().put("id", "gd:" + UUID.randomUUID().toString().replace("-", "").take(6))
            .put("email", email).put("name", name.ifEmpty { email }.take(40))
        cur.put(c)
        Cfg.setGdrive(cur)
        Cfg.save()
        return c.getString("id")
    }

    fun remove(id: String) {
        val cur = Cfg.gdrive()
        val keep = JSONArray()
        for (i in 0 until cur.length()) {
            val c = cur.getJSONObject(i)
            if (c.optString("id") == id) { GAuth.forget(c.optString("email")); try { FtpCache.clear(id); GdCache.clear(id) } catch (_: Throwable) {} } else keep.put(c)
        }
        Cfg.setGdrive(keep)
        Cfg.save()
    }

    /** The saved accounts as device-list entries (kind "Drive" is added by /api/peers). Always "reachable": Drive is checked when opened. */
    fun peers(): List<JSONObject> {
        val a = Cfg.gdrive()
        return (0 until a.length()).map {
            val c = a.getJSONObject(it)
            val email = c.optString("email")
            JSONObject().put("id", c.getString("id")).put("name", "Drive: " + email.ifEmpty { c.optString("name") })
                .put("ip", email).put("ok", true).put("smb", true).put("gd", true)
        }
    }

    fun status(): JSONArray {
        val a = Cfg.gdrive()
        return JSONArray((0 until a.length()).map {
            val c = a.getJSONObject(it)
            JSONObject().put("id", c.getString("id")).put("name", c.optString("name")).put("email", c.optString("email"))
        })
    }

    /**
     * Drive "about" for the signed-in user: {user:{emailAddress, displayName}, storageQuota:{limit?, usage}}. Doubles as the first real test
     * of the token and of the Drive API switch in Google Cloud Console (the error text says which one is wrong).
     */
    fun about(token: String): JSONObject {
        val c = URL("https://www.googleapis.com/drive/v3/about?fields=" + java.net.URLEncoder.encode("user(emailAddress,displayName),storageQuota(limit,usage)", "UTF-8"))
            .openConnection() as HttpURLConnection
        try {
            c.setRequestProperty("Authorization", "Bearer $token")
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            val code = c.responseCode
            val body = ((if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)).toString(Charsets.UTF_8)
            if (code in 200..299) return JSONObject(body)
            val msg = try { JSONObject(body).getJSONObject("error").optString("message") } catch (_: Exception) { body.take(200) }
            val hint = if (msg.contains("has not been used", true) || msg.contains("disabled", true))
                " - switch on the Google Drive API for this project in Google Cloud Console (APIs & Services > Library)" else ""
            if (code == 401 || code == 403) throw Denied("Google Drive refused the sign-in ($code): $msg$hint")
            throw IOException("Google Drive error $code: $msg")
        } finally { c.disconnect() }
    }

    /** Sign-in finished with [token]: reads the account from Drive and saves it. Returns the saved entry. */
    fun connect(token: String): JSONObject {
        val u = about(token).optJSONObject("user") ?: throw IOException("Google Drive did not return the account")
        val id = add(u.optString("emailAddress"), u.optString("displayName"))
        return cfg(id)!!
    }
}
