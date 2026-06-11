package com.echo.device.ble

/**
 * Decoded glasses notification (frame `BC 73 <len:2LE> <CRC16-MODBUS:2LE> <payload>` on
 * char de5bf729). Byte map reverse-engineered on device 2026-06-12 — see
 * docs/recon/Glasses_Controls.md §4.
 */
sealed class GlassesEvent {
    /**
     * A capture was just saved on the glasses (photo taken, or audio/video recording stopped).
     * Carries the glasses' current file inventory.
     * Payload: `01 <photos:u16LE> <videos:u16LE> <audio:u16LE> 01`.
     */
    data class CaptureSaved(val photos: Int, val videos: Int, val audio: Int) : GlassesEvent()

    /** Double-click BACK ("AI fast image recognition") — pure button signal, no file is saved. */
    object AiGesture : GlassesEvent()

    /** ~3 s heartbeat while an audio/video recording is in progress. Payload: `0B <counter>`. */
    data class RecordingTick(val counter: Int) : GlassesEvent()

    /** The glasses' Wi-Fi IP for media transfer. Payload: `08 <ip:4>`. */
    data class WifiIp(val ip: String) : GlassesEvent()

    companion object {
        /**
         * Parse a raw notification; null if it isn't a recognized event frame.
         * Events are strictly opcode `BC 73`. The same char also carries `BC 41` frames — those
         * are command-ACK echoes (payload mirrors the sent command, e.g. `02 01 01 FF 01` after
         * the capture cmd) and MUST NOT be parsed as events: an ACK starting `02` would otherwise
         * read as AiGesture and trigger a self-sustaining capture/ack storm (observed 2026-06-12).
         */
        fun parse(bytes: ByteArray): GlassesEvent? {
            if (bytes.size < 7 || bytes[0] != 0xBC.toByte() || bytes[1] != 0x73.toByte()) return null
            val len = (bytes[2].toInt() and 0xFF) or ((bytes[3].toInt() and 0xFF) shl 8)
            if (len < 1 || bytes.size < 6 + len) return null
            val p = bytes.copyOfRange(6, 6 + len)
            return when (p[0].toInt() and 0xFF) {
                0x01 -> if (len >= 7) CaptureSaved(u16(p, 1), u16(p, 3), u16(p, 5)) else null
                0x02 -> AiGesture
                0x0B -> RecordingTick(if (len >= 2) p[1].toInt() and 0xFF else 0)
                0x08 -> if (len >= 5) WifiIp((1..4).joinToString(".") { (p[it].toInt() and 0xFF).toString() }) else null
                else -> null
            }
        }

        private fun u16(p: ByteArray, i: Int): Int =
            (p[i].toInt() and 0xFF) or ((p[i + 1].toInt() and 0xFF) shl 8)
    }
}
