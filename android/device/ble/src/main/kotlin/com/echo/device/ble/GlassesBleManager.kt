package com.echo.device.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/** A raw BLE notification from the glasses (e.g. a physical-button press, the Wi-Fi IP). */
data class GlassesNotification(val char: UUID, val bytes: ByteArray)

/**
 * BLE control of the AIMB-G2 glasses. Two roles:
 *  - Diagnostics (scan + dump GATT) — used to resolve characteristics.
 *  - Control: send framed oudmon `glassesControl` commands (capture photo, start Wi-Fi transfer)
 *    and receive notifications (the glasses' Wi-Fi IP). See docs/recon/Transfer_Protocol.md.
 * Logs to "EchoBle".
 */
@SuppressLint("MissingPermission")
class GlassesBleManager(private val context: Context) {

    companion object {
        const val TAG = "EchoBle"
        const val GLASSES_ADDR = "63:93:E1:8A:A0:34"
        val SVC: UUID = UUID.fromString("de5bf728-d711-4e47-af26-65e3012a5dc7")
        val WRITE_CHAR: UUID = UUID.fromString("de5bf72a-d711-4e47-af26-65e3012a5dc7") // handle 0x008E
        val NOTIFY_CHAR: UUID = UUID.fromString("de5bf729-d711-4e47-af26-65e3012a5dc7")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** oudmon frame: BC 41 <len:2 LE> <CRC16-MODBUS(payload):2 LE> <payload>. */
        fun frame(payload: ByteArray): ByteArray {
            val crc = crc16Modbus(payload)
            val len = payload.size
            return byteArrayOf(
                0xBC.toByte(), 0x41,
                (len and 0xFF).toByte(), ((len shr 8) and 0xFF).toByte(),
                (crc and 0xFF).toByte(), ((crc shr 8) and 0xFF).toByte(),
            ) + payload
        }

        private fun crc16Modbus(data: ByteArray): Int {
            var crc = 0xFFFF
            for (b in data) {
                crc = crc xor (b.toInt() and 0xFF)
                repeat(8) {
                    crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
                }
            }
            return crc and 0xFFFF
        }

        val CMD_CAPTURE_PHOTO = byteArrayOf(0x02, 0x01, 0x01)
        val CMD_START_WIFI = byteArrayOf(0x02, 0x01, 0x04)
        val CMD_RESET_P2P = byteArrayOf(0x02, 0x01, 0x0F)
    }

    private val manager get() = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = manager.adapter
    private val handler = Handler(Looper.getMainLooper())

    private var glassesGatt: BluetoothGatt? = null
    private val gatts = mutableMapOf<String, BluetoothGatt>() // diagnostic connections

    private val _status = MutableStateFlow("BLE idle")
    val status: StateFlow<String> = _status

    /** Set when the glasses report their Wi-Fi IP over BLE (notify type 0x08). Cleared on new transfer. */
    private val _glassesWifiIp = MutableStateFlow<String?>(null)
    val glassesWifiIp: StateFlow<String?> = _glassesWifiIp

    /** Glasses battery percent (0–100), pushed unsolicited via a `BC 73 … 05` status frame; null = unknown. */
    private val _battery = MutableStateFlow<Int?>(null)
    val battery: StateFlow<Int?> = _battery

    /** Every notification from any subscribed characteristic (button presses, IP, …). */
    private val _notifications = MutableSharedFlow<GlassesNotification>(extraBufferCapacity = 32)
    val notifications: SharedFlow<GlassesNotification> = _notifications

    /** CCCD writes must be serialized — Android silently drops concurrent descriptor writes. */
    private val cccdQueue = ArrayDeque<BluetoothGattCharacteristic>()

    // ---- Control: connect to the glasses, subscribe to notifications ----

    /** True while we want to stay connected — drives auto-reconnect on an unexpected drop. */
    @Volatile private var wantConnected = false
    private var reconnectAttempts = 0
    private val reconnectRunnable = Runnable { openGatt() }

    fun connectGlasses() {
        wantConnected = true
        ensureBtReceiver()
        if (glassesGatt != null) { _status.value = "BLE: already connected"; return }
        openGatt()
    }

    /**
     * Turning Bluetooth off does NOT deliver a GATT disconnect callback, so the
     * onConnectionStateChange-based reconnect can't see it. Listen for the adapter coming back ON
     * and reconnect then — covering the BT-toggle case on top of range/timeout drops.
     */
    private var btReceiver: BroadcastReceiver? = null
    private fun ensureBtReceiver() {
        if (btReceiver != null) return
        btReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_OFF -> glassesGatt = null // stack tore down, no callback
                    BluetoothAdapter.STATE_ON -> if (wantConnected && glassesGatt == null) {
                        Log.i(TAG, "BT back on — reconnecting glasses")
                        reconnectAttempts = 0
                        handler.postDelayed(reconnectRunnable, 1500) // let the stack settle
                    }
                }
            }
        }
        runCatching { context.registerReceiver(btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)) }
    }

    private fun openGatt() {
        if (glassesGatt != null) return
        _status.value = "BLE: connecting…"
        val device = adapter.getRemoteDevice(GLASSES_ADDR)
        device.connectGatt(context, false, controlCallback)
    }

    /** Stop staying connected and tear the link down (called when no host needs reactions). */
    fun disconnectGlasses() {
        wantConnected = false
        handler.removeCallbacks(reconnectRunnable)
        reconnectAttempts = 0
        btReceiver?.let { runCatching { context.unregisterReceiver(it) }; btReceiver = null }
        glassesGatt?.let { runCatching { it.disconnect(); it.close() } }
        glassesGatt = null
        _status.value = "BLE idle"
    }

    /** Reconnect with capped exponential backoff (2,4,8,16,30s) as long as we want the link. */
    private fun scheduleReconnect() {
        if (!wantConnected) return
        handler.removeCallbacks(reconnectRunnable)
        val delay = minOf(30_000L, 2_000L shl minOf(reconnectAttempts, 4))
        reconnectAttempts++
        _status.value = "BLE: reconnecting in ${delay / 1000}s…"
        Log.i(TAG, "scheduling reconnect in ${delay}ms (attempt $reconnectAttempts)")
        handler.postDelayed(reconnectRunnable, delay)
    }

    private val controlCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "glasses conn status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                glassesGatt = g
                reconnectAttempts = 0 // healthy link — reset backoff
                g.requestMtu(517)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                glassesGatt = null
                runCatching { g.close() } // free the GATT client or it leaks across reconnects
                _status.value = "BLE: disconnected"
                scheduleReconnect() // auto-recover from drops (after Wi-Fi transfer / idle timeout)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { g.discoverServices() }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            // Subscribe to EVERY notify/indicate characteristic: button presses may arrive on any
            // of the candidate channels (de5bf729, ae02/ae04, NUS 6e400003, fee3 — see Glasses_Controls.md §4).
            cccdQueue.clear()
            g.services.forEach { svc ->
                svc.characteristics.forEach { ch ->
                    val p = ch.properties
                    if (p and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                        cccdQueue.add(ch)
                    }
                }
            }
            if (cccdQueue.isEmpty()) { _status.value = "BLE: no notify chars found"; return }
            Log.i(TAG, "subscribing to ${cccdQueue.size} notify/indicate chars")
            subscribeNext(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            subscribeNext(g)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotify(ch.uuid, value)
        }

        @Deprecated("API <33")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") handleNotify(ch.uuid, ch.value ?: return)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            Log.i(TAG, "control write ${ch.uuid} status=$status")
        }
    }

    /** Serialized CCCD-enable: writes one descriptor, continues from onDescriptorWrite. */
    private fun subscribeNext(g: BluetoothGatt) {
        val ch = cccdQueue.removeFirstOrNull()
        if (ch == null) {
            _status.value = "BLE: connected, listening"
            Log.i(TAG, "all notify subscriptions done")
            return
        }
        g.setCharacteristicNotification(ch, true)
        val d = ch.getDescriptor(CCCD) ?: return subscribeNext(g)
        val value = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        Log.i(TAG, "enable notify ${ch.uuid} inst=0x%04X".format(ch.instanceId))
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, value)
        } else {
            @Suppress("DEPRECATION") d.value = value
            @Suppress("DEPRECATION") g.writeDescriptor(d)
        }
    }

    private fun handleNotify(char: UUID, value: ByteArray) {
        val hex = value.joinToString(" ") { "%02X".format(it) }
        Log.i(TAG, "notify ${char}: $hex")
        _notifications.tryEmit(GlassesNotification(char, value))
        // Frame: BC 41 len crc payload; payload[0] = value[6]. 0x08 => Wi-Fi IP in value[7..10].
        if (value.size >= 11 && value[6].toInt() and 0xFF == 0x08) {
            val ip = (7..10).joinToString(".") { (value[it].toInt() and 0xFF).toString() }
            Log.i(TAG, "glasses Wi-Fi IP = $ip")
            _glassesWifiIp.value = ip
            _status.value = "BLE: glasses IP $ip"
        }
        // Battery status (BC 73 … 05 <percent> 00), pushed unsolicited by the glasses.
        (GlassesEvent.parse(value) as? GlassesEvent.Battery)?.let { b ->
            if (b.percent in 0..100) { _battery.value = b.percent; Log.i(TAG, "glasses battery = ${b.percent}%") }
        }
    }

    private fun sendGlassesControl(payload: ByteArray): Boolean {
        val g = glassesGatt ?: run { _status.value = "BLE: not connected"; return false }
        val ch = g.getService(SVC)?.getCharacteristic(WRITE_CHAR) ?: run { _status.value = "BLE: write char missing"; return false }
        writeNoResponse(g, ch, frame(payload))
        return true
    }

    /** Trigger an on-demand photo capture. */
    fun capturePhoto(): Boolean {
        if (glassesGatt == null) connectGlasses()
        return sendGlassesControl(CMD_CAPTURE_PHOTO)
    }

    /** Tell the glasses to bring up Wi-Fi for transfer. IP then arrives via [glassesWifiIp]. */
    fun startWifiTransfer(): Boolean {
        _glassesWifiIp.value = null
        return sendGlassesControl(CMD_START_WIFI)
    }

    fun resetP2p() = sendGlassesControl(CMD_RESET_P2P)

    @Suppress("DEPRECATION")
    private fun writeNoResponse(g: BluetoothGatt, ch: BluetoothGattCharacteristic, data: ByteArray) {
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            ch.value = data
            g.writeCharacteristic(ch)
        }
    }

    // ---- Diagnostics (scan + dump GATT) ----

    fun runDiagnostic() {
        _status.value = "BLE: listing bonded + scanning…"
        val bonded = adapter.bondedDevices.orEmpty()
        Log.i(TAG, "==== BONDED (${bonded.size}) ====")
        bonded.forEach { Log.i(TAG, "BONDED name=${it.name} addr=${it.address}") }
        val scanner = adapter.bluetoothLeScanner
        val seen = HashSet<String>()
        val cb = object : ScanCallback() {
            override fun onScanResult(t: Int, r: ScanResult) {
                if (seen.add(r.device.address)) Log.i(TAG, "SCAN ${r.device.name} ${r.device.address} rssi=${r.rssi}")
            }
        }
        runCatching { scanner?.startScan(cb) }
        handler.postDelayed({
            runCatching { scanner?.stopScan(cb) }
            bonded.forEach { dumpGatt(it) }
        }, 8000)
    }

    private fun dumpGatt(device: BluetoothDevice) {
        device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, s: Int, n: Int) {
                if (n == BluetoothProfile.STATE_CONNECTED) { gatts[device.address] = g; g.requestMtu(517) }
                else if (n == BluetoothProfile.STATE_DISCONNECTED) gatts.remove(device.address)
            }
            override fun onMtuChanged(g: BluetoothGatt, m: Int, s: Int) { g.discoverServices() }
            override fun onServicesDiscovered(g: BluetoothGatt, s: Int) {
                Log.i(TAG, "==== GATT ${device.address} (${device.name}) ====")
                g.services.forEach { svc ->
                    svc.characteristics.forEach { ch ->
                        Log.i(TAG, "  CHR ${ch.uuid} props=${ch.properties} inst=0x%04X".format(ch.instanceId))
                    }
                }
            }
        })
    }

    fun close() {
        runCatching { glassesGatt?.close() }; glassesGatt = null
        gatts.values.forEach { runCatching { it.close() } }; gatts.clear()
    }
}
