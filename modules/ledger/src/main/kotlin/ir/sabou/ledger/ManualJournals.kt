package ir.sabou.ledger

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Scope
import ir.sabou.kernel.ensure
import ir.sabou.platform.AuditDraft
import ir.sabou.platform.Command
import ir.sabou.platform.CommandBus
import ir.sabou.platform.CommandOutcome
import ir.sabou.platform.ModuleId
import ir.sabou.platform.Permission

data class ManualLine(val account: AccountCode, val debit: Money, val credit: Money, val memo: String = "")

data class PostManualJournal(
    override val commandId: GlobalId,
    override val scope: Scope,
    val date: BusinessDate,
    val description: String,
    val lines: List<ManualLine>,
) : Command {
    override val requiredPermission = Permission.JOURNAL_MANUAL_POST
    override fun fingerprint() = "$scope|${date.epochDay}|$description|" +
        lines.joinToString(";") { "${it.account}:${it.debit.rial}:${it.credit.rial}:${it.memo}" }
}

data class ReverseManualJournal(
    override val commandId: GlobalId,
    override val scope: Scope,
    val entryId: GlobalId,
    val date: BusinessDate,
    val reason: String,
) : Command {
    override val requiredPermission = Permission.JOURNAL_MANUAL_REVERSE
    override fun fingerprint() = "$scope|$entryId|${date.epochDay}|$reason"
}

data class ClosePeriod(
    override val commandId: GlobalId,
    val from: BusinessDate,
    val to: BusinessDate,
) : Command {
    override val requiredPermission = Permission.PERIOD_CLOSE
    // A period lock applies to the whole organization, so it needs organization scope.
    override val scope: Scope = Scope.Organization
    override fun fingerprint() = "${from.epochDay}|${to.epochDay}"
}

data class ReopenPeriod(override val commandId: GlobalId, val lockId: GlobalId, val reason: String) : Command {
    override val requiredPermission = Permission.PERIOD_REOPEN
    override val scope: Scope = Scope.Organization
    override fun fingerprint() = "$lockId|$reason"
}

/** Manual accounting. Manual journals can never touch control accounts (AUD-010). */
class ManualAccounting(
    private val bus: CommandBus,
    private val ledger: Ledger,
    private val capability: PostingCapability,
    private val periods: PeriodStore,
) {
    init {
        require(capability.module == ModuleId.LEDGER_MANUAL)
    }

    fun post(command: PostManualJournal): CommandOutcome = bus.execute(ModuleId.LEDGER_MANUAL, command) { c, ctx ->
        val entryId = GlobalId.new()
        ledger.post(
            ctx, capability,
            JournalDraft(
                date = c.date, scope = c.scope, sourceType = "MANUAL_JOURNAL", sourceId = entryId,
                description = c.description,
                lines = c.lines.map { LineDraft(it.account, it.debit, it.credit, it.memo, capability) },
            ),
        ).id
    }

    fun reverse(command: ReverseManualJournal): CommandOutcome = bus.execute(ModuleId.LEDGER_MANUAL, command) { c, ctx ->
        ledger.reverse(ctx, capability, emptySet(), c.entryId, c.date, c.reason).id
    }

    fun closePeriod(command: ClosePeriod): CommandOutcome = bus.execute(ModuleId.LEDGER_MANUAL, command) { c, ctx ->
        ensure(c.from <= c.to) { DomainError.InvalidInput("period", "بازه دوره معتبر نیست.") }
        ensure(!periods.overlapsClosed(c.from, c.to)) { DomainError.InvalidState("PERIOD", "OVERLAP") }
        val lock = PeriodLock(GlobalId.new(), c.from, c.to, closed = true)
        periods.save(lock)
        ctx.audit(AuditDraft("PERIOD_CLOSE", "PERIOD", lock.id.value, "${c.from.epochDay}..${c.to.epochDay}"))
        lock.id
    }

    fun reopenPeriod(command: ReopenPeriod): CommandOutcome = bus.execute(ModuleId.LEDGER_MANUAL, command) { c, ctx ->
        ensure(c.reason.trim().length in 3..300) { DomainError.InvalidInput("reason", "دلیل بازگشایی الزامی است.") }
        val lock = periods.byId(c.lockId) ?: throw ir.sabou.kernel.DomainException(DomainError.NotFound("PERIOD"))
        ensure(lock.closed) { DomainError.InvalidState("PERIOD", "NOT_CLOSED") }
        periods.save(lock.copy(closed = false))
        ctx.audit(AuditDraft("PERIOD_REOPEN", "PERIOD", lock.id.value, c.reason.trim()))
        lock.id
    }
}
