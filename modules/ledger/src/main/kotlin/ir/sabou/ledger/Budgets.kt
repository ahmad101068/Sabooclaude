package ir.sabou.ledger

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
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

/** A planned amount for one revenue or expense account, one scope and one period (usually a month). */
data class BudgetEntry(
    val id: GlobalId,
    val scope: Scope,
    val account: AccountCode,
    val from: BusinessDate,
    val to: BusinessDate,
    val amount: Money,
)

data class BudgetPeriod(val from: BusinessDate, val to: BusinessDate, val amount: Money)

interface BudgetStore {
    fun entries(): List<BudgetEntry>
    /** Replaces the entry with the same scope, account and start date. */
    fun save(entry: BudgetEntry)
}

/** Sets the budget of one account for several periods at once (e.g. the twelve months of a year). Nothing is posted. */
data class SetBudget(
    override val commandId: GlobalId,
    override val scope: Scope,
    val account: AccountCode,
    val periods: List<BudgetPeriod>,
) : Command {
    override val requiredPermission = Permission.BUDGET_MANAGE
    override fun fingerprint() = "$scope|$account|" + periods.joinToString(";") { "${it.from.epochDay}:${it.to.epochDay}:${it.amount.rial}" }
}

class BudgetOperations(
    private val bus: CommandBus,
    private val accounts: AccountStore,
    private val budgets: BudgetStore,
) {
    fun set(c: SetBudget): CommandOutcome = bus.execute(ModuleId.LEDGER_MANUAL, c) { cmd, ctx ->
        val account = accounts.byCode(cmd.account) ?: throw DomainException(DomainError.NotFound("ACCOUNT:${cmd.account}"))
        ensure(account.type == AccountType.REVENUE || account.type == AccountType.EXPENSE) { DomainError.InvalidInput("account", "بودجه فقط برای حساب‌های درآمد و هزینه است.") }
        ensure(cmd.periods.isNotEmpty() && cmd.periods.size <= 24) { DomainError.InvalidInput("periods", "دوره‌ها معتبر نیست.") }
        val sorted = cmd.periods.sortedBy { it.from }
        ensure(sorted.all { it.from <= it.to }) { DomainError.InvalidInput("periods", "تاریخ پایان دوره قبل از شروع است.") }
        ensure(sorted.zipWithNext().all { (a, b) -> a.to < b.from }) { DomainError.InvalidInput("periods", "دوره‌ها هم‌پوشانی دارند.") }
        val existing = budgets.entries().filter { it.scope == cmd.scope && it.account == cmd.account }
        sorted.forEach { p ->
            ensure(existing.none { it.from != p.from && it.from <= p.to && p.from <= it.to }) { DomainError.InvalidInput("periods", "با بودجه‌ی ثبت‌شده‌ی دیگری هم‌پوشانی دارد.") }
            val id = existing.firstOrNull { it.from == p.from }?.id ?: GlobalId.new()
            budgets.save(BudgetEntry(id, cmd.scope, cmd.account, p.from, p.to, p.amount))
        }
        ctx.audit(AuditDraft("BUDGET_SET", "BUDGET", cmd.account.value, "scope=${cmd.scope};periods=${sorted.size};total=${sorted.sumOf { it.amount.rial }}"))
        GlobalId.new()
    }
}
