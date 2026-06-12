package com.echo.memory.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Sync state of a locally-stored memory relative to the cloud. */
enum class SyncState { PENDING, SYNCED, FAILED }

/**
 * A memory as it lives on the phone — the **primary** write target (offline-first, Phase C).
 * Every capture lands here first and is durable immediately, internet or not. The outbox drains
 * PENDING/FAILED rows to Supabase when connectivity allows; [clientId] is the idempotency key the
 * server dedupes on, so a retry never creates a duplicate.
 */
@Entity(tableName = "local_memories")
data class LocalMemory(
    /** Client-generated UUID — the idempotency key; also this row's identity before it has a serverId. */
    @PrimaryKey val clientId: String,
    val type: String,
    val text: String?,
    /** Storage object key once the media has been uploaded (null until then / for text-only memories). */
    val mediaPath: String? = null,
    /** Absolute path of the not-yet-uploaded local media file (null for text-only memories). */
    val localMediaPath: String? = null,
    /** Storage bucket the media belongs in ("media" or "audio"). */
    val bucket: String = "media",
    val lat: Double? = null,
    val lng: Double? = null,
    /** Tags joined with '\n' (Room has no native list column). */
    val tags: String = "",
    val createdAt: Long,
    val serverId: String? = null,
    val syncState: SyncState = SyncState.PENDING,
    val attempts: Int = 0,
    val lastError: String? = null,
    val updatedAt: Long,
)
