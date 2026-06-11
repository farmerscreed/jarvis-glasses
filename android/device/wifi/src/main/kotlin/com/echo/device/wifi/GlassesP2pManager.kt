package com.echo.device.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Wi-Fi Direct connection to the glasses (mirrors the stock app's WifiP2pManagerSingleton).
 * After we send the BLE Wi-Fi-start command, the glasses advertise a P2P peer; we discover and
 * connect to it. The glasses' HTTP IP arrives separately over BLE (not from groupOwnerAddress).
 */
@SuppressLint("MissingPermission")
class GlassesP2pManager(private val context: Context) {

    companion object { const val TAG = "EchoP2p" }

    private val manager: WifiP2pManager? get() = context.getSystemService(WifiP2pManager::class.java)
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var connecting = false

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected
    private val _status = MutableStateFlow("p2p idle")
    val status: StateFlow<String> = _status

    fun start() {
        val mgr = manager ?: return
        if (channel == null) channel = mgr.initialize(context, Looper.getMainLooper(), null)
        if (receiver != null) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        @Suppress("DEPRECATION")
                        val net = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                        _connected.value = net?.isConnected == true
                        _status.value = if (_connected.value) "p2p connected" else "p2p disconnected"
                    }
                }
            }
        }
        context.registerReceiver(receiver, filter)
    }

    /** Begin discovery; auto-connects to the glasses peer when found. */
    fun discoverAndConnect() {
        val mgr = manager ?: return
        connecting = false
        _connected.value = false
        _status.value = "p2p discovering…"
        mgr.discoverPeers(channel, listener("discoverPeers"))
    }

    private fun requestPeers() {
        val mgr = manager ?: return
        mgr.requestPeers(channel) { peers ->
            if (connecting) return@requestPeers
            val dev = peers.deviceList.firstOrNull {
                (it.deviceName ?: "").contains("AIMB", true) || (it.deviceName ?: "").contains("G2", true)
            } ?: peers.deviceList.firstOrNull()
            if (dev != null) { connecting = true; connect(dev) }
        }
    }

    private fun connect(device: WifiP2pDevice) {
        val mgr = manager ?: return
        _status.value = "p2p connecting to ${device.deviceName}…"
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = 0
        }
        mgr.connect(channel, config, listener("connect"))
    }

    private fun listener(tag: String) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() { Log.i(TAG, "$tag ok") }
        override fun onFailure(reason: Int) { Log.e(TAG, "$tag failed=$reason"); _status.value = "p2p $tag failed ($reason)" }
    }

    fun stop() {
        val mgr = manager
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        runCatching { mgr?.removeGroup(channel, null) }
        connecting = false
        _connected.value = false
    }
}
