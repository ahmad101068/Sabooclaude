package ir.sabou.core

import ir.sabou.inventory.CreateItem
import ir.sabou.inventory.DefineMenuItem
import ir.sabou.inventory.PublishPrepRecipe
import ir.sabou.inventory.PublishRecipe
import ir.sabou.inventory.RecipeLine
import ir.sabou.inventory.StockUnit
import ir.sabou.inventory.UpdateItem
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.persistence.JdbcSqlDatabase
import ir.sabou.platform.AttachmentInput
import ir.sabou.platform.Role
import ir.sabou.platform.memory.InMemoryAnchorStore
import ir.sabou.purchasing.CreatePurchaseOrder
import ir.sabou.purchasing.InvoiceLine
import ir.sabou.purchasing.OrderLine
import ir.sabou.purchasing.PostPurchaseInvoice
import ir.sabou.purchasing.RegisterSupplier
import ir.sabou.purchasing.ReviewLine
import ir.sabou.purchasing.UpdateSupplier
import ir.sabou.purchasing.Weekdays
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BuyingTest {
    private val dir = Files.createTempDirectory("sabou-buying")
    private val db = JdbcSqlDatabase(DriverManager.getConnection("jdbc:sqlite:${dir.resolve("b.db")}"))
    private val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { 1_700_000_000_000L }, emptyList(), null)
    private val day = generateSequence(BusinessDate(20_000)) { it.plusDays(1) }.first { Weekdays.index(it) == 0 }   // a Saturday
    private fun id() = GlobalId.new()
    private fun rial(v: Long) = Money.of(v)
    private fun kg(v: Long) = Quantity.units(v)

    @AfterTest fun close() { db.close(); dir.toFile().deleteRecursively() }

    private lateinit var branch: Scope.Branch
    private var kitchen: GlobalId = GlobalId.new()
    private var supplier: GlobalId = GlobalId.new()
    private var cheese: GlobalId = GlobalId.new()
    private var tomato: GlobalId = GlobalId.new()
    private var pizza: GlobalId = GlobalId.new()

    private fun setUp() {
        core.bootstrap("شعبه ونک", "مالک", "owner", "123456".toCharArray())
        branch = Scope.Branch(core.overview.branches().single().id)
        kitchen = core.overview.locations(branch).single().id
        supplier = core.purchasing.registerSupplier(RegisterSupplier(id(), "لبنیات", "021")).resultId
        // Delivers on Sundays and Wednesdays; order the day before by 14:00.
        core.purchasing.updateSupplier(UpdateSupplier(id(), supplier, "لبنیات", "021", true, setOf(1, 4), 14 * 60, 1, ""))
        cheese = core.inventory.createItem(CreateItem(id(), "پنیر", StockUnit.KILOGRAM, kg(1))).resultId
        core.inventory.updateItem(UpdateItem(id(), cheese, "پنیر", kg(1), kg(5), "یخچال", "لبنیات", supplier, emptySet(), true))
        tomato = core.inventory.createItem(CreateItem(id(), "گوجه", StockUnit.KILOGRAM, kg(0))).resultId
        val sauce = core.inventory.createItem(CreateItem(id(), "سس", StockUnit.KILOGRAM, kg(0), prepared = true)).resultId
        core.inventory.publishPrepRecipe(PublishPrepRecipe(id(), sauce, BusinessDate(19_000), kg(4), listOf(RecipeLine(tomato, kg(5)))))
        pizza = core.inventory.defineMenuItem(DefineMenuItem(id(), "پیتزا")).resultId
        core.inventory.publishRecipe(PublishRecipe(id(), pizza, BusinessDate(19_000), listOf(RecipeLine(cheese, Quantity.of(200_000)), RecipeLine(sauce, Quantity.of(100_000)))))
        core.purchasing.postInvoice(PostPurchaseInvoice(id(), branch, supplier, "1", kitchen, day.plusDays(-10), day, listOf(InvoiceLine(cheese, kg(2), rial(600_000)))))
        core.purchasing.postInvoice(PostPurchaseInvoice(id(), branch, supplier, "2", kitchen, day.plusDays(-1), day, listOf(InvoiceLine(cheese, kg(1), rial(360_000)))))
        core.orders.create(CreatePurchaseOrder(id(), branch, supplier, kitchen, day, day.plusDays(1), listOf(OrderLine(cheese, kg(1), rial(360_000)))))
    }

    @Test fun suggestedOrderCoversParAndPlannedDishesMinusStockAndOpenOrders() {
        setUp()
        val groups = core.buying.suggestions(kitchen, listOf(PlanLine(pizza, kg(10))), day, 13 * 60)
        val withSupplier = groups.first()
        assertEquals("لبنیات", withSupplier.supplier!!.name)
        assertEquals(day.plusDays(1), withSupplier.delivery!!.date)                 // Sunday, ordered before 14:00 today
        val c = withSupplier.lines.single()
        assertEquals(kg(3), c.onHand); assertEquals(kg(1), c.onOrder); assertEquals(kg(2), c.planned)
        assertEquals(kg(3), c.suggested)                                             // 5 + 2 − 3 − 1
        assertEquals(360_000, c.unitPrice)                                           // last price from this supplier
        assertEquals(1_080_000, withSupplier.total.rial)
        // 10 pizzas need 1 kg of sauce; none is prepared, so its tomatoes are bought (5 kg per 4 kg of sauce).
        val t = groups.single { it.supplier == null }.lines.single()
        assertEquals(tomato, t.item.id)
        assertEquals(Quantity.of(1_250_000), t.suggested)
        assertNull(t.unitPrice)
        // After the cutoff the next delivery is Wednesday.
        assertEquals(day.plusDays(4), core.buying.suggestions(kitchen, emptyList(), day, 15 * 60).first().delivery!!.date)
    }

    @Test fun priceChangesCompareWithTheSameSuppliersPreviousInvoice() {
        setUp()
        val change = core.buying.priceChanges(day.plusDays(-30), day).single()
        assertEquals(300_000, change.previousPrice)
        assertEquals(360_000, change.price)
        assertEquals(2_000, change.changeBp)
        assertEquals(mapOf(cheese to 360_000L), core.buying.lastPrices(supplier, branch))
        assertEquals(emptyList(), core.buying.priceChanges(day.plusDays(-30), day, thresholdBp = 2_500))
    }

    @Test fun reviewQueueAttachmentsAndAccess() {
        setUp()
        val photo = AttachmentInput("f.jpg", "image/jpeg", ByteArray(1_000) { 3 })
        val inv = core.purchasing.postInvoice(PostPurchaseInvoice(id(), branch, supplier, "3", null, day, day, emptyList(),
            reviewLines = listOf(ReviewLine("پنیر گودا", "", rial(90_000))), attachments = listOf(photo))).resultId
        assertEquals(listOf("پنیر گودا"), core.buying.reviewQueue().map { it.line.supplierItemName })
        val view = core.overview.invoice(inv)
        val (meta, bytes) = core.buying.attachment(view.attachments.single().id)
        assertEquals("f.jpg", meta.fileName)
        assertContentEquals(photo.bytes, bytes)
        core.identity.createUser("cashier", "صندوقدار", Role.CASHIER, setOf(branch.branchId), "654321".toCharArray())
        core.identity.logout(); core.identity.login("cashier", "654321".toCharArray())
        for (read in listOf<() -> Unit>({ core.buying.reviewQueue() }, { core.buying.attachment(meta.id) }, { core.buying.suggestions(kitchen, emptyList(), day, 0) })) {
            assertEquals("PERMISSION_DENIED", assertFailsWith<DomainException> { read() }.error.code.substringBefore(':'))
        }
    }
}
