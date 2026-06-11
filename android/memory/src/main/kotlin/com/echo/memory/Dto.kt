package com.echo.memory

import com.echo.core.model.Memory
import com.echo.core.model.MemoryType
import kotlinx.serialization.Serializable

@Serializable
data class AuthRequest(val email: String, val password: String)

@Serializable
data class AuthResponse(val access_token: String? = null)

@Serializable
data class IngestRequest(val text: String, val type: String = "note")

@Serializable
data class IngestResponse(val memory: MemoryDto)

@Serializable
data class RecallRequest(val query: String, val limit: Int = 10, val type: String? = null)

@Serializable
data class RecallResponse(val matches: List<MemoryDto> = emptyList())

@Serializable
data class TranscribeRequest(val audioBase64: String, val mimeType: String = "audio/wav")

@Serializable
data class TranscribeResponse(val text: String = "")

@Serializable
data class ChatRequest(val message: String)

@Serializable
data class ChatResponse(
    val answer: String = "",
    val memories_used: List<MemoryDto> = emptyList(),
)

@Serializable
data class MemoryDto(
    val id: String? = null,
    val type: String = "note",
    val text: String? = null,
    val similarity: Double? = null,
    val created_at: String? = null,
    val media_path: String? = null,
) {
    fun toMemory(): Memory = Memory(
        id = id,
        type = runCatching { MemoryType.fromWire(type) }.getOrDefault(MemoryType.NOTE),
        text = text,
        mediaPath = media_path,
        similarity = similarity,
    )
}

/** Result of a RAG chat turn. */
data class ChatResult(val answer: String, val memoriesUsed: List<Memory>)
