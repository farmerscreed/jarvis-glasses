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
