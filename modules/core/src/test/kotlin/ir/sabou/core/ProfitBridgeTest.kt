package ir.sabou.core

import ir.sabou.inventory.CreateItem
import ir.sabou.inventory.DefineMenuItem
import ir.sabou.inventory.PublishRecipe
import ir.sabou.inventory.RecipeLine
import ir.sabou.inventory.RecordWaste
import ir.sabou.inventory.StockUnit
import ir.sabou.inventory.WasteReason
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.ledger.StandardAccounts
import ir.sabou.persistence.JdbcSqlDatabase
import ir.sabou.platform.Role
import ir.sabou.platform.memory.InMemoryAnchorStore
import ir.sabou.purchasing.AccountLine
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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** ADR-0053: why profit changed, to the Rial. */
class ProfitBridgeTest {
    private val dir = Files.createTempDirectory("sabou-bridge")
    private val db = JdbcSqlDatabase(DriverManager.getConnection("jdbc:sqlite:${dir.resolve("b.db")}"))
    private val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { 1_700_000_000_000L }, emptyList(), null)
    private val day = BusinessDate(20_000)
    private val week2 = day.plusDays(7)
    private fun id() = GlobalId.new()
    private fun rial(v: Long) = Money.of(v)

    @AfterTest fun close() { db.close(); dir.toFile().deleteRecursively() }

    private lateinit var branch: Scope.Branch
    private var kitchen = GlobalId.new()
    private var cash = GlobalId.new()
    private var cheese = GlobalId.new()
    private var pizza = GlobalId.new()
    private var pasta = GlobalId.new()
    private var supplier = GlobalId.new()

    private fun sell(date: BusinessDate, discount: Long, vararg lines: Triple<GlobalId, Long, Long>) {
        val net = lines.sumOf { it.second * it.third } - discount
        val s = core.salesOps.saveDraft(SaveSaleDraft(id(), branch, date, kitchen, lines.map { SaleLineInput(it.first, Quantity.units(it.second), rial(it.third)) },
            rial(discount), rial(0), rial(0), listOf(Settlement.Liquid(cash, rial(net))), discountReason = if (discount > 0) "مشتری ثابت" else "")).resultId
        core.salesOps.post(PostDailySale(id(), branch, s))
    }

    /**
     * Week 1: 3 kg cheese at 3,000,000; 10 pizzas (0.2 kg) at 5,000,000 and 10 pastas (0.1 kg) at 3,000,000.
     * Week 2: cheese at 4,000,000; pizza up to 5,500,000; 12 pizzas and 5 pastas, 1,000,000 discount;
     * 0.5 kg wasted; 5,000,000 rent.
     */
    private fun twoWeeks() {
        core.bootstrap("شعبه ونک", "مالک", "owner", "123456".toCharArray())
        branch = Scope.Branch(core.overview.branches().single().id)
        kitchen = core.overview.locations(branch).single().id
        cash = core.overview.treasury().single().account.id
        cheese = core.inventory.createItem(CreateItem(id(), "پنیر", StockUnit.KILOGRAM, Quantity.units(0))).resultId
        pizza = core.inventory.defineMenuItem(DefineMenuItem(id(), "پیتزا")).resultId
        pasta = core.inventory.defineMenuItem(DefineMenuItem(id(), "پاستا")).resultId
        core.inventory.publishRecipe(PublishRecipe(id(), pizza, BusinessDate(19_000), listOf(RecipeLine(cheese, Quantity.of(200_000)))))
        core.inventory.publishRecipe(PublishRecipe(id(), pasta, BusinessDate(19_000), listOf(RecipeLine(cheese, Quantity.of(100_000)))))
        supplier = core.purchasing.registerSupplier(RegisterSupplier(id(), "لبنیات", "021")).resultId
        core.purchasing.postInvoice(PostPurchaseInvoice(id(), branch, supplier, "1", kitchen, day, day, listOf(InvoiceLine(cheese, Quantity.units(3), rial(9_000_000)))))
        sell(day, 0, Triple(pizza, 10, 5_000_000), Triple(pasta, 10, 3_000_000))
        core.purchasing.postInvoice(PostPurchaseInvoice(id(), branch, supplier, "2", kitchen, week2, week2, listOf(InvoiceLine(cheese, Quantity.units(10), rial(40_000_000)))))
        sell(week2, 1_000_000, Triple(pizza, 12, 5_500_000), Triple(pasta, 5, 3_000_000))
        core.inventory.waste(RecordWaste(id(), branch, kitchen, cheese, Quantity.of(500_000), WasteReason.SPOILAGE, "", week2))
        core.purchasing.postInvoice(PostPurchaseInvoice(id(), branch, supplier, "R1", null, week2, week2, emptyList(),
            accountLines = listOf(AccountLine(StandardAccounts.RENT, rial(5_000_000)))))
    }

    @Test fun theStepsAddUpToTheChangeInProfitAndNameTheCauses() {
        twoWeeks()
        val (from0, to0) = core.profitBridges.previous(week2, week2.plusDays(6))
        assertEquals(day to day.plusDays(6), from0 to to0)
        val b = core.profitBridges.of(branch, week2, week2.plusDays(6), from0, to0)
        // Books: 80 m − 9 m = 71 m; then 80 m net sales − 11.6 m cost of sales − 2 m waste − 5 m rent = 61.4 m.
        assertEquals(71_000_000, b.base.profit)
        assertEquals(61_400_000, b.current.profit)
        assertEquals(b.change, b.steps.sumOf { it.amount })
        fun s(k: BridgeStep.Kind) = b.step(k).amount
        // Base margin 71 m on 20 portions; 17 now: 71 m × −3/20.
        assertEquals(-10_650_000, s(BridgeStep.Kind.VOLUME))
        // At base margins the 12 pizzas and 5 pastas earn 66.3 m: the richer mix gives back 5.95 m.
        assertEquals(5_950_000, s(BridgeStep.Kind.MIX))
        assertEquals(6_000_000, s(BridgeStep.Kind.PRICES))              // 12 × 500,000
        assertEquals(-2_900_000, s(BridgeStep.Kind.INGREDIENT_COSTS))   // 12 × 200,000 + 5 × 100,000
        assertEquals(-1_000_000, s(BridgeStep.Kind.DISCOUNTS))
        assertEquals(0, s(BridgeStep.Kind.UNTRACED_COST))
        assertEquals(-2_000_000, s(BridgeStep.Kind.FOOD_LOSSES))
        assertEquals(-5_000_000, s(BridgeStep.Kind.OTHER_COSTS))
        assertEquals(0, s(BridgeStep.Kind.LABOR)); assertEquals(0, s(BridgeStep.Kind.ROUNDING))
        assertEquals(listOf(BridgeDetail("پیتزا", 6_000_000)), b.step(BridgeStep.Kind.PRICES).details)
        assertEquals(listOf(BridgeDetail("پیتزا", -2_400_000), BridgeDetail("پاستا", -500_000)), b.step(BridgeStep.Kind.INGREDIENT_COSTS).details)
        assertEquals("اجاره", b.step(BridgeStep.Kind.OTHER_COSTS).details.single().name)
        assertEquals(10_000, b.current.costedBp)
        // The summary: the change, then the three biggest causes, biggest first.
        assertTrue(b.summary.first().contains("کاهش ۹٬۶۰۰٬۰۰۰"), b.summary.first())
        assertTrue(b.summary[1].startsWith("تعداد پرس فروخته‌شده: −۱۰٬۶۵۰٬۰۰۰"), b.summary[1])
        assertTrue(b.summary[2].startsWith("قیمت فروش غذاها: +۶٬۰۰۰٬۰۰۰") && b.summary[2].contains("«پیتزا»"), b.summary[2])
        assertEquals(4, b.summary.size)
    }

    @Test fun cheapPortionsAndNewDishesStillAddUp() {
        twoWeeks()
        // A dish sold only in the current week, without a recipe: all of it is volume and mix, at no cost.
        val tea = core.inventory.defineMenuItem(DefineMenuItem(id(), "چای")).resultId
        sell(week2.plusDays(1), 0, Triple(tea, 7, 333_333))
        val b = core.profitBridges.of(branch, week2, week2.plusDays(6), day, day.plusDays(6))
        assertEquals(b.change, b.steps.sumOf { it.amount })
        assertEquals(0, b.step(BridgeStep.Kind.PRICES).details.count { it.name == "چای" })
        assertTrue(kotlin.math.abs(b.step(BridgeStep.Kind.ROUNDING).amount) < 10)
    }

    @Test fun exportsAndNeedsTheBooksRole() {
        twoWeeks()
        val b = core.profitBridges.of(branch, week2, week2.plusDays(6), day, day.plusDays(6))
        val t = ReportTables.profitBridge(b)
        assertEquals(b.steps.size + 2, t.rows.size)                         // opening, steps, closing
        assertTrue(Xlsx.write(listOf(t)).size > 1_000)
        core.identity.createUser("cashier", "صندوقدار", Role.CASHIER, setOf(branch.branchId), "654321".toCharArray())
        core.identity.logout(); core.identity.login("cashier", "654321".toCharArray())
        assertTrue(assertFailsWith<DomainException> { core.profitBridges.of(branch, week2, week2, day, day) }.error.code.startsWith("PERMISSION_DENIED"))
    }
}
