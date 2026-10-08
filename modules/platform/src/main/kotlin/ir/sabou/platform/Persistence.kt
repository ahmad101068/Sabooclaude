package ir.sabou.platform

/**
 * A single atomic unit of work. Every command runs inside exactly one transaction; nested calls
 * join the outer transaction. The Android adapter maps this to Room `runInTransaction`.
 */
interface UnitOfWork {
    fun <T> transaction(block: () -> T): T
}

/** Records the result of each command so a retried command returns the same result (idempotency). */
interface IdempotencyStore {
    fun find(commandId: String): IdempotencyRecord?
    fun save(record: IdempotencyRecord)
}

data class IdempotencyRecord(
    val commandId: String,
    val commandType: String,
    val fingerprint: String,
    val resultId: String,
    val recordedAtEpochMillis: Long,
)
