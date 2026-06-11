package com.echo.device.wifi

/**
 * Image/video transfer off the glasses. Reverse-engineered (Sessions 03–04): the glasses run an
 * HTTP server on port 80, reachable over a Wi-Fi Direct link (phone = group owner). The app fetches
 * a manifest, then each file. See 00_HANDOFF_START_HERE.md §4.
 */
object MediaProtocol {
    const val PORT = 80
    const val CONFIG_PATH = "/files/media.config"  // manifest of media available to import
    const val FILES_PREFIX = "/files/"             // each media file: /files/<filename>
    const val LOG_PATH = "/files/log/log.list"

    fun configUrl(glassesIp: String): String = "http://$glassesIp$CONFIG_PATH"
    fun fileUrl(glassesIp: String, fileName: String): String = "http://$glassesIp$FILES_PREFIX$fileName"
}

/**
 * Orchestrates: BLE start command -> Wi-Fi Direct group -> read glasses IP (from BLE) ->
 * GET /files/media.config -> GET each file. Implementation = Phase 2.
 */
interface GlassesMediaTransfer {
    /** Bring up the transfer session, download all pending media, and return local file paths. */
    suspend fun importPending(): List<String>
}
