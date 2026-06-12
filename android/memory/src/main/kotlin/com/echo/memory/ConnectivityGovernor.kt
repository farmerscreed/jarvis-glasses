package com.echo.memory

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tracks connectivity and pins the app to a capability tier (Phase C §4.1):
 * - **FULL** — validated internet and our API answers quickly.
 * - **LEAN** — online, but a confirmed-slow round-trip to our API (degrade: tight timeouts + local
 *   fallback). LEAN never *blocks* a path; it makes it fail fast to the on-device answer.
 * - **OFF_GRID** — no validated network.
 *
 * [online] stays true for FULL and LEAN (network is present); [tier] is the finer signal. We only
 * downgrade to LEAN on a *confirmed* slow probe — a failed probe is left as FULL so a wrong health
 * URL never falsely degrades the live experience (the real API calls reveal genuine failures).
 */
enum class Tier { FULL, LEAN, OFF_GRID }

class ConnectivityGovernor(
    context: Context,
    private val healthUrl: String? = null,
) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _online = MutableStateFlow(hasValidatedNetwork())
    val online: StateFlow<Boolean> = _online

    private val _tier = MutableStateFlow(if (_online.value) Tier.FULL else Tier.OFF_GRID)
    val tier: StateFlow<Tier> = _tier

    /** Invoked whenever connectivity is (re)gained — the SyncManager hooks this to drain the outbox. */
    var onConnected: (() -> Unit)? = null

    private companion object {
        const val LEAN_THRESHOLD_MS = 1500L
        const val PROBE_INTERVAL_MS = 30_000L
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = update()
        override fun onLost(network: Network) = update()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update()
    }

    init {
        runCatching { cm.registerDefaultNetworkCallback(callback) }
        scope.launch { // periodic RTT probe to refine FULL vs LEAN
            while (true) {
                refreshTier()
                delay(PROBE_INTERVAL_MS)
            }
        }
    }

    private fun update() {
        val was = _online.value
        val now = hasValidatedNetwork()
        _online.value = now
        if (!now) _tier.value = Tier.OFF_GRID
        scope.launch { refreshTier() } // re-probe on every network change
        if (now && !was) onConnected?.invoke()
    }

    private suspend fun refreshTier() {
        if (!hasValidatedNetwork()) { _tier.value = Tier.OFF_GRID; return }
        val rtt = probe()
        _tier.value = if (rtt != null && rtt > LEAN_THRESHOLD_MS) Tier.LEAN else Tier.FULL
    }

    /** Round-trip ms to the health endpoint, or null if it couldn't be measured. */
    private suspend fun probe(): Long? = withContext(Dispatchers.IO) {
        val url = healthUrl ?: return@withContext null
        runCatching {
            val start = System.nanoTime()
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000; readTimeout = 4000; requestMethod = "GET"
            }
            conn.responseCode
            conn.disconnect()
            (System.nanoTime() - start) / 1_000_000
        }.getOrNull()
    }

    private fun hasValidatedNetwork(): Boolean {
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
