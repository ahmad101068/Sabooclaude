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
    /** 1-based storage position; assigned by the store, not part of the hash. */
    val position: Long = 0,
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
    fun at(position: Long): AuditEvent?
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
        .joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }   // locale-independent: device and server must agree
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
        fun broken(code: String, e: AuditEvent): Nothing = throw DomainException(DomainError.IntegrityViolation("$code:${e.epoch}:${e.sequence}"))
        if (from != null && from.globalPosition > 0) {
            // The checkpoint itself must still be there, unchanged.
            val anchor = store.at(from.globalPosition)
            if (anchor == null || anchor.hash != from.hash || anchor.epoch != from.epoch || anchor.sequence != from.sequence) {
                throw DomainException(DomainError.IntegrityViolation("AUDIT_CHECKPOINT_MISSING:${from.epoch}:${from.sequence}"))
            }
        }
        // From scratch the chain must start at the genesis link: position 1, empty previous hash.
        var expectedPrevious = if (from == null || from.globalPosition == 0L) "" else from.hash
        var expectedPosition = (from?.globalPosition ?: 0L) + 1
        var lastEpoch: String? = from?.takeIf { it.globalPosition > 0 }?.epoch
        var lastSequence = from?.sequence ?: 0L
        var last: AuditEvent? = null
        while (true) {
            val page = store.page(expectedPosition - 1, pageSize)
            if (page.isEmpty()) break
            for (event in page) {
                if (event.position != expectedPosition) broken("AUDIT_GAP", event)
                if (event.previousHash != expectedPrevious) broken("AUDIT_CHAIN_BROKEN", event)
                val expectedSequence = if (event.epoch == lastEpoch) lastSequence + 1 else 1L
                if (event.sequence != expectedSequence) broken("AUDIT_SEQUENCE", event)
                if (AuditHashing.hash(event.copy(hash = "", position = 0)) != event.hash) broken("AUDIT_EVENT_TAMPERED", event)
                expectedPrevious = event.hash
                lastEpoch = event.epoch
                lastSequence = event.sequence
                expectedPosition++
                last = event
            }
        }
        return last?.let { AuditCheckpoint(it.epoch, it.sequence, it.hash, it.position) } ?: from
    }
}

/** A verified position in the audit chain; [globalPosition] is the count of events verified so far. */
data class AuditCheckpoint(val epoch: String, val sequence: Long, val hash: String, val globalPosition: Long)
