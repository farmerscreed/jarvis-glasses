package com.echo.memory.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: LocalMemory)

    /** The outbox: rows still owed to the cloud, oldest first. */
    @Query("SELECT * FROM local_memories WHERE syncState != 'SYNCED' ORDER BY createdAt ASC")
    suspend fun pending(): List<LocalMemory>

    @Query("SELECT COUNT(*) FROM local_memories WHERE syncState != 'SYNCED'")
    fun pendingCount(): Flow<Int>

    @Query("SELECT * FROM local_memories WHERE clientId = :clientId")
    suspend fun byClientId(clientId: String): LocalMemory?

    @Query("UPDATE local_memories SET mediaPath = :mediaPath, updatedAt = :now WHERE clientId = :clientId")
    suspend fun setMediaPath(clientId: String, mediaPath: String, now: Long)

    @Query("UPDATE local_memories SET embedding = :embedding WHERE clientId = :clientId")
    suspend fun setEmbedding(clientId: String, embedding: ByteArray)

    /** All memories that have a local embedding — the corpus for offline cosine search. */
    @Query("SELECT * FROM local_memories WHERE embedding IS NOT NULL")
    suspend fun withEmbeddings(): List<LocalMemory>

    @Query(
        "UPDATE local_memories SET syncState = 'SYNCED', serverId = :serverId, lastError = NULL, updatedAt = :now " +
            "WHERE clientId = :clientId",
    )
    suspend fun markSynced(clientId: String, serverId: String, now: Long)

    @Query(
        "UPDATE local_memories SET syncState = 'FAILED', attempts = attempts + 1, lastError = :error, updatedAt = :now " +
            "WHERE clientId = :clientId",
    )
    suspend fun markFailed(clientId: String, error: String, now: Long)

    /** Offline recall fallback: naive keyword match over text (no embeddings, no network). */
    @Query(
        "SELECT * FROM local_memories WHERE text LIKE '%' || :query || '%' " +
            "ORDER BY createdAt DESC LIMIT :limit",
    )
    suspend fun search(query: String, limit: Int): List<LocalMemory>

    @Query("SELECT * FROM local_memories ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<LocalMemory>
}
