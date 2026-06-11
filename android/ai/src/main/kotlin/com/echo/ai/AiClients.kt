package com.echo.ai

/**
 * Model-provider abstractions. Each implementation calls a Supabase Edge Function so that API keys
 * stay server-side; the concrete provider (Claude / Deepgram / ElevenLabs / Voyage / OpenAI) can be
 * swapped without touching callers. See 01_IMPLEMENTATION_PLAN.md §1.
 */

data class ChatMessage(val role: String, val content: String)

/** Reasoning + vision (Claude by default). */
interface LlmClient {
    suspend fun chat(messages: List<ChatMessage>, system: String? = null): String
}

/** Turn text into a vector for the Personal Memory Index. Dim MUST match the DB (currently 1536). */
interface EmbeddingClient {
    suspend fun embed(text: String): FloatArray
}

/** Speech-to-text. [audio] is encoded/PCM bytes; returns the transcript. */
interface SttClient {
    suspend fun transcribe(audio: ByteArray): String
}

/** Text-to-speech. Returns encoded audio bytes to play over A2DP. */
interface TtsClient {
    suspend fun synthesize(text: String): ByteArray
}
