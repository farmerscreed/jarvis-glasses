package com.echo.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google One-Tap via Credential Manager. Returns the Google ID token to hand to Supabase
 * (`signInWithGoogle`). Requires `BuildConfig.GOOGLE_WEB_CLIENT_ID` (the Web/server client ID
 * from Google Cloud) — until that's set + the Google provider is enabled in Supabase, the
 * "Continue with Google" button is hidden, so this is never reached.
 *
 * Also: [authorizeWorkspace] — the separate consent for Gmail drafts, Calendar and Contacts
 * (Phase 1). Signing in with Google does NOT grant those; this asks for them and returns a one-time
 * server auth code that the `google-connect` Edge Function swaps for a refresh token it keeps.
 */
object GoogleSignInHelper {
    val isConfigured: Boolean get() = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

    /** Must match GOOGLE_SCOPES in supabase/functions/_shared/google.ts. */
    private val WORKSPACE_SCOPES = listOf(
        "https://www.googleapis.com/auth/gmail.compose",
        "https://www.googleapis.com/auth/calendar.events",
        "https://www.googleapis.com/auth/contacts.readonly",
        "https://www.googleapis.com/auth/contacts.other.readonly",
        "https://www.googleapis.com/auth/drive.file", // M6b: only files JARVIS creates (starred meetings)
        "email",
    )

    /** Launch the One-Tap chooser and return the selected account's ID token. Throws on cancel/error. */
    suspend fun getIdToken(context: Context): String {
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val result = CredentialManager.create(context).getCredential(context, request)
        val cred = result.credential
        val googleCred = GoogleIdTokenCredential.createFrom(cred.data)
        return googleCred.idToken
    }

    /** Either Google needs to show its consent screen ([consent]) or it already has consent ([code]). */
    sealed interface Authorization {
        data class Consent(val pendingIntent: PendingIntent) : Authorization
        data class Code(val serverAuthCode: String) : Authorization
    }

    /**
     * Ask Google for Gmail-drafts / Calendar / Contacts access with offline access (so the cloud can
     * act later without the phone). forceCodeForRefreshToken = always return a code that yields a
     * refresh token, even when consent was given before.
     */
    suspend fun authorizeWorkspace(context: Context): Authorization = suspendCancellableCoroutine { cont ->
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(WORKSPACE_SCOPES.map { Scope(it) })
            .requestOfflineAccess(BuildConfig.GOOGLE_WEB_CLIENT_ID, true)
            .build()
        Identity.getAuthorizationClient(context).authorize(request)
            .addOnSuccessListener { r ->
                val pi = r.pendingIntent
                val code = r.serverAuthCode
                when {
                    r.hasResolution() && pi != null -> cont.resume(Authorization.Consent(pi))
                    !code.isNullOrBlank() -> cont.resume(Authorization.Code(code))
                    else -> cont.resumeWithException(IllegalStateException("Google didn't return an access code"))
                }
            }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    /** The server auth code from the consent screen's result intent (null if cancelled). */
    fun codeFromConsentResult(context: Context, data: Intent?): String? = runCatching {
        Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data).serverAuthCode
    }.getOrNull()
}
