package ir.sabou.core

import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.Money
import ir.sabou.kernel.Scope
import ir.sabou.ledger.StandardAccounts
import ir.sabou.platform.Actor
import ir.sabou.platform.Permission

/** What a branch owes in VAT and levies on its sales, and the VAT on purchases it may set against it. */
data class SalesTaxView(val owed: Money, val inputCredit: Money)

/** One account of the trial balance: opening before the period, the period's debits and credits, closing. */
data class TrialRow(val account: ir.sabou.ledger.Account, val opening: Long, val debit: Long, val credit: Long) {
    val closing: Long get() = opening + debit - credit
}

data class SheetLine(val account: ir.sabou.ledger.Account, val amount: Long)

/**
 * The balance sheet on [asOf]: each side with its accounts (positive = the side's normal balance). Profit is not
 * closed into retained earnings by entries; it is shown as earlier years' and this year's result (ADR-0045).
 */
data class BalanceSheet(
    val asOf: ir.sabou.kernel.BusinessDate,
    val assets: List<SheetLine>,
    val liabilities: List<SheetLine>,
    val equity: List<SheetLine>,
    val earlierProfit: Long,
    val yearProfit: Long,
) {
    val totalAssets: Long get() = assets.sumOf { it.amount }
    val totalLiabilities: Long get() = liabilities.sumOf { it.amount }
    val totalEquity: Long get() = equity.sumOf { it.amount } + earlierProfit + yearProfit
}

/** A journal entry with its legal number, for the journal book. */
data class JournalView(val entry: ir.sabou.ledger.JournalEntry, val number: String?)

/** One row of a supplier's or customer's statement; [balance] after it (what we owe them / they owe us). */
data class StatementRow(val date: ir.sabou.kernel.BusinessDate, val title: String, val number: String?, val debit: Long, val credit: Long, val balance: Long)

data class Statement(val party: String, val from: ir.sabou.kernel.BusinessDate, val to: ir.sabou.kernel.BusinessDate, val opening: Long, val rows: List<StatementRow>) {
    val closing: Long get() = rows.lastOrNull()?.balance ?: opening
}

/** What one party owes (or is owed) by how late: not yet due, 1–30, 31–60, 61–90 and over 90 days. */
data class AgingRow(val party: String, val buckets: LongArray) {
    val total: Long get() = buckets.sum()
}

/** Accounting read models (ADR-0045). */
class AccountingReads internal constructor(private val core: SabouCore) {
    private fun actor(vararg anyOf: Permission): Actor {
        val a = core.session.currentActor() ?: throw DomainException(DomainError.AuthenticationRequired)
        if (anyOf.none { a.role.allows(it) }) throw DomainException(DomainError.PermissionDenied(anyOf.first().name))
        return a
    }

    private fun Actor.require(scope: Scope) {
        if (!canAccess(scope)) throw DomainException(DomainError.ScopeDenied(ir.sabou.platform.CommandContext.scopeLabel(scope)))
    }

    /** The scopes whose books the person may read: [branch] alone, or every branch they hold (and the organization). */
    private fun scopes(a: Actor, branch: Scope.Branch?): Set<Scope>? {
        branch?.let { a.require(it); return setOf(it) }
        if (a.isOwner) return null
        return buildSet { addAll(a.branchGrants.map { Scope.Branch(it) }); if (a.canAccess(Scope.Organization)) add(Scope.Organization) }
    }

    private fun numberOf(id: ir.sabou.kernel.GlobalId): String? = core.documentNumbers.ofDocument(id).firstOrNull()?.number?.text

    /** Every account (inactive ones too), for the chart page. */
    fun chart(): List<ir.sabou.ledger.Account> {
        actor(Permission.LEDGER_VIEW, Permission.ACCOUNT_MANAGE, Permission.TREASURY_PAYMENT, Permission.TREASURY_RECEIPT, Permission.PURCHASE_RECORD)
        return core.accounts.all().sortedBy { it.code.value }
    }

    fun trialBalance(from: ir.sabou.kernel.BusinessDate, to: ir.sabou.kernel.BusinessDate, branch: Scope.Branch? = null): List<TrialRow> {
        val visible = scopes(actor(Permission.LEDGER_VIEW), branch)
        fun ok(s: Scope) = visible == null || s in visible
        val opening = core.journals.balancesUpTo(from.plusDays(-1)).filter { ok(it.second) }.groupBy { it.first }.mapValues { e -> e.value.sumOf { it.third } }
        val period = core.journals.dailyTotals(from, to).filter { ok(it.scope) }.groupBy { it.account }
        return core.accounts.all().sortedBy { it.code.value }.map { acc ->
            val rows = period[acc.code].orEmpty()
            TrialRow(acc, opening[acc.code] ?: 0L, rows.sumOf { it.debit }, rows.sumOf { it.credit })
        }.filter { it.opening != 0L || it.debit != 0L || it.credit != 0L }
    }

    fun balanceSheet(asOf: ir.sabou.kernel.BusinessDate, branch: Scope.Branch? = null): BalanceSheet {
        val visible = scopes(actor(Permission.LEDGER_VIEW), branch)
        fun net(upTo: ir.sabou.kernel.BusinessDate) = core.journals.balancesUpTo(upTo).filter { visible == null || it.second in visible }
            .groupBy { it.first }.mapValues { e -> e.value.sumOf { it.third } }
        val now = net(asOf)
        val yearStart = ir.sabou.kernel.JalaliCalendar.date(ir.sabou.kernel.JalaliCalendar.yearOf(asOf), 1, 1)
        val before = net(yearStart.plusDays(-1))
        val accounts = core.accounts.all().sortedBy { it.code.value }
        fun profit(map: Map<ir.sabou.ledger.AccountCode, Long>) =
            -accounts.filter { it.type == ir.sabou.ledger.AccountType.REVENUE || it.type == ir.sabou.ledger.AccountType.EXPENSE }.sumOf { map[it.code] ?: 0L }
        fun side(type: ir.sabou.ledger.AccountType, sign: Long) = accounts.filter { it.type == type }
            .map { SheetLine(it, sign * (now[it.code] ?: 0L)) }.filter { it.amount != 0L }
        val earlier = profit(before)
        return BalanceSheet(asOf, side(ir.sabou.ledger.AccountType.ASSET, 1), side(ir.sabou.ledger.AccountType.LIABILITY, -1),
            side(ir.sabou.ledger.AccountType.EQUITY, -1), earlier, profit(now) - earlier)
    }

    fun journalBook(from: ir.sabou.kernel.BusinessDate, to: ir.sabou.kernel.BusinessDate, branch: Scope.Branch? = null): List<JournalView> {
        val visible = scopes(actor(Permission.LEDGER_VIEW), branch)
        val numbers = core.documentNumbers
        return core.journals.entriesBetween(from, to, branch).filter { visible == null || it.scope in visible }
            .map { JournalView(it, numbers.of(ir.sabou.platform.DocumentSeries.JOURNAL, it.id)?.number?.text) }
    }

    /** What we owe a supplier over time: invoices (credit) against payments and returns (debit); refunds bring it back. */
    fun supplierStatement(supplierId: ir.sabou.kernel.GlobalId, from: ir.sabou.kernel.BusinessDate, to: ir.sabou.kernel.BusinessDate, branch: Scope.Branch? = null): Statement {
        val visible = scopes(actor(Permission.PURCHASE_VIEW), branch)
        fun ok(s: Scope) = visible == null || s in visible
        val supplier = core.suppliers.byId(supplierId) ?: throw DomainException(DomainError.NotFound("SUPPLIER"))
        data class E(val date: ir.sabou.kernel.BusinessDate, val title: String, val id: ir.sabou.kernel.GlobalId, val debit: Long, val credit: Long)
        val invoices = core.purchases.invoices().filter { it.supplierId == supplierId && it.status == ir.sabou.purchasing.InvoiceStatus.POSTED && ok(it.scope) }
        val events = buildList {
            invoices.forEach { inv ->
                add(E(inv.date, if (inv.opening) "مانده‌ی اول دوره" else "فاکتور ${inv.supplierInvoiceNo}", inv.id, 0, inv.total.rial))
                core.purchases.payments(inv.id).filter { !it.reversed }.forEach { add(E(it.date, "پرداخت بابت ${inv.supplierInvoiceNo}", it.id, it.amount.rial, 0)) }
                core.purchases.returns(inv.id).forEach { add(E(it.date, "مرجوعی از فاکتور ${inv.supplierInvoiceNo}", it.id, it.credit.rial, 0)) }
            }
            invoices.map { it.scope }.distinct().forEach { s ->
                core.purchases.refundsOf(supplierId, s).filter { !it.reversed }.forEach { add(E(it.date, "استرداد وجه از تأمین‌کننده", it.id, 0, it.amount.rial)) }
            }
        }.sortedBy { it.date }
        val opening = events.filter { it.date < from }.sumOf { it.credit - it.debit }
        var balance = opening
        val rows = events.filter { it.date >= from && it.date <= to }.map { e ->
            balance += e.credit - e.debit
            StatementRow(e.date, e.title, numberOf(e.id), e.debit, e.credit, balance)
        }
        return Statement(supplier.name, from, to, opening, rows)
    }

    /** What a customer owes over time: credit sales and opening balances (debit) against collections and write-offs. */
    fun customerStatement(customerId: ir.sabou.kernel.GlobalId, from: ir.sabou.kernel.BusinessDate, to: ir.sabou.kernel.BusinessDate, branch: Scope.Branch? = null): Statement {
        val visible = scopes(actor(Permission.SALES_VIEW, Permission.CUSTOMER_MANAGE), branch)
        fun ok(s: Scope) = visible == null || s in visible
        val customer = core.customers.byId(customerId) ?: throw DomainException(DomainError.NotFound("CUSTOMER"))
        data class E(val date: ir.sabou.kernel.BusinessDate, val title: String, val id: ir.sabou.kernel.GlobalId?, val debit: Long, val credit: Long)
        val events = buildList {
            core.sales.receivablesOfCustomer(customerId).filter { !it.voided && ok(it.scope) }.forEach { r ->
                val sale = core.sales.sale(r.saleId)
                add(E(sale?.date ?: r.dueDate, if (sale != null) "فروش نسیه" else "مانده‌ی اول دوره", if (sale != null) r.saleId else null, r.amount.rial, 0))
                core.sales.collections(r.id).filter { !it.reversed }.forEach { add(E(it.date, "دریافت", it.id, 0, it.amount.rial)) }
                r.writeOffs.forEach { add(E(it.date, "سوخت: ${it.reason}", null, 0, it.amount.rial)) }
            }
        }.sortedBy { it.date }
        val opening = events.filter { it.date < from }.sumOf { it.debit - it.credit }
        var balance = opening
        val rows = events.filter { it.date >= from && it.date <= to }.map { e ->
            balance += e.debit - e.credit
            StatementRow(e.date, e.title, e.id?.let(::numberOf), e.debit, e.credit, balance)
        }
        return Statement(customer.name, from, to, opening, rows)
    }

    /** Customers' open debts by how far past due on [asOf]. */
    fun receivablesAging(asOf: ir.sabou.kernel.BusinessDate): List<AgingRow> {
        val visible = scopes(actor(Permission.SALES_VIEW), null)
        val names = core.customers.all().associate { it.id to it.name }
        return core.customers.all().mapNotNull { c ->
            val buckets = LongArray(5)
            core.sales.receivablesOfCustomer(c.id).filter { !it.voided && (visible == null || it.scope in visible) }.forEach { r ->
                val open = core.salesOps.outstanding(r.id).rial
                if (open > 0) buckets[bucket(asOf.epochDay - r.dueDate.epochDay)] += open
            }
            AgingRow(names[c.id].orEmpty(), buckets).takeIf { it.total > 0 }
        }.sortedByDescending { it.total }
    }

    /** What we owe suppliers by how far past due on [asOf]. */
    fun payablesAging(asOf: ir.sabou.kernel.BusinessDate): List<AgingRow> {
        val visible = scopes(actor(Permission.PURCHASE_VIEW), null)
        val names = core.suppliers.all().associate { it.id to it.name }
        return core.purchases.invoices().filter { it.status == ir.sabou.purchasing.InvoiceStatus.POSTED && (visible == null || it.scope in visible) }
            .groupBy { it.supplierId }.mapNotNull { (supplierId, invoices) ->
                val buckets = LongArray(5)
                invoices.forEach { inv ->
                    val open = core.purchasing.outstanding(inv.id).rial
                    if (open > 0) buckets[bucket(asOf.epochDay - inv.dueDate.epochDay)] += open
                }
                AgingRow(names[supplierId].orEmpty(), buckets).takeIf { it.total > 0 }
            }.sortedByDescending { it.total }
    }

    private fun bucket(daysLate: Long) = when {
        daysLate <= 0 -> 0
        daysLate <= 30 -> 1
        daysLate <= 60 -> 2
        daysLate <= 90 -> 3
        else -> 4
    }

    /** Every customer (any branch), for statements and opening balances. */
    fun allCustomers(): List<ir.sabou.sales.Customer> {
        actor(Permission.SALES_VIEW, Permission.CUSTOMER_MANAGE, Permission.JOURNAL_MANUAL_POST)
        return core.customers.all().sortedBy { it.name }
    }

    fun salesTax(branch: Scope.Branch): SalesTaxView {
        actor(Permission.TREASURY_PAYMENT, Permission.LEDGER_VIEW).require(branch)
        return SalesTaxView(core.salesOps.salesTaxOwed(branch), Money.of(maxOf(0L, core.ledger.balance(StandardAccounts.VAT_RECEIVABLE, branch).rial)))
    }
}
