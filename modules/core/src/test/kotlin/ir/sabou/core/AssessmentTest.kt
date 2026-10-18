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
import ir.sabou.payroll.RegisterEmployee
import ir.sabou.persistence.DevDatabases
import ir.sabou.platform.Role
import ir.sabou.platform.memory.InMemoryAnchorStore
import ir.sabou.purchasing.AccountLine
import ir.sabou.purchasing.InvoiceLine
import ir.sabou.purchasing.PostPurchaseInvoice
import ir.sabou.purchasing.RegisterSupplier
import ir.sabou.sales.PostDailySale
import ir.sabou.sales.SaleLineInput
import ir.sabou.sales.SaveSaleDraft
import ir.sabou.sales.SetFoodCostTarget
import ir.sabou.sales.Settlement
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR-0061: the 360° scorecard and diagnosis, from the books only. */
class AssessmentTest {
    private val dir = Files.createTempDirectory("sabou-assess")
    private val db = DevDatabases.open(dir.resolve("a.db"))
    private val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { 1_700_000_000_000L }, emptyList(), null)
    private val day = BusinessDate(20_000)
    private val week2 = day.plusDays(7)
    private fun id() = GlobalId.new()
    private fun rial(v: Long) = Money.of(v)

    @AfterTest fun close() { db.close(); dir.toFile().deleteRecursively() }

    private lateinit var branch: Scope.Branch
    private var kitchen = GlobalId.new()
    private var cash = GlobalId.new()

    private fun sell(date: BusinessDate, discount: Long, vararg lines: Triple<GlobalId, Long, Long>) {
        val net = lines.sumOf { it.second * it.third } - discount
        val s = core.salesOps.saveDraft(SaveSaleDraft(id(), branch, date, kitchen, lines.map { SaleLineInput(it.first, Quantity.units(it.second), rial(it.third)) },
            rial(discount), rial(0), rial(0), listOf(Settlement.Liquid(cash, rial(net))), discountReason = if (discount > 0) "مشتری ثابت" else "")).resultId
        core.salesOps.post(PostDailySale(id(), branch, s))
    }

    /**
     * The two weeks of ProfitBridgeTest. Week 1: 3 kg cheese at 3,000,000; 10 pizzas at 5,000,000 and 10 pastas at
     * 3,000,000 (80 m). Week 2: cheese at 4,000,000; 12 pizzas at 5,500,000 and 5 pastas, 1 m discount (80 m net);
     * 0.5 kg wasted (2 m); 5 m rent. Profit 71 m → 61.4 m.
     */
    private fun twoWeeks() {
        core.bootstrap("شعبه ونک", "مالک", "owner", "123456".toCharArray())
        branch = Scope.Branch(core.overview.branches().single().id)
        kitchen = core.overview.locations(branch).single().id
        cash = core.overview.treasury().single().account.id
        val cheese = core.inventory.createItem(CreateItem(id(), "پنیر", StockUnit.KILOGRAM, Quantity.units(0))).resultId
        val pizza = core.inventory.defineMenuItem(DefineMenuItem(id(), "پیتزا")).resultId
        val pasta = core.inventory.defineMenuItem(DefineMenuItem(id(), "پاستا")).resultId
        core.inventory.publishRecipe(PublishRecipe(id(), pizza, BusinessDate(19_000), listOf(RecipeLine(cheese, Quantity.of(200_000)))))
        core.inventory.publishRecipe(PublishRecipe(id(), pasta, BusinessDate(19_000), listOf(RecipeLine(cheese, Quantity.of(100_000)))))
        val supplier = core.purchasing.registerSupplier(RegisterSupplier(id(), "لبنیات", "021")).resultId
        core.purchasing.postInvoice(PostPurchaseInvoice(id(), branch, supplier, "1", kitchen, day, day, listOf(InvoiceLine(cheese, Quantity.units(3), rial(9_000_000)))))
        sell(day, 0, Triple(pizza, 10, 5_000_000), Triple(pasta, 10, 3_000_000))
        core.purchasing.postInvoice(PostPurchaseInvoice(id(), branch, supplier, "2", kitchen, week2, week2, listOf(InvoiceLine(cheese, Quantity.units(10), rial(40_000_000)))))
        sell(week2, 1_000_000, Triple(pizza, 12, 5_500_000), Triple(pasta, 5, 3_000_000))
        core.inventory.waste(RecordWaste(id(), branch, kitchen, cheese, Quantity.of(500_000), WasteReason.SPOILAGE, "", week2))
        core.purchasing.postInvoice(PostPurchaseInvoice(id(), branch, supplier, "R1", null, week2, week2, emptyList(),
            accountLines = listOf(AccountLine(StandardAccounts.RENT, rial(5_000_000)))))
    }

    private fun assess() = core.assessments.of(branch, week2, week2.plusDays(6))
    private fun Assessment.kpi(id: String) = kpis.single { it.id == id }

    @Test fun theIndicatorsAreTheBooksFigures() {
        twoWeeks()
        val a = assess()
        assertEquals(day to day.plusDays(6), a.baseFrom to a.baseTo)
        assertEquals(20, a.dimensions.size)
        // 61.4 m on 80 m; 13.6 m food cost (11.6 m cost of sales + 2 m waste) on 80 m; 2 m waste on 80 m.
        assertEquals(7_675, a.kpi("D02.MARGIN").value)
        assertEquals(61_400_000, a.kpi("D02.MARGIN").numerator)
        assertEquals(1_700, a.kpi("D03.FOOD").value)
        assertEquals(100, a.kpi("D03.FOOD").score)                         // under the 32 % assumed without a target
        assertEquals(250, a.kpi("D03.WASTE").value)
        assertEquals(50, a.kpi("D03.WASTE").score)                         // halfway between 1 % and 4 %
        assertEquals(0, a.kpi("D20.GROWTH").value)                         // 80 m both weeks
        assertEquals(1, a.kpi("D09.PRICES").value)                         // cheese +33 %
        assertEquals(10_000, a.kpi("D11.RECIPES").value)
        assertEquals(0, a.kpi("D01.TRIAL").value)
        // Without enough data: Unknown, never zero, and the report asks for the data.
        val variance = a.kpi("D03.VARIANCE")
        assertEquals(KpiQuality.UNKNOWN, variance.quality); assertNull(variance.value); assertNull(variance.score)
        assertTrue(a.dataRequests.any { it.dimension == Dimension.D03 && it.text.contains("انبارگردانی") })
        listOf(Dimension.D07, Dimension.D08, Dimension.D12, Dimension.D15).forEach { d ->
            assertNull(a.dimensions.single { it.dimension == d }.score)
            assertTrue(a.dataRequests.any { it.dimension == d }, d.code)
        }
    }

    @Test fun theOverallScoreIsTheWeightedMeanOfWhatCouldBeScored() {
        twoWeeks()
        val a = assess()
        fun w(d: Dimension) = d.area.weight * 1_000 / Dimension.entries.count { it.area == d.area }
        val applicable = a.dimensions.filter { it.applicable }
        val scored = applicable.filter { it.score != null }
        val expected = scored.sumOf { w(it.dimension) * it.score!! } / scored.sumOf { w(it.dimension) }
        assertEquals(expected.toInt(), a.score)
        assertEquals(scored.sumOf { w(it.dimension) } * 10_000 / applicable.sumOf { w(it.dimension) }, a.coverageBp)
        assertTrue(a.coverageBp in Assessments.MIN_COVERAGE_BP..9_999)
        // A dimension that does not apply (no credit customers) is left out of the weight, not scored zero.
        assertEquals(KpiQuality.NOT_APPLICABLE, a.kpi("D19.CUSTOMER").quality)
    }

    @Test fun theProfitDropIsDiagnosedWithItsCausesToTheRial() {
        twoWeeks()
        val f = assess().findings.single { it.key == "PROFIT_DROP" }
        assertEquals(Finding.Severity.HIGH, f.severity)
        assertEquals(9_600_000, f.exposureHigh)
        // The biggest loss first: 3 fewer portions at the base margins, then the other costs and the ingredients.
        assertTrue(f.causes.first().startsWith("تعداد پرس فروخته‌شده: −۱۰٬۶۵۰٬۰۰۰"), f.causes.first())
        assertEquals(3, f.causes.size)
        assertTrue(f.action.contains("پیش‌بینی فروش"))
        // Supplier prices: cheese 3,000,000 → 4,000,000.
        val p = assess().findings.single { it.key == "PRICE_RISES" }
        assertTrue(p.evidence.single().contains("پنیر") && p.evidence.single().contains("+۳۳٫۳٪"), p.evidence.single())
    }

    @Test fun foodCostOverTheTargetIsPriced() {
        twoWeeks()
        core.salesOps.setFoodCostTarget(SetFoodCostTarget(id(), 1_400))
        val a = assess()
        val f = a.findings.single { it.key == "FOOD_COST" }
        assertEquals(Finding.Severity.MEDIUM, f.severity)
        assertEquals(2_400_000, f.exposureHigh)                            // 3 points over the target on 80 m
        assertTrue(f.causes.any { it.contains("پنیر") && it.contains("گران‌تر") })
        assertTrue(f.causes.any { it.startsWith("ضایعات ۲٫۵٪") }, f.causes.toString())
        assertEquals(70, a.kpi("D03.FOOD").score)                          // 17 % against 14 %–24 %
    }

    @Test fun aMissingHealthCardIsCriticalWhateverTheScore() {
        twoWeeks()
        core.payroll.registerEmployee(RegisterEmployee(id(), branch, "علی رضایی", "0084575948", Money.of(150_000_000), day.plusDays(-30)))
        val a = assess()
        val first = a.findings.first()
        assertEquals("HEALTH_CARDS", first.key)
        assertEquals(Finding.Severity.CRITICAL, first.severity)
        assertTrue(first.evidence.single().startsWith("علی رضایی"))
        assertEquals(listOf(first), a.critical)
        assertEquals(0, a.kpi("D14.HEALTH").value)
        assertEquals(1, a.kpi("D16.CONTRACTS").value)
    }

    @Test fun anEmptyRestaurantHasNoScoreButAReport() {
        core.bootstrap("شعبه ونک", "مالک", "owner", "123456".toCharArray())
        branch = Scope.Branch(core.overview.branches().single().id)
        val a = core.assessments.of(branch, day, day.plusDays(6))
        assertNull(a.score)
        assertTrue(a.critical.isEmpty())
        assertTrue(a.kpis.none { it.quality == KpiQuality.UNKNOWN && it.value != null })
        assertTrue(a.dataRequests.size >= 5)
    }

    @Test fun exportsAndNeedsTheBooksRole() {
        twoWeeks()
        val tables = ReportTables.assessment(assess())
        assertEquals(4, tables.size)
        assertEquals(assess().kpis.size, tables[1].rows.size)
        assertNotNull(tables[0].rows.first().last())
        assertTrue(Xlsx.write(tables).size > 1_000)
        core.identity.createUser("cashier", "صندوقدار", Role.CASHIER, setOf(branch.branchId), "654321".toCharArray())
        core.identity.logout(); core.identity.login("cashier", "654321".toCharArray())
        assertTrue(assertFailsWith<DomainException> { assess() }.error.code.startsWith("PERMISSION_DENIED"))
    }
}
