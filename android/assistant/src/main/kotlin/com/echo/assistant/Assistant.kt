package com.echo.assistant

/** A capability the LLM can invoke (function-calling): recall memory, capture a photo, set a reminder… */
interface Tool {
    val name: String
    val description: String
    suspend fun invoke(args: Map<String, Any?>): String
}

class ToolRegistry(private val tools: List<Tool>) {
    fun all(): List<Tool> = tools
    fun byName(name: String): Tool? = tools.firstOrNull { it.name == name }
}

/**
 * The core loop: capture -> retrieve (memory) -> think (LLM + tools) -> act -> speak -> remember.
 * This layer is "ours to own" — it's where the memory-first differentiation lives.
 * Implementation lands in Phase 1 (audio-only Meeting Capture first).
 */
interface AssistantOrchestrator {
    /** Handle one user utterance end-to-end; returns the reply text to speak. */
    suspend fun handle(utterance: String): String
}
