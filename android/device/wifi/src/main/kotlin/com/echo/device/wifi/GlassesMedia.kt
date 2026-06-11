package com.echo.device.wifi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Image/video transfer off the glasses. Reverse-engineered (see docs/recon/Transfer_Protocol.md):
 * the glasses run an HTTP server on port 80 over a Wi-Fi Direct link; the app GETs a line-based
 * manifest then each file.
 */
object MediaProtocol {
    const val PORT = 80
    const val CONFIG_PATH = "/files/media.config"  // one filename per line
    const val FILES_PREFIX = "/files/"
    fun configUrl(ip: String) = "http://$ip$CONFIG_PATH"
    fun fileUrl(ip: String, name: String) = "http://$ip$FILES_PREFIX$name"
}

/** Pulls the glasses' captured media over HTTP once the Wi-Fi link + IP are known. */
class MediaTransferClient(
    private val http: OkHttpClient,
    private val outDir: File,
) {
    /** Returns (manifest filenames, locally saved files). */
    suspend fun pull(ip: String, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<File> =
        withContext(Dispatchers.IO) {
            outDir.mkdirs()
            val manifest = http.newCall(Request.Builder().url(MediaProtocol.configUrl(ip)).build())
                .execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyList()
                    resp.body?.string().orEmpty()
                }
            val names = manifest.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val saved = mutableListOf<File>()
            names.forEachIndexed { i, name ->
                onProgress(i + 1, names.size)
                val out = File(outDir, name)
                runCatching {
                    http.newCall(Request.Builder().url(MediaProtocol.fileUrl(ip, name)).build())
                        .execute().use { resp ->
                            if (resp.isSuccessful) {
                                resp.body?.byteStream()?.use { input ->
                                    out.outputStream().use { input.copyTo(it) }
                                }
                                saved += out
                            }
                        }
                }
            }
            saved
        }

    /** The most recently synced photo (jpg/png), or null. */
    fun latestPhoto(): File? = outDir.listFiles { f ->
        f.extension.lowercase() in setOf("jpg", "jpeg", "png")
    }?.maxByOrNull { it.lastModified() }
}
