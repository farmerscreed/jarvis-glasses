package com.echo.memory

import android.content.Context
import com.echo.memory.local.MemoryDatabase

/**
 * Builds a [MemoryStore] (and its Room DB) without exposing Room to callers. The `:app` DI module
 * depends only on this factory + [MemoryStore]/[ConnectivityGovernor], keeping Room internal to
 * the `:memory` module.
 */
object MemoryStoreFactory {
    fun create(
        context: Context,
        backend: EchoBackend,
        governor: ConnectivityGovernor,
        embedder: Embedder? = null,
    ): MemoryStore {
        val db = MemoryDatabase.build(context.applicationContext)
        return MemoryStore(db.memoryDao(), backend, governor, embedder)
    }
}
