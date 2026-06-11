package com.echo.device.ble

/**
 * BLE control of the AIMB-G2 glasses. The stock app drives these through the closed
 * `com.oudmon.ble` SDK; we reimplement only the commands we need, validated against captured
 * HCI-snoop bytes. See ../../../Jarvis Glasses/00_HANDOFF_START_HERE.md (§4) and Device_Recon_Record.md.
 */
object GlassesProtocol {
    /**
     * Reverse-engineered photo-capture trigger. Confirmed by 3x timing correlation in Session 02.
     * Write to the CAMERA endpoint (a second BLE device), ATT handle 0x008E.
     * Frame: BC | cmd(0x41) | len(0x0003 LE) | payload(10 50 02) | trailer(01 01).
     */
    val CAMERA_CAPTURE: ByteArray = byteArrayOf(
        0xBC.toByte(), 0x41, 0x03, 0x00, 0x10, 0x50, 0x02, 0x01, 0x01,
    )

    /** oudmon `glassesControl({2,1,15})` — reset/start the Wi-Fi Direct transfer session. */
    val WIFI_P2P_START: ByteArray = byteArrayOf(0x02, 0x01, 0x0F)

    /** Main-controller (JL7018F) Nordic-UART command channel. */
    const val NUS_SERVICE = "6e40fff0-b5a3-f393-e0a9-e50e24dcca9e"
    const val NUS_WRITE = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
    const val NUS_NOTIFY = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"

    // Camera capture target — RESOLVED + confirmed on hardware (Phase 0D, 2026-06-11):
    // handle 0x008E is characteristic de5bf72a in service de5bf728 on the MAIN glasses (addr 63:93:E1:8A:A0:34).
    // Writing CAMERA_CAPTURE here (WRITE_NO_RESPONSE) shoots a photo — verified: 3 writes -> 3 new photos.
    const val CAPTURE_SERVICE = "de5bf728-d711-4e47-af26-65e3012a5dc7"
    const val CAPTURE_CHAR = "de5bf72a-d711-4e47-af26-65e3012a5dc7"
}

/** High-level BLE control surface for the glasses. Implementation = Phase 0D. */
interface GlassesBleClient {
    suspend fun connect(): Boolean
    fun isConnected(): Boolean
    /** Trigger an on-demand photo capture (writes [GlassesProtocol.CAMERA_CAPTURE]). */
    suspend fun capturePhoto()
    /** Ask the glasses to bring up the Wi-Fi Direct transfer session. */
    suspend fun startWifiTransfer()
    suspend fun batteryLevel(): Int?
}
