package com.echo.app.ml

import android.content.Context
import android.util.Log
import com.echo.memory.Embedder
import com.google.mediapipe.tasks.components.containers.Embedding
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * On-device text embedder backed by MediaPipe's TextEmbedder + a bundled Universal Sentence Encoder
 * model (`assets/text_embedder.tflite`, ~6 MB). L2-normalized output so cosine == dot product.
 * Loads lazily on first use; failures degrade gracefully (recall falls back to keyword search).
 */
class MediaPipeEmbedder(private val context: Context) : Embedder {

    private val initLock = Mutex()
    @Volatile private var embedder: TextEmbedder? = null
    @Volatile private var failed = false

    private suspend fun ensureLoaded(): TextEmbedder? {
        embedder?.let { return it }
        if (failed) return null
        return initLock.withLock {
            embedder?.let { return it }
            try {
                val options = TextEmbedder.TextEmbedderOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
                    .setL2Normalize(true)
                    .build()
                TextEmbedder.createFromOptions(context, options).also { embedder = it }
            } catch (e: Exception) {
                Log.w("EchoEmbed", "embedder load failed: ${e.message}")
                failed = true
                null
            }
        }
    }

    override suspend fun embed(text: String): FloatArray? = withContext(Dispatchers.Default) {
        val e = ensureLoaded() ?: return@withContext null
        runCatching {
            val emb: Embedding = e.embed(text).embeddingResult().embeddings().first()
            emb.floatEmbedding()
        }.getOrNull()
    }

    private companion object {
        const val MODEL_ASSET = "text_embedder.tflite"
    }
}
