package ir.sabou.core

import ir.sabou.inventory.CreateItem
import ir.sabou.inventory.StockUnit
import ir.sabou.kernel.Clock
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.JalaliCalendar
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.persistence.JdbcSqlDatabase
import ir.sabou.platform.memory.InMemoryAnchorStore
import ir.sabou.purchasing.CreatePurchaseOrder
import ir.sabou.purchasing.Discrepancy
import ir.sabou.purchasing.InvoiceLine
import ir.sabou.purchasing.OrderLine
import ir.sabou.purchasing.PostPurchaseInvoice
import ir.sabou.purchasing.RegisterSupplier
import ir.sabou.purchasing.ReviewInvoiceMatch
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Order ↔ invoice differences survive the database and reach the owner's alerts (ADR-0029). */
class InvoiceMatchTest {
    private val dir = Files.createTempDirectory("match")
    private val db = JdbcSqlDatabase(DriverManager.getConnection("jdbc:sqlite:${dir.resolve("m.db")}"))
    private val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { 1_790_000_000_000L })
    private val day = JalaliCalendar.date(1405, 7, 18)

    @AfterTest fun close() { db.close(); dir.toFile().deleteRecursively() }

    @Test fun differencesArePersistedAlertedAndClearedByReview() {
        core.bootstrap("شعبه یک", "مالک", "owner", "123456".toCharArray())
        val branch = Scope.Branch(core.overview.branches().single().id)
        val kitchen = core.overview.locations(branch).single().id
        val cheese = core.inventory.createItem(CreateItem(GlobalId.new(), "پنیر", StockUnit.KILOGRAM, Quantity.ZERO)).resultId
        val supplier = core.purchasing.registerSupplier(RegisterSupplier(GlobalId.new(), "لبنیات", "021")).resultId
        val order = core.orders.create(CreatePurchaseOrder(GlobalId.new(), branch, supplier, kitchen, day, day,
            listOf(OrderLine(cheese, Quantity.units(3), Money.of(2_000_000))))).resultId
        val inv = core.purchasing.postInvoice(PostPurchaseInvoice(GlobalId.new(), branch, supplier, "1", kitchen, day, day,
            listOf(InvoiceLine(cheese, Quantity.units(2), Money.of(4_000_000))), orderId = order)).resultId

        val stored = core.purchases.invoice(inv)!!
        assertEquals(listOf(Discrepancy(Discrepancy.Kind.QUANTITY, cheese, 3_000_000, 2_000_000)), stored.discrepancies)
        assertEquals(listOf(inv), core.books.matchReviews().map { it.invoice.id })
        val owner = core.overview.me().userId
        assertTrue(core.alerts.forUser(owner, day).any { it.key == "MATCH:${inv.value}" })

        core.purchasing.reviewMatch(ReviewInvoiceMatch(GlobalId.new(), branch, inv, "دو کیلو کافی بود"))
        assertEquals("دو کیلو کافی بود", core.purchases.invoice(inv)!!.matchReview!!.note)
        assertEquals(emptyList(), core.books.matchReviews())
        assertTrue(core.alerts.forUser(owner, day).none { it.kind == Alert.Kind.INVOICE_MATCH })
    }
}
