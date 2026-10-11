package ir.sabou.core

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
import ir.sabou.persistence.JdbcSqlDatabase
import ir.sabou.platform.memory.InMemoryAnchorStore
import ir.sabou.purchasing.InvoiceLine
import ir.sabou.purchasing.PostPurchaseInvoice
import ir.sabou.purchasing.RegisterSupplier
import ir.sabou.sales.PostDailySale
import ir.sabou.sales.SaleLineInput
import ir.sabou.sales.SaveSaleDraft
import ir.sabou.sales.SetFoodCostTarget
import ir.sabou.sales.SetMenuPrice
import ir.sabou.sales.Settlement
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Menu engineering and food cost against target (ADR-0028). */
class MenuEngineeringTest {
    private val dir = Files.createTempDirectory("menu")
    private val db = JdbcSqlDatabase(DriverManager.getConnection("jdbc:sqlite:${dir.resolve("m.db")}"))
    private val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { 1_790_000_000_000L })
    private val day = JalaliCalendar.date(1405, 7, 18)

    @AfterTest fun close() { db.close(); dir.toFile().deleteRecursively() }

    @Test fun dishesAreClassedByPopularityAndMarginAndTheirCostFollowsPurchasePrices() {
        core.bootstrap("شعبه یک", "مالک", "owner", "123456".toCharArray())
        val branch = Scope.Branch(core.overview.branches().single().id)
        val cash = core.overview.treasury().single().account.id
        val kitchen = core.overview.locations(branch).single().id
        val cheese = core.inventory.createItem(CreateItem(GlobalId.new(), "پنیر", StockUnit.KILOGRAM, Quantity.ZERO)).resultId
        val supplier = core.purchasing.registerSupplier(RegisterSupplier(GlobalId.new(), "لبنیات", "021")).resultId
        core.purchasing.postInvoice(PostPurchaseInvoice(GlobalId.new(), branch, supplier, "1", kitchen, day.plusDays(-1), day,
            listOf(InvoiceLine(cheese, Quantity.units(100), Money.of(100_000_000)))))          // 1,000,000 a kg
        // name → (cheese per portion, menu price, portions sold)
        val plan = mapOf(
            "ستاره" to Triple(100_000L, 4_000_000L, 40L),       // cost 100,000; margin 3,900,000; popular
            "اسب کاری" to Triple(500_000L, 2_000_000L, 40L),    // margin 1,500,000; popular
            "معما" to Triple(100_000L, 5_000_000L, 5L),         // margin 4,900,000; rare
            "سگ" to Triple(800_000L, 1_000_000L, 5L),           // margin 200,000; rare
        )
        val ids = plan.mapValues { (name, p) ->
            core.inventory.defineMenuItem(DefineMenuItem(GlobalId.new(), name)).resultId.also { id ->
                core.inventory.publishRecipe(PublishRecipe(GlobalId.new(), id, BusinessDate(19_000), listOf(RecipeLine(cheese, Quantity.of(p.first)))))
                core.salesOps.setMenuPrice(SetMenuPrice(GlobalId.new(), Scope.Organization, id, day.plusDays(-10), Money.of(p.second)))
            }
        }
        val lines = plan.map { (name, p) -> SaleLineInput(ids.getValue(name), Quantity.units(p.third)) }
        val total = plan.values.sumOf { it.second * it.third }
        val sale = core.salesOps.saveDraft(SaveSaleDraft(GlobalId.new(), branch, day, kitchen, lines, Money.ZERO, Money.ZERO, Money.ZERO,
            listOf(Settlement.Liquid(cash, Money.of(total))))).resultId
        core.salesOps.post(PostDailySale(GlobalId.new(), branch, sale))

        val e = core.reports.menuEngineering(branch, day, day)
        assertEquals(mapOf("ستاره" to MenuClass.STAR, "اسب کاری" to MenuClass.PLOWHORSE, "معما" to MenuClass.PUZZLE, "سگ" to MenuClass.DOG),
            e.rows.associate { it.mix.name to it.menuClass })

        // Target 40 %: only «سگ» (80 %) is over. Then cheese gets dearer (average ≈ 2,870,000): «اسب کاری» goes to ≈ 72 %.
        assertEquals("INVALID_INPUT:target", assertFailsWith<DomainException> { core.salesOps.setFoodCostTarget(SetFoodCostTarget(GlobalId.new(), 9_500)) }.error.code)
        core.salesOps.setFoodCostTarget(SetFoodCostTarget(GlobalId.new(), 4_000))
        assertEquals(listOf("سگ"), core.reports.foodCosts(branch, day).filter { it.costBp >= 4_000 }.map { it.item.name })
        core.purchasing.postInvoice(PostPurchaseInvoice(GlobalId.new(), branch, supplier, "2", kitchen, day, day,
            listOf(InvoiceLine(cheese, Quantity.units(1_000), Money.of(3_000_000_000)))))          // the average moves towards 3,000,000
        val over = core.reports.foodCosts(branch, day).filter { it.costBp >= 4_000 }.map { it.item.name }.toSet()
        assertEquals(setOf("سگ", "اسب کاری"), over)
        val owner = core.overview.me().userId
        val alerts = core.alerts.forUser(owner, day).filter { it.kind == Alert.Kind.FOOD_COST }
        assertEquals(2, alerts.size)
        assertTrue(alerts.any { "اسب کاری" in it.title })
    }
}
