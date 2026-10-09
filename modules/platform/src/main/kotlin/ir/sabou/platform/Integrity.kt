package ir.sabou.platform

import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException

/**
 * An external, tamper-protected record of "the audit chain was at this point". On Android it is an
 * HMAC-signed file outside the database. Every legitimate discontinuity (factory reset, restore,
 * first install) is recorded as a [AnchorKind.REBASE] **before** the destructive step, so startup
 * can tell a legitimate rebase from an unauthorized rollback (AUD-001, AUD-006).
 */
enum class AnchorKind { CHECKPOINT, REBASE }

data class IntegrityAnchor(
    val kind: AnchorKind,
    val epoch: String,
    val sequence: Long,
    val hash: String,
    val reason: String,
    val recordedAtEpochMillis: Long,
    /** Audit store position of the anchored event (CHECKPOINT only); lets startup verify incrementally. */
    val position: Long = 0,
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
     */
    fun verify(currentEpoch: String): StartupVerdict {
        val anchor = anchors.latest() ?: return StartupVerdict.Healthy
        return when (anchor.kind) {
            AnchorKind.REBASE ->
                if (anchor.epoch == currentEpoch) StartupVerdict.Healthy
                else StartupVerdict.RollbackDetected("EPOCH_MISMATCH:${anchor.epoch}/$currentEpoch")
            AnchorKind.CHECKPOINT ->
                if (anchor.epoch == currentEpoch && (anchor.sequence == 0L || audit.contains(anchor.epoch, anchor.sequence, anchor.hash))) {
                    StartupVerdict.Healthy
                } else {
                    StartupVerdict.RollbackDetected("ANCHOR_NOT_FOUND:${anchor.epoch}:${anchor.sequence}")
                }
        }
    }

    /** Must be called (and must succeed) before a reset or restore replaces the database. */
    fun recordRebase(newEpoch: String, reason: String, nowEpochMillis: Long) {
        if (reason.isBlank()) throw DomainException(DomainError.InvalidInput("reason", "دلیل الزامی است."))
        anchors.record(IntegrityAnchor(AnchorKind.REBASE, newEpoch, 0, "", reason, nowEpochMillis))
    }

    fun recordCheckpoint(epoch: String, nowEpochMillis: Long) {
        val head = audit.head()?.takeIf { it.epoch == epoch }
        anchors.record(
            IntegrityAnchor(AnchorKind.CHECKPOINT, epoch, head?.sequence ?: 0L, head?.hash.orEmpty(), "STARTUP", nowEpochMillis, head?.position ?: 0L),
        )
    }
}
