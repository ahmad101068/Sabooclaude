package ir.sabou.core

import ir.sabou.persistence.DevDatabases
import ir.sabou.persistence.ClosableSqlDatabase

import ir.sabou.inventory.CreateItem
import ir.sabou.inventory.DefineMenuItem
import ir.sabou.inventory.PublishRecipe
import ir.sabou.inventory.RecipeLine
import ir.sabou.inventory.StockUnit
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.Clock
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.JalaliCalendar
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.ledger.BudgetPeriod
import ir.sabou.ledger.SetBudget
import ir.sabou.ledger.StandardAccounts
import ir.sabou.platform.memory.InMemoryAnchorStore
import ir.sabou.purchasing.InvoiceLine
import ir.sabou.purchasing.PostPurchaseInvoice
import ir.sabou.purchasing.RegisterSupplier
import ir.sabou.sales.PostDailySale
import ir.sabou.sales.SaleLineInput
import ir.sabou.sales.SaveSaleDraft
import ir.sabou.sales.Settlement
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A day's P&L with accruals for monthly costs, against budget (ADR-0034). */
class DailyPnlTest {
    private val dir = Files.createTempDirectory("dpnl")
    private val db = DevDatabases.open(dir.resolve("d.db"))
    private val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { 1_790_000_000_000L })
    private val day = JalaliCalendar.date(1405, 7, 10)            // Mehr has 30 days
    private fun id() = GlobalId.new()

    @AfterTest fun close() { db.close(); dir.toFile().deleteRecursively() }

    @Test fun monthlyCostsAreAccruedDailyUntilBooked() {
        core.bootstrap("شعبه یک", "مالک", "owner", "123456".toCharArray())
        val branch = Scope.Branch(core.overview.branches().single().id)
        val kitchen = core.overview.locations(branch).single().id
        val cash = core.overview.treasury().single().account.id
        val month = listOf(BudgetPeriod(JalaliCalendar.date(1405, 7, 1), JalaliCalendar.date(1405, 7, 30), Money.ZERO))
        fun budget(code: ir.sabou.ledger.AccountCode, amount: Long) =
            core.budgets.set(SetBudget(id(), branch, code, month.map { it.copy(amount = Money.of(amount)) }))
        budget(StandardAccounts.FOOD_SALES, 300_000_000)     // 10,000,000 a day
        budget(StandardAccounts.SALARIES, 90_000_000)        // 3,000,000 a day
        budget(StandardAccounts.RENT, 30_000_000)            // 1,000,000 a day

        val meat = core.inventory.createItem(CreateItem(id(), "گوشت", StockUnit.KILOGRAM, Quantity.ZERO)).resultId
        val supplier = core.purchasing.registerSupplier(RegisterSupplier(id(), "قصابی", "021")).resultId
        core.purchasing.postInvoice(PostPurchaseInvoice(id(), branch, supplier, "1", kitchen, JalaliCalendar.date(1405, 7, 1), day,
            listOf(InvoiceLine(meat, Quantity.units(10), Money.of(20_000_000)))))
        val kebab = core.inventory.defineMenuItem(DefineMenuItem(id(), "کباب")).resultId
        core.inventory.publishRecipe(PublishRecipe(id(), kebab, BusinessDate(19_000), listOf(RecipeLine(meat, Quantity.of(200_000)))))
        val sale = core.salesOps.saveDraft(SaveSaleDraft(id(), branch, day, kitchen, listOf(SaleLineInput(kebab, Quantity.units(10), Money.of(1_200_000))),
            Money.ZERO, Money.ZERO, Money.ZERO, listOf(Settlement.Liquid(cash, Money.of(12_000_000))))).resultId
        core.salesOps.post(PostDailySale(id(), branch, sale))

        val p = core.reports.dailyPnl(branch, day)
        val sales = p.line(DailyPnl.Group.SALES)
        assertEquals(12_000_000L, sales.day); assertEquals(10_000_000L, sales.dayBudget)
        assertEquals(4_000_000L, p.line(DailyPnl.Group.FOOD_COST).day)               // 2 kg at 2,000,000
        val labor = p.line(DailyPnl.Group.LABOR)
        assertEquals(3_000_000L, labor.day); assertTrue(labor.dayEstimated)
        assertEquals(30_000_000L, labor.mtd); assertTrue(labor.mtdEstimated)          // 10 days of the budget, nothing booked
        assertEquals(1_000_000L, p.line(DailyPnl.Group.OTHER).day)
        assertEquals(12_000_000L - 4_000_000 - 3_000_000 - 1_000_000, p.dayProfit)
        assertEquals(36_000_000L, p.salesRunRate)                                      // 12,000,000 in 10 days → 30 days
        assertEquals(300_000_000L, p.monthSalesBudget)
        assertFalse(p.line(DailyPnl.Group.FOOD_COST).dayEstimated)
    }
}
