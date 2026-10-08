package ir.sabou.ledger

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Scope
import ir.sabou.platform.ModuleId

/** The business document that caused a journal. Its module is the only one allowed to reverse it. */
data class SourceDocument(val module: ModuleId, val type: String, val id: GlobalId)

data class JournalLine(
    val account: AccountCode,
    val debit: Money,
    val credit: Money,
    val memo: String,
    val contributor: ModuleId,
)

data class JournalEntry(
    val id: GlobalId,
    val number: Long,
    val date: BusinessDate,
    val scope: Scope,
    val source: SourceDocument,
    val description: String,
    val lines: List<JournalLine>,
    val reversalOf: GlobalId?,
    val postedBy: GlobalId,
    val postedAtEpochMillis: Long,
)

/** A line before posting. [by] proves which module contributes it (see [PostingCapability]). */
data class LineDraft(
    val account: AccountCode,
    val debit: Money = Money.ZERO,
    val credit: Money = Money.ZERO,
    val memo: String = "",
    val by: PostingCapability,
)

data class JournalDraft(
    val date: BusinessDate,
    val scope: Scope,
    val sourceType: String,
    val sourceId: GlobalId,
    val description: String,
    val lines: List<LineDraft>,
)

/**
 * An unforgeable right to contribute journal lines on behalf of one module. Instances are issued
 * once per module by [LedgerAccessRegistry] at the composition root, so one module cannot write
 * to another module's control accounts by simply naming it (ADR-0002, AUD-002/003/010).
 */
class PostingCapability internal constructor(val module: ModuleId)

class LedgerAccessRegistry {
    private val issued = HashMap<ModuleId, PostingCapability>()

    @Synchronized
    fun issue(module: ModuleId): PostingCapability {
        check(module !in issued) { "posting_capability_already_issued:$module" }
        return PostingCapability(module).also { issued[module] = it }
    }

    internal fun isGenuine(capability: PostingCapability): Boolean = issued[capability.module] === capability
}

data class PeriodLock(val id: GlobalId, val from: BusinessDate, val to: BusinessDate, val closed: Boolean)

interface AccountStore {
    fun byCode(code: AccountCode): Account?
    fun all(): List<Account>
    fun upsert(account: Account)
}

interface JournalStore {
    fun nextNumber(): Long
    fun insert(entry: JournalEntry)
    fun byId(id: GlobalId): JournalEntry?
    fun reversalOf(id: GlobalId): JournalEntry?
    fun bySource(type: String, id: GlobalId): List<JournalEntry>
    /** Signed debit-minus-credit total for an account, optionally limited to one scope and date. */
    fun netDebit(account: AccountCode, scope: Scope?, upTo: BusinessDate?): Long
    fun all(): List<JournalEntry>
}

interface PeriodStore {
    fun closedLockCovering(date: BusinessDate): PeriodLock?
    fun overlapsClosed(from: BusinessDate, to: BusinessDate): Boolean
    fun byId(id: GlobalId): PeriodLock?
    fun save(lock: PeriodLock)
}
