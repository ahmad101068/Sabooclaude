package ir.sabou.ledger

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Scope
import ir.sabou.kernel.SignedAmount
import ir.sabou.kernel.ensure
import ir.sabou.platform.AuditDraft
import ir.sabou.platform.CommandContext

/**
 * The general ledger. Not a command handler: modules call it from inside their own command, so the
 * journal, the module's document and the audit record commit in one transaction.
 */
class Ledger(
    private val registry: LedgerAccessRegistry,
    private val accounts: AccountStore,
    private val journals: JournalStore,
    private val periods: PeriodStore,
) {
    fun post(context: CommandContext, owner: PostingCapability, draft: JournalDraft): JournalEntry {
        requireOwner(context, owner)
        ensure(draft.description.isNotBlank() && draft.description.length <= 300) { DomainError.InvalidInput("description", "شرح سند الزامی است.") }
        ensure(draft.lines.size >= 2) { DomainError.InvalidInput("lines", "سند حداقل دو ردیف لازم دارد.") }
        var debit = Money.ZERO
        var credit = Money.ZERO
        for (line in draft.lines) {
            ensure(registry.isGenuine(line.by)) { DomainError.IntegrityViolation("FORGED_CAPABILITY") }
            ensure(line.debit.isZero != line.credit.isZero) { DomainError.InvalidInput("line", "هر ردیف باید فقط بدهکار یا فقط بستانکار باشد.") }
            val account = accounts.byCode(line.account) ?: throw ir.sabou.kernel.DomainException(DomainError.NotFound("ACCOUNT:${line.account}"))
            ensure(account.isActive) { DomainError.InvalidState("ACCOUNT:${account.code}", "INACTIVE") }
            ensure(!account.isControl || line.by.module in account.postingModules) { DomainError.ControlAccount(account.code.value) }
            debit += line.debit
            credit += line.credit
        }
        ensure(debit == credit) { DomainError.UnbalancedJournal(debit.rial, credit.rial) }
        requireOpenPeriod(draft.date)
        context.requireScope(draft.scope)
        val entry = JournalEntry(
            id = GlobalId.new(),
            number = journals.nextNumber(),
            date = draft.date,
            scope = draft.scope,
            source = SourceDocument(owner.module, draft.sourceType, draft.sourceId),
            description = draft.description.trim(),
            lines = draft.lines.map { JournalLine(it.account, it.debit, it.credit, it.memo.trim(), it.by.module) },
            reversalOf = null,
            postedBy = context.actor.userId,
            postedAtEpochMillis = context.nowEpochMillis,
        )
        journals.insert(entry)
        context.audit(AuditDraft("JOURNAL_POST", "JOURNAL", entry.id.value, "no=${entry.number};src=${draft.sourceType}:${draft.sourceId};amount=${debit.rial}"))
        return entry
    }

    /**
     * Reverses a journal. Only the owning module may do it, and it must present the capabilities of
     * every module that contributed lines, so a reversal can never bypass a sub-ledger (AUD-002).
     */
    fun reverse(
        context: CommandContext,
        owner: PostingCapability,
        contributors: Set<PostingCapability>,
        entryId: GlobalId,
        date: BusinessDate,
        reason: String,
    ): JournalEntry {
        requireOwner(context, owner)
        val original = journals.byId(entryId) ?: throw ir.sabou.kernel.DomainException(DomainError.NotFound("JOURNAL"))
        ensure(original.source.module == owner.module) { DomainError.OwnedByAnotherModule(original.source.module.name) }
        ensure(original.reversalOf == null) { DomainError.InvalidState("JOURNAL", "IS_REVERSAL") }
        ensure(journals.reversalOf(original.id) == null) { DomainError.InvalidState("JOURNAL", "ALREADY_REVERSED") }
        ensure(date >= original.date) { DomainError.InvalidInput("date", "تاریخ برگشت نمی‌تواند قبل از تاریخ سند باشد.") }
        ensure(reason.trim().length in 3..300) { DomainError.InvalidInput("reason", "دلیل برگشت الزامی است.") }
        val presented = (contributors + owner).onEach {
            ensure(registry.isGenuine(it)) { DomainError.IntegrityViolation("FORGED_CAPABILITY") }
        }.map { it.module }.toSet()
        val missing = original.lines.map { it.contributor }.toSet() - presented
        ensure(missing.isEmpty()) { DomainError.OwnedByAnotherModule(missing.joinToString(",")) }
        requireOpenPeriod(date)
        context.requireScope(original.scope)
        val reversal = original.copy(
            id = GlobalId.new(),
            number = journals.nextNumber(),
            date = date,
            description = "برگشت سند ${original.number}: ${reason.trim()}",
            lines = original.lines.map { it.copy(debit = it.credit, credit = it.debit) },
            reversalOf = original.id,
            postedBy = context.actor.userId,
            postedAtEpochMillis = context.nowEpochMillis,
        )
        journals.insert(reversal)
        context.audit(AuditDraft("JOURNAL_REVERSE", "JOURNAL", reversal.id.value, "of=${original.id};reason=${reason.trim()}"))
        return reversal
    }

    fun balance(account: AccountCode, scope: Scope? = null, upTo: BusinessDate? = null): SignedAmount =
        SignedAmount(journals.netDebit(account, scope, upTo))

    fun requireOpenPeriod(date: BusinessDate) {
        ensure(periods.closedLockCovering(date) == null) { DomainError.PeriodClosed(date.epochDay) }
    }

    private fun requireOwner(context: CommandContext, owner: PostingCapability) {
        ensure(registry.isGenuine(owner)) { DomainError.IntegrityViolation("FORGED_CAPABILITY") }
        ensure(owner.module == context.module) { DomainError.OwnedByAnotherModule(owner.module.name) }
    }
}
