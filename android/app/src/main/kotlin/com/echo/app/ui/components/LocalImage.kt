package com.echo.app.ui.components

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

/**
 * Loads a downscaled bitmap for a local media file (glasses captures live in app storage).
 * Decodes off the main thread with inSampleSize; returns null while loading / if missing.
 */
@Composable
fun rememberLocalImage(path: String?, targetPx: Int = 512): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value = path?.let {
            withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(it)
                    if (!file.exists()) return@runCatching null
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(it, bounds)
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= targetPx) sample *= 2
                    BitmapFactory.decodeFile(it, BitmapFactory.Options().apply { inSampleSize = sample })
                        ?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    return bitmap
}

/**
 * Loads a bitmap from a remote URL (a Supabase signed media URL) off the main thread.
 * Returns null while loading / on failure. For synced photos whose local capture file is gone.
 */
@Composable
fun rememberRemoteImage(url: String?): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, url) {
        value = url?.let {
            withContext(Dispatchers.IO) {
                runCatching {
                    URL(it).openStream().use { stream -> BitmapFactory.decodeStream(stream) }?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    return bitmap
}
