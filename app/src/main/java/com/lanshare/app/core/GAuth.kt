package com.lanshare.app.core

import android.accounts.Account
import android.app.PendingIntent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * Google sign-in for Drive through Play Services' Authorization API: no client secret and no redirect in the app.
 * Google matches this build by package name + signing SHA-1 (the Android OAuth client in Google Cloud Console).
 * Access tokens last ~1 h; [token] asks Play Services again silently (works from background threads once the user agreed on the consent screen).
 */
object GAuth {
    const val SCOPE_DRIVE = "https://www.googleapis.com/auth/drive"
    private const val TTL_MS = 50 * 60_000L

    private class Tok(val value: String, val until: Long)
    private val cache = ConcurrentHashMap<String, Tok>()

    /** Result of [begin]: either a token right away, or a consent screen the activity must show. */
    class Step(val token: String?, val consent: PendingIntent?, val email: String?)

    private fun request(email: String?): AuthorizationRequest {
        val b = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE_DRIVE)))
        if (!email.isNullOrEmpty()) b.setAccount(Account(email, "com.google"))
        return b.build()
    }

    private fun readable(e: Throwable): IOException {
        val c = (e as? ExecutionException)?.cause ?: e
        if (c is ApiException) {
            val hint = when (c.statusCode) {
                10 -> " - Google does not accept this app build: the Android OAuth client needs package com.lanshare.app and this build's SHA-1"
                12500, 12501 -> " - sign-in was cancelled"
                7 -> " - no internet connection"
                else -> ""
            }
            return IOException("Google sign-in failed (code ${c.statusCode})$hint")
        }
        return IOException("Google sign-in failed: " + errText(c))
    }

    /** Blocking (call from a worker thread). [email] = null lets the user pick the account on the consent screen. */
    fun begin(email: String?): Step {
        val app = Core.appCtx ?: throw IOException("app not ready")
        try {
            val r: AuthorizationResult = Tasks.await(Identity.getAuthorizationClient(app).authorize(request(email)), 40, TimeUnit.SECONDS)
            if (r.hasResolution()) return Step(null, r.pendingIntent, email)
            val t = r.accessToken ?: throw IOException("Google returned no access token")
            if (!email.isNullOrEmpty()) cache[email] = Tok(t, System.currentTimeMillis() + TTL_MS)
            return Step(t, null, email)
        } catch (e: Exception) { throw readable(e) }
    }

    /** Consent screen finished (activity result): the token for the account the user chose. */
    fun finish(data: android.content.Intent?, ctx: android.content.Context): String {
        try {
            val r = Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(data)
            return r.accessToken ?: throw IOException("Google returned no access token")
        } catch (e: Exception) { throw readable(e) }
    }

    /** A valid access token for the saved account [email]. Throws [Denied] when the user has to sign in again from the Google Drive screen. */
    fun token(email: String, force: Boolean = false): String {
        if (!force) cache[email]?.let { if (it.until > System.currentTimeMillis()) return it.value }
        val s = begin(email)
        if (s.token == null) throw Denied("Google Drive needs you to sign in again: Main menu > Google Drive > Reconnect")
        return s.token
    }

    fun forget(email: String) { cache.remove(email) }
}
