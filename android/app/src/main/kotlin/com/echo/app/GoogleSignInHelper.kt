package com.echo.app

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/**
 * Google One-Tap via Credential Manager. Returns the Google ID token to hand to Supabase
 * (`signInWithGoogle`). Requires `BuildConfig.GOOGLE_WEB_CLIENT_ID` (the Web/server client ID
 * from Google Cloud) — until that's set + the Google provider is enabled in Supabase, the
 * "Continue with Google" button is hidden, so this is never reached.
 */
object GoogleSignInHelper {
    val isConfigured: Boolean get() = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

    /** Launch the One-Tap chooser and return the selected account's ID token. Throws on cancel/error. */
    suspend fun getIdToken(context: Context): String {
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val result = CredentialManager.create(context).getCredential(context, request)
        val cred = result.credential
        val googleCred = GoogleIdTokenCredential.createFrom(cred.data)
        return googleCred.idToken
    }
}
