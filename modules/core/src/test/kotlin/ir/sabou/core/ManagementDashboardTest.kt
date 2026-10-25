package ir.sabou.core

import ir.sabou.inventory.CreateItem
import ir.sabou.inventory.DefineMenuItem
import ir.sabou.inventory.PublishRecipe
import ir.sabou.inventory.RecipeLine
import ir.sabou.inventory.SetMenuItemCategory
import ir.sabou.inventory.StockUnit
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.persistence.DevDatabases
import ir.sabou.platform.Role
import ir.sabou.platform.memory.InMemoryAnchorStore
import ir.sabou.purchasing.InvoiceLine
import ir.sabou.purchasing.PostPurchaseInvoice
import ir.sabou.purchasing.RegisterSupplier
import ir.sabou.sales.PostDailySale
import ir.sabou.sales.SaleLineInput
import ir.sabou.sales.SaveSaleDraft
import ir.sabou.sales.Settlement
import ir.sabou.treasury.OpenTreasuryAccount
import ir.sabou.treasury.TreasuryKind
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR-0064: the manager's dashboard is the books' figures, arranged. */
class ManagementDashboardTest {
    private val dir = Files.createTempDirectory("sabou-dash")
    private val db = DevDatabases.open(dir.resolve("d.db"))
    private val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { 1_700_000_000_000L }, emptyList(), null)
    private val day = BusinessDate(20_000)
    private fun id() = GlobalId.new()
    private fun rial(v: Long) = Money.of(v)

    @AfterTest fun close() { db.close(); dir.toFile().deleteRecursively() }

    @Test fun theDashboardIsTheBooksFiguresArranged() {
        core.bootstrap("شعبه ونک", "مالک", "owner", "123456".toCharArray())
        val branch = Scope.Branch(core.overview.branches().single().id)
        val kitchen = core.overview.locations(branch).single().id
        val cash = core.overview.treasury().single().account.id
        val card = core.treasury.openAccount(OpenTreasuryAccount(id(), branch, "کارت‌خوان", TreasuryKind.CARD_TERMINAL)).resultId
        val meat = core.inventory.createItem(CreateItem(id(), "گوشت", StockUnit.KILOGRAM, Quantity.units(0))).resultId
        val kabab = core.inventory.defineMenuItem(DefineMenuItem(id(), "کباب")).resultId
        val dough = core.inventory.defineMenuItem(DefineMenuItem(id(), "دوغ")).resultId
        core.inventory.publishRecipe(PublishRecipe(id(), kabab, BusinessDate(19_000), listOf(RecipeLine(meat, Quantity.of(200_000)))))
        core.inventory.setMenuItemCategory(SetMenuItemCategory(id(), kabab, "کباب"))
        core.inventory.setMenuItemCategory(SetMenuItemCategory(id(), dough, " نوشیدنی "))
        val supplier = core.purchasing.registerSupplier(RegisterSupplier(id(), "قصابی", "021")).resultId
        core.purchasing.postInvoice(PostPurchaseInvoice(id(), branch, supplier, "1", kitchen, day.plusDays(-7), day.plusDays(-7),
            listOf(InvoiceLine(meat, Quantity.units(20), rial(100_000_000)))))
        fun sell(d: BusinessDate, kababs: Long, doughs: Long, cashPart: Long) {
            val total = kababs * 4_000_000 + doughs * 500_000
            val s = core.salesOps.saveDraft(SaveSaleDraft(id(), branch, d, kitchen,
                listOfNotNull(SaleLineInput(kabab, Quantity.units(kababs), rial(4_000_000)), SaleLineInput(dough, Quantity.units(doughs), rial(500_000)).takeIf { doughs > 0 }),
                rial(0), rial(0), rial(0), listOfNotNull(Settlement.Liquid(cash, rial(cashPart)).takeIf { cashPart > 0 }, Settlement.Liquid(card, rial(total - cashPart)).takeIf { total > cashPart }))).resultId
            core.salesOps.post(PostDailySale(id(), branch, s))
        }
        sell(day.plusDays(-8), 5, 0, 20_000_000)          // the week before
        sell(day.plusDays(-1), 4, 0, 16_000_000)          // yesterday: 16 m
        sell(day, 10, 8, 14_000_000)                      // today: 40 m + 4 m, 14 m cash and 30 m card
        core.assessments.record(RecordField(id(), branch, FieldRecord.Kind.FEEDBACK, day, "", 5))
        core.assessments.record(RecordField(id(), branch, FieldRecord.Kind.FEEDBACK, day, "", 2))
        // Two ratings are no measure yet (SABOU-AUDIT-005): the scorecard's minimum of five applies.
        val few = core.managementDashboards.of(branch, day.plusDays(-6), day).kpis.single { it.title.startsWith("رضایت") }
        assertNull(few.value)
        assertTrue(few.note.contains("۲ نظر ثبت شده"), few.note)
        listOf(4L, 5L, 1L, 3L).forEach { core.assessments.record(RecordField(id(), branch, FieldRecord.Kind.FEEDBACK, day, "", it)) }

        val d = core.managementDashboards.of(branch, day.plusDays(-6), day)
        fun kpi(prefix: String) = d.kpis.single { it.title.startsWith(prefix) }
        assertEquals(44_000_000, kpi("فروش").value)
        assertEquals(17_500, kpi("فروش").changeBp)                                         // 44 m against 16 m: +175 %
        assertEquals(true, kpi("فروش").good)
        assertEquals(5_000, kpi("رضایت").value)                                           // three of six rated 4 or 5
        assertNull(kpi("میانگین زمان").value)                                             // not recorded: not invented
        // The week: today 44 m, the same weekday a week earlier nothing; the day before 16 m, a week earlier 20 m.
        assertEquals(44_000_000, d.week.last().sales)
        assertEquals(16_000_000, d.week[5].sales)
        assertEquals(20_000_000, d.week[5].previous)
        // Categories: 56 m kabab (14 × 4 m), 4 m drinks; the trimmed name.
        assertEquals(listOf("کباب" to 56_000_000L, "نوشیدنی" to 4_000_000L), d.categories.map { it.name to it.amount })
        assertEquals(9_333, d.categories.first().shareBp)
        // Payments over the period: 30 m cash, 30 m card.
        assertEquals(mapOf("نقدی" to 30_000_000L, "کارت" to 30_000_000L), d.payments.associate { it.name to it.amount })
        assertEquals(6, d.months.size)
        assertEquals(listOf("کباب", "دوغ"), d.topItems.map { it.name })
        assertEquals(7_500, d.topItems.first().marginBp)                                  // 4 m price, 1 m of meat (0.2 kg × 5 m)
        assertEquals(listOf(day, day.plusDays(-1), day.plusDays(-8)), d.latestSales.map { it.date })     // newest first, last 30 days
        assertTrue(d.operations.size == 5)
        assertEquals(7, ReportTables.managementDashboard(d).size)
        assertTrue(Xlsx.write(ReportTables.managementDashboard(d)).size > 1_000)

        core.identity.createUser("cashier", "صندوقدار", Role.CASHIER, setOf(branch.branchId), "654321".toCharArray())
        core.identity.logout(); core.identity.login("cashier", "654321".toCharArray())
        assertTrue(assertFailsWith<DomainException> { core.managementDashboards.of(branch, day, day) }.error.code.startsWith("PERMISSION_DENIED"))
    }

    /** SABOU-AUDIT-004: today's sales are recorded at the end of the day; until then the headline is the last recorded day. */
    @Test fun theHeadlineIsTheLastDayWithRecordedSales() {
        core.bootstrap("شعبه ونک", "مالک", "owner", "123456".toCharArray())
        val branch = Scope.Branch(core.overview.branches().single().id)
        val kitchen = core.overview.locations(branch).single().id
        val cash = core.overview.treasury().single().account.id
        val drink = core.inventory.defineMenuItem(DefineMenuItem(id(), "دوغ")).resultId
        val yogurt = core.inventory.createItem(CreateItem(id(), "ماست", StockUnit.KILOGRAM, Quantity.units(0))).resultId
        core.inventory.publishRecipe(PublishRecipe(id(), drink, BusinessDate(19_000), listOf(RecipeLine(yogurt, Quantity.of(100_000)))))
        fun sell(d: BusinessDate, amount: Long) {
            val s = core.salesOps.saveDraft(SaveSaleDraft(id(), branch, d, kitchen, listOf(SaleLineInput(drink, Quantity.units(1), rial(amount))),
                rial(0), rial(0), rial(0), listOf(Settlement.Liquid(cash, rial(amount))))).resultId
            core.salesOps.post(PostDailySale(id(), branch, s))
        }
        sell(day.plusDays(-2), 10_000_000)
        sell(day.plusDays(-1), 12_000_000)
        val headline = core.managementDashboards.of(branch, day.plusDays(-6), day).kpis.first()
        assertEquals("فروش ${Fa.dayTitle(day.plusDays(-1))}", headline.title)
        assertEquals(12_000_000, headline.value)
        assertEquals(2_000, headline.changeBp)                                            // +20 % on the day before it
        assertEquals(true, headline.good)
        assertTrue(headline.note.startsWith("آخرین روز ثبت‌شده"))
    }

    @Test fun aCategoryIsCheckedAndKeptAcrossRenames() {
        core.bootstrap("شعبه ونک", "مالک", "owner", "123456".toCharArray())
        val kabab = core.inventory.defineMenuItem(DefineMenuItem(id(), "کباب")).resultId
        core.inventory.setMenuItemCategory(SetMenuItemCategory(id(), kabab, "کبابی‌ها"))
        core.inventory.updateMenuItem(ir.sabou.inventory.UpdateMenuItem(id(), kabab, "کباب کوبیده", true))
        assertEquals("کبابی‌ها", core.overview.menu().single().item.category)
        assertTrue(assertFailsWith<DomainException> { core.inventory.setMenuItemCategory(SetMenuItemCategory(id(), kabab, "x".repeat(41))) }.error.code.startsWith("INVALID_INPUT"))
    }
}
