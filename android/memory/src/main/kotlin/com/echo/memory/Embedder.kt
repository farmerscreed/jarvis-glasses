package com.echo.memory

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * On-device text → vector, for OFFLINE semantic recall (Phase C §4.2). The vector is L2-normalized
 * so cosine similarity is a plain dot product. Kept as an interface so `:memory` doesn't depend on
 * the ML runtime; `:app` supplies a MediaPipe-backed implementation.
 *
 * Dual-embedding: this local vector is used ONLY for off-grid recall. When a memory syncs, the
 * server re-embeds it with Gemini (1536-dim) as the canonical online vector — the two spaces never
 * mix, so there's no migration hazard.
 */
interface Embedder {
    /** Returns an L2-normalized embedding, or null if the model is unavailable. */
    suspend fun embed(text: String): FloatArray?
}

/** Pack/unpack a FloatArray to a BLOB for Room, and cosine over normalized vectors. */
object VectorUtil {
    fun toBytes(v: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in v) buf.putFloat(f)
        return buf.array()
    }

    fun fromBytes(b: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.size / 4) { buf.float }
    }

    /** Dot product; equals cosine similarity when both inputs are L2-normalized. */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return -1f
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }
}
