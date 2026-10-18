package ir.sabou.core

import ir.sabou.persistence.DevDatabases
import ir.sabou.persistence.ClosableSqlDatabase

import ir.sabou.inventory.CreateItem
import ir.sabou.inventory.DefineMenuItem
import ir.sabou.inventory.PublishRecipe
import ir.sabou.inventory.RecipeLine
import ir.sabou.inventory.StockUnit
import ir.sabou.inventory.UpdateItem
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.Clock
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.platform.memory.InMemoryAnchorStore
import ir.sabou.purchasing.CreatePurchaseOrder
import ir.sabou.purchasing.InvoiceLine
import ir.sabou.purchasing.OrderLine
import ir.sabou.purchasing.PostPurchaseInvoice
import ir.sabou.purchasing.RegisterSupplier
import ir.sabou.purchasing.UpdateSupplier
import ir.sabou.purchasing.Weekdays
import ir.sabou.sales.PostDailySale
import ir.sabou.sales.SaleLineInput
import ir.sabou.sales.SaveSaleDraft
import ir.sabou.sales.Settlement
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ADR-0054: purchase suggestions and run-outs from the sales forecast. */
class ForecastBuyingTest {
    private val dir = Files.createTempDirectory("sabou-fbuy")
    private val db = DevDatabases.open(dir.resolve("f.db"))
    private val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { 1_700_000_000_000L }, emptyList(), null)
    private val today = generateSequence(BusinessDate(20_100)) { it.plusDays(1) }.first { Weekdays.index(it) == 0 }   // a Saturday
    private fun id() = GlobalId.new()
    private fun kg(v: Long) = Quantity.units(v)

    @AfterTest fun close() { db.close(); dir.toFile().deleteRecursively() }

    private lateinit var branch: Scope.Branch
    private var kitchen = GlobalId.new()
    private var supplier = GlobalId.new()
    private var cheese = GlobalId.new()

    /**
     * Four weeks of 10 pizzas a day (0.5 kg cheese each: 5 kg a day). [left] kg of cheese remain. The supplier
     * delivers on Mondays only (ordered the same day), so from Saturday the next delivery is in 2 days and the one
     * after it 7 days later.
     */
    private fun setUp(left: Long, par: Long = 0) {
        core.bootstrap("شعبه ونک", "مالک", "owner", "123456".toCharArray())
        branch = Scope.Branch(core.overview.branches().single().id)
        kitchen = core.overview.locations(branch).single().id
        val cash = core.overview.treasury().single().account.id
        supplier = core.purchasing.registerSupplier(RegisterSupplier(id(), "لبنیات", "021")).resultId
        core.purchasing.updateSupplier(UpdateSupplier(id(), supplier, "لبنیات", "021", true, setOf(2), null, 0, ""))
        cheese = core.inventory.createItem(CreateItem(id(), "پنیر", StockUnit.KILOGRAM, kg(0))).resultId
        core.inventory.updateItem(UpdateItem(id(), cheese, "پنیر", kg(0), kg(par), "یخچال", "لبنیات", supplier, emptySet(), true))
        val pizza = core.inventory.defineMenuItem(DefineMenuItem(id(), "پیتزا")).resultId
        core.inventory.publishRecipe(PublishRecipe(id(), pizza, BusinessDate(19_000), listOf(RecipeLine(cheese, Quantity.of(500_000)))))
        core.purchasing.postInvoice(PostPurchaseInvoice(id(), branch, supplier, "1", kitchen, today.plusDays(-29), today.plusDays(-29),
            listOf(InvoiceLine(cheese, kg(140 + left), Money.of((140 + left) * 3_000_000)))))
        for (d in 28 downTo 1) {
            val s = core.salesOps.saveDraft(SaveSaleDraft(id(), branch, today.plusDays(-d.toLong()), kitchen, listOf(SaleLineInput(pizza, Quantity.units(10), Money.of(5_000_000))),
                Money.ZERO, Money.ZERO, Money.ZERO, listOf(Settlement.Liquid(cash, Money.of(50_000_000))))).resultId
            core.salesOps.post(PostDailySale(id(), branch, s))
        }
    }

    @Test fun theOrderCoversUntilTheDeliveryAndTheWeekAfterIt() {
        setUp(left = 7)
        val line = core.buying.forecastSuggestions(kitchen, today, 0).groups.single().lines.single()
        assertEquals(kg(10), line.planned)                               // Saturday and Sunday: 2 × 5 kg
        assertEquals(kg(35), line.forecastCover)                         // Monday to Sunday after it: 7 × 5 kg
        assertEquals(kg(7), line.onHand)
        assertEquals(kg(38), line.suggested)                             // 10 + 35 − 7
        // The manual plan is unchanged: no forecast cover, nothing planned.
        assertTrue(core.buying.suggestions(kitchen, emptyList(), today, 0).groups.none { g -> g.lines.any { it.item.id == cheese } })
    }

    @Test fun aHigherParWins() {
        setUp(left = 7, par = 50)
        val line = core.buying.forecastSuggestions(kitchen, today, 0).groups.single().lines.single()
        assertEquals(kg(53), line.suggested)                             // 10 + max(50, 35) − 7
    }

    @Test fun runningOutBeforeTheDeliveryIsFoundAndAlerted() {
        setUp(left = 7)
        val r = core.buying.runOuts(branch, today, 0).single()
        assertEquals(cheese, r.item.id)
        assertEquals(today.plusDays(1), r.runsOutOn)                     // 5 kg Saturday, 10 kg by Sunday > 7 kg
        assertEquals(today.plusDays(2), r.delivery)
        assertEquals(kg(10), r.need)
        assertTrue(core.alerts.forUser(core.overview.me().userId, today).any { it.kind == Alert.Kind.RUN_OUT && it.title.contains("پنیر") })
        // What is on order counts: 4 kg more covers until Monday.
        core.orders.create(CreatePurchaseOrder(id(), branch, supplier, kitchen, today, today.plusDays(2), listOf(OrderLine(cheese, kg(4), Money.of(3_000_000)))))
        assertTrue(core.buying.runOuts(branch, today, 0).isEmpty())
    }

    @Test fun enoughStockRaisesNothing() {
        setUp(left = 12)
        assertTrue(core.buying.runOuts(branch, today, 0).isEmpty())
        assertTrue(core.alerts.forUser(core.overview.me().userId, today).none { it.kind == Alert.Kind.RUN_OUT })
    }
}
