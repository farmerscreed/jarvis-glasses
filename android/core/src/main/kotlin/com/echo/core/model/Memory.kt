package com.echo.core.model

import java.time.Instant

/** Kind of remembered event. Mirrors the `type` check constraint on the Supabase `memories` table. */
enum class MemoryType(val wire: String) {
    NOTE("note"),
    VOICE_NOTE("voice_note"),
    MEETING("meeting"),
    PHOTO("photo"),
    QA("qa"),
    OCR("ocr"),
    JOURNAL("journal");

    companion object {
        fun fromWire(value: String): MemoryType = entries.first { it.wire == value }
    }
}

/**
 * A single entry in the Personal Memory Index.
 * Mirrors `public.memories` in Supabase (see ../../supabase/migrations).
 */
data class Memory(
    val id: String? = null,
    val type: MemoryType,
    val text: String? = null,
    val mediaPath: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val tags: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Instant? = null,
    /** Cosine similarity in [0,1]; populated only on recall results. */
    val similarity: Double? = null,
)
