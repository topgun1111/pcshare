package com.lanshare.app.core.vault

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Lockdown addendum rows 7 / 8: intent + FileProvider entry points. Android-only; the path logic stays in [VaultGuard]. */
object VaultIntents {
    /** Drop-in for FileProvider.getUriForFile: a vault blob or the plaintext vault cache never gets a URI. */
    fun uri(ctx: Context, authority: String, f: File): Uri {
        if (VaultGuard.isVaultFile(f)) throw SecurityException("not found")
        return FileProvider.getUriForFile(ctx, authority, f)
    }

    /** True when an incoming uri / path points into the vault (file://, raw paths, document ids, encoded variants). */
    fun refuse(u: Uri?): Boolean {
        if (u == null) return false
        val parts = listOfNotNull(u.path, u.toString(), u.lastPathSegment)
        if (parts.any { VaultGuard.isVaultName(it) }) return true
        if (u.scheme == "file" && VaultGuard.isVaultPath(u.path)) return true
        return try {
            if (u.authority == "com.android.externalstorage.documents") VaultGuard.isVaultName(android.provider.DocumentsContract.getDocumentId(u)) else false
        } catch (_: Exception) { false }
    }

    fun refuse(i: android.content.Intent?): Boolean {
        if (i == null) return false
        if (refuse(i.data)) return true
        if (i.getStringExtra("path")?.let { VaultGuard.isVaultPath(it) } == true) return true
        @Suppress("DEPRECATION")
        val one = try { i.getParcelableExtra<Uri>(android.content.Intent.EXTRA_STREAM) } catch (_: Exception) { null }
        if (refuse(one)) return true
        @Suppress("DEPRECATION")
        val many = try { i.getParcelableArrayListExtra<Uri>(android.content.Intent.EXTRA_STREAM) } catch (_: Exception) { null }
        return many?.any { refuse(it) } == true
    }
}
