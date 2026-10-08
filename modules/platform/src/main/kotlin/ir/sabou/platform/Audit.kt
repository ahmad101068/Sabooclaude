package ir.sabou.platform

import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import java.security.MessageDigest

/** One tamper-evident audit record. Each event hashes its content plus the previous hash. */
data class AuditEvent(
    val epoch: String,
    val sequence: Long,
    val previousHash: String,
    val hash: String,
    val occurredAtEpochMillis: Long,
    val actorId: String,
    val actorName: String,
    val module: ModuleId,
    val action: String,
    val entityType: String,
    val entityId: String,
    val scope: String,
    val commandId: String,
    val detail: String,
)

data class AuditDraft(
    val action: String,
    val entityType: String,
    val entityId: String,
    val detail: String,
)

/** Append-only storage of audit events. [page] supports streaming verification (AUD-007). */
interface AuditStore {
    fun head(): AuditEvent?
    fun append(event: AuditEvent)
    fun page(afterPosition: Long, limit: Int): List<AuditEvent>
    fun contains(epoch: String, sequence: Long, hash: String): Boolean
}

object AuditHashing {
    fun hash(e: AuditEvent): String = sha256(
        listOf(
            e.epoch, e.sequence.toString(), e.previousHash, e.occurredAtEpochMillis.toString(), e.actorId, e.actorName,
            e.module.name, e.action, e.entityType, e.entityId, e.scope, e.commandId, e.detail,
        ).joinToString("\u001F") { it.replace("\u001F", " ") },
    )

    fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

class AuditTrail(private val store: AuditStore) {
    fun append(
        draft: AuditDraft,
        actor: Actor,
        module: ModuleId,
        scope: String,
        commandId: String,
        nowEpochMillis: Long,
        epoch: String,
    ): AuditEvent {
        val head = store.head()
        val sequence = if (head == null || head.epoch != epoch) 1L else Math.addExact(head.sequence, 1L)
        val previous = head?.hash.orEmpty()
        val unsigned = AuditEvent(
            epoch = epoch, sequence = sequence, previousHash = previous, hash = "",
            occurredAtEpochMillis = nowEpochMillis, actorId = actor.userId.value, actorName = actor.displayName,
            module = module, action = draft.action, entityType = draft.entityType, entityId = draft.entityId,
            scope = scope, commandId = commandId, detail = draft.detail,
        )
        val event = unsigned.copy(hash = AuditHashing.hash(unsigned))
        store.append(event)
        return event
    }

    /**
     * Streams the chain page by page starting after a verified checkpoint. Memory use is bounded by
     * [pageSize] regardless of how many events exist (AUD-007).
     */
    fun verify(from: AuditCheckpoint? = null, pageSize: Int = 1_000): AuditCheckpoint? {
        require(pageSize in 1..10_000)
        var last: AuditEvent? = null
        var expectedPrevious = from?.hash
        var after = from?.globalPosition ?: 0L
        while (true) {
            val page = store.page(after, pageSize)
            if (page.isEmpty()) break
            for (event in page) {
                if (expectedPrevious != null && event.previousHash != expectedPrevious) {
                    throw DomainException(DomainError.IntegrityViolation("AUDIT_CHAIN_BROKEN:${event.epoch}:${event.sequence}"))
                }
                if (AuditHashing.hash(event.copy(hash = "")) != event.hash) {
                    throw DomainException(DomainError.IntegrityViolation("AUDIT_EVENT_TAMPERED:${event.epoch}:${event.sequence}"))
                }
                expectedPrevious = event.hash
                last = event
            }
            after += page.size
        }
        return last?.let { AuditCheckpoint(it.epoch, it.sequence, it.hash, after) } ?: from
    }
}

/** A verified position in the audit chain; [globalPosition] is the count of events verified so far. */
data class AuditCheckpoint(val epoch: String, val sequence: Long, val hash: String, val globalPosition: Long)
