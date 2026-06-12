package com.echo.memory

import android.content.SharedPreferences

/**
 * Holds the Supabase endpoint + the signed-in user's token. Persisted to [prefs] so a process the
 * system woke for a background sync (no UI sign-in) can still authenticate — without this, the
 * offline-first outbox can never drain while the app is killed. (Refresh-token rotation for
 * long-lived background auth is a Phase E concern; this persists the access token + uid.)
 */
class SupabaseSession(
    val baseUrl: String,
    val anonKey: String,
    private val prefs: SharedPreferences? = null,
) {
    @Volatile
    var accessToken: String? = prefs?.getString(KEY_TOKEN, null)
        set(value) {
            field = value
            prefs?.edit()?.putString(KEY_TOKEN, value)?.apply()
        }

    /** Signed-in user's id; storage object keys are namespaced by it (RLS). */
    @Volatile
    var userId: String? = prefs?.getString(KEY_UID, null)
        set(value) {
            field = value
            prefs?.edit()?.putString(KEY_UID, value)?.apply()
        }

    val isLoggedIn: Boolean get() = accessToken != null

    private companion object {
        const val KEY_TOKEN = "access_token"
        const val KEY_UID = "user_id"
    }
}
