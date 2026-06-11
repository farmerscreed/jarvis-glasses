package com.echo.memory

import com.echo.core.model.Memory
import com.echo.core.model.MemoryType

/**
 * The keystone of the whole app: read/write access to the Personal Memory Index (Supabase pgvector).
 * `recall` maps to the `match_memories` RPC; `remember` embeds + inserts a row.
 * Implementation (Phase 0) wires this to the local Supabase stack via supabase-kt + the Edge Functions.
 */
interface MemoryRepository {
    /** Embed [query] and return the most semantically similar memories for the signed-in user. */
    suspend fun recall(query: String, limit: Int = 10, type: MemoryType? = null): List<Memory>

    /** Embed and persist a new memory; returns it with its server-assigned id. */
    suspend fun remember(memory: Memory): Memory
}
