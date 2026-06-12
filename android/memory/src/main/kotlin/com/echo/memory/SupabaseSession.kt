package com.echo.memory

import android.content.SharedPreferences

/**
 * Holds the Supabase endpoint + the signed-in user's tokens. Persisted to [prefs] so a process the
 * system woke for a background sync (no UI sign-in) can still authenticate — without this, the
 * offline-first outbox can never drain while the app is killed. The refresh token lets
 * [EchoBackend.refreshSession] rotate an expired access token, so background auth outlives the
 * access-token lifetime.
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

    @Volatile
    var refreshToken: String? = prefs?.getString(KEY_REFRESH, null)
        set(value) {
            field = value
            prefs?.edit()?.putString(KEY_REFRESH, value)?.apply()
        }

    /** Signed-in user's id; storage object keys are namespaced by it (RLS). */
    @Volatile
    var userId: String? = prefs?.getString(KEY_UID, null)
        set(value) {
            field = value
            prefs?.edit()?.putString(KEY_UID, value)?.apply()
        }

    val isLoggedIn: Boolean get() = accessToken != null

    /** Forget the signed-in user (sign-out): wipes tokens + uid from memory and prefs. */
    fun clear() {
        accessToken = null
        refreshToken = null
        userId = null
    }

    private companion object {
        const val KEY_TOKEN = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_UID = "user_id"
    }
}
