package com.echo.app.video

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The glasses' audio recordings (`*.opus`, hold BACK / M8 glasses meetings) are NOT Ogg files: they're bare Opus packets
 * back to back — the Jieli chip's format, decoded by the stock app's `OpusManager` with `OpusOption{channel=1,
 * sampleRate=16000, packetSize=40}`. Each 40-byte block is one standard Opus packet of 20 ms (16 kbps CBR: TOC 0x4B =
 * SILK wideband 20 ms, code 3 with padding), so 730 blocks = 14.6 s. Android (and the cloud) can't open that, so we wrap
 * the packets in a standard Ogg Opus file (RFC 7845) — no re-encoding. Found 2026-10-17 (a glasses meeting test said
 * "can't read …opus").
 */
object GlassesAudio {
    const val PACKET = 40
    private const val SAMPLES_PER_PACKET_48K = 960 // 20 ms at 48 kHz (Ogg Opus granules always count 48 kHz)
    private const val PACKETS_PER_PAGE = 50        // 1 s per page

    /** True if [bytes] look like the glasses' bare 40-byte Opus packets (not already Ogg). */
    fun isBarePackets(bytes: ByteArray): Boolean {
        if (bytes.size < PACKET || bytes.size % PACKET != 0) return false
        if (bytes[0] == 'O'.code.toByte() && bytes[1] == 'g'.code.toByte() && bytes[2] == 'g'.code.toByte() && bytes[3] == 'S'.code.toByte()) return false
        // Every packet's TOC byte has the same mode/bandwidth/frame size (top 5 bits) and channel bit; only the packing
        // code (low 2 bits) varies — the glasses mix 0x4B (code 3 + padding) and 0x48 (code 0, exactly 40 bytes).
        val toc = bytes[0].toInt() and 0xFC
        val n = bytes.size / PACKET
        return (0 until minOf(n, 50)).all { (bytes[it * PACKET].toInt() and 0xFC) == toc }
    }

    /** Length in ms of a bare-packet file (0 if it isn't one). */
    fun durationMs(f: File): Long = runCatching {
        if (f.length() < PACKET || f.length() % PACKET != 0L) return 0L
        val head = ByteArray(minOf(f.length(), PACKET * 20L).toInt())
        f.inputStream().use { it.read(head) }
        if (isBarePackets(head)) f.length() / PACKET * 20 else 0L
    }.getOrDefault(0L)

    /** A standard Ogg Opus file holding the same packets (mono, 16 kHz input). */
    fun toOgg(bytes: ByteArray, serial: Int = 0x4A415256 /* "JARV" */): ByteArray {
        val packets = (0 until bytes.size / PACKET).map { bytes.copyOfRange(it * PACKET, (it + 1) * PACKET) }
        val out = ByteArrayOutputStream()
        var seq = 0
        // 1. OpusHead
        val head = ByteArrayOutputStream().apply {
            write("OpusHead".toByteArray(Charsets.US_ASCII)); write(1); write(1) // version, channels
            le16(0)            // pre-skip (none — the glasses' encoder adds none we know of)
            le32(16000)        // input sample rate (informational)
            le16(0); write(0)  // output gain, mapping family 0
        }.toByteArray()
        out.write(page(listOf(head), granule = 0, serial = serial, seq = seq++, flags = 0x02))
        // 2. OpusTags
        val vendor = "JARVIS".toByteArray(Charsets.US_ASCII)
        val tags = ByteArrayOutputStream().apply { write("OpusTags".toByteArray(Charsets.US_ASCII)); le32(vendor.size); write(vendor); le32(0) }.toByteArray()
        out.write(page(listOf(tags), granule = 0, serial = serial, seq = seq++, flags = 0))
        // 3. Audio pages
        var done = 0L
        packets.chunked(PACKETS_PER_PAGE).forEachIndexed { i, group ->
            done += group.size
            val last = i == (packets.size - 1) / PACKETS_PER_PAGE
            out.write(page(group, granule = done * SAMPLES_PER_PACKET_48K, serial = serial, seq = seq++, flags = if (last) 0x04 else 0))
        }
        return out.toByteArray()
    }

    /** [f] as a playable Ogg Opus file in [outDir] (the file itself if it isn't bare packets). */
    fun playable(f: File, outDir: File): File {
        val bytes = f.readBytes()
        if (!isBarePackets(bytes)) return f
        outDir.mkdirs()
        return File(outDir, f.nameWithoutExtension + ".ogg").apply { writeBytes(toOgg(bytes)) }
    }

    private fun page(packets: List<ByteArray>, granule: Long, serial: Int, seq: Int, flags: Int): ByteArray {
        val lacing = ByteArrayOutputStream()
        for (p in packets) { var n = p.size; while (n >= 255) { lacing.write(255); n -= 255 }; lacing.write(n) }
        val seg = lacing.toByteArray()
        require(seg.size <= 255) { "too many segments on one page" }
        val b = ByteArrayOutputStream().apply {
            write("OggS".toByteArray(Charsets.US_ASCII)); write(0); write(flags)
            le64(granule); le32(serial); le32(seq); le32(0) // CRC filled in below
            write(seg.size); write(seg)
            packets.forEach { write(it) }
        }.toByteArray()
        val crc = crc(b)
        b[22] = (crc and 0xFF).toByte(); b[23] = ((crc shr 8) and 0xFF).toByte()
        b[24] = ((crc shr 16) and 0xFF).toByte(); b[25] = ((crc shr 24) and 0xFF).toByte()
        return b
    }

    private val CRC_TABLE = IntArray(256) { i ->
        var r = i shl 24
        repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
        r
    }

    /** Ogg's CRC-32 (polynomial 0x04C11DB7, initial 0, not reflected). */
    fun crc(data: ByteArray): Int {
        var c = 0
        for (x in data) c = (c shl 8) xor CRC_TABLE[((c ushr 24) xor (x.toInt() and 0xFF)) and 0xFF]
        return c
    }

    private fun ByteArrayOutputStream.le16(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF) }
    private fun ByteArrayOutputStream.le32(v: Int) { repeat(4) { write((v shr (8 * it)) and 0xFF) } }
    private fun ByteArrayOutputStream.le64(v: Long) { repeat(8) { write(((v shr (8 * it)) and 0xFF).toInt()) } }
}
