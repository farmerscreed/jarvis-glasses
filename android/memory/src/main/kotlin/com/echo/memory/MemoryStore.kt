package com.echo.memory

import com.echo.core.model.Memory
import com.echo.core.model.MemoryType
import com.echo.memory.local.LocalMemory
import com.echo.memory.local.MemoryDao
import com.echo.memory.local.SyncState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * The offline-first memory core (Phase C §4.2). Implements [MemoryRepository] but writes the phone
 * FIRST — a capture is durable the instant it happens, internet or not — then drains an outbox to
 * the cloud via [EchoBackend] when connectivity allows. The server dedupes on `client_id`, so the
 * drain can retry freely without ever creating a duplicate.
 */
class MemoryStore(
    private val dao: MemoryDao,
    private val backend: EchoBackend,
    private val governor: ConnectivityGovernor,
    private val embedder: Embedder? = null,
) : MemoryRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val drainMutex = Mutex() // one drain at a time

    /**
     * Set by `:app` to enqueue a WorkManager drain. Called on every local write so the outbox is
     * flushed even if the process dies right after (WorkManager survives app kill / reboot and
     * carries a CONNECTED constraint). Kept as a hook so `:memory` needn't depend on WorkManager.
     */
    var onNeedsSync: (() -> Unit)? = null

    init {
        // On reconnect / at startup: first re-run any AI that was deferred while off-grid
        // (vision/transcribe), then drain. Startup uses force=true: a freshly-started process's
        // governor may not have validated the network yet, so don't let a cold flag skip it.
        governor.onConnected = { scope.launch { syncAll() } }
        scope.launch { syncAll(force = true) }
    }

    /** Catch up everything owed to the cloud: deferred AI first, then the outbox. */
    suspend fun syncAll(force: Boolean = false): Boolean {
        reprocessDeferred(force)
        return drain(force)
    }

    /** Text-only memory (note / Q&A). */
    override suspend fun remember(memory: Memory): Memory = rememberLocal(memory, null, "media")

    /**
     * Persist a memory locally (durable immediately), optionally with a not-yet-uploaded media file,
     * then attempt to sync now if we're online. Returns the memory tagged with its clientId; the
     * server id is filled in asynchronously once the drain confirms it.
     */
    suspend fun rememberLocal(memory: Memory, localFile: File?, bucket: String): Memory =
        withContext(Dispatchers.IO) {
            val clientId = memory.clientId ?: UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            dao.upsert(
                LocalMemory(
                    clientId = clientId,
                    type = memory.type.wire,
                    text = memory.text,
                    mediaPath = memory.mediaPath,
                    localMediaPath = localFile?.absolutePath,
                    bucket = bucket,
                    lat = memory.lat,
                    lng = memory.lng,
                    tags = memory.tags.joinToString("\n"),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            // Compute the on-device embedding so this memory is recallable offline. Best-effort:
            // if the model isn't ready, the row is still saved (recall falls back to keywords).
            memory.text?.takeIf { it.isNotBlank() }?.let { t ->
                embedder?.embed(t)?.let { vec -> dao.setEmbedding(clientId, VectorUtil.toBytes(vec)) }
            }
            onNeedsSync?.invoke() // guaranteed drain even if the app is killed right after this write
            if (governor.online.value) drain() // keep online behaviour immediate (upload + ingest now)
            memory.copy(clientId = clientId)
        }

    /**
     * Push every pending memory to the cloud, oldest first. Safe to call repeatedly.
     * [force] = true skips the cached online check — the background worker only runs once
     * WorkManager's CONNECTED constraint is satisfied, so it must attempt even if this fresh
     * process's governor hasn't validated the network yet.
     * Returns true iff the outbox is fully drained afterwards (the worker retries when false).
     */
    suspend fun drain(force: Boolean = false): Boolean = drainMutex.withLock {
        if (!force && !governor.online.value) return false
        for (row in dao.pending()) {
            // Don't sync a placeholder: rows still owing vision/transcribe wait for reprocessDeferred.
            if (row.tags.contains("needs_vision") || row.tags.contains("needs_transcribe")) continue
            runCatching { syncOne(row) }
                .onFailure { dao.markFailed(row.clientId, it.message ?: "sync error", System.currentTimeMillis()) }
        }
        dao.pending().none { !it.tags.contains("needs_vision") && !it.tags.contains("needs_transcribe") }
    }

    /**
     * Re-run AI that was deferred while off-grid: a photo saved without a Claude description, or a
     * voice clip without a transcript. Updates the memory's text + embedding and clears the
     * needs_* tag, so the next drain syncs the *real* content (not the placeholder).
     */
    private suspend fun reprocessDeferred(force: Boolean = false) {
        if (!force && !governor.online.value) return
        for (row in dao.needingReprocess()) {
            val file = row.localMediaPath?.let { java.io.File(it) } ?: continue
            if (!file.exists()) continue
            val tags = row.tags.split("\n").filter { it.isNotBlank() }
            runCatching {
                val newText = when {
                    "needs_vision" in tags -> backend.describeImage(
                        file.readBytes(),
                        "One short sentence: caption this photo for a personal memory index.",
                    )
                    "needs_transcribe" in tags -> backend.transcribe(file.readBytes(), EchoBackend.mimeFor(file.name))
                    else -> return@runCatching
                }
                if (newText.isBlank()) return@runCatching
                val newTags = tags - "needs_vision" - "needs_transcribe"
                val emb = embedder?.embed(newText)?.let { VectorUtil.toBytes(it) }
                dao.updateContent(row.clientId, newText, newTags.joinToString("\n"), emb, System.currentTimeMillis())
            }
        }
    }

    private suspend fun syncOne(row: LocalMemory) {
        // 1. Upload media first if it hasn't been (so the row carries a valid storage key).
        var mediaPath = row.mediaPath
        if (mediaPath == null && row.localMediaPath != null) {
            val file = File(row.localMediaPath)
            if (file.exists()) {
                mediaPath = backend.uploadMedia(file.readBytes(), file.name, bucket = row.bucket)
                dao.setMediaPath(row.clientId, mediaPath, System.currentTimeMillis())
            }
        }
        // 2. Ingest with the idempotency key — a retry of an already-landed row just re-confirms it.
        val saved = backend.remember(
            Memory(
                type = runCatching { MemoryType.fromWire(row.type) }.getOrDefault(MemoryType.NOTE),
                text = row.text,
                mediaPath = mediaPath,
                lat = row.lat,
                lng = row.lng,
                tags = if (row.tags.isBlank()) emptyList() else row.tags.split("\n"),
                clientId = row.clientId,
            ),
        )
        dao.markSynced(row.clientId, saved.id ?: row.clientId, System.currentTimeMillis())
    }

    /** Number of memories still owed to the cloud — for a UI "N to sync" chip. */
    fun pendingCount() = dao.pendingCount()

    /**
     * Recall: cloud semantic search when online; **on-device semantic search** when off-grid
     * (embed the query, brute-force cosine over local vectors), with a keyword search as the final
     * fallback if no embeddings exist. Never throws on no-network.
     */
    override suspend fun recall(query: String, limit: Int, type: MemoryType?): List<Memory> =
        withContext(Dispatchers.IO) {
            if (governor.online.value) {
                runCatching { backend.recall(query, limit, type) }.getOrNull()?.let { return@withContext it }
            }
            localRecall(query, limit) ?: dao.search(query, limit).map { it.toMemory() }
        }

    /** Off-grid semantic recall: cosine of the query vector against every stored local vector. */
    private suspend fun localRecall(query: String, limit: Int): List<Memory>? {
        val q = embedder?.embed(query) ?: return null
        val corpus = dao.withEmbeddings()
        if (corpus.isEmpty()) return null
        return corpus
            .mapNotNull { row ->
                row.embedding?.let { row to VectorUtil.cosine(q, VectorUtil.fromBytes(it)) }
            }
            .sortedByDescending { it.second }
            .take(limit)
            .map { (row, score) -> row.toMemory().copy(similarity = score.toDouble()) }
    }

    private fun LocalMemory.toMemory(): Memory = Memory(
        id = serverId ?: clientId,
        type = runCatching { MemoryType.fromWire(type) }.getOrDefault(MemoryType.NOTE),
        text = text,
        mediaPath = mediaPath,
        lat = lat,
        lng = lng,
        tags = if (tags.isBlank()) emptyList() else tags.split("\n"),
        clientId = clientId,
    )
}
