package ir.sabou.core

import ir.sabou.kernel.sumOfExact
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Ratio
import ir.sabou.kernel.Scope
import ir.sabou.ledger.AccountCode
import ir.sabou.ledger.StandardAccounts
import ir.sabou.platform.Actor
import ir.sabou.platform.Permission
import ir.sabou.purchasing.InvoiceStatus
import ir.sabou.sales.Customer
import ir.sabou.sales.SaleStatus
import ir.sabou.sales.Settlement
import ir.sabou.treasury.ChequeDirection
import ir.sabou.treasury.ChequeStatus

/**
 * One expected movement of money (ADR-0051): [amount] in Rial, positive in, negative out. [estimated] when it comes
 * from a pattern (sales, purchases to come, payroll, budgets) rather than a dated document (an invoice, a cheque, a
 * receivable).
 */
data class CashItem(val date: BusinessDate, val kind: Kind, val amount: Long, val text: String, val estimated: Boolean) {
    enum class Kind {
        /** Sales settled in cash, card or transfer, from the sales forecast. */
        CASH_SALES,
        /** Credit sales to come, collected after the customers' usual time. */
        CREDIT_SALES,
        /** What customers owe now, at its due date moved by the customer's usual delay. */
        RECEIVABLE,
        /** Customers' cheques we hold, at their due date. */
        CHEQUE_IN,
        /** Supplier invoices still owed, at their due date. */
        SUPPLIER,
        /** Our cheques not yet cleared, at their due date; bounced ones now. */
        CHEQUE_OUT,
        /** Purchases not yet invoiced, from the sales forecast and the recent purchase ratio, after the usual terms. */
        PURCHASES,
        /** Salaries: what is approved and unpaid, then each month like the last approved payroll. */
        PAYROLL,
        /** Insurance and salary tax owed, then each month like the last approved payroll. */
        PAYROLL_DUES,
        /** Rent, utilities and the other budgeted running costs not booked yet. */
        OVERHEADS,
    }
}

data class CashWeek(
    val index: Int,
    val from: BusinessDate,
    val to: BusinessDate,
    val opening: Long,
    val inflow: Long,
    val outflow: Long,
    val byKind: Map<CashItem.Kind, Long>,
) {
    val closing: Long get() = opening + inflow - outflow
    val net: Long get() = inflow - outflow
}

/**
 * What-if settings. [salesBp]: sales as a share of the forecast (10,000 = as forecast); purchases follow sales.
 * [extraDelayDays]: every customer pays this much later than usual. [customerId]/[customerBp]: one customer's
 * purchases from us as a share of their usual (8,000 = 20 % less).
 */
data class CashScenario(
    val name: String,
    val salesBp: Long = 10_000,
    val extraDelayDays: Int = 0,
    val customerId: GlobalId? = null,
    val customerBp: Long = 10_000,
) : java.io.Serializable {
    companion object {
        val BASE = CashScenario("پایه")
        val CAUTIOUS = CashScenario("محتاط", salesBp = 8_500, extraDelayDays = 30)
        val HOPEFUL = CashScenario("خوش‌بینانه", salesBp = 11_000)
        val PRESETS = listOf(BASE, CAUTIOUS, HOPEFUL)
    }
}

/** Owed or owing with no date the books can give (tax, loans): shown beside the forecast, not inside it. */
data class Unscheduled(val title: String, val amount: Long)

/** The patterns the estimated items come from, so the owner can judge them. */
data class CashAssumptions(
    /** Days of recorded sales the patterns were read from (out of [CashFlows.HISTORY_DAYS]). */
    val historyDays: Int,
    /** Settled in cash/card/transfer, per Rial of net sales (basis points); null without sales history. */
    val liquidBp: Long?,
    /** Sold on credit, per Rial of net sales (basis points). */
    val creditBp: Long?,
    /** Goods bought, per Rial of net sales (basis points); null without sales history. */
    val purchaseBp: Long?,
    /** Days from invoice to due date, weighted by amount. */
    val supplierTermDays: Int,
    /** Days from a credit sale to its collection, weighted by amount (customers' history, else their due dates). */
    val collectionDays: Int,
    /** A payroll was approved before: monthly salaries are repeated from it. */
    val payrollKnown: Boolean,
    /** Running costs have a budget; without one they are left out (and the forecast says so). */
    val overheadsBudgeted: Boolean,
)

data class CashForecast(
    val branch: Scope.Branch,
    val branchName: String,
    val today: BusinessDate,
    val scenario: CashScenario,
    val opening: Long,
    val weeks: List<CashWeek>,
    val items: List<CashItem>,
    val unscheduled: List<Unscheduled>,
    val assumptions: CashAssumptions,
) {
    val lowest: CashWeek? get() = weeks.minByOrNull { it.closing }
    /** The first week that ends below zero, if any. */
    val firstShortWeek: CashWeek? get() = weeks.firstOrNull { it.closing < 0 }
}

/** One customer buying on credit: how much, how much of our sales, how they pay. */
data class CustomerInsight(
    val customer: Customer,
    /** Sold to them on credit in the last [CashFlows.CUSTOMER_DAYS] days (as invoiced, with service and tax). */
    val sales: Long,
    /** [sales] as a share of all the branch's sales (payable) in those days, basis points. */
    val shareBp: Long?,
    val outstanding: Long,
    val overdue: Long,
    /** Days paid after the due date, weighted by amount, over fully collected debts; null without any. */
    val delayDays: Int?,
    /** Days from the sale to its collection, weighted by amount; null without any. */
    val collectionDays: Int?,
    val collectedDebts: Int,
)

data class CustomerReport(
    val branch: Scope.Branch,
    val branchName: String,
    val from: BusinessDate,
    val to: BusinessDate,
    /** All posted sales (payable) of the branch in the period. */
    val totalSales: Long,
    val rows: List<CustomerInsight>,
) {
    val topShareBp: Long? get() = rows.firstOrNull()?.shareBp
    val topThreeShareBp: Long? get() = if (totalSales <= 0) null else Ratio.mulDiv(rows.take(3).sumOfExact { it.sales }, 10_000, totalSales)
    val creditShareBp: Long? get() = if (totalSales <= 0) null else Ratio.mulDiv(rows.sumOfExact { it.sales }, 10_000, totalSales)
}

/**
 * The 13-week cash forecast and the customers behind it (ADR-0051). Dated documents come first (balances,
 * receivables, cheques, supplier invoices, approved payroll); the rest is the sales forecast and the recent patterns,
 * marked estimated. Everything is exact Rial arithmetic; nothing is guessed by a model.
 */
class CashFlows internal constructor(private val core: SabouCore) {
    private fun actor(): Actor {
        val a = core.session.currentActor() ?: throw DomainException(DomainError.AuthenticationRequired)
        if (!a.role.allows(Permission.LEDGER_VIEW)) throw DomainException(DomainError.PermissionDenied(Permission.LEDGER_VIEW.name))
        return a
    }

    private fun Actor.require(branch: Scope.Branch) {
        if (!canAccess(branch)) throw DomainException(DomainError.ScopeDenied(ir.sabou.platform.CommandContext.scopeLabel(branch)))
    }

    fun forecast(branch: Scope.Branch, today: BusinessDate, scenario: CashScenario = CashScenario.BASE, weeks: Int = WEEKS): CashForecast {
        actor().require(branch)
        require(weeks in 1..26) { "weeks" }
        require(scenario.salesBp in 0..30_000 && scenario.customerBp in 0..30_000 && scenario.extraDelayDays in 0..365) { "scenario" }
        val end = today.plusDays(7L * weeks - 1)
        val items = ArrayList<CashItem>()
        fun add(date: BusinessDate, kind: CashItem.Kind, amount: Long, text: String, estimated: Boolean) {
            if (amount != 0L && date <= end) items += CashItem(maxOf(date, today), kind, amount, text, estimated)
        }

        // ---- Money on hand: cash, bank, card terminals, petty cash of the branch.
        val opening = core.treasuryAccounts.all().filter { it.isActive && it.scope == branch && it.kind.isOrdinary }
            .sumOfExact { core.treasuryGateway.balance(it.id) }

        // ---- What customers owe, at due date + their usual delay.
        val history = customerHistory(branch)
        val customers = core.customers.all().associateBy { it.id }
        receivablesOf(branch).filter { !it.voided }.forEach { r ->
            val open = core.salesOps.outstanding(r.id).rial
            if (open <= 0) return@forEach
            val delay = (history[r.customerId]?.delayDays ?: 0).coerceAtLeast(0) + scenario.extraDelayDays
            add(r.dueDate.plusDays(delay.toLong()), CashItem.Kind.RECEIVABLE, open,
                "${customers[r.customerId]?.name.orEmpty()} · سررسید ${Fa.date(r.dueDate)}", estimated = delay > 0)
        }

        // ---- Cheques: customers' we hold come in at their due date; ours go out at theirs, bounced ones now.
        core.cheques.all().filter { it.scope == branch }.forEach { c ->
            when {
                c.direction == ChequeDirection.RECEIVED && (c.status == ChequeStatus.IN_HAND || c.status == ChequeStatus.DEPOSITED) ->
                    add(c.dueDate, CashItem.Kind.CHEQUE_IN, c.amount.rial, "چک ${c.details.counterparty} · ${c.details.number}", false)
                c.direction == ChequeDirection.ISSUED && c.status == ChequeStatus.ISSUED ->
                    add(c.dueDate, CashItem.Kind.CHEQUE_OUT, -c.amount.rial, "چک به ${c.details.counterparty} · ${c.details.number}", false)
                c.direction == ChequeDirection.ISSUED && c.status == ChequeStatus.BOUNCED ->
                    add(today, CashItem.Kind.CHEQUE_OUT, -c.amount.rial, "چک برگشتی ${c.details.counterparty} · ${c.details.number}", false)
            }
        }

        // ---- Supplier invoices still owed.
        val suppliers = core.suppliers.all().associate { it.id to it.name }
        val invoices = core.purchases.invoices().filter { it.scope == branch && it.status == InvoiceStatus.POSTED }
        invoices.forEach { inv ->
            val open = core.purchasing.outstanding(inv.id).rial
            if (open > 0) add(inv.dueDate, CashItem.Kind.SUPPLIER, -open, "${suppliers[inv.supplierId].orEmpty()} · فاکتور ${inv.supplierInvoiceNo}", false)
        }

        // ---- Patterns of the last weeks: how sales are settled, what is bought per Rial sold, supplier terms.
        val from = today.plusDays(-HISTORY_DAYS.toLong())
        val days = core.tradingDays(branch, (from.epochDay until today.epochDay).map { BusinessDate(it) })
        val posted = days.flatMap { it.sales }
        val net = posted.sumOfExact { it.netFood.rial }
        val liquid = posted.sumOfExact { s -> s.settlements.filterIsInstance<Settlement.Liquid>().sumOfExact { it.amount.rial } }
        val credit = posted.sumOfExact { s -> s.settlements.filterIsInstance<Settlement.Credit>().sumOfExact { it.amount.rial } }
        val recent = invoices.filter { it.date >= from && it.date < today && !it.opening }
        val goods = recent.sumOfExact { inv -> inv.total.rial - inv.accountLines.indices.sumOfExact { inv.accountValue(it) } }.coerceAtLeast(0)
        val term = weightedDays(recent.map { (it.dueDate.epochDay - it.date.epochDay) to it.total.rial }) ?: 0
        val collection = history.values.filter { it.collectionDays != null }
            .let { h -> weightedDays(h.map { it.collectionDays!!.toLong() to it.collected }) }
            ?: weightedDays(receivablesOf(branch).filter { !it.voided }.mapNotNull { r ->
                core.sales.sale(r.saleId)?.let { (r.dueDate.epochDay - it.date.epochDay) to r.amount.rial }
            }) ?: 0
        val liquidBp = if (net > 0) Ratio.mulDiv(liquid, 10_000, net) else null
        val creditBp = if (net > 0) Ratio.mulDiv(credit, 10_000, net) else null
        val purchaseBp = if (net > 0) Ratio.mulDiv(goods, 10_000, net) else null

        // ---- Sales to come: the weekly pattern × the owner's special days × the scenario.
        if (net > 0) {
            val week = core.forecasts.normalWeek(branch, today)
            val special = core.specialDays.of(branch).associateBy { it.date }
            // One customer buying less takes their share of credit sales out of the forecast.
            val customerShareBp = scenario.customerId?.let { id ->
                val theirs = history[id]?.creditSales ?: 0L
                val all = history.values.sumOfExact { it.creditSales }
                if (all > 0) Ratio.mulDiv(theirs, 10_000, all) else 0L
            } ?: 0L
            val customerCutBp = Ratio.mulDiv(customerShareBp, 10_000 - scenario.customerBp, 10_000)    // of credit sales
            for (i in 0 until 7L * weeks) {
                val date = today.plusDays(i)
                val mark = special[date]
                val percent = when (mark?.kind) { ir.sabou.sales.SpecialDay.Kind.CLOSED -> 0L; null -> 100L; else -> mark.percent.toLong() }
                val expected = Ratio.mulDiv(Ratio.mulDiv(week[(i % 7).toInt()], percent, 100), scenario.salesBp, 10_000)
                if (expected <= 0) continue
                val cash = Ratio.mulDiv(expected, liquidBp!!, 10_000)
                val creditFull = Ratio.mulDiv(expected, creditBp!!, 10_000)
                val creditDay = creditFull - Ratio.mulDiv(creditFull, customerCutBp, 10_000)
                // Purchases follow what is sold: the cut customer's dishes are not bought either.
                val sold = expected - Ratio.mulDiv(creditFull - creditDay, 10_000, (liquidBp + creditBp).coerceAtLeast(1))
                add(date, CashItem.Kind.CASH_SALES, cash, "فروش نقد و کارت (پیش‌بینی)", true)
                add(date.plusDays((collection + scenario.extraDelayDays).toLong()), CashItem.Kind.CREDIT_SALES, creditDay, "وصول فروش نسیه (پیش‌بینی)", true)
                add(date.plusDays(term.toLong()), CashItem.Kind.PURCHASES, -Ratio.mulDiv(sold.coerceAtLeast(0), purchaseBp!!, 10_000), "خرید مواد (پیش‌بینی)", true)
            }
        }

        // ---- Payroll: approved and unpaid now; then each month like the last approved run.
        fun liability(code: AccountCode): Long = -core.ledger.balance(code, branch).rial     // credit balance → positive
        val payrollOwed = liability(StandardAccounts.PAYROLL_PAYABLE)
        add(today, CashItem.Kind.PAYROLL, -payrollOwed.coerceAtLeast(0), "حقوق تأییدشده‌ی پرداخت‌نشده", false)
        val dues = liability(StandardAccounts.INSURANCE_PAYABLE) + liability(StandardAccounts.PAYROLL_TAX_PAYABLE)
        add(monthEnd(today), CashItem.Kind.PAYROLL_DUES, -dues.coerceAtLeast(0), "بیمه و مالیات حقوق پرداختنی", true)
        val last = core.payrollStore.runs(branch).filter { it.status == ir.sabou.payroll.RunStatus.APPROVED }.maxByOrNull { it.to }
        if (last != null) {
            val monthNet = last.payslips.sumOfExact { it.net.rial }
            val monthDues = last.payslips.sumOfExact { it.employeeInsurance.rial + it.employerInsurance.rial + it.unemploymentInsurance.rial + it.incomeTax.rial }
            var e = monthEnd(last.to.plusDays(1))
            while (e <= end) {
                add(e, CashItem.Kind.PAYROLL, -monthNet, "حقوق ${monthTitle(e)} (مثل آخرین حقوق تأییدشده)", true)
                add(monthEnd(e.plusDays(1)), CashItem.Kind.PAYROLL_DUES, -monthDues, "بیمه و مالیات حقوق ${monthTitle(e)}", true)
                e = monthEnd(e.plusDays(1))
            }
        }

        // ---- Running costs: each budgeted month spread over its days, less what is already booked this month.
        val budgets = core.budgetStore.entries().filter { it.scope == branch && it.account in overheadAccounts() }
        if (budgets.isNotEmpty()) {
            var start = monthStart(today)
            while (start <= end) {
                val stop = monthEnd(start)
                budgets.map { it.account }.distinct().forEach { code ->
                    val budget = budgets.filter { it.account == code }.sumOfExact { b -> overlap(b.from, b.to, start, stop, b.amount.rial) }
                    val booked = if (today > start) booked(code, branch, start, today.plusDays(-1)) else 0L
                    val left = (budget - booked).coerceAtLeast(0)
                    val first = maxOf(start, today)
                    val n = stop.epochDay - first.epochDay + 1
                    if (left > 0 && n > 0) {
                        val name = core.accounts.byCode(code)?.name.orEmpty()
                        Ratio.allocate(left, List(n.toInt()) { 1L }).forEachIndexed { k, v -> add(first.plusDays(k.toLong()), CashItem.Kind.OVERHEADS, -v, "$name (بودجه)", true) }
                    }
                }
                start = stop.plusDays(1)
            }
        }

        // ---- Weeks.
        var balance = opening
        val result = (0 until weeks).map { w ->
            val f = today.plusDays(7L * w); val t = f.plusDays(6)
            val mine = items.filter { it.date >= f && it.date <= t }
            val inflow = mine.filter { it.amount > 0 }.sumOfExact { it.amount }
            val outflow = -mine.filter { it.amount < 0 }.sumOfExact { it.amount }
            CashWeek(w + 1, f, t, balance, inflow, outflow, mine.groupBy { it.kind }.mapValues { (_, l) -> l.sumOfExact { it.amount } }).also { balance = it.closing }
        }

        val unscheduled = listOfNotNull(
            liability(StandardAccounts.SALES_TAX_PAYABLE).takeIf { it > 0 }?.let { Unscheduled("مالیات و عوارض فروش پرداختنی", -it) },
            liability(StandardAccounts.LOANS_PAYABLE).takeIf { it > 0 }?.let { Unscheduled("وام و تسهیلات (اقساط در دفاتر تاریخ ندارد)", -it) },
            liability(StandardAccounts.ASSET_PURCHASE_PAYABLE).takeIf { it > 0 }?.let { Unscheduled("بدهی خرید دارایی", -it) },
            core.ledger.balance(StandardAccounts.BOUNCED_CHEQUES_RECEIVABLE, branch).rial.takeIf { it > 0 }?.let { Unscheduled("چک‌های برگشتی مشتریان (وصول نامعلوم)", it) },
        )
        return CashForecast(branch, nameOf(branch), today, scenario, opening, result, items.sortedBy { it.date }, unscheduled,
            CashAssumptions(days.size, liquidBp, creditBp, purchaseBp, term, collection, last != null, budgets.isNotEmpty()))
    }

    /** Customers who bought on credit from [branch] or owe it, biggest first (ADR-0051). */
    fun customers(branch: Scope.Branch, today: BusinessDate): CustomerReport {
        actor().require(branch)
        val from = today.plusDays(-CUSTOMER_DAYS.toLong())
        val total = core.sales.sales(branch, from, today).filter { it.status == SaleStatus.POSTED }.sumOfExact { it.payable.rial }
        val history = customerHistory(branch)
        val customers = core.customers.all().associateBy { it.id }
        val receivables = receivablesOf(branch).filter { !it.voided }.groupBy { it.customerId }
        val rows = receivables.mapNotNull { (id, list) ->
            val customer = customers[id] ?: return@mapNotNull null
            val sold = list.sumOfExact { r -> core.sales.sale(r.saleId)?.takeIf { it.date >= from && it.date <= today && it.status == SaleStatus.POSTED }?.let { r.amount.rial } ?: 0L }
            val open = list.associateWith { core.salesOps.outstanding(it.id).rial }
            val h = history[id]
            CustomerInsight(customer, sold, if (total > 0) Ratio.mulDiv(sold, 10_000, total) else null, open.values.sum(),
                open.filter { (r, v) -> v > 0 && r.dueDate < today }.values.sum(), h?.delayDays, h?.collectionDays, h?.debts ?: 0)
        }.filter { it.sales > 0 || it.outstanding > 0 }.sortedWith(compareByDescending<CustomerInsight> { it.sales }.thenByDescending { it.outstanding })
        return CustomerReport(branch, nameOf(branch), from, today, total, rows)
    }

    // ------------------------------------------------------------ helpers

    /** How each customer of [branch] has paid: over debts fully collected, weighted by amount. */
    private class PayHistory(val delayDays: Int?, val collectionDays: Int?, val collected: Long, val debts: Int, val creditSales: Long)

    private fun customerHistory(branch: Scope.Branch): Map<GlobalId, PayHistory> =
        receivablesOf(branch).filter { !it.voided }.groupBy { it.customerId }.mapValues { (_, list) ->
            val done = list.mapNotNull { r ->
                val collections = core.sales.collections(r.id).filter { !it.reversed }
                if (collections.isEmpty() || core.salesOps.outstanding(r.id).rial > 0 || !r.writtenOff.isZero) return@mapNotNull null
                val paid = collections.maxOf { it.date }
                val sold = core.sales.sale(r.saleId)?.date
                Triple(paid.epochDay - r.dueDate.epochDay, sold?.let { paid.epochDay - it.epochDay }, r.amount.rial)
            }
            PayHistory(
                weightedDays(done.map { it.first to it.third }),
                weightedDays(done.mapNotNull { d -> d.second?.let { it to d.third } }),
                done.sumOfExact { it.third }, done.size, list.sumOfExact { it.amount.rial },
            )
        }

    private fun nameOf(branch: Scope.Branch): String = core.branches.all().firstOrNull { it.id == branch.branchId }?.name ?: "شعبه"

    private fun receivablesOf(branch: Scope.Branch): List<ir.sabou.sales.Receivable> =
        core.customers.all().flatMap { core.sales.receivablesOfCustomer(it.id) }.filter { it.scope == branch }

    private fun monthTitle(d: BusinessDate): String = Fa.jalali(d).let { "${Fa.monthNames[it.month - 1]} ${Fa.digits(it.year.toString())}" }

    /** Mean of [days] weighted by amount (rounded), or null when there is nothing to weigh. */
    private fun weightedDays(days: List<Pair<Long, Long>>): Int? {
        val weight = days.sumOfExact { it.second.coerceAtLeast(0) }
        if (weight <= 0) return null
        val sum = days.fold(java.math.BigInteger.ZERO) { acc, (d, w) -> acc + java.math.BigInteger.valueOf(d).multiply(java.math.BigInteger.valueOf(w.coerceAtLeast(0))) }
        return sum.add(java.math.BigInteger.valueOf(weight / 2)).divide(java.math.BigInteger.valueOf(weight)).toInt()
    }

    /** The part of a budget [amount] for [from]…[to] that falls in [start]…[stop], by days. */
    private fun overlap(from: BusinessDate, to: BusinessDate, start: BusinessDate, stop: BusinessDate, amount: Long): Long {
        val a = maxOf(from.epochDay, start.epochDay); val b = minOf(to.epochDay, stop.epochDay)
        return if (b < a) 0 else Ratio.mulDiv(amount, b - a + 1, to.epochDay - from.epochDay + 1)
    }

    /** What the books already show spent on [code] in [branch] over [from]…[to] (debit − credit). */
    private fun booked(code: AccountCode, branch: Scope.Branch, from: BusinessDate, to: BusinessDate): Long =
        core.journals.dailyTotals(from, to).filter { it.account == code && it.scope == branch }.sumOfExact { it.debit - it.credit }

    private fun overheadAccounts(): Set<AccountCode> = core.accounts.all()
        .filter { it.type == ir.sabou.ledger.AccountType.EXPENSE && it.code !in NOT_OVERHEAD }.map { it.code }.toSet()

    private fun monthStart(d: BusinessDate): BusinessDate = Fa.jalali(d).let { Fa.fromJalali(it.year, it.month, 1) }
    private fun monthEnd(d: BusinessDate): BusinessDate = Fa.jalali(d).let { Fa.fromJalali(it.year, it.month, Fa.monthLength(it.year, it.month)) }

    companion object {
        const val WEEKS = 13
        const val HISTORY_DAYS = 56
        const val CUSTOMER_DAYS = 90
        /** Weeks ahead in which a forecast below zero raises the alert. */
        const val ALERT_WEEKS = 4

        /** Expenses that are not paid as running costs: stock, payroll (its own lines), non-cash entries. */
        private val NOT_OVERHEAD = setOf(
            StandardAccounts.COGS, StandardAccounts.WASTE, StandardAccounts.INVENTORY_VARIANCE, StandardAccounts.COMPS,
            StandardAccounts.SALARIES, StandardAccounts.EMPLOYER_INSURANCE, StandardAccounts.DEPRECIATION,
            StandardAccounts.BAD_DEBTS, StandardAccounts.CASH_OVER_SHORT,
        )
    }
}
