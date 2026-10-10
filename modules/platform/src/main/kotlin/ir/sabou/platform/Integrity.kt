package ir.sabou.platform

import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException

/**
 * An external, tamper-protected record of "the audit chain was at this point". On Android it is an
 * HMAC-signed file outside the database. Every legitimate discontinuity (factory reset, restore,
 * first install) is recorded as a [AnchorKind.REBASE] **before** the destructive step, so startup
 * can tell a legitimate rebase from an unauthorized rollback (AUD-001, AUD-006).
 */
enum class AnchorKind {
    CHECKPOINT,
    /** A completed replacement (kept for anchors written by earlier versions). */
    REBASE,
    /**
     * A replacement that has been authorized but whose file swap may not have happened yet: until the next
     * checkpoint, either the database it replaces ([IntegrityAnchor.previousEpoch]) or the new one is genuine.
     * This makes the swap crash-safe without accepting any third database.
     */
    PENDING_REBASE,
}

data class IntegrityAnchor(
    val kind: AnchorKind,
    val epoch: String,
    val sequence: Long,
    val hash: String,
    val reason: String,
    val recordedAtEpochMillis: Long,
    /** Audit store position of the anchored event (CHECKPOINT only); lets startup verify incrementally. */
    val position: Long = 0,
    /** PENDING_REBASE only: the epoch of the database being replaced ("" when it was unreadable). */
    val previousEpoch: String = "",
)

interface AnchorStore {
    fun latest(): IntegrityAnchor?
    fun record(anchor: IntegrityAnchor)
}

sealed interface StartupVerdict {
    data object Healthy : StartupVerdict
    data class RollbackDetected(val detail: String) : StartupVerdict
}

class IntegrityGuard(
    private val anchors: AnchorStore,
    private val audit: AuditStore,
) {
    /**
     * Decides whether the current database continues the anchored history. Pure decision; the
     * caller decides how to present a rollback (it never bricks silently: the user sees a recovery screen).
     *
     * [anchoredBefore] is the database's own record that it has been anchored. A database that says so while
     * no anchor exists means the anchor file was removed — the same access that swaps in an older copy of the
     * database can delete the file, so a missing anchor is not proof of a first start.
     */
    fun verify(currentEpoch: String, anchoredBefore: Boolean = false): StartupVerdict {
        val anchor = anchors.latest()
            ?: return if (anchoredBefore) StartupVerdict.RollbackDetected("ANCHOR_MISSING") else StartupVerdict.Healthy
        return when (anchor.kind) {
            AnchorKind.REBASE ->
                if (anchor.epoch == currentEpoch) StartupVerdict.Healthy
                else StartupVerdict.RollbackDetected("EPOCH_MISMATCH:${anchor.epoch}/$currentEpoch")
            AnchorKind.PENDING_REBASE ->
                if (anchor.epoch == currentEpoch || (anchor.previousEpoch.isNotEmpty() && anchor.previousEpoch == currentEpoch)) StartupVerdict.Healthy
                else StartupVerdict.RollbackDetected("EPOCH_MISMATCH:${anchor.epoch}|${anchor.previousEpoch}/$currentEpoch")
            AnchorKind.CHECKPOINT ->
                if (anchor.epoch == currentEpoch && (anchor.sequence == 0L || audit.contains(anchor.epoch, anchor.sequence, anchor.hash))) {
                    StartupVerdict.Healthy
                } else {
                    StartupVerdict.RollbackDetected("ANCHOR_NOT_FOUND:${anchor.epoch}:${anchor.sequence}")
                }
        }
    }

    /** The epoch a brand-new database must take: the one announced by an unfinished replacement, if any. */
    fun pendingEpoch(): String? = anchors.latest()?.takeIf { it.kind == AnchorKind.PENDING_REBASE }?.epoch

    /**
     * Must be called (and must succeed) before a reset or restore replaces the database. Until the next
     * checkpoint, both the replaced database ([previousEpoch]) and the new one ([newEpoch]) are accepted, so
     * a crash at any point of the swap leaves a database that opens normally.
     */
    fun recordRebase(newEpoch: String, previousEpoch: String, reason: String, nowEpochMillis: Long) {
        if (reason.isBlank()) throw DomainException(DomainError.InvalidInput("reason", "دلیل الزامی است."))
        anchors.record(IntegrityAnchor(AnchorKind.PENDING_REBASE, newEpoch, 0, "", reason, nowEpochMillis, previousEpoch = previousEpoch))
    }

    fun recordCheckpoint(epoch: String, nowEpochMillis: Long) {
        val head = audit.head()?.takeIf { it.epoch == epoch }
        anchors.record(
            IntegrityAnchor(AnchorKind.CHECKPOINT, epoch, head?.sequence ?: 0L, head?.hash.orEmpty(), "STARTUP", nowEpochMillis, head?.position ?: 0L),
        )
    }
}
