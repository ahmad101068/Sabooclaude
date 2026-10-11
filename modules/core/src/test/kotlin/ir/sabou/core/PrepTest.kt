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
import ir.sabou.sales.Settlement
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The kitchen's day: forecast from past weekdays, prep tasks in order, allergens through sub-recipes (ADR-0033). */
class PrepTest {
    private val dir = Files.createTempDirectory("prep")
    private val db = JdbcSqlDatabase(DriverManager.getConnection("jdbc:sqlite:${dir.resolve("p.db")}"))
    private val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { 1_790_000_000_000L })
    private val day = JalaliCalendar.date(1405, 7, 20)
    private fun kg(v: Long) = Quantity.units(v)
    private fun id() = GlobalId.new()

    @AfterTest fun close() { db.close(); dir.toFile().deleteRecursively() }

    @Test fun forecastPrepPlanAndAllergens() {
        core.bootstrap("شعبه یک", "مالک", "owner", "123456".toCharArray())
        val branch = Scope.Branch(core.overview.branches().single().id)
        val kitchen = core.overview.locations(branch).single().id
        val cash = core.overview.treasury().single().account.id
        val flour = core.inventory.createItem(CreateItem(id(), "آرد", StockUnit.KILOGRAM, Quantity.ZERO)).resultId
        val tomato = core.inventory.createItem(CreateItem(id(), "گوجه", StockUnit.KILOGRAM, Quantity.ZERO)).resultId
        val cheese = core.inventory.createItem(CreateItem(id(), "پنیر", StockUnit.KILOGRAM, Quantity.ZERO)).resultId
        core.inventory.updateItem(UpdateItem(id(), flour, "آرد", Quantity.ZERO, Quantity.ZERO, "", "گلوتن", null, emptySet(), true))
        core.inventory.updateItem(UpdateItem(id(), cheese, "پنیر", Quantity.ZERO, Quantity.ZERO, "", "لبنیات، گلوتن", null, emptySet(), true))
        // Dough (from flour) is kept at 2 kg; pizza base is made from dough; sauce from tomatoes.
        val dough = core.inventory.createItem(CreateItem(id(), "خمیر", StockUnit.KILOGRAM, Quantity.ZERO, prepared = true)).resultId
        core.inventory.updateItem(UpdateItem(id(), dough, "خمیر", Quantity.ZERO, kg(2), "", "", null, emptySet(), true))
        val base = core.inventory.createItem(CreateItem(id(), "نان پیتزا", StockUnit.PIECE, Quantity.ZERO, prepared = true)).resultId
        val sauce = core.inventory.createItem(CreateItem(id(), "سس", StockUnit.KILOGRAM, Quantity.ZERO, prepared = true)).resultId
        core.inventory.publishPrepRecipe(PublishPrepRecipe(id(), dough, BusinessDate(19_000), kg(1), listOf(RecipeLine(flour, Quantity.of(600_000)))))
        core.inventory.publishPrepRecipe(PublishPrepRecipe(id(), base, BusinessDate(19_000), kg(1), listOf(RecipeLine(dough, Quantity.of(250_000)))))
        core.inventory.publishPrepRecipe(PublishPrepRecipe(id(), sauce, BusinessDate(19_000), kg(1), listOf(RecipeLine(tomato, kg(1)))))
        val pizza = core.inventory.defineMenuItem(DefineMenuItem(id(), "پیتزا")).resultId
        core.inventory.publishRecipe(PublishRecipe(id(), pizza, BusinessDate(19_000), listOf(RecipeLine(base, kg(1)), RecipeLine(sauce, Quantity.of(100_000)), RecipeLine(cheese, Quantity.of(100_000)))))
        val salad = core.inventory.defineMenuItem(DefineMenuItem(id(), "سالاد")).resultId
        core.inventory.publishRecipe(PublishRecipe(id(), salad, BusinessDate(19_000), listOf(RecipeLine(tomato, Quantity.of(200_000)))))

        val supplier = core.purchasing.registerSupplier(RegisterSupplier(id(), "عمده", "021")).resultId
        core.purchasing.postInvoice(PostPurchaseInvoice(id(), branch, supplier, "1", kitchen, day.plusDays(-30), day, listOf(
            InvoiceLine(flour, kg(100), Money.of(10_000_000)), InvoiceLine(tomato, kg(2), Money.of(1_000_000)), InvoiceLine(cheese, kg(100), Money.of(10_000_000)))))
        // Same weekday: 3 weeks ago 10 pizzas, 2 weeks ago 13, last week closed. Another weekday is ignored.
        fun sell(d: BusinessDate, pizzas: Long) {
            val lines = listOf(SaleLineInput(pizza, kg(pizzas), Money.of(1_000_000)))
            val sale = core.salesOps.saveDraft(SaveSaleDraft(id(), branch, d, kitchen, lines, Money.ZERO, Money.ZERO, Money.ZERO,
                listOf(Settlement.Liquid(cash, Money.of(pizzas * 1_000_000))))).resultId
            core.salesOps.post(PostDailySale(id(), branch, sale))
        }
        sell(day.plusDays(-21), 10); sell(day.plusDays(-14), 13); sell(day.plusDays(-3), 40)
        val forecast = core.prep.forecast(branch, day)
        assertEquals(listOf(Triple("پیتزا", kg(12), 2)), forecast.map { Triple(it.item.name, it.portions, it.weeks) })   // (10 + 13) / 2, up

        val plan = core.prep.plan(kitchen, day, listOf(PlanLine(pizza, kg(12)), PlanLine(salad, kg(5))))
        // Dough: 12 bases × 0.25 = 3 kg + par 2 = 5. Bases 12. Sauce 1.2 kg. Dough first (bases use it).
        assertEquals(listOf("خمیر" to kg(5), "نان پیتزا" to kg(12), "سس" to Quantity.of(1_200_000)).sortedBy { it.first }.toSet(),
            plan.tasks.map { it.item.name to it.toMake }.toSet())
        assertEquals("خمیر", plan.tasks.first().item.name)
        assertEquals(kg(2), plan.tasks.single { it.item.id == dough }.par)
        // Tomatoes: sauce 1.2 + salads 1.0 = 2.2 kg, only 2 in stock (the sales consumed none: their recipe uses base).
        assertEquals(listOf("گوجه" to Quantity.of(2_200_000)), plan.shortages.map { it.item.name to it.needed })

        assertEquals(mapOf("پیتزا" to listOf("گلوتن", "لبنیات"), "سالاد" to emptyList()), core.prep.allergens(day).associate { it.item.name to it.allergens })
    }
}
