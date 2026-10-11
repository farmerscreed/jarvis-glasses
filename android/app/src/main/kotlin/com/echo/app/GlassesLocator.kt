package com.echo.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import androidx.core.content.ContextCompat
import com.echo.device.ble.GlassesBleManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Find my glasses (Phase 1). The moment the glasses' Bluetooth link drops, the phone notes where
 * IT is — the glasses were last within Bluetooth range of that spot. "Where are my glasses?" then
 * answers with when and where, and the Device screen opens it on a map.
 *
 * Works while the app is in the background only if location is allowed "all the time" (Android
 * blocks background location otherwise); the Device screen asks for that in plain words.
 * Uses Android's own LocationManager — no Play Services location dependency.
 */
@Singleton
class GlassesLocator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ble: GlassesBleManager,
) {
    data class LastSeen(val lat: Double, val lng: Double, val accuracyM: Float, val at: Long, val place: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs = context.getSharedPreferences("find_glasses", Context.MODE_PRIVATE)
    private var started = false

    /** True while the glasses' Bluetooth link is up. */
    @Volatile var connected = false
        private set

    /** Start watching the glasses link (idempotent; lives as long as the app process). */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            ble.status.collect { s ->
                val up = s.startsWith("BLE: connected") || s.startsWith("BLE: glasses IP") || s == "BLE: already connected"
                when {
                    up -> connected = true
                    s == "BLE: disconnected" && connected -> {
                        connected = false
                        recordLocation()
                    }
                }
            }
        }
    }

    fun hasLocationPermission(): Boolean =
        granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    /** "Allow all the time" — needed to note the spot when the app isn't on screen. */
    fun hasBackgroundPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    private fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    fun lastSeen(): LastSeen? {
        if (!prefs.contains("lat_bits")) return null
        return LastSeen(
            lat = Double.fromBits(prefs.getLong("lat_bits", 0L)),
            lng = Double.fromBits(prefs.getLong("lng_bits", 0L)),
            accuracyM = prefs.getFloat("acc", 0f),
            at = prefs.getLong("at", 0L),
            place = prefs.getString("place", "").orEmpty(),
        )
    }

    /** Note where the phone is now (called when the glasses disconnect). */
    suspend fun recordLocation() {
        if (!hasLocationPermission()) { Log.i(TAG, "glasses disconnected — no location permission, not noted"); return }
        val loc = currentLocation() ?: run { Log.w(TAG, "glasses disconnected — no location fix"); return }
        val place = placeName(loc.latitude, loc.longitude)
        prefs.edit()
            .putLong("lat_bits", loc.latitude.toBits())
            .putLong("lng_bits", loc.longitude.toBits())
            .putFloat("acc", loc.accuracy)
            .putLong("at", System.currentTimeMillis())
            .putString("place", place)
            .apply()
        Log.i(TAG, "glasses last seen noted (±${loc.accuracy.toInt()} m) $place")
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(): Location? = withContext(Dispatchers.Main) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && lm.hasProvider(LocationManager.FUSED_PROVIDER)) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        // A fresh fix first (≤20 s); otherwise the best recent one Android already has.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            for (p in providers) {
                val fresh = withTimeoutOrNull(20_000) {
                    suspendCancellableCoroutine<Location?> { cont ->
                        val cancel = CancellationSignal()
                        cont.invokeOnCancellation { cancel.cancel() }
                        runCatching { lm.getCurrentLocation(p, cancel, context.mainExecutor) { cont.resume(it) } }
                            .onFailure { cont.resume(null) }
                    }
                }
                if (fresh != null) return@withContext fresh
            }
        }
        providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 10 * 60_000 }
            .minByOrNull { it.accuracy }
    }

    /** "Trans-Amadi Road, Port Harcourt" — best effort (needs a connection; "" if unavailable). */
    @Suppress("DEPRECATION")
    private suspend fun placeName(lat: Double, lng: Double): String = withContext(Dispatchers.IO) {
        runCatching {
            val a = Geocoder(context, Locale.ENGLISH).getFromLocation(lat, lng, 1)?.firstOrNull() ?: return@runCatching ""
            listOfNotNull(a.thoroughfare ?: a.featureName, a.subLocality ?: a.locality).distinct().joinToString(", ")
        }.getOrDefault("")
    }

    /** What JARVIS says to "where are my glasses?". */
    fun spokenAnswer(now: Long = System.currentTimeMillis()): String {
        if (connected) return "Your glasses are connected right now, so they're within a few metres of your phone."
        val ls = lastSeen()
            ?: return if (!hasLocationPermission()) "I can't track them yet. Open the Device screen and turn on Find my glasses."
            else "I haven't seen them disconnect since Find my glasses was turned on, so I don't have a spot yet."
        val mins = ((now - ls.at) / 60_000).toInt()
        val ago = when {
            mins < 2 -> "just now"
            mins < 60 -> "$mins minutes ago"
            mins < 120 -> "about an hour ago"
            mins < 24 * 60 -> "about ${mins / 60} hours ago"
            else -> "${mins / (24 * 60)} days ago"
        }
        val where = if (ls.place.isNotBlank()) "near ${ls.place}" else "at a spot I've saved on the map"
        return "They were last connected $ago, $where. Open the Device screen to see it on the map."
    }

    /** Opens the spot in Google Maps (or any maps app). */
    fun mapIntent(ls: LastSeen): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("geo:${ls.lat},${ls.lng}?q=${ls.lat},${ls.lng}(My glasses)"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private companion object { const val TAG = "EchoFindGlasses" }
}
