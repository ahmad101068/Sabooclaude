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
    private val cheques: ChequeStore = ir.sabou.treasury.memory.InMemoryChequeStore(),
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
        cheque: ChequeInstruction? = null,
    ): Settlement {
        ensure(owner.module == context.module) { DomainError.OwnedByAnotherModule(owner.module.name) }
        ensure(!amount.isZero) { DomainError.InvalidInput("amount", "مبلغ باید بیشتر از صفر باشد.") }
        val account = account(accountId)
        context.requireScope(account.scope)
        val chequeStep = prepareCheque(account, direction, amount, cheque)
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
        val chequeId = chequeStep?.let { applyCheque(context, it, account, amount, date, sourceType, sourceId) }
        val movement = TreasuryMovement(
            id = GlobalId.new(), accountId = account.id, direction = direction, amount = amount, date = date,
            journalId = journal.id, source = SourceDocument(owner.module, sourceType, sourceId), reversalOf = null,
            recordedAtEpochMillis = context.nowEpochMillis, chequeId = chequeId,
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
        // Cheques touched by this document go back one step; refused if they have moved on since.
        originals.mapNotNull { it.chequeId }.distinct().forEach { chequeId ->
            val cheque = cheques.byId(chequeId) ?: ChequeRules.notFound()
            val last = cheque.effective.lastOrNull()
            ensure(last != null && last.sourceType == sourceType && last.sourceId == sourceId) { DomainError.InvalidState("CHEQUE", "MOVED_ON:${cheque.status}") }
            val index = cheque.events.indexOfLast { !it.reversed }
            val events = cheque.events.mapIndexed { i, e -> if (i == index) e.copy(reversed = true) else e }
            val previous = events.lastOrNull { !it.reversed }?.status ?: ChequeStatus.VOID
            cheques.save(cheque.copy(status = previous, events = events))
            context.audit(AuditDraft("CHEQUE_STEP_REVERSE", "CHEQUE", cheque.id.value, "${cheque.status}->$previous;src=$sourceType:$sourceId"))
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

    fun cheque(id: GlobalId): Cheque = cheques.byId(id) ?: ChequeRules.notFound()

    /**
     * One cheque moving between two accounts of the same scope in one journal: a received cheque
     * collected into the bank (box → bank) or our cheque cleared by the bank (bank → book).
     */
    fun moveCheque(
        context: CommandContext,
        owner: PostingCapability,
        chequeId: GlobalId,
        from: TreasuryAccount,
        to: TreasuryAccount,
        target: ChequeStatus,
        date: BusinessDate,
        sourceType: String,
        sourceId: GlobalId,
        description: String,
    ): JournalEntry {
        ensure(owner.module == context.module) { DomainError.OwnedByAnotherModule(owner.module.name) }
        ensure(from.scope == to.scope) { DomainError.InvalidInput("scope", "حساب بانک باید در همان شعبه باشد.") }
        context.requireScope(from.scope)
        val cheque = cheque(chequeId)
        val chequeSide = if (cheque.direction == ChequeDirection.RECEIVED) from else to
        val step = prepareCheque(chequeSide, if (chequeSide == from) Direction.PAYMENT else Direction.RECEIPT, cheque.amount, ChequeInstruction.Move(chequeId, target))!!
        requireFunds(from, cheque.amount)
        val journal = ledger.post(
            context, owner,
            JournalDraft(date, from.scope, sourceType, sourceId, description, listOf(
                LineDraft(to.glAccount, debit = cheque.amount, memo = to.name, by = capability),
                LineDraft(from.glAccount, credit = cheque.amount, memo = from.name, by = capability),
            )),
        )
        applyCheque(context, step, chequeSide, cheque.amount, date, sourceType, sourceId)
        listOf(from to Direction.PAYMENT, to to Direction.RECEIPT).forEach { (account, direction) ->
            movements.insert(TreasuryMovement(GlobalId.new(), account.id, direction, cheque.amount, date, journal.id,
                SourceDocument(owner.module, sourceType, sourceId), null, context.nowEpochMillis, chequeId))
        }
        return journal
    }

    /** A step without money: a received cheque handed to the bank, or taken back. */
    fun noteCheque(context: CommandContext, chequeId: GlobalId, to: ChequeStatus, bankAccountId: GlobalId?, date: BusinessDate, sourceType: String, sourceId: GlobalId, note: String) {
        val cheque = cheque(chequeId)
        context.requireScope(cheque.scope)
        val ok = cheque.direction == ChequeDirection.RECEIVED && when (to) {
            ChequeStatus.DEPOSITED -> cheque.status == ChequeStatus.IN_HAND
            ChequeStatus.IN_HAND -> cheque.status == ChequeStatus.DEPOSITED
            else -> false
        }
        ensure(ok) { DomainError.InvalidState("CHEQUE", cheque.status.name) }
        val updated = if (to == ChequeStatus.DEPOSITED) {
            cheque.copy(status = to, bankAccountId = bankAccountId, events = cheque.events + ChequeEvent(to, date, sourceType, sourceId, bankAccountId, note.trim()))
        } else {
            // Taking it back from the bank undoes the deposit, so the cheque is exactly as before.
            val index = cheque.events.indexOfLast { !it.reversed }
            cheque.copy(status = to, bankAccountId = null, events = cheque.events.mapIndexed { i, e -> if (i == index) e.copy(reversed = true, note = note.trim()) else e })
        }
        cheques.save(updated)
        context.audit(AuditDraft("CHEQUE_$to", "CHEQUE", cheque.id.value, "bank=$bankAccountId;note=${note.trim()}"))
    }

    private class ChequeStep(val instruction: ChequeInstruction, val existing: Cheque?)

    /** Validates what the movement does to a cheque before anything is written. */
    private fun prepareCheque(account: TreasuryAccount, direction: Direction, amount: Money, instruction: ChequeInstruction?): ChequeStep? {
        val register = !account.kind.isOrdinary
        val createsHere = (account.kind == TreasuryKind.RECEIVED_CHEQUES && direction == Direction.RECEIPT) ||
            (account.kind == TreasuryKind.ISSUED_CHEQUES && direction == Direction.PAYMENT)
        return when (instruction) {
            null -> {
                ensure(!register) { DomainError.InvalidInput("cheque", if (createsHere) "مشخصات چک لازم است." else "چک را انتخاب کنید.") }
                null
            }
            is ChequeInstruction.New -> {
                ensure(createsHere) { DomainError.InvalidInput("cheque", "چک جدید فقط به صندوق چک‌های دریافتی وارد یا از دسته‌چک صادر می‌شود.") }
                instruction.details.validate()
                if (account.kind == TreasuryKind.ISSUED_CHEQUES) {
                    val bankId = instruction.details.bankAccountId ?: throw DomainException(DomainError.InvalidInput("bankAccount", "حساب بانکی چک را انتخاب کنید."))
                    val bank = account(bankId)
                    ensure(bank.kind == TreasuryKind.BANK && bank.scope == account.scope) { DomainError.InvalidInput("bankAccount", "چک باید از حساب بانکی همین شعبه باشد.") }
                }
                ChequeStep(instruction, null)
            }
            is ChequeInstruction.Move -> {
                ensure(!createsHere) { DomainError.InvalidInput("cheque", "مشخصات چک جدید لازم است.") }
                val cheque = cheque(instruction.chequeId)
                ChequeRules.requireMove(cheque, account, direction, amount, instruction.to)
                ChequeStep(instruction, cheque)
            }
        }
    }

    private fun applyCheque(context: CommandContext, step: ChequeStep, account: TreasuryAccount, amount: Money, date: BusinessDate, sourceType: String, sourceId: GlobalId): GlobalId =
        when (val i = step.instruction) {
            is ChequeInstruction.New -> {
                val received = account.kind == TreasuryKind.RECEIVED_CHEQUES
                val status = if (received) ChequeStatus.IN_HAND else ChequeStatus.ISSUED
                val details = i.details.copy(number = i.details.number.trim(), bank = i.details.bank.trim(), counterparty = i.details.counterparty.trim(), note = i.details.note.trim())
                val cheque = Cheque(
                    GlobalId.new(), if (received) ChequeDirection.RECEIVED else ChequeDirection.ISSUED, account.id, account.scope, amount, details, status,
                    listOf(ChequeEvent(status, date, sourceType, sourceId, account.id)), if (received) null else details.bankAccountId,
                )
                cheques.save(cheque)
                context.audit(AuditDraft("CHEQUE_${cheque.direction}", "CHEQUE", cheque.id.value, "no=${details.number};amount=${amount.rial};due=${details.dueDate.epochDay}"))
                cheque.id
            }
            is ChequeInstruction.Move -> {
                val cheque = step.existing!!
                cheques.save(cheque.copy(status = i.to, events = cheque.events + ChequeEvent(i.to, date, sourceType, sourceId, account.id)))
                context.audit(AuditDraft("CHEQUE_${i.to}", "CHEQUE", cheque.id.value, "src=$sourceType:$sourceId"))
                cheque.id
            }
        }

    private fun requireFunds(account: TreasuryAccount, amount: Money) {
        if (account.allowOverdraft) return
        val available = movements.balance(account.id)
        ensure(available >= amount.rial) { DomainError.InsufficientFunds(account.name, available, amount.rial) }
    }
}
