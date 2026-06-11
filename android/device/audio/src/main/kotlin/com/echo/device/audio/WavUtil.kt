package com.echo.device.audio

/** Wrap raw 16-bit PCM in a minimal WAV container so cloud STT can decode it. */
object WavUtil {
    fun pcm16ToWav(pcm: ByteArray, sampleRate: Int, channels: Int = 1): ByteArray {
        val byteRate = sampleRate * channels * 2
        val header = ByteArray(44)

        fun str(off: Int, s: String) { for (i in s.indices) header[off + i] = s[i].code.toByte() }
        fun int(off: Int, v: Int) {
            header[off] = (v and 0xff).toByte()
            header[off + 1] = ((v shr 8) and 0xff).toByte()
            header[off + 2] = ((v shr 16) and 0xff).toByte()
            header[off + 3] = ((v shr 24) and 0xff).toByte()
        }
        fun short(off: Int, v: Int) {
            header[off] = (v and 0xff).toByte()
            header[off + 1] = ((v shr 8) and 0xff).toByte()
        }

        str(0, "RIFF"); int(4, 36 + pcm.size); str(8, "WAVE")
        str(12, "fmt "); int(16, 16); short(20, 1); short(22, channels)
        int(24, sampleRate); int(28, byteRate); short(32, channels * 2); short(34, 16)
        str(36, "data"); int(40, pcm.size)

        return header + pcm
    }
}
