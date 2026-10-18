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
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.JalaliCalendar
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.platform.memory.InMemoryAnchorStore
import ir.sabou.purchasing.InvoiceLine
import ir.sabou.purchasing.PostPurchaseInvoice
import ir.sabou.purchasing.RegisterSupplier
import ir.sabou.purchasing.ResolveReviewLine
import ir.sabou.purchasing.ReviewLine
import ir.sabou.sales.CloseSalesDay
import ir.sabou.sales.PostDailySale
import ir.sabou.sales.SaleLineInput
import ir.sabou.sales.SaveSaleDraft
import ir.sabou.sales.Settlement
import ir.sabou.treasury.PaymentPurpose
import ir.sabou.treasury.RecordPayment
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The month-end checklist blocks a close until sales days and the review queue are done; then the month is locked (ADR-0036). */
class ClosingTest {
    private val dir = Files.createTempDirectory("close")
    private val db = DevDatabases.open(dir.resolve("c.db"))
    private val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { 1_790_000_000_000L })
    private fun d(day: Int) = JalaliCalendar.date(1405, 6, day)
    private fun id() = GlobalId.new()

    @AfterTest fun close() { db.close(); dir.toFile().deleteRecursively() }

    @Test fun blockersThenCloseThenReopen() {
        core.bootstrap("شعبه یک", "مالک", "owner", "123456".toCharArray())
        val branch = Scope.Branch(core.overview.branches().single().id)
        val kitchen = core.overview.locations(branch).single().id
        val cash = core.overview.treasury().single().account.id
        val bread = core.inventory.createItem(CreateItem(id(), "نان", StockUnit.PIECE, Quantity.ZERO)).resultId
        val supplier = core.purchasing.registerSupplier(RegisterSupplier(id(), "نانوایی", "021")).resultId
        val inv = core.purchasing.postInvoice(PostPurchaseInvoice(id(), branch, supplier, "1", kitchen, d(5), d(5),
            listOf(InvoiceLine(bread, Quantity.units(100), Money.of(10_000_000))), reviewLines = listOf(ReviewLine("سس ویژه", "۲ قوطی", Money.of(500_000))))).resultId
        val sandwich = core.inventory.defineMenuItem(DefineMenuItem(id(), "ساندویچ")).resultId
        core.inventory.publishRecipe(PublishRecipe(id(), sandwich, BusinessDate(19_000), listOf(RecipeLine(bread, Quantity.units(1)))))
        val sale = core.salesOps.saveDraft(SaveSaleDraft(id(), branch, d(10), kitchen, listOf(SaleLineInput(sandwich, Quantity.units(10), Money.of(500_000))),
            Money.ZERO, Money.ZERO, Money.ZERO, listOf(Settlement.Liquid(cash, Money.of(5_000_000))))).resultId
        core.salesOps.post(PostDailySale(id(), branch, sale))

        val first = core.closing.checklist(1405, 6)
        assertEquals(setOf("SALES_DAYS", "REVIEW_QUEUE"), first.checks.filter { it.severity == CloseCheck.Severity.BLOCK }.map { it.key }.toSet())
        assertTrue(first.checks.single { it.key == "MONTH_COUNT" }.severity == CloseCheck.Severity.WARN)
        assertEquals("INVALID_STATE:PERIOD:CHECKLIST:SALES_DAYS", runCatching { core.closing.close(id(), 1405, 6) }.exceptionOrNull().let { (it as DomainException).error.code })

        core.salesOps.closeDay(CloseSalesDay(id(), branch, d(10), cash, Money.of(5_000_000)))
        core.purchasing.resolveReviewLine(ResolveReviewLine(id(), branch, inv, 0, null, null, null, ir.sabou.ledger.StandardAccounts.OTHER_EXPENSE, d(5)))
        val ready = core.closing.checklist(1405, 6)
        assertFalse(ready.blocked)
        core.closing.close(id(), 1405, 6)
        assertTrue(core.closing.checklist(1405, 6).closed)
        // Nothing can be dated inside a closed month.
        val refused = runCatching { core.treasury.payment(RecordPayment(id(), branch, cash, PaymentPurpose.OTHER_EXPENSE, Money.of(1_000), d(20), "دیرکرد")) }.exceptionOrNull()
        assertTrue(refused is DomainException && "PERIOD" in refused.error.code)

        core.closing.reopen(id(), 1405, 6, "اصلاح فاکتور")
        assertFalse(core.closing.checklist(1405, 6).closed)
        core.treasury.payment(RecordPayment(id(), branch, cash, PaymentPurpose.OTHER_EXPENSE, Money.of(1_000), d(20), "دیرکرد"))
    }
}
