package com.lanshare.app.core.settings

import android.content.Context
import com.lanshare.app.core.GdApi
import com.lanshare.app.core.Gdrive
import org.json.JSONObject
import java.io.IOException

/**
 * Settings backup to Google Drive: uploads the same file Settings export makes ([SettingsIo.export]) as "LANShare-settings.json" in the
 * root of a saved Google Drive account, and reads it back for a restore ([SettingsSchema.parse] + [SettingsIo.apply] do the checking and writing).
 * Passwords and the Phone Explorer PIN are NEVER uploaded (secrets = false): the copy in the cloud is a plain-text file.
 */
object SettingsCloud {
    const val FILE_NAME = "LANShare-settings.json"
    private const val MIME = "application/json"

    private fun api(accountId: String): GdApi {
        val email = Gdrive.cfg(accountId)?.optString("email").orEmpty()
        if (email.isEmpty()) throw IOException("That Google account is not saved in LANShare any more")
        return GdApi(accountId, email)
    }

    /** Id of the backup file in the account's root, or null when there is none yet. */
    private fun find(a: GdApi): String? {
        val q = "name = ${GdApi.qs(FILE_NAME)} and 'root' in parents and trashed = false"
        val r = a.json("GET", "/files", mapOf("q" to q, "fields" to "files(id,modifiedTime)", "orderBy" to "modifiedTime desc", "pageSize" to "1"))
        return r.optJSONArray("files")?.optJSONObject(0)?.optString("id")?.takeIf { it.isNotEmpty() }
    }

    /** Blocking (worker thread). Returns the text for the toast. */
    fun backup(c: Context, accountId: String, webJson: String): String {
        val a = api(accountId)
        val text = SettingsIo.export(c, webJson, false)
        val body = text.toByteArray(Charsets.UTF_8)
        val id = find(a) ?: a.json("POST", "/files", body = JSONObject().put("name", FILE_NAME).put("mimeType", MIME).put("parents", org.json.JSONArray().put("root"))).getString("id")
        a.send("PATCH", GdApi.url("/files/$id", mapOf("uploadType" to "media"), GdApi.UPLOAD), body, MIME)
        return "Settings saved to Google Drive (${Gdrive.cfg(accountId)?.optString("email")})"
    }

    /** Blocking. Downloads and validates the backup; nothing is changed on the phone yet. */
    fun fetch(accountId: String): SettingsSchema.Result {
        val a = api(accountId)
        val id = find(a) ?: throw IOException("No settings backup found in this Google Drive (nothing named $FILE_NAME in My Drive)")
        val text = a.send("GET", GdApi.url("/files/$id", mapOf("alt" to "media"))).text()
        return SettingsSchema.parse(text)
    }
}
