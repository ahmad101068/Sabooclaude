package ir.sabou.core

import ir.sabou.inventory.CreateItem
import ir.sabou.inventory.IssueLine
import ir.sabou.inventory.StockUnit
import ir.sabou.kernel.Clock
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.ledger.AccountCode
import ir.sabou.ledger.StandardAccounts
import ir.sabou.persistence.DevDatabases
import ir.sabou.platform.memory.InMemoryAnchorStore
import ir.sabou.purchasing.AccountLine
import ir.sabou.purchasing.AccountLineValue
import ir.sabou.purchasing.CorrectPurchaseInvoice
import ir.sabou.purchasing.InvoiceLine
import ir.sabou.purchasing.InvoiceLineValue
import ir.sabou.purchasing.PostPurchaseInvoice
import ir.sabou.purchasing.RegisterSupplier
import ir.sabou.purchasing.ReturnToSupplier
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** SABOU-AUDIT-002 (second audit): corrections of expense lines, and goods returned after a correction. */
class InvoiceCorrectionTest {
    private val today = Fa.fromJalali(1405, 8, 6)
    private val dir = Files.createTempDirectory("sabou-correct")
    private val db = DevDatabases.open(dir.resolve("c.db"))
    private val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { today.epochDay * 86_400_000L + 9 * 3_600_000L }, emptyList(), null,
        datePolicy = SabouCore.restaurantDatePolicy())
    private fun id() = GlobalId.new()

    @AfterTest fun close() { db.close(); dir.toFile().deleteRecursively() }

    private fun booksAgree(branch: Scope.Branch, kitchen: GlobalId, item: GlobalId) {
        assertEquals(0L, core.overview.trialBalance().sumOf { it.second })
        assertEquals(core.stock.balance(item, kitchen).value.rial, core.ledger.balance(StandardAccounts.INVENTORY, branch).rial)
        val open = core.overview.invoices().filter { it.invoice.status == ir.sabou.purchasing.InvoiceStatus.POSTED }.sumOf { it.outstanding.rial }
        assertEquals(open, -core.ledger.balance(StandardAccounts.PAYABLE, branch).rial)
    }

    @Test fun anExpenseLineAndGoodsReturnedAfterACorrection() {
        core.bootstrap("شعبه", "مالک", "owner", "123456".toCharArray())
        val branch = Scope.Branch(core.overview.branches().single().id)
        val kitchen = core.overview.locations(branch).single().id
        val oil = core.inventory.createItem(CreateItem(id(), "روغن", StockUnit.LITER, Quantity.units(0))).resultId
        val supplier = core.purchasing.registerSupplier(RegisterSupplier(id(), "پخش", "021")).resultId
        val expense = core.buying.expenseAccounts().first().code
        // 10 L of oil at 30,000,000 (should be 20,000,000) and a delivery charge of 5,000,000 (should be 500,000).
        val inv = core.purchasing.postInvoice(PostPurchaseInvoice(id(), branch, supplier, "P-1", kitchen, today, today.plusDays(30),
            listOf(InvoiceLine(oil, Quantity.units(10), Money.of(30_000_000))), accountLines = listOf(AccountLine(expense, Money.of(5_000_000))))).resultId
        val expenseBefore = core.ledger.balance(expense, branch).rial
        core.purchasing.correctInvoice(CorrectPurchaseInvoice(id(), branch, inv, listOf(InvoiceLineValue(oil, Money.of(20_000_000))), today, "قیمت توافقی",
            accounts = listOf(AccountLineValue(0, Money.of(500_000)))))
        assertEquals(20_500_000, core.purchasing.outstanding(inv).rial)
        assertEquals(20_500_000, core.overview.invoice(inv).invoice.total.rial)
        assertEquals(20_000_000, core.stock.balance(oil, kitchen).value.rial)            // all still in stock: the stock takes the whole change
        assertEquals(expenseBefore - 4_500_000, core.ledger.balance(expense, branch).rial)
        booksAgree(branch, kitchen, oil)
        // Half returned: credited at the corrected price (10,000,000), not the first one (15,000,000).
        core.purchasing.returnGoods(ReturnToSupplier(id(), branch, inv, listOf(IssueLine(oil, Quantity.units(5))), today, "روغن معیوب"))
        assertEquals(10_500_000, core.purchasing.outstanding(inv).rial)
        assertEquals(10_000_000, core.stock.balance(oil, kitchen).value.rial)
        booksAgree(branch, kitchen, oil)
    }
}
