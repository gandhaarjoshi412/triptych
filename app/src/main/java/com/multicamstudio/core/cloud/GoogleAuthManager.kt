package com.multicamstudio.core.cloud

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.multicamstudio.core.common.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

sealed interface AuthStep {
    data class Authorized(val accessToken: String) : AuthStep
    /** The user must see a consent / account picker screen: launch this with StartIntentSenderForResult. */
    data class NeedsUserInteraction(val pendingIntent: PendingIntent) : AuthStep
    data class Failed(val message: String) : AuthStep
}

/**
 * Google Identity Services authorization for the least-privilege `drive.file` scope.
 *
 * - No client secret lives in the app: the OAuth *Android* client is matched by package name + signing
 *   certificate SHA-1 in Google Cloud Console (see docs/HANDOFF.md).
 * - Access tokens are never persisted. Each upload run asks Play services for a token; if access was
 *   granted before, it returns one without UI. If the grant was revoked/expired, background uploads stop
 *   with "authorization required" and the user reconnects from Settings.
 */
class GoogleAuthManager(private val context: Context) {
    private val client = Identity.getAuthorizationClient(context)

    private fun request(): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
            .build()

    /** Use from the UI. */
    suspend fun authorize(): AuthStep = try {
        val r = client.authorize(request()).await()
        toStep(r)
    } catch (e: Exception) {
        AppLog.w(TAG, "authorize failed", e)
        AuthStep.Failed(e.message ?: "Authorization failed")
    }

    fun resultFromIntent(data: Intent?): AuthStep = try {
        toStep(client.getAuthorizationResultFromIntent(data))
    } catch (e: Exception) {
        AuthStep.Failed(e.message ?: "Authorization cancelled")
    }

    /** Use from background work: a token, or null when user interaction is needed. */
    suspend fun accessTokenSilently(): String? = when (val s = authorize()) {
        is AuthStep.Authorized -> s.accessToken
        else -> null
    }

    /** Drop a cached token (after a 401) so the next call fetches a fresh one. */
    suspend fun invalidate(token: String) = withContext(Dispatchers.IO) {
        runCatching { GoogleAuthUtil.clearToken(context, token) }.onFailure { AppLog.w(TAG, "clearToken failed", it) }
        Unit
    }

    private fun toStep(r: AuthorizationResult): AuthStep {
        if (r.hasResolution()) {
            val pi = r.pendingIntent ?: return AuthStep.Failed("Authorization needs UI but no intent was provided")
            return AuthStep.NeedsUserInteraction(pi)
        }
        val token = r.accessToken ?: return AuthStep.Failed("No access token returned")
        return AuthStep.Authorized(token)
    }

    companion object {
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val TAG = "GoogleAuth"
    }
}
