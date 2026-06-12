package com.echo.memory

import com.echo.core.model.Memory
import com.echo.core.model.MemoryType
import kotlinx.serialization.Serializable

@Serializable
data class AuthRequest(val email: String, val password: String)

@Serializable
data class AuthResponse(
    val access_token: String? = null,
    val refresh_token: String? = null,
    val user: AuthUser? = null,
)

@Serializable
data class OtpRequest(val email: String, val create_user: Boolean = true)

@Serializable
data class VerifyOtpRequest(val email: String, val token: String, val type: String = "email")

@Serializable
data class RefreshRequest(val refresh_token: String)

@Serializable
data class AuthUser(val id: String? = null)

@Serializable
data class IngestRequest(
    val text: String,
    val type: String = "note",
    val media_path: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val tags: List<String> = emptyList(),
    val client_id: String? = null,
)

@Serializable
data class SignUrlRequest(val expiresIn: Int)

@Serializable
data class SignUrlResponse(val signedURL: String = "")

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
data class VisionRequest(val imageBase64: String, val mediaType: String = "image/jpeg", val prompt: String? = null)

@Serializable
data class VisionResponse(val text: String = "")

@Serializable
data class ChatRequest(val message: String)

@Serializable
data class ChatResponse(
    val answer: String = "",
    val memories_used: List<MemoryDto> = emptyList(),
)

/** SSE payloads from /chat-stream. */
@Serializable
data class StreamDelta(val t: String = "")

@Serializable
data class StreamMemories(val matches: List<MemoryDto> = emptyList())

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
