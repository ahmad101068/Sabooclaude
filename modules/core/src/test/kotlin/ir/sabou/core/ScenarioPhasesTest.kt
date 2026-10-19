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
import ir.sabou.kernel.Ratio
import ir.sabou.kernel.Scope
import ir.sabou.ledger.StandardAccounts
import ir.sabou.persistence.DevDatabases
import ir.sabou.platform.Role
import ir.sabou.platform.memory.InMemoryAnchorStore
import ir.sabou.purchasing.AccountLine
import ir.sabou.purchasing.CreatePurchaseOrder
import ir.sabou.purchasing.InvoiceLine
import ir.sabou.purchasing.OrderLine
import ir.sabou.purchasing.PostPurchaseInvoice
import ir.sabou.purchasing.RegisterSupplier
import ir.sabou.sales.PostDailySale
import ir.sabou.sales.SaleLineInput
import ir.sabou.sales.SaveSaleDraft
import ir.sabou.sales.Settlement
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR-0062: the phases of the single-restaurant scenario, each against its acceptance criterion. */
class ScenarioPhasesTest {
    private val dir = Files.createTempDirectory("sabou-phases")
    private val db = DevDatabases.open(dir.resolve("p.db"))
    private val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { 1_700_000_000_000L }, emptyList(), null)
    private val day = BusinessDate(20_000)
    private val week2 = day.plusDays(7)
    private val end = week2.plusDays(6)
    private fun id() = GlobalId.new()
    private fun rial(v: Long) = Money.of(v)

    @AfterTest fun close() { db.close(); dir.toFile().deleteRecursively() }

    private lateinit var branch: Scope.Branch
    private var kitchen = GlobalId.new()
    private var cash = GlobalId.new()
    private var cheese = GlobalId.new()
    private var supplier = GlobalId.new()

    private fun sell(date: BusinessDate, discount: Long, vararg lines: Triple<GlobalId, Long, Long>) {
        val net = lines.sumOf { it.second * it.third } - discount
        val s = core.salesOps.saveDraft(SaveSaleDraft(id(), branch, date, kitchen, lines.map { SaleLineInput(it.first, Quantity.units(it.second), rial(it.third)) },
            rial(discount), rial(0), rial(0), listOf(Settlement.Liquid(cash, rial(net))), discountReason = if (discount > 0) "مشتری ثابت" else "")).resultId
        core.salesOps.post(PostDailySale(id(), branch, s))
    }

    /** The two weeks of ProfitBridgeTest / AssessmentTest. */
    private fun twoWeeks() {
        core.bootstrap("شعبه ونک", "مالک", "owner", "123456".toCharArray())
        branch = Scope.Branch(core.overview.branches().single().id)
        kitchen = core.overview.locations(branch).single().id
        cash = core.overview.treasury().single().account.id
        cheese = core.inventory.createItem(CreateItem(id(), "پنیر", StockUnit.KILOGRAM, Quantity.units(0))).resultId
        val pizza = core.inventory.defineMenuItem(DefineMenuItem(id(), "پیتزا")).resultId
        val pasta = core.inventory.defineMenuItem(DefineMenuItem(id(), "پاستا")).resultId
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

    private fun assess() = core.assessments.of(branch, week2, end)
    private fun Assessment.kpi(id: String) = kpis.single { it.id == id }
    private fun code(block: () -> Unit) = assertFailsWith<DomainException> { block() }.error.code

    // ------------------------------------------------------------------ phase 1: targets and weights

    @Test fun phase1_aTargetChangesOnlyItsIndicatorAndTheReportSaysSo() {
        twoWeeks()
        val before = assess()
        assertEquals(50, before.kpi("D03.WASTE").score)
        core.assessments.setSettings(SetAssessmentSettings(id(), mapOf("D03.WASTE" to listOf(200L, 500L)), emptyMap()))
        val after = assess()
        assertEquals(83, after.kpi("D03.WASTE").score)                                  // 2.5 % between 2 % and 5 %
        assertEquals(1, after.settings.revision)
        assertEquals(before.kpis.filter { it.id != "D03.WASTE" }.map { it.id to it.score }, after.kpis.filter { it.id != "D03.WASTE" }.map { it.id to it.score })
        // A target the wrong way round, an unknown indicator, a bad weight: refused, nothing changes.
        assertTrue(code { core.assessments.setSettings(SetAssessmentSettings(id(), mapOf("D03.WASTE" to listOf(500L, 200L)), emptyMap())) }.startsWith("INVALID_INPUT"))
        assertTrue(code { core.assessments.setSettings(SetAssessmentSettings(id(), mapOf("X" to listOf(1L, 2L)), emptyMap())) }.startsWith("INVALID_INPUT"))
        assertTrue(code { core.assessments.setSettings(SetAssessmentSettings(id(), emptyMap(), mapOf("FINANCE" to 101L))) }.startsWith("INVALID_INPUT"))
        assertEquals(1, core.assessments.settings().revision)
        // Weights: finance out of the score; the score is the weighted mean of what is left.
        core.assessments.setSettings(SetAssessmentSettings(id(), mapOf("D03.WASTE" to listOf(200L, 500L)), mapOf("FINANCE" to 0L)))
        val w = assess()
        fun weight(d: Dimension) = w.settings.weight(d.area) * 1_000 / Dimension.entries.count { it.area == d.area }
        val scored = w.dimensions.filter { it.applicable && it.score != null }
        assertEquals(Ratio.mulDiv(scored.sumOf { weight(it.dimension) * it.score!! }, 1, scored.sumOf { weight(it.dimension) }).toInt(), w.score)
        // Defaults reproduce today's figures exactly.
        core.assessments.setSettings(SetAssessmentSettings(id(), emptyMap(), emptyMap()))
        assertEquals(before.kpis.map { it.id to it.score }, assess().kpis.map { it.id to it.score })
        assertEquals(before.score, assess().score)
    }

    @Test fun phase1_settingsOfARetiredIndicatorDoNotBreakTheReport() {
        twoWeeks()
        ir.sabou.persistence.DatabaseMeta(db).put(Assessments.SETTINGS_KEY,
            """{"targets":{"D99.GONE":[1,2],"D03.WASTE":[200,500]},"weights":{"GONE_AREA":5,"FINANCE":20},"revision":4}""")
        val s = core.assessments.settings()
        assertEquals(setOf("D03.WASTE"), s.targets.keys)
        assertEquals(mapOf(AssessmentArea.FINANCE to 20L), s.weights)
        assertEquals(83, assess().kpi("D03.WASTE").score)
    }

    @Test fun phase3_anActionClosedEarlyIsMeasuredOnlyOnDaysThatHappened() {
        twoWeeks()
        // Opened and closed on the day of week 2's waste: one day after (2 m on 80 m sold that day), one day before.
        val action = core.assessments.openAction(OpenAssessmentAction(id(), branch, "FOOD_COST", "D03.WASTE", "ضایعات", "ثبت روزانه", "سرآشپز", week2, 0, week2)).resultId
        assertTrue(code { core.assessments.closeAction(CloseAssessmentAction(id(), branch, action, AssessmentAction.Status.DONE, "", week2.plusDays(-1))) }.startsWith("INVALID_INPUT"))
        core.assessments.closeAction(CloseAssessmentAction(id(), branch, action, AssessmentAction.Status.DONE, "", week2))
        val closed = core.assessments.actions(branch).single()
        assertEquals(250L, closed.after)
        assertNull(closed.before)                                                        // the day before had no sales: unknown, not zero
    }

    @Test fun channelCostsAreTheOwnersRatesOnEachChannelsSales() {
        twoWeeks()
        val pizza = core.overview.menu().first { it.item.name == "پیتزا" }.item.id
        // A delivery-app day in week 2: 10 pizzas, 50 m, all through the app.
        val s = core.salesOps.saveDraft(SaveSaleDraft(id(), branch, week2.plusDays(1), kitchen, listOf(SaleLineInput(pizza, Quantity.units(10), rial(5_000_000))),
            rial(0), rial(0), rial(0), listOf(Settlement.Liquid(cash, rial(50_000_000))),
            channels = listOf(ir.sabou.sales.ChannelShare(ir.sabou.sales.SalesChannel.ONLINE, rial(50_000_000), 10)))).resultId
        core.salesOps.post(PostDailySale(id(), branch, s))
        assertTrue(assess().dataRequests.any { it.dimension == Dimension.D05 && it.text.contains("کمیسیون") })
        core.assessments.setSettings(SetAssessmentSettings(id(), emptyMap(), emptyMap(), mapOf("ONLINE" to 2_500L)))
        val a = assess()
        assertEquals(12_500_000, a.kpi("D05.COMMISSION").numerator)                      // 25 % of 50 m
        val f = a.findings.single { it.key == "CHANNEL_COST:ONLINE" }
        assertEquals(12_500_000, f.exposureHigh)
        assertTrue(code { core.assessments.setSettings(SetAssessmentSettings(id(), emptyMap(), emptyMap(), mapOf("ONLINE" to 9_500L))) }.startsWith("INVALID_INPUT"))
        // Saving targets alone with the channel costs passed back keeps them.
        core.assessments.setSettings(SetAssessmentSettings(id(), mapOf("D03.WASTE" to listOf(200L, 500L)), emptyMap(), mapOf("ONLINE" to 2_500L)))
        assertEquals(2_500L, core.assessments.settings().channelCosts[ir.sabou.sales.SalesChannel.ONLINE])
    }

    @Test fun theWeeklyPdfIsLastWeeksCardAsTheBackupOwnerSeesIt() {
        twoWeeks()
        val owner = core.overview.me().userId
        core.identity.logout()
        val saturday = generateSequence(end) { it.plusDays(1) }.first { ir.sabou.purchasing.Weekdays.index(it) == 0 }
        // The week before that Saturday must hold week 2's sales for the card; take the Saturday right after week 2.
        val tables = core.assessments.weeklyTables(owner, week2.plusDays(7))
        assertEquals(4, tables.size)
        assertTrue(tables.first().subtitle.contains("شعبه ونک"))
        assertNull(core.session.currentActor())                                           // nobody signed in afterwards either
        assertTrue(core.assessments.weeklyTables(owner, saturday.plusDays(70)).isEmpty())   // a week without sales: no PDF
        assertTrue(core.assessments.weeklyTables(GlobalId.new(), saturday).isEmpty())
    }

    // ------------------------------------------------------------------ phase 2: alerts

    @Test fun phase2_highFindingsAreAlertedOnceWithAStableKey() {
        twoWeeks()
        val owner = core.overview.me().userId
        // The alerts look at the last four weeks: no backup yet is high; the profit drop of a single week is not
        // one (the four weeks before hold no sales to compare with).
        val first = core.alerts.forUser(owner, end).filter { it.kind == Alert.Kind.FINDING }
        assertTrue(first.any { it.key.endsWith(":BACKUP_OLD") && it.title.startsWith("زیاد:") }, first.toString())
        assertEquals(first.map { it.key }, core.alerts.forUser(owner, end).filter { it.kind == Alert.Kind.FINDING }.map { it.key })
        assertTrue(first.none { it.key.contains("HEALTH_CARDS") || it.key.contains("CASH_SHORT") })       // announced by their own alerts
    }

    // ------------------------------------------------------------------ phase 3: actions

    @Test fun phase3_anActionIsMeasuredAndAFalseAlarmIsQuiet() {
        twoWeeks()
        val f = assess().findings.single { it.key == "PROFIT_DROP" }
        assertEquals("D02.MARGIN", f.kpiId)
        // Opened on the first day of week 2 about waste, closed at its end: waste 0 % the week before, 2.5 % after.
        val action = core.assessments.openAction(OpenAssessmentAction(id(), branch, "FOOD_COST", "D03.WASTE", "ضایعات", "ضایعات روزانه ثبت شود", "سرآشپز", end, 0, week2)).resultId
        assertTrue(code { core.assessments.openAction(OpenAssessmentAction(id(), branch, "X", null, "t", "a", "", end, 0, week2)) }.startsWith("INVALID_INPUT"))
        core.assessments.closeAction(CloseAssessmentAction(id(), branch, action, AssessmentAction.Status.DONE, "انجام شد", end))
        val closed = core.assessments.actions(branch).single { it.id == action }
        assertEquals(0L, closed.before); assertEquals(250L, closed.after)
        assertEquals(false, closed.improved)
        assertEquals("INVALID_STATE:ACTION:CLOSED", code { core.assessments.closeAction(CloseAssessmentAction(id(), branch, action, AssessmentAction.Status.DONE, "", end)) })
        // An open action past its due date counts against D17 and is listed.
        core.assessments.openAction(OpenAssessmentAction(id(), branch, "MENU", "D06.DOGS", "منو", "پاستا بازبینی شود", "مدیر", week2, 0, week2))
        val a = assess()
        assertEquals(1, a.kpi("D17.ACTIONS").value)
        assertTrue(a.findings.any { it.key == "ACTIONS_LATE" })
        assertEquals(2, a.actions.size)
        // A false alarm: the finding leaves the report (and the alerts) for 30 days, with the reason on record.
        assertTrue(code { core.assessments.markFalseAlarm(MarkFalseAlarm(id(), branch, "PROFIT_DROP", f.title, "", end)) }.startsWith("INVALID_INPUT"))
        core.assessments.markFalseAlarm(MarkFalseAlarm(id(), branch, "PROFIT_DROP", f.title, "هفته‌ی اول جشن بود", end))
        val quiet = assess()
        assertTrue(quiet.findings.none { it.key == "PROFIT_DROP" })
        assertEquals(listOf(f.title), quiet.silenced)
        assertTrue(core.alerts.forUser(core.overview.me().userId, end).none { it.key.endsWith(":PROFIT_DROP") })
    }

    // ------------------------------------------------------------------ phase 4: field records

    private fun rec(kind: FieldRecord.Kind, date: BusinessDate, subject: String, value: Long?, value2: Long? = null, endDate: BusinessDate? = null, note: String = "") =
        core.assessments.record(RecordField(id(), branch, kind, date, subject, value, value2, endDate, note)).resultId

    @Test fun phase4_recordedFactsBecomeIndicatorsAndFindings() {
        twoWeeks()
        assertTrue(assess().dataRequests.any { it.dimension == Dimension.D08 })
        assertNull(assess().kpi("D14.TEMP_DAYS").value)
        // Temperatures: every day of week 2, the last reading of the freezer too warm.
        (0L..6L).forEach { rec(FieldRecord.Kind.TEMPERATURE, week2.plusDays(it), "یخچال آشپزخانه", 40, 0) }
        val warm = rec(FieldRecord.Kind.TEMPERATURE, end, "فریزر", -120, 1)
        // Five customers: 5, 4, 4, 2, 1.
        listOf(5L, 4L, 4L, 2L, 1L).forEach { rec(FieldRecord.Kind.FEEDBACK, week2, "", it, note = "نظر $it") }
        rec(FieldRecord.Kind.CAMPAIGN, week2, "تخفیف دانشجویی", 1_000_000, endDate = end)
        rec(FieldRecord.Kind.BREAKDOWN, week2, "فر پیتزا", 2_000_000, 120)
        rec(FieldRecord.Kind.BREAKDOWN, week2.plusDays(3), "فر پیتزا", 3_000_000, 60)
        listOf(200L to 230L, 100L to 110L, 200L to 220L).forEach { (r, w) -> rec(FieldRecord.Kind.PORTION, week2, "پنیر پیتزا", r, w) }
        rec(FieldRecord.Kind.PERMIT, week2, "پروانه‌ی کسب", null, endDate = end.plusDays(10))
        rec(FieldRecord.Kind.PERMIT, week2, "کارت بهداشت مکان", null, endDate = end.plusDays(-1))

        val a = assess()
        assertEquals(10_000, a.kpi("D14.TEMP_DAYS").value)
        assertEquals(1, a.kpi("D14.TEMP_BAD").value)
        assertEquals(320, a.kpi("D08.RATING").value)                                     // 16 ÷ 5
        assertEquals("۳٫۲ از ۵", Assessments.valueText(a.kpi("D08.RATING")))
        assertEquals(4_000, a.kpi("D08.COMPLAINTS").value)
        assertEquals(0, a.kpi("D07.LIFT").value)                                          // 80 m both weeks
        assertEquals(2, a.kpi("D15.BREAKDOWNS").value)
        assertEquals(0, a.kpi("D11.PORTION").value)                                       // 15 %, 10 %, 10 % over: none within 5 %
        assertEquals(2, a.kpi("D16.PERMITS").value)
        assertTrue(a.dataRequests.none { it.dimension == Dimension.D08 || it.dimension == Dimension.D15 })
        fun f(key: String) = a.findings.single { it.key == key }
        assertEquals(Finding.Severity.CRITICAL, f("TEMPERATURE:فریزر").severity)
        assertTrue(f("TEMPERATURE:فریزر").title.contains("−۱۲ درجه"), f("TEMPERATURE:فریزر").title)
        assertEquals(Finding.Severity.CRITICAL, f("PERMIT:کارت بهداشت مکان").severity)
        assertEquals(Finding.Severity.HIGH, f("PERMIT:پروانه‌ی کسب").severity)
        assertEquals(5_000_000, f("BREAKDOWN:فر پیتزا").exposureHigh)
        assertTrue(f("PORTIONS_HEAVY").title.contains("۱۲٪"), f("PORTIONS_HEAVY").title)       // (30 + 10 + 20) ÷ 500 = 12 %
        assertEquals(Finding.Severity.MEDIUM, f("COMPLAINTS").severity)
        assertTrue(a.critical.size >= 2)

        // A reading entered by mistake is voided with its reason; the critical finding goes.
        core.assessments.voidRecord(VoidFieldRecord(id(), branch, warm, FieldRecord.Kind.TEMPERATURE, "اشتباه در ثبت"))
        assertTrue(assess().findings.none { it.key == "TEMPERATURE:فریزر" })
        assertTrue(code { rec(FieldRecord.Kind.FEEDBACK, week2, "", 6) }.startsWith("INVALID_INPUT"))
        assertTrue(code { rec(FieldRecord.Kind.TEMPERATURE, week2, "", 40, 0) }.startsWith("INVALID_INPUT"))

        // A cashier records customers' feedback, not temperatures.
        core.identity.createUser("cashier", "صندوقدار", Role.CASHIER, setOf(branch.branchId), "654321".toCharArray())
        core.identity.logout(); core.identity.login("cashier", "654321".toCharArray())
        rec(FieldRecord.Kind.FEEDBACK, end, "", 5)
        assertTrue(code { rec(FieldRecord.Kind.TEMPERATURE, end, "یخچال", 30, 0) }.startsWith("PERMISSION_DENIED"))
    }

    // ------------------------------------------------------------------ phase 5: what if

    @Test fun phase5_aDearerIngredientIsPricedToTheRialAndNothingIsWritten() {
        twoWeeks()
        val journals = core.journals.all().size
        val w = core.whatIfs.prices(branch, week2, end, mapOf(cheese to 1_500L))
        assertEquals(listOf("پیتزا", "پاستا"), w.dishes.map { it.name })                  // biggest loss first
        w.dishes.forEach { d ->
            assertEquals(Ratio.mulDiv(d.costNow, 11_500, 10_000), d.costNew)                // one ingredient: exactly 15 % dearer
            assertEquals(d.priceNow, d.priceNew)
            assertEquals(Ratio.mulDiv(d.costNew, d.priceNow, d.costNow), d.suggestedPrice)
        }
        val pizza = w.dishes.first()
        assertEquals(Quantity.units(12), pizza.portions)
        assertEquals(-12 * (pizza.costNew - pizza.costNow), pizza.profitChange)
        assertEquals(w.dishes.sumOf { it.profitChange }, w.profitChange)
        assertEquals(w.costNew - w.costNow, -w.profitChange)
        // The menu 10 % up on top: prices follow, the margin gains it.
        val both = core.whatIfs.prices(branch, week2, end, mapOf(cheese to 1_500L), w.dishes.associate { it.menuItemId to 1_000L })
        assertEquals(Ratio.mulDiv(pizza.priceNow, 11_000, 10_000), both.dishes.single { it.name == "پیتزا" }.priceNew)
        assertTrue(both.profitChange > w.profitChange)
        assertEquals(journals, core.journals.all().size)
    }

    // ------------------------------------------------------------------ phase 6: backups

    @Test fun phase6_backupAgeAndRestoreTestsComeFromTheAuditTrail() {
        twoWeeks()
        val owner = core.overview.me().userId
        assertNull(assess().kpi("D18.BACKUP").value)
        assertEquals(Finding.Severity.HIGH, assess().findings.single { it.key == "BACKUP_OLD" }.severity)
        core.authorizeBackup()
        assertEquals(0, assess().kpi("D18.BACKUP").value)
        assertTrue(assess().findings.none { it.key == "BACKUP_OLD" })
        core.recordBackupCheck(owner, false, "BACKUP_INTEGRITY:chain")
        assertEquals(Finding.Severity.CRITICAL, assess().findings.single { it.key == "BACKUP_UNREADABLE" }.severity)
        core.recordBackupCheck(owner, true, "")
        assertEquals(0, assess().kpi("D19.RESTORE").value)
        assertTrue(assess().findings.none { it.key == "BACKUP_UNREADABLE" })
    }

    // ------------------------------------------------------------------ phase 7: derived indicators

    @Test fun phase7_supplierDeliveryAndTheSameWeeksLastYear() {
        twoWeeks()
        assertNull(assess().kpi("D09.ONTIME").value)
        // Ordered 4 kg for Monday of week 2; 3 kg came a day late.
        val order = core.orders.create(CreatePurchaseOrder(id(), branch, supplier, kitchen, week2, week2.plusDays(1), listOf(OrderLine(cheese, Quantity.units(4), rial(4_000_000))))).resultId
        core.purchasing.postInvoice(PostPurchaseInvoice(id(), branch, supplier, "3", kitchen, week2.plusDays(2), week2.plusDays(2),
            listOf(InvoiceLine(cheese, Quantity.units(3), rial(12_000_000))), orderId = order))
        val a = assess()
        assertEquals(0, a.kpi("D09.ONTIME").value)
        assertEquals(0, a.kpi("D09.FULL").value)
        assertEquals(1, a.kpi("D09.FULL").denominator)
        // No sales a year earlier: unknown, never zero.
        assertNull(a.kpi("D20.YOY").value)
        assertTrue(a.dataRequests.any { it.dimension == Dimension.D20 && it.text.contains("یک سال") })
    }
}
