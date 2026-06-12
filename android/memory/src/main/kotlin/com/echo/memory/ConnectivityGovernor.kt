package com.echo.memory

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tracks connectivity and pins the app to a capability tier (Phase C §4.1).
 * C1 distinguishes only FULL (validated internet) vs OFF_GRID (none); LEAN (slow/flaky, via an
 * RTT probe to our API) is a later refinement. Everything that can degrade reads [tier]/[online].
 */
enum class Tier { FULL, LEAN, OFF_GRID }

class ConnectivityGovernor(context: Context) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private val _online = MutableStateFlow(currentlyOnline())
    val online: StateFlow<Boolean> = _online

    private val _tier = MutableStateFlow(if (_online.value) Tier.FULL else Tier.OFF_GRID)
    val tier: StateFlow<Tier> = _tier

    /** Invoked whenever connectivity is (re)gained — the SyncManager hooks this to drain the outbox. */
    var onConnected: (() -> Unit)? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = update()
        override fun onLost(network: Network) = update()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update()
    }

    init {
        runCatching { cm.registerDefaultNetworkCallback(callback) }
    }

    private fun update() {
        val was = _online.value
        val now = currentlyOnline()
        _online.value = now
        _tier.value = if (now) Tier.FULL else Tier.OFF_GRID
        if (now && !was) onConnected?.invoke()
    }

    private fun currentlyOnline(): Boolean {
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
