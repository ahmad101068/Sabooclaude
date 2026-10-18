package ir.sabou.core

import ir.sabou.persistence.DevDatabases
import ir.sabou.persistence.ClosableSqlDatabase

import ir.sabou.inventory.DefineMenuItem
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.ledger.BudgetPeriod
import ir.sabou.ledger.SetBudget
import ir.sabou.ledger.StandardAccounts
import ir.sabou.platform.Role
import ir.sabou.platform.memory.InMemoryAnchorStore
import ir.sabou.purchasing.InvoiceLine
import ir.sabou.purchasing.PostPurchaseInvoice
import ir.sabou.purchasing.RegisterSupplier
import ir.sabou.sales.CollectReceivable
import ir.sabou.sales.CustomerType
import ir.sabou.sales.PostDailySale
import ir.sabou.sales.RegisterCustomer
import ir.sabou.sales.SaleLineInput
import ir.sabou.sales.SaveSaleDraft
import ir.sabou.sales.Settlement
import ir.sabou.treasury.ReceiptPurpose
import ir.sabou.treasury.RecordReceipt
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR-0051: the 13-week cash forecast and the customers behind it. */
class CashFlowTest {
    private val dir = Files.createTempDirectory("sabou-cash")
    private val db = DevDatabases.open(dir.resolve("c.db"))
    private val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { 1_700_000_000_000L }, emptyList(), null)
    private val today = BusinessDate(20_100)
    private fun id() = GlobalId.new()
    private fun rial(v: Long) = Money.of(v)

    @AfterTest fun close() { db.close(); dir.toFile().deleteRecursively() }

    private lateinit var branch: Scope.Branch
    private var kitchen = GlobalId.new()
    private var cash = GlobalId.new()
    private var tea = GlobalId.new()
    private var company = GlobalId.new()
    private var supplier = GlobalId.new()

    private fun sale(date: BusinessDate, amount: Long, credit: Long = 0, due: BusinessDate = date): GlobalId {
        val settlements = buildList<Settlement> {
            if (amount - credit > 0) add(Settlement.Liquid(cash, rial(amount - credit)))
            if (credit > 0) add(Settlement.Credit(company, rial(credit), due))
        }
        val s = core.salesOps.saveDraft(SaveSaleDraft(id(), branch, date, kitchen, listOf(SaleLineInput(tea, Quantity.units(amount / 100_000), rial(100_000))),
            rial(0), rial(0), rial(0), settlements)).resultId
        core.salesOps.post(PostDailySale(id(), branch, s))
        return s
    }

    /**
     * 100 m in the till; four weeks of 10 m sales a day, 2 m of each on credit to one company (due in 10 days) that
     * paid its earlier debts 6 days late; one supplier invoice of 40 m due in 9 days; rent budgeted for the month.
     */
    private fun setUp() {
        core.bootstrap("شعبه ونک", "مالک", "owner", "123456".toCharArray())
        branch = Scope.Branch(core.overview.branches().single().id)
        kitchen = core.overview.locations(branch).single().id
        cash = core.overview.treasury().single().account.id
        core.treasury.receipt(RecordReceipt(id(), branch, cash, ReceiptPurpose.OWNER_CAPITAL, rial(100_000_000), today.plusDays(-40), "آورده"))
        tea = core.inventory.defineMenuItem(DefineMenuItem(id(), "چای")).resultId
        company = core.salesOps.registerCustomer(RegisterCustomer(id(), branch, "شرکت آلفا", CustomerType.COMPANY, "021", rial(0))).resultId
        supplier = core.purchasing.registerSupplier(RegisterSupplier(id(), "لبنیات", "021")).resultId
        for (d in 28 downTo 1) {
            val date = today.plusDays(-d.toLong())
            val s = sale(date, 10_000_000, credit = 2_000_000, due = date.plusDays(10))
            // Debts due before today were paid 6 days after their due date (when that is already past).
            val r = core.sales.receivablesOfSale(s).single()
            val paid = r.dueDate.plusDays(6)
            if (paid < today) core.salesOps.collect(CollectReceivable(id(), branch, r.id, cash, rial(2_000_000), paid))
        }
        core.purchasing.postInvoice(PostPurchaseInvoice(id(), branch, supplier, "B1", null, today.plusDays(-1), today.plusDays(9),
            emptyList(), accountLines = listOf(ir.sabou.purchasing.AccountLine(StandardAccounts.OTHER_EXPENSE, rial(40_000_000)))))
        val start = Fa.jalali(today).let { Fa.fromJalali(it.year, it.month, 1) }
        val end = Fa.jalali(today).let { Fa.fromJalali(it.year, it.month, Fa.monthLength(it.year, it.month)) }
        core.budgets.set(SetBudget(id(), branch, StandardAccounts.RENT, listOf(BudgetPeriod(start, end, rial(30_000_000)))))
    }

    @Test fun theForecastAddsUpFromTheTillThroughEveryWeek() {
        setUp()
        val f = core.cashFlows.forecast(branch, today)
        assertEquals(13, f.weeks.size)
        assertEquals(core.overview.treasury().single().balance, f.opening)
        assertEquals(f.opening, f.weeks.first().opening)
        // Each week opens where the last closed, and the weeks hold every item exactly once.
        f.weeks.zipWithNext().forEach { (a, b) -> assertEquals(a.closing, b.opening) }
        assertEquals(f.opening + f.items.sumOf { it.amount }, f.weeks.last().closing)
        f.weeks.forEach { w -> assertEquals(w.net, w.byKind.values.sum()) }

        // The patterns: 8 of each 10 settled at once, 2 on credit; collections 16 days after the sale (10 + 6 late).
        assertEquals(28, f.assumptions.historyDays)
        assertEquals(8_000, f.assumptions.liquidBp)
        assertEquals(2_000, f.assumptions.creditBp)
        assertEquals(16, f.assumptions.collectionDays)
        assertTrue(f.assumptions.overheadsBudgeted)

        // The supplier invoice goes out on its due date.
        assertEquals(listOf(today.plusDays(9) to -40_000_000L), f.items.filter { it.kind == CashItem.Kind.SUPPLIER }.map { it.date to it.amount })
        // Open debts come in at due date + the company's 6 days: a debt due tomorrow is expected in 7 days.
        val due = f.items.filter { it.kind == CashItem.Kind.RECEIVABLE }
        assertTrue(due.isNotEmpty())
        assertTrue(due.all { it.estimated && it.amount == 2_000_000L })
        assertTrue(due.any { it.date == today.plusDays(7) })
        // Cash sales repeat the week: 8 m a day.
        assertTrue(f.items.filter { it.kind == CashItem.Kind.CASH_SALES }.all { it.amount == 8_000_000L })
        assertEquals(7L * 13, f.items.count { it.kind == CashItem.Kind.CASH_SALES }.toLong())
        // Rent: what is left of the month's budget, spread over the days left (nothing booked yet).
        val monthEnd = Fa.jalali(today).let { Fa.fromJalali(it.year, it.month, Fa.monthLength(it.year, it.month)) }
        val rentThisMonth = f.items.filter { it.kind == CashItem.Kind.OVERHEADS && it.date <= monthEnd }.sumOf { it.amount }
        val start = Fa.jalali(today).let { Fa.fromJalali(it.year, it.month, 1) }
        val days = monthEnd.epochDay - start.epochDay + 1
        val left = monthEnd.epochDay - today.epochDay + 1
        assertEquals(-ir.sabou.kernel.Ratio.mulDiv(30_000_000, days, days), rentThisMonth)   // budget for the whole month…
        assertEquals(left, f.items.count { it.kind == CashItem.Kind.OVERHEADS && it.date <= monthEnd }.toLong())   // …over the days left
    }

    @Test fun scenariosMoveTheSalesTheDelaysAndOneCustomer() {
        setUp()
        val base = core.cashFlows.forecast(branch, today)
        val cautious = core.cashFlows.forecast(branch, today, CashScenario.CAUTIOUS)
        val hopeful = core.cashFlows.forecast(branch, today, CashScenario.HOPEFUL)
        assertTrue(cautious.weeks.last().closing < base.weeks.last().closing)
        assertTrue(hopeful.weeks.last().closing > base.weeks.last().closing)
        // Paying 30 days later moves the company's debts out of the first weeks.
        assertTrue(cautious.items.filter { it.kind == CashItem.Kind.RECEIVABLE }.all { it.date >= today.plusDays(30) })
        // The company buying 20 % less: less credit collected, and less bought for it.
        val cut = core.cashFlows.forecast(branch, today, CashScenario("آلفا ۲۰٪ کمتر", customerId = company, customerBp = 8_000))
        fun sum(f: CashForecast, k: CashItem.Kind) = f.items.filter { it.kind == k }.sumOf { it.amount }
        assertEquals(sum(base, CashItem.Kind.CASH_SALES), sum(cut, CashItem.Kind.CASH_SALES))
        assertTrue(sum(cut, CashItem.Kind.CREDIT_SALES) < sum(base, CashItem.Kind.CREDIT_SALES))
        assertTrue(sum(cut, CashItem.Kind.PURCHASES) >= sum(base, CashItem.Kind.PURCHASES))   // outflows are negative
    }

    @Test fun aWeekBelowZeroRaisesTheAlert() {
        setUp()
        assertTrue(core.alerts.forUser(core.overview.me().userId, today).none { it.kind == Alert.Kind.CASH_LOW })
        core.purchasing.postInvoice(PostPurchaseInvoice(id(), branch, supplier, "B2", null, today, today.plusDays(10),
            emptyList(), accountLines = listOf(ir.sabou.purchasing.AccountLine(StandardAccounts.OTHER_EXPENSE, rial(900_000_000)))))
        val f = core.cashFlows.forecast(branch, today)
        val short = assertNotNull(f.firstShortWeek)
        assertEquals(2, short.index)
        val alert = core.alerts.forUser(core.overview.me().userId, today).single { it.kind == Alert.Kind.CASH_LOW }
        assertTrue(alert.title.contains("۲"), alert.title)
    }

    @Test fun customersShowShareAndHowTheyPay() {
        setUp()
        val r = core.cashFlows.customers(branch, today)
        assertEquals(280_000_000, r.totalSales)
        val alfa = r.rows.single()
        assertEquals(56_000_000, alfa.sales)
        assertEquals(2_000, alfa.shareBp)
        assertEquals(2_000, r.topShareBp)
        assertEquals(6, alfa.delayDays)
        assertEquals(16, alfa.collectionDays)
        assertTrue(alfa.outstanding > 0)
        assertTrue(alfa.overdue > 0)                                 // debts due in the last 6 days are not paid yet
        assertEquals(alfa.outstanding, core.salesOps.customerBalance(company).rial)
    }

    @Test fun bothTablesExportToExcel() {
        setUp()
        val tables = ReportTables.cashForecast(core.cashFlows.forecast(branch, today)) + ReportTables.customers(core.cashFlows.customers(branch, today))
        assertEquals(3, tables.size)
        assertEquals(13, tables.first().rows.size)
        assertTrue(Xlsx.write(tables).size > 1_000)
    }

    @Test fun onlyTheBooksRolesSeeIt() {
        setUp()
        core.identity.createUser("cashier", "صندوقدار", Role.CASHIER, setOf(branch.branchId), "654321".toCharArray())
        core.identity.logout(); core.identity.login("cashier", "654321".toCharArray())
        assertTrue(assertFailsWith<DomainException> { core.cashFlows.forecast(branch, today) }.error.code.startsWith("PERMISSION_DENIED"))
        assertTrue(assertFailsWith<DomainException> { core.cashFlows.customers(branch, today) }.error.code.startsWith("PERMISSION_DENIED"))
        assertTrue(core.alerts.forUser(core.overview.me().userId, today).none { it.kind == Alert.Kind.CASH_LOW })
    }

    @Test fun withoutHistoryItSaysSoInsteadOfInventingSales() {
        core.bootstrap("شعبه ونک", "مالک", "owner", "123456".toCharArray())
        branch = Scope.Branch(core.overview.branches().single().id)
        val f = core.cashFlows.forecast(branch, today)
        assertEquals(0, f.assumptions.historyDays)
        assertNull(f.assumptions.liquidBp)
        assertTrue(f.items.none { it.estimated && it.kind == CashItem.Kind.CASH_SALES })
        assertTrue(!f.assumptions.overheadsBudgeted)
        // A bill with an empty till: the forecast goes below zero, but with no income to see it raises no cash alert
        // (the bill has its own).
        val supplier = core.purchasing.registerSupplier(RegisterSupplier(id(), "لبنیات", "021")).resultId
        core.purchasing.postInvoice(PostPurchaseInvoice(id(), branch, supplier, "X", null, today, today.plusDays(3),
            emptyList(), accountLines = listOf(ir.sabou.purchasing.AccountLine(StandardAccounts.OTHER_EXPENSE, rial(5_000_000)))))
        assertNotNull(core.cashFlows.forecast(branch, today).firstShortWeek)
        assertTrue(core.alerts.forUser(core.overview.me().userId, today).none { it.kind == Alert.Kind.CASH_LOW })
    }
}
