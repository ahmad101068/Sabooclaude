package ir.sabou.ledger

import ir.sabou.platform.NoDocument

import ir.sabou.platform.DocumentSeries

import ir.sabou.platform.IssuesDocument

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

data class ManualLine(val account: AccountCode, val debit: Money, val credit: Money, val memo: String = "") : java.io.Serializable

@IssuesDocument(DocumentSeries.JOURNAL)
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

@IssuesDocument(DocumentSeries.JOURNAL)
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

@NoDocument
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

@NoDocument
data class ReopenPeriod(override val commandId: GlobalId, val lockId: GlobalId, val reason: String) : Command {
    override val requiredPermission = Permission.PERIOD_REOPEN
    override val scope: Scope = Scope.Organization
    override fun fingerprint() = "$lockId|$reason"
}

/** Manual accounting. Manual journals can never touch control accounts (AUD-010). */
/**
 * One of the restaurant's own accounts beside the standard chart: an expense (repairs of the hood), a loan, a deposit.
 * The first digit of the four-digit [code] gives its kind: 1 asset, 2 liability, 3 equity, 4 revenue, 5–9 expense.
 */
@NoDocument
data class DefineAccount(override val commandId: GlobalId, val code: String, val name: String) : Command {
    override val requiredPermission = Permission.ACCOUNT_MANAGE
    override val scope: Scope = Scope.Organization
    override val sharedCatalog = true
    override fun fingerprint() = "$code|$name"
}

/** Renames an account of the restaurant's own, or retires one with nothing left on it. */
@NoDocument
data class UpdateAccount(override val commandId: GlobalId, val code: AccountCode, val name: String, val isActive: Boolean) : Command {
    override val requiredPermission = Permission.ACCOUNT_MANAGE
    override val scope: Scope = Scope.Organization
    override val sharedCatalog = true
    override fun fingerprint() = "$code|$name|$isActive"
}

class ManualAccounting(
    private val bus: CommandBus,
    private val ledger: Ledger,
    private val capability: PostingCapability,
    private val periods: PeriodStore,
    private val accounts: AccountStore? = null,
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

    fun defineAccount(command: DefineAccount): CommandOutcome = bus.execute(ModuleId.LEDGER_MANUAL, command) { c, ctx ->
        val store = accounts ?: error("accounts")
        val code = AccountCode.of(c.code.map { ch -> when (ch) { in '۰'..'۹' -> '0' + (ch - '۰'); in '٠'..'٩' -> '0' + (ch - '٠'); else -> ch } }.joinToString(""))
        val name = c.name.trim()
        ensure(name.length in 2..80) { DomainError.InvalidInput("name", "نام حساب الزامی است.") }
        ensure(store.byCode(code) == null) { DomainError.InvalidState("ACCOUNT", "DUPLICATE_CODE") }
        ensure(store.all().none { it.name == name }) { DomainError.InvalidState("ACCOUNT", "DUPLICATE_NAME") }
        val type = when (code.value[0]) {
            '1' -> AccountType.ASSET; '2' -> AccountType.LIABILITY; '3' -> AccountType.EQUITY; '4' -> AccountType.REVENUE
            else -> AccountType.EXPENSE
        }
        store.upsert(Account(code, name, type, emptySet(), isSystem = false))
        ctx.audit(AuditDraft("ACCOUNT_DEFINE", "ACCOUNT", code.value, "$type:$name"))
        GlobalId.new()
    }

    fun updateAccount(command: UpdateAccount): CommandOutcome = bus.execute(ModuleId.LEDGER_MANUAL, command) { c, ctx ->
        val store = accounts ?: error("accounts")
        val account = store.byCode(c.code) ?: throw ir.sabou.kernel.DomainException(DomainError.NotFound("ACCOUNT"))
        ensure(!account.isSystem) { DomainError.InvalidState("ACCOUNT", "SYSTEM") }
        val name = c.name.trim()
        ensure(name.length in 2..80) { DomainError.InvalidInput("name", "نام حساب الزامی است.") }
        ensure(store.all().none { it.code != account.code && it.name == name }) { DomainError.InvalidState("ACCOUNT", "DUPLICATE_NAME") }
        if (account.isActive && !c.isActive) ensure(ledger.balance(account.code).rial == 0L) { DomainError.InvalidState("ACCOUNT", "HAS_BALANCE") }
        store.upsert(account.copy(name = name, isActive = c.isActive))
        ctx.audit(AuditDraft("ACCOUNT_UPDATE", "ACCOUNT", account.code.value, "name=$name;active=${c.isActive}"))
        GlobalId.new()
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
