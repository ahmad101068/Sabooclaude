package ir.sabou.treasury

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Scope
import ir.sabou.ledger.AccountCode
import ir.sabou.ledger.SourceDocument

enum class TreasuryKind { CASH, BANK, CARD_TERMINAL, PETTY_CASH }

/**
 * A real cash box / bank account. Each one belongs to exactly one scope (a branch or the
 * organization), so "how much cash does branch A have" is a direct question (AUD-008).
 */
data class TreasuryAccount(
    val id: GlobalId,
    val name: String,
    val kind: TreasuryKind,
    val scope: Scope,
    val glAccount: AccountCode,
    val allowOverdraft: Boolean = false,
    val isActive: Boolean = true,
)

enum class Direction { RECEIPT, PAYMENT;
    fun opposite() = if (this == RECEIPT) PAYMENT else RECEIPT
}

/** An immutable movement. Reversal is a new movement pointing to the original. */
data class TreasuryMovement(
    val id: GlobalId,
    val accountId: GlobalId,
    val direction: Direction,
    val amount: Money,
    val date: BusinessDate,
    val journalId: GlobalId,
    val source: SourceDocument,
    val reversalOf: GlobalId?,
    val recordedAtEpochMillis: Long,
)

interface TreasuryAccountStore {
    fun byId(id: GlobalId): TreasuryAccount?
    fun all(): List<TreasuryAccount>
    fun save(account: TreasuryAccount)
}

interface MovementStore {
    fun insert(movement: TreasuryMovement)
    fun byId(id: GlobalId): TreasuryMovement?
    fun reversalOf(id: GlobalId): TreasuryMovement?
    fun bySource(type: String, id: GlobalId): List<TreasuryMovement>
    /** Receipts minus payments. */
    fun balance(accountId: GlobalId): Long
}
