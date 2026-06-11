package com.echo.memory

/** Holds the local/cloud Supabase endpoint + the signed-in user's access token. */
class SupabaseSession(
    val baseUrl: String,
    val anonKey: String,
) {
    @Volatile
    var accessToken: String? = null

    val isLoggedIn: Boolean get() = accessToken != null
}
