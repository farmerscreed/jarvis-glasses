package com.echo.device.wifi

import android.net.Network
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.net.InetSocketAddress
import javax.net.SocketFactory

/**
 * Image/video transfer off the glasses. Reverse-engineered (see docs/recon/Transfer_Protocol.md):
 * the glasses run an HTTP server on port 80 over a Wi-Fi Direct link; the app GETs a line-based
 * manifest then each file.
 */
object MediaProtocol {
    const val PORT = 80
    const val CONFIG_PATH = "/files/media.config"  // one filename per line
    const val FILES_PREFIX = "/files/"
}

/** Pulls the glasses' captured media over HTTP once the Wi-Fi link + IP are known. */
class MediaTransferClient(
    @Suppress("unused") private val http: OkHttpClient, // kept for DI compatibility; raw sockets used below
    private val outDir: File,
) {
    companion object {
        const val TAG = "EchoMedia"
        private const val CONNECT_TIMEOUT_MS = 8000
        private const val READ_TIMEOUT_MS = 20000
    }

    /**
     * Pull files named in the manifest that we don't already have (dedup by name; downloads go
     * to a .part temp first so partial files never count as imported). Returns only NEW files.
     *
     * Uses a RAW socket with a bare HTTP/1.0 request, NOT OkHttp: the glasses' embedded Jieli
     * server only speaks minimal HTTP/1.0 and hangs up ("unexpected end of stream") on OkHttp's
     * mandatory 1.1 headers (Host/User-Agent/Connection). [network] is the Wi-Fi Direct group's
     * Network — the socket is created from it so traffic egresses the p2p interface, not the
     * phone's default network (home Wi-Fi).
     */
    /** Whether the last [pull] reached the glasses' server at all (false = link not usable). */
    @Volatile var lastPullReached = false
        private set

    suspend fun pull(
        ip: String,
        network: Network? = null,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        /** Manifest tries (2 s apart). A freshly started server needs a few; a warm link just one or two. */
        manifestAttempts: Int = 6,
    ): List<File> =
        withContext(Dispatchers.IO) {
            outDir.mkdirs()
            Log.i(TAG, "pull from $ip (bound=${network != null})")
            // The server needs a moment after its AP comes up; retry the manifest a few times.
            var manifestBytes: ByteArray? = null
            for (attempt in 1..manifestAttempts) {
                manifestBytes = rawGet(network, ip, MediaProtocol.CONFIG_PATH, attempt)
                if (manifestBytes != null) break
                if (attempt < manifestAttempts) delay(2000)
            }
            lastPullReached = manifestBytes != null
            val manifest = manifestBytes?.toString(Charsets.UTF_8).orEmpty()
            val names = manifest.lines().map { it.trim() }.filter { it.isNotEmpty() }
            Log.i(TAG, "manifest: ${names.size} file(s)")
            val fresh = names.filter { !File(outDir, it).let { f -> f.exists() && f.length() > 0 } }
            val saved = mutableListOf<File>()
            fresh.forEachIndexed { i, name ->
                onProgress(i + 1, fresh.size)
                val out = File(outDir, name)
                val tmp = File(outDir, "$name.part")
                // Streamed to disk (a 3-min glasses video is ~250 MB — far too big to hold in memory); one retry,
                // since the Wi-Fi Direct link sometimes drops mid-file.
                var ok = false
                for (attempt in 1..2) {
                    ok = rawGetToFile(network, ip, MediaProtocol.FILES_PREFIX + name, tmp, attempt)
                    if (ok) break
                    delay(2000)
                }
                if (ok && tmp.renameTo(out)) saved += out
                else Log.w(TAG, "download $name failed")
                tmp.delete()
            }
            Log.i(TAG, "pulled ${saved.size}/${fresh.size} new file(s)")
            saved
        }

    /**
     * One bare HTTP/1.0 GET over a raw socket. Returns the response body bytes on a 200, or null.
     * Reads to EOF (HTTP/1.0 servers close after the body). No Host/User-Agent — a minimal
     * embedded server may reject anything beyond the request line.
     */
    private fun rawGet(network: Network?, ip: String, path: String, attempt: Int): ByteArray? {
        val socket = (network?.socketFactory ?: SocketFactory.getDefault()).createSocket()
        return try {
            socket.soTimeout = READ_TIMEOUT_MS
            socket.connect(InetSocketAddress(ip, MediaProtocol.PORT), CONNECT_TIMEOUT_MS)
            socket.getOutputStream().apply {
                write("GET $path HTTP/1.0\r\n\r\n".toByteArray(Charsets.US_ASCII))
                flush()
            }
            val raw = socket.getInputStream().readBytes() // to EOF
            val sep = indexOfHeaderEnd(raw)
            if (sep < 0) { Log.w(TAG, "no header terminator for $path (try $attempt, ${raw.size}B)"); return null }
            val statusLine = String(raw, 0, minOf(sep, 64), Charsets.ISO_8859_1).substringBefore("\r\n")
            if (!statusLine.contains(" 200")) { Log.w(TAG, "$path -> '$statusLine' (try $attempt)"); return null }
            raw.copyOfRange(sep, raw.size)
        } catch (e: Exception) {
            Log.w(TAG, "$path raw GET failed (try $attempt): ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    /** Like [rawGet] but writes the body to [dest] as it arrives. True on a 200 with a non-empty body. */
    private fun rawGetToFile(network: Network?, ip: String, path: String, dest: File, attempt: Int): Boolean {
        val socket = (network?.socketFactory ?: SocketFactory.getDefault()).createSocket()
        return try {
            socket.soTimeout = READ_TIMEOUT_MS
            socket.connect(InetSocketAddress(ip, MediaProtocol.PORT), CONNECT_TIMEOUT_MS)
            socket.getOutputStream().apply {
                write("GET $path HTTP/1.0\r\n\r\n".toByteArray(Charsets.US_ASCII))
                flush()
            }
            val input = java.io.BufferedInputStream(socket.getInputStream(), 64 * 1024)
            // Header: read byte by byte up to CRLFCRLF (it's short).
            val head = java.io.ByteArrayOutputStream()
            var last4 = 0
            while (true) {
                val b = input.read()
                if (b < 0) { Log.w(TAG, "no header terminator for $path (try $attempt)"); return false }
                head.write(b)
                last4 = (last4 shl 8) or b
                if (last4 == 0x0D0A0D0A) break
                if (head.size() > 16 * 1024) { Log.w(TAG, "header too long for $path"); return false }
            }
            val statusLine = head.toString(Charsets.ISO_8859_1.name()).substringBefore("\r\n")
            if (!statusLine.contains(" 200")) { Log.w(TAG, "$path -> '$statusLine' (try $attempt)"); return false }
            val n = dest.outputStream().use { input.copyTo(it, 64 * 1024) }
            if (n <= 0) { Log.w(TAG, "$path: empty body (try $attempt)"); return false }
            Log.i(TAG, "$path: ${n / 1000} KB")
            true
        } catch (e: Exception) {
            Log.w(TAG, "$path download failed (try $attempt): ${e.javaClass.simpleName}: ${e.message}")
            false
        } finally {
            runCatching { socket.close() }
        }
    }

    /** Index just past the first CRLFCRLF (header/body boundary), or -1. */
    private fun indexOfHeaderEnd(b: ByteArray): Int {
        for (i in 0..b.size - 4) {
            if (b[i] == '\r'.code.toByte() && b[i + 1] == '\n'.code.toByte() &&
                b[i + 2] == '\r'.code.toByte() && b[i + 3] == '\n'.code.toByte()
            ) return i + 4
        }
        return -1
    }

    /** The most recently synced photo (jpg/png), or null. */
    fun latestPhoto(): File? = outDir.listFiles { f ->
        f.extension.lowercase() in setOf("jpg", "jpeg", "png")
    }?.maxByOrNull { it.lastModified() }

    /** All media files pulled to local storage (for reconciling orphans into memories). */
    fun mediaFiles(): List<File> = outDir.listFiles { f ->
        f.isFile && f.extension.lowercase() in
            setOf("jpg", "jpeg", "png", "mp4", "wav", "mp3", "aac", "m4a", "amr", "ogg", "opus")
    }?.toList().orEmpty()
}
