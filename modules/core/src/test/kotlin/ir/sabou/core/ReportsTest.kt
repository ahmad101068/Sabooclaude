package ir.sabou.core

import ir.sabou.inventory.CountLine
import ir.sabou.inventory.CreateItem
import ir.sabou.inventory.DefineMenuItem
import ir.sabou.inventory.PostStockCount
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
import ir.sabou.purchasing.InvoiceLine
import ir.sabou.purchasing.PostPurchaseInvoice
import ir.sabou.purchasing.RegisterSupplier
import ir.sabou.sales.CloseSalesDay
import ir.sabou.sales.PostDailySale
import ir.sabou.sales.SaleLine
import ir.sabou.sales.SaveSaleDraft
import ir.sabou.sales.Settlement
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.sql.DriverManager
import java.util.zip.ZipInputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportsTest {
    private val dir = Files.createTempDirectory("sabou-reports")
    private val db = JdbcSqlDatabase(DriverManager.getConnection("jdbc:sqlite:${dir.resolve("r.db")}"))
    private val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { 1_700_000_000_000L }, emptyList(), null)
    private val day = BusinessDate(20_000)
    private fun id() = GlobalId.new()
    private fun rial(v: Long) = Money.of(v)

    @AfterTest fun close() { db.close(); dir.toFile().deleteRecursively() }

    private lateinit var branch: Scope.Branch
    private var kitchen: GlobalId = GlobalId.new()
    private var cheese: GlobalId = GlobalId.new()

    /** 10 kg cheese at 3,000,000/kg; 10 pizzas (0.2 kg each) sold for 5,000,000 each; 0.5 kg wasted; count finds 0.5 kg missing. */
    private fun businessDay() {
        core.bootstrap("شعبه ونک", "مالک", "owner", "123456".toCharArray())
        branch = Scope.Branch(core.overview.branches().single().id)
        kitchen = core.overview.locations(branch).single().id
        val cash = core.overview.treasury().single().account.id
        cheese = core.inventory.createItem(CreateItem(id(), "پنیر", StockUnit.KILOGRAM, Quantity.units(2))).resultId
        val pizza = core.inventory.defineMenuItem(DefineMenuItem(id(), "پیتزا")).resultId
        core.inventory.publishRecipe(PublishRecipe(id(), pizza, BusinessDate(19_000), listOf(RecipeLine(cheese, Quantity.of(200_000)))))
        val supplier = core.purchasing.registerSupplier(RegisterSupplier(id(), "لبنیات", "021")).resultId
        core.purchasing.postInvoice(PostPurchaseInvoice(id(), branch, supplier, "1", kitchen, day, day, listOf(InvoiceLine(cheese, Quantity.units(10), rial(30_000_000)))))
        val sale = core.salesOps.saveDraft(SaveSaleDraft(id(), branch, day, kitchen, listOf(SaleLine(pizza, Quantity.units(10), rial(50_000_000))),
            rial(0), rial(0), rial(0), listOf(Settlement.Liquid(cash, rial(50_000_000))), guests = 25, transactions = 8)).resultId
        core.salesOps.post(PostDailySale(id(), branch, sale))
        core.inventory.waste(RecordWaste(id(), branch, kitchen, cheese, Quantity.of(500_000), WasteReason.SPOILAGE, "", day))
        core.inventory.count(PostStockCount(id(), branch, kitchen, listOf(CountLine(cheese, Quantity.units(7))), day))
        core.salesOps.closeDay(CloseSalesDay(id(), branch, day, rial(49_000_000)))
    }

    @Test fun profitAndLossAndCostRatiosComeStraightFromTheBooks() {
        businessDay()
        val p = core.reports.profitAndLoss(day, day)
        assertEquals(50_000_000, p.totals.revenue)
        assertEquals(6_000_000, p.totals.cogs)                       // 2 kg × 3,000,000
        assertEquals(3_000_000, p.totals.expenses)                   // waste 1,500,000 + count loss 1,500,000
        assertEquals(41_000_000, p.totals.profit)
        assertEquals(listOf("شعبه ونک" to p.totals), p.byBranch)
        assertEquals(listOf(day to p.totals), p.byDay)
        assertEquals(1_800, p.ratios.foodBp)                         // 9,000,000 / 50,000,000
        assertEquals(0, p.ratios.laborBp)
        // Drill-down: the postings behind the cost of sales.
        val detail = core.reports.ledgerDetail(StandardAccounts.COGS, day, day)
        assertEquals(6_000_000, detail.sumOf { it.debit - it.credit })
        // A cashier sees no financial statements.
        core.identity.createUser("cashier", "صندوقدار", Role.CASHIER, setOf(branch.branchId), "654321".toCharArray())
        core.identity.logout(); core.identity.login("cashier", "654321".toCharArray())
        assertTrue(assertFailsWith<DomainException> { core.reports.profitAndLoss(day, day) }.error.code.startsWith("PERMISSION_DENIED"))
    }

    @Test fun productMixFlashAndUsage() {
        businessDay()
        val mix = core.reports.productMix(branch, day, day)
        val row = mix.rows.single()
        assertEquals(Quantity.units(10), row.portions)
        assertEquals(10_000, row.shareBp)
        assertEquals(rial(5_000_000), row.averagePrice)
        assertEquals(rial(600_000), row.unitCost)                    // 0.2 kg at the current average 3,000,000/kg
        assertEquals(4_400_000, row.unitMargin)
        assertEquals(1_200, row.costBp)

        val flash = core.reports.dayFlash(branch, day)
        assertTrue(flash.posted && flash.closed)
        assertEquals(rial(2_000_000), flash.perGuest)
        assertEquals(rial(6_250_000), flash.perTransaction)
        assertEquals(rial(30_000_000), flash.purchases)
        assertEquals(rial(1_500_000), flash.waste)
        assertEquals(-1_000_000, flash.cashDifference)               // counted 49 of 50 million
        assertEquals(1_200, flash.foodCostBp)

        val usage = core.reports.actualVsTheoretical(branch, null, day, day)
        val u = usage.rows.single()
        assertEquals(10_000_000, u.purchases.quantity)
        assertEquals(2_000_000, u.theoretical.quantity)
        assertEquals(500_000, u.waste.quantity)
        assertEquals(500_000, u.unexplained.quantity)
        assertEquals(1_500_000, u.unexplained.value)
        assertEquals(7_000_000, u.closing.quantity)
        assertEquals(3_000_000, u.actual.quantity)
        assertEquals(6_667, u.efficiencyBp)
        // The next day opens with this day's closing and nothing used.
        val next = core.reports.actualVsTheoretical(branch, kitchen, day.plusDays(1), day.plusDays(1)).rows.single()
        assertEquals(7_000_000, next.opening.quantity); assertEquals(0, next.actual.quantity); assertNull(next.efficiencyBp)
    }

    @Test fun everyReportExportsToAValidRightToLeftWorkbook() {
        businessDay()
        val tables = ReportTables.profitAndLoss(core.reports.profitAndLoss(day, day), "همه شعب") +
            ReportTables.productMix(core.reports.productMix(branch, day, day)) +
            ReportTables.dayFlash(core.reports.dayFlash(branch, day)) +
            ReportTables.usage(core.reports.actualVsTheoretical(branch, null, day, day)) +
            ReportTables.trialBalance(core.overview.trialBalance(), day) +
            ReportTables.stock("آشپزخانه", core.overview.stock(kitchen).map { b -> core.overview.items().first { it.id == b.itemId } to b }, day) +
            ReportTables.invoices(core.overview.invoices()) + ReportTables.suppliers(core.overview.suppliers()) +
            ReportTables.receivables(core.overview.openReceivables()) +
            core.overview.treasury().first().let { acc -> ReportTables.accountHistory(acc, core.overview.accountHistory(acc.account.id).second) } +
            ReportTables.attendance(core.reports.attendance(branch, day, day), "شعبه", day, day) +
            samplePayroll()
        val bytes = Xlsx.write(tables)
        val entries = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            generateSequence { z.nextEntry }.forEach { e -> entries[e.name] = z.readBytes().toString(Charsets.UTF_8) }
        }
        assertEquals(tables.size, entries.keys.count { it.startsWith("xl/worksheets/sheet") })
        assertTrue(entries.getValue("xl/workbook.xml").contains("سود و زیان"))
        val pnl = entries.getValue("xl/worksheets/sheet1.xml")
        assertTrue(pnl.contains("""rightToLeft="1""""))
        assertTrue(pnl.contains("<v>5000000</v>"))                   // 50,000,000 rial = 5,000,000 Toman, as a number
        assertTrue(entries.getValue("[Content_Types].xml").contains("sheet${tables.size}.xml"))
        // Every sheet is well-formed XML.
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        entries.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.values.forEach {
            factory.newDocumentBuilder().parse(ByteArrayInputStream(it.toByteArray()))
        }
        assertEquals(listOf("a", "A 2", "b"), Xlsx.sheetNames(listOf("a", "A", "b")))
        assertEquals("۱۲٫۳٪", Fa.percent(1_234))
        assertEquals("۱۸٪", Fa.percent(1_800))
    }

    private fun samplePayroll(): List<ReportTable> {
        val policy = ir.sabou.payroll.StatutoryPolicy("T", BusinessDate(19_000), BusinessDate(21_000), 11_520, 140, 700, 2_000, 300, rial(300_000_000), 2, 7,
            listOf(ir.sabou.payroll.TaxBracket(null, 0)))
        val e = id()
        val slip = policy.calculate(e, rial(30_000_000), 0, 60, payableDays = 20)
        val run = ir.sabou.payroll.PayrollRun(id(), branch, day, day.plusDays(29), "T", listOf(slip), ir.sabou.payroll.RunStatus.DRAFT, id(), null, null)
        return listOf(ReportTables.payrollRun(run, mapOf(e to "سرآشپز"), "شعبه"), ReportTables.payslip(run, slip, "سرآشپز", "شعبه"))
    }

    @Test fun aCashierSeesSalesFiguresButNoCostsPurchasesOrWaste() {
        businessDay()
        core.identity.createUser("cashier1", "صندوقدار", Role.CASHIER, setOf(branch.branchId), "654321".toCharArray())
        core.identity.logout()
        core.identity.login("cashier1", "654321".toCharArray())
        val mix = core.reports.productMix(branch, day, day)
        assertEquals(50_000_000, mix.gross.rial)
        assertNull(mix.rows.single().unitCost)
        val flash = core.reports.dayFlash(branch, day)
        assertEquals(50_000_000, flash.payable.rial)
        assertNull(flash.purchases); assertNull(flash.waste)
    }
}
