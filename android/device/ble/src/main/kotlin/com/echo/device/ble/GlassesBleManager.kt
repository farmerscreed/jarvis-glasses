package com.echo.device.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Real BLE control of the AIMB-G2 glasses (Phase 0D). Two jobs:
 *  1) DISCOVER — enumerate bonded devices, scan, connect + dump every service/characteristic
 *     (UUID, properties, instanceId) so we can resolve the camera endpoint that serves ATT
 *     handle 0x008E (its UUID was never captured — see handoff "Open Items").
 *  2) CAPTURE — write [GlassesProtocol.CAMERA_CAPTURE] to that characteristic to shoot a photo.
 * Everything is logged to Logcat tag "EchoBle" (read with: adb logcat -s EchoBle).
 */
@SuppressLint("MissingPermission")
class GlassesBleManager(private val context: Context) {

    companion object {
        const val TAG = "EchoBle"
        /** The camera trigger's ATT value handle, confirmed in Session 02. */
        const val CAPTURE_HANDLE = 0x008E
    }

    private val manager get() = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = manager.adapter
    private val handler = Handler(Looper.getMainLooper())
    private val gatts = mutableMapOf<String, BluetoothGatt>()
    /** address -> (serviceUuid, charUuid) of the characteristic whose instanceId == CAPTURE_HANDLE. */
    private val captureTargets = mutableMapOf<String, Pair<UUID, UUID>>()

    private val _status = MutableStateFlow("BLE idle")
    val status: StateFlow<String> = _status

    /** Enumerate bonded devices, scan ~8s, then connect to every bonded device and dump its GATT. */
    fun runDiagnostic() {
        _status.value = "BLE: listing bonded + scanning…"
        val bonded = adapter.bondedDevices.orEmpty()
        Log.i(TAG, "==== BONDED (${bonded.size}) ====")
        bonded.forEach { Log.i(TAG, "BONDED name=${it.name} addr=${it.address} type=${it.type}") }

        val scanner = adapter.bluetoothLeScanner
        val seen = HashSet<String>()
        val scanCb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val d = result.device
                if (seen.add(d.address)) {
                    Log.i(TAG, "SCAN name=${d.name ?: result.scanRecord?.deviceName} addr=${d.address} rssi=${result.rssi} uuids=${result.scanRecord?.serviceUuids}")
                }
            }
        }
        runCatching { scanner?.startScan(scanCb) }
        handler.postDelayed({
            runCatching { scanner?.stopScan(scanCb) }
            Log.i(TAG, "==== SCAN done; connecting to bonded devices ====")
            bonded.forEach { connectAndDump(it) }
            _status.value = "BLE: discovering GATT (see logcat)…"
        }, 8000)
    }

    private fun connectAndDump(device: BluetoothDevice) {
        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                Log.i(TAG, "conn ${device.address} status=$status newState=$newState")
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatts[device.address] = g
                    g.requestMtu(517)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    gatts.remove(device.address)
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                Log.i(TAG, "mtu ${device.address} = $mtu; discovering…")
                g.discoverServices()
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                Log.i(TAG, "==== GATT ${device.address} (${device.name}) status=$status ====")
                g.services.forEach { svc ->
                    Log.i(TAG, "SVC ${svc.uuid}")
                    svc.characteristics.forEach { ch ->
                        val handleHex = "0x%04X".format(ch.instanceId)
                        val mark = if (ch.instanceId == CAPTURE_HANDLE) "  <== CAPTURE HANDLE 0x008E" else ""
                        Log.i(TAG, "  CHR ${ch.uuid} props=${ch.properties} inst=$handleHex$mark")
                        if (ch.instanceId == CAPTURE_HANDLE) {
                            captureTargets[device.address] = svc.uuid to ch.uuid
                            _status.value = "Found capture char on ${device.address}: ${ch.uuid}"
                            Log.i(TAG, "RESOLVED capture target: addr=${device.address} svc=${svc.uuid} chr=${ch.uuid}")
                        }
                    }
                }
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
                Log.i(TAG, "WRITE ${ch.uuid} status=$status (0=success)")
                _status.value = if (status == 0) "Capture command written OK" else "Capture write failed ($status)"
            }
        }
        device.connectGatt(context, false, cb)
    }

    /** Write the capture trigger to the resolved characteristic (instanceId 0x008E). */
    fun capturePhoto(): Boolean {
        val (addr, target) = captureTargets.entries.firstOrNull()?.let { it.key to it.value }
            ?: run { _status.value = "No capture target resolved yet — run diagnostic first"; return false }
        val g = gatts[addr] ?: run { _status.value = "Not connected to $addr"; return false }
        val ch = g.getService(target.first)?.getCharacteristic(target.second)
            ?: run { _status.value = "Capture characteristic missing"; return false }
        _status.value = "Writing CAMERA_CAPTURE to ${ch.uuid}…"
        writeNoResponse(g, ch, GlassesProtocol.CAMERA_CAPTURE)
        return true
    }

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

    fun close() {
        gatts.values.forEach { runCatching { it.close() } }
        gatts.clear()
    }
}
