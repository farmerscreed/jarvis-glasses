package com.echo.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesAudioTest {
    /** Fake glasses recording: [n] 40-byte packets, each starting with TOC 0x4B and frame-count byte 0x41 ("KA"). */
    private fun bare(n: Int) = ByteArray(n * GlassesAudio.PACKET) { i -> when (i % GlassesAudio.PACKET) { 0 -> 0x4B; 1 -> 0x41; else -> (i % 7).toByte() } }

    @Test fun oggCrcMatchesTheStandardCheckValue() {
        // CRC-32 poly 0x04C11DB7, init 0, no reflection, no final xor → check value for "123456789".
        assertEquals(0x89A1897F.toInt(), GlassesAudio.crc("123456789".toByteArray()))
    }

    @Test fun acceptsMixedPackingCodesLikeTheRealGlasses() {
        // Real glasses files mix TOC 0x4B and 0x48 (same mode, different packing) — 2026-10-17 a file starting this way was missed.
        val b = bare(30); b[0] = 0x48; b[GlassesAudio.PACKET * 3] = 0x48
        assertTrue(GlassesAudio.isBarePackets(b))
        b[GlassesAudio.PACKET * 5] = 0x08 // a different mode = not one recording
        assertFalse(GlassesAudio.isBarePackets(b))
    }

    @Test fun recognisesBarePacketsButNotOgg() {
        assertTrue(GlassesAudio.isBarePackets(bare(30)))
        assertFalse(GlassesAudio.isBarePackets(GlassesAudio.toOgg(bare(30))))
        assertFalse(GlassesAudio.isBarePackets(ByteArray(41)))
    }

    @Test fun wrapsEveryPacketIntoOggPages() {
        val ogg = GlassesAudio.toOgg(bare(120)) // 2.4 s → head + tags + 3 audio pages (50, 50, 20 packets)
        val pages = mutableListOf<Int>()
        var i = 0
        while (i < ogg.size) {
            assertEquals("OggS", String(ogg, i, 4, Charsets.US_ASCII))
            // the stored CRC equals the CRC of the page with its CRC field zeroed
            val segs = ogg[i + 26].toInt() and 0xFF
            val body = (0 until segs).sumOf { ogg[i + 27 + it].toInt() and 0xFF }
            val len = 27 + segs + body
            val page = ogg.copyOfRange(i, i + len)
            val stored = (0..3).sumOf { (page[22 + it].toInt() and 0xFF) shl (8 * it) }
            for (k in 22..25) page[k] = 0
            assertEquals(stored, GlassesAudio.crc(page))
            pages += ogg[i + 5].toInt() and 0xFF // header type
            i += len
        }
        assertEquals(listOf(0x02, 0, 0, 0, 0x04), pages) // BOS … EOS
        assertEquals("OpusHead", String(ogg, 28, 8, Charsets.US_ASCII))
        // last page granule = 120 packets × 960 samples (48 kHz)
        var last = 0; i = 0
        while (i < ogg.size) { last = i; val segs = ogg[i + 26].toInt() and 0xFF; i += 27 + segs + (0 until segs).sumOf { ogg[i + 27 + it].toInt() and 0xFF } }
        val granule = (0..7).sumOf { ((ogg[last + 6 + it].toLong() and 0xFF) shl (8 * it)) }
        assertEquals(120L * 960, granule)
    }
}
