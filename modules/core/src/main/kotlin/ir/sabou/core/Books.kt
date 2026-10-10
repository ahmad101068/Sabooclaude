package ir.sabou.core

import ir.sabou.assets.AssetStatus
import ir.sabou.assets.DepreciationRun
import ir.sabou.assets.FixedAsset
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Ratio
import ir.sabou.kernel.Scope
import ir.sabou.ledger.Account
import ir.sabou.ledger.AccountType
import ir.sabou.ledger.BudgetEntry
import ir.sabou.platform.Actor
import ir.sabou.platform.Permission
import ir.sabou.purchasing.ApprovalRule
import ir.sabou.purchasing.InvoiceStatus
import ir.sabou.treasury.Cheque
import ir.sabou.treasury.ChequeDirection
import ir.sabou.treasury.ChequeStatus

data class ChequeRow(val cheque: Cheque, val account: String, val bank: String?, val branch: String)

data class ApprovalItem(val invoice: InvoiceRow, val approvedBy: List<String>)

/** Budget and actual of one account (natural sign: income and expenses both positive). */
data class BudgetLine(val account: Account, val budget: Long, val actual: Long) {
    val variance: Long get() = actual - budget
    /** Actual as basis points of budget; null without a budget. */
    val usedBp: Long? get() = if (budget <= 0 || actual < 0) null else Ratio.mulDiv(actual, 10_000, budget)
}

data class BudgetReport(val from: BusinessDate, val to: BusinessDate, val place: String, val lines: List<BudgetLine>)

data class AssetRow(val asset: FixedAsset, val branch: String, val nextDepreciation: Money, val number: String? = null)

/**
 * Read models of stage C (ADR-0013): cheques, invoice approvals, budgets and fixed assets. Same rules as
 * [Overview]: signed-in actor, permission, branch scope.
 */
class Books internal constructor(private val core: SabouCore) {
    private fun actor(vararg anyOf: Permission): Actor {
        val a = core.session.currentActor() ?: throw DomainException(DomainError.AuthenticationRequired)
        if (anyOf.none { a.role.allows(it) }) throw DomainException(DomainError.PermissionDenied(anyOf.first().name))
        return a
    }

    private fun Actor.require(scope: Scope) {
        if (!canAccess(scope)) throw DomainException(DomainError.ScopeDenied(ir.sabou.platform.CommandContext.scopeLabel(scope)))
    }

    private fun scopeName(scope: Scope): String = when (scope) {
        Scope.Organization -> "دفتر مرکزی"
        is Scope.Branch -> core.branches.all().firstOrNull { it.id == scope.branchId }?.name ?: "شعبه"
    }

    // ------------------------------------------------------------ Cheques

    private fun rows(filter: (Cheque) -> Boolean): List<ChequeRow> = rows(actor(Permission.TREASURY_VIEW, Permission.CHEQUE_MANAGE), filter)

    private fun rows(a: Actor, filter: (Cheque) -> Boolean): List<ChequeRow> {
        val accounts = core.treasuryAccounts.all().associateBy { it.id }
        return core.cheques.all().filter { a.canAccess(it.scope) && filter(it) }
            .map { ChequeRow(it, accounts[it.accountId]?.name.orEmpty(), it.bankAccountId?.let { b -> accounts[b]?.name }, scopeName(it.scope)) }
    }

    fun cheques(direction: ChequeDirection? = null, pendingOnly: Boolean = false): List<ChequeRow> =
        rows { (direction == null || it.direction == direction) && (!pendingOnly || it.pending || it.status == ChequeStatus.BOUNCED) }

    /** Open cheques due on or before [today] + [days] (overdue included), soonest first. */
    fun chequesDue(today: BusinessDate, days: Long): List<ChequeRow> =
        rows { it.pending && it.dueDate <= today.plusDays(days) }.sortedBy { it.cheque.dueDate }

    fun cheque(id: GlobalId): ChequeRow = rows { it.id == id }.singleOrNull() ?: throw DomainException(DomainError.NotFound("CHEQUE"))

    /** Customers' cheques in hand that can be passed on (to pay a supplier or an expense) from [scope]'s boxes. */
    fun heldCheques(scope: Scope): List<ChequeRow> {
        val a = actor(Permission.PURCHASE_PAY, Permission.TREASURY_PAYMENT, Permission.CHEQUE_MANAGE)
        a.require(scope)
        return rows(a) { it.direction == ChequeDirection.RECEIVED && it.status == ChequeStatus.IN_HAND && it.scope == scope }
    }

    // ------------------------------------------------------------ Approvals

    fun approvalRules(): List<ApprovalRule> {
        actor(Permission.APPROVAL_RULES, Permission.PURCHASE_APPROVE)
        return core.approvalRules.all()
    }

    /** Whether, and up to which total, the owner may approve invoices they recorded. */
    fun selfApprovalPolicy(): ir.sabou.purchasing.SelfApprovalPolicy {
        actor(Permission.APPROVAL_RULES, Permission.PURCHASE_APPROVE)
        return core.approvalRules.selfApproval()
    }

    /** Invoices waiting for approval in the visible branches. */
    fun pendingApprovals(): List<ApprovalItem> {
        val a = actor(Permission.PURCHASE_APPROVE, Permission.PURCHASE_VIEW)
        val names = core.suppliers.all().associate { it.id to it.name }
        return core.purchases.invoices().filter { it.status == InvoiceStatus.POSTED && !it.approved && a.canAccess(it.scope) }
            .sortedBy { it.dueDate }
            .map { ApprovalItem(InvoiceRow(it, names[it.supplierId].orEmpty(), core.purchasing.outstanding(it.id)), it.approvals.map { ap -> ap.name }) }
    }

    // ------------------------------------------------------------ Budgets

    fun budgetEntries(scope: Scope): List<BudgetEntry> {
        val a = actor(Permission.BUDGET_MANAGE, Permission.LEDGER_VIEW)
        a.require(scope)
        return core.budgetStore.entries().filter { it.scope == scope }
    }

    /** Revenue and expense accounts a budget can be set for. */
    fun budgetAccounts(): List<Account> {
        actor(Permission.BUDGET_MANAGE, Permission.LEDGER_VIEW)
        return core.accounts.all().filter { it.isActive && (it.type == AccountType.REVENUE || it.type == AccountType.EXPENSE) }.sortedBy { it.code.value }
    }

    /**
     * Budget against actual for [from]..[to]: a budget period partly inside the range counts by its share of
     * days. [scope] null = every scope the actor may see.
     */
    fun budgetVsActual(from: BusinessDate, to: BusinessDate, scope: Scope? = null): BudgetReport {
        val a = actor(Permission.LEDGER_VIEW)
        if (from > to || to.epochDay - from.epochDay > 3_660) throw DomainException(DomainError.InvalidInput("period", "بازه‌ی گزارش معتبر نیست."))
        scope?.let { a.require(it) }
        fun visible(s: Scope) = if (scope != null) s == scope else a.canAccess(s)
        val accounts = core.accounts.all().associateBy { it.code }
        val budget = HashMap<ir.sabou.ledger.AccountCode, Long>()
        core.budgetStore.entries().filter { visible(it.scope) }.forEach { e ->
            val start = maxOf(e.from.epochDay, from.epochDay); val end = minOf(e.to.epochDay, to.epochDay)
            if (end < start) return@forEach
            val share = Ratio.mulDiv(e.amount.rial, end - start + 1, e.to.epochDay - e.from.epochDay + 1)
            budget[e.account] = (budget[e.account] ?: 0) + share
        }
        val actual = HashMap<ir.sabou.ledger.AccountCode, Long>()
        core.journals.dailyTotals(from, to).filter { visible(it.scope) }.forEach { t ->
            val account = accounts[t.account] ?: return@forEach
            val amount = when (account.type) {
                AccountType.REVENUE -> t.credit - t.debit
                AccountType.EXPENSE -> t.debit - t.credit
                else -> return@forEach
            }
            actual[t.account] = (actual[t.account] ?: 0) + amount
        }
        val lines = (budget.keys + actual.keys).distinct().mapNotNull { code ->
            val account = accounts[code] ?: return@mapNotNull null
            BudgetLine(account, budget[code] ?: 0, actual[code] ?: 0).takeIf { it.budget != 0L || it.actual != 0L }
        }.sortedBy { it.account.code.value }
        return BudgetReport(from, to, scope?.let(::scopeName) ?: "همه‌ی شعب", lines)
    }

    // ------------------------------------------------------------ Fixed assets

    fun assets(branch: Scope.Branch? = null, through: BusinessDate? = null): List<AssetRow> {
        val a = actor(Permission.ASSET_VIEW, Permission.ASSET_MANAGE)
        branch?.let { a.require(it) }
        return core.assetStore.all().filter { (branch == null || it.scope == branch) && a.canAccess(it.scope) }
            .map { AssetRow(it, scopeName(it.scope), through?.let { d -> it.depreciationThrough(d) } ?: Money.ZERO) }
            .sortedWith(compareBy({ it.asset.status != AssetStatus.ACTIVE }, { it.asset.name }))
    }

    fun asset(id: GlobalId): AssetRow {
        val a = actor(Permission.ASSET_VIEW, Permission.ASSET_MANAGE)
        val asset = core.assetStore.byId(id) ?: throw DomainException(DomainError.NotFound("FIXED_ASSET"))
        a.require(asset.scope)
        return AssetRow(asset, scopeName(asset.scope), Money.ZERO, core.numbers.of(ir.sabou.platform.DocumentSeries.ASSET, asset.id)?.text)
    }

    fun depreciationRuns(branch: Scope.Branch): List<DepreciationRun> {
        val a = actor(Permission.ASSET_VIEW, Permission.ASSET_MANAGE)
        a.require(branch)
        return core.assetStore.runs().filter { it.scope == branch }.sortedByDescending { it.through }
    }
}
