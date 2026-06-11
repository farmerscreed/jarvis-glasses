package com.echo.app

/**
 * Local dev backend config. The Pixel reaches the PC's local Supabase via
 * `adb reverse tcp:54421 tcp:54421`, so 127.0.0.1 on the device maps to the host.
 * (For an emulator, use http://10.0.2.2:54421.) Anon key = the local "publishable" key.
 */
object DevConfig {
    const val SUPABASE_URL = "http://127.0.0.1:54421"
    const val SUPABASE_ANON_KEY = "sb_publishable_ACJWlzQHlZjBrEguHvfOxg_3BJgxAaH"
}
