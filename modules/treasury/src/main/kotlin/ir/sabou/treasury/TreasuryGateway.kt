package ir.sabou.treasury

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.ensure
import ir.sabou.ledger.JournalDraft
import ir.sabou.ledger.JournalEntry
import ir.sabou.ledger.Ledger
import ir.sabou.ledger.LineDraft
import ir.sabou.ledger.PostingCapability
import ir.sabou.ledger.SourceDocument
import ir.sabou.platform.AuditDraft
import ir.sabou.platform.CommandContext
import ir.sabou.platform.ModuleId

data class Settlement(val movement: TreasuryMovement, val journal: JournalEntry)

/**
 * The only way money enters or leaves a treasury account. Other modules (sales, purchasing,
 * payroll) call it inside their own command; the resulting movement is owned by *their* document,
 * and only they can reverse it (AUD-002). Treasury never creates AP/AR settlements on its own (AUD-003).
 */
class TreasuryGateway(
    private val ledger: Ledger,
    private val capability: PostingCapability,
    private val accounts: TreasuryAccountStore,
    private val movements: MovementStore,
) {
    init {
        require(capability.module == ModuleId.TREASURY)
    }

    fun balance(accountId: GlobalId): Long = movements.balance(accountId)

    fun account(accountId: GlobalId): TreasuryAccount {
        val account = accounts.byId(accountId) ?: throw DomainException(DomainError.NotFound("TREASURY_ACCOUNT"))
        ensure(account.isActive) { DomainError.InvalidState("TREASURY_ACCOUNT", "INACTIVE") }
        return account
    }

    /**
     * Records a receipt into / payment from [accountId]. [counterLines] are the owner's side of the
     * journal (for example AP debit for a supplier payment) and must balance the treasury line.
     */
    fun settle(
        context: CommandContext,
        owner: PostingCapability,
        accountId: GlobalId,
        direction: Direction,
        amount: Money,
        date: BusinessDate,
        sourceType: String,
        sourceId: GlobalId,
        description: String,
        counterLines: List<LineDraft>,
    ): Settlement {
        ensure(owner.module == context.module) { DomainError.OwnedByAnotherModule(owner.module.name) }
        ensure(!amount.isZero) { DomainError.InvalidInput("amount", "مبلغ باید بیشتر از صفر باشد.") }
        val account = account(accountId)
        context.requireScope(account.scope)
        if (direction == Direction.PAYMENT) requireFunds(account, amount)
        val counterDebit = Money.sum(counterLines.map { it.debit })
        val counterCredit = Money.sum(counterLines.map { it.credit })
        val treasuryLine = when (direction) {
            Direction.RECEIPT -> {
                ensure(counterCredit == amount && counterDebit.isZero) { DomainError.UnbalancedJournal(amount.rial, counterCredit.rial) }
                LineDraft(account.glAccount, debit = amount, memo = account.name, by = capability)
            }
            Direction.PAYMENT -> {
                ensure(counterDebit == amount && counterCredit.isZero) { DomainError.UnbalancedJournal(counterDebit.rial, amount.rial) }
                LineDraft(account.glAccount, credit = amount, memo = account.name, by = capability)
            }
        }
        val journal = ledger.post(
            context, owner,
            JournalDraft(date, account.scope, sourceType, sourceId, description, listOf(treasuryLine) + counterLines),
        )
        val movement = TreasuryMovement(
            id = GlobalId.new(), accountId = account.id, direction = direction, amount = amount, date = date,
            journalId = journal.id, source = SourceDocument(owner.module, sourceType, sourceId), reversalOf = null,
            recordedAtEpochMillis = context.nowEpochMillis,
        )
        movements.insert(movement)
        context.audit(AuditDraft("TREASURY_${direction.name}", "TREASURY_MOVEMENT", movement.id.value, "account=${account.id};amount=${amount.rial};src=$sourceType:$sourceId"))
        return Settlement(movement, journal)
    }

    /**
     * Reverses every treasury movement (and its journal) belonging to one source document, on
     * behalf of the document owner. Reversing a receipt takes money out again, so it is subject to
     * the same no-negative-balance rule as a payment (AUD-009).
     */
    fun reverseDocument(
        context: CommandContext,
        owner: PostingCapability,
        sourceType: String,
        sourceId: GlobalId,
        date: BusinessDate,
        reason: String,
    ): List<Settlement> {
        ensure(owner.module == context.module) { DomainError.OwnedByAnotherModule(owner.module.name) }
        val originals = movements.bySource(sourceType, sourceId).filter { it.reversalOf == null }
        ensure(originals.isNotEmpty()) { DomainError.NotFound("TREASURY_MOVEMENT") }
        originals.forEach { original ->
            ensure(original.source.module == owner.module) { DomainError.OwnedByAnotherModule(original.source.module.name) }
            ensure(movements.reversalOf(original.id) == null) { DomainError.InvalidState("TREASURY_MOVEMENT", "ALREADY_REVERSED") }
        }
        // Funds are checked on the net effect per account before anything is written.
        originals.groupBy { it.accountId }.forEach { (accountId, rows) ->
            val account = accounts.byId(accountId) ?: throw DomainException(DomainError.NotFound("TREASURY_ACCOUNT"))
            context.requireScope(account.scope)
            val outflow = rows.fold(0L) { acc, m -> if (m.direction == Direction.RECEIPT) acc + m.amount.rial else acc - m.amount.rial }
            if (outflow > 0) requireFunds(account, Money.of(outflow))
        }
        val reversedJournals = HashMap<GlobalId, JournalEntry>()
        return originals.map { original ->
            val journal = reversedJournals.getOrPut(original.journalId) {
                ledger.reverse(context, owner, setOf(capability), original.journalId, date, reason)
            }
            val movement = original.copy(
                id = GlobalId.new(), direction = original.direction.opposite(), date = date, journalId = journal.id,
                reversalOf = original.id, recordedAtEpochMillis = context.nowEpochMillis,
            )
            movements.insert(movement)
            context.audit(AuditDraft("TREASURY_REVERSE", "TREASURY_MOVEMENT", movement.id.value, "of=${original.id};reason=${reason.trim()}"))
            Settlement(movement, journal)
        }
    }

    /** Same-scope transfer: one journal, two movements. Used only by [TreasuryOperations]. */
    internal fun recordTransferWithinScope(
        context: CommandContext,
        from: TreasuryAccount,
        to: TreasuryAccount,
        amount: Money,
        date: BusinessDate,
        sourceId: GlobalId,
        description: String,
    ): JournalEntry {
        requireFunds(from, amount)
        val journal = ledger.post(
            context, capability,
            JournalDraft(
                date, from.scope, TreasuryOperations.TRANSFER, sourceId, description,
                listOf(
                    LineDraft(to.glAccount, debit = amount, memo = to.name, by = capability),
                    LineDraft(from.glAccount, credit = amount, memo = from.name, by = capability),
                ),
            ),
        )
        listOf(from to Direction.PAYMENT, to to Direction.RECEIPT).forEach { (account, direction) ->
            movements.insert(
                TreasuryMovement(
                    GlobalId.new(), account.id, direction, amount, date, journal.id,
                    SourceDocument(ModuleId.TREASURY, TreasuryOperations.TRANSFER, sourceId), null, context.nowEpochMillis,
                ),
            )
        }
        return journal
    }

    private fun requireFunds(account: TreasuryAccount, amount: Money) {
        if (account.allowOverdraft) return
        val available = movements.balance(account.id)
        ensure(available >= amount.rial) { DomainError.InsufficientFunds(account.name, available, amount.rial) }
    }
}
