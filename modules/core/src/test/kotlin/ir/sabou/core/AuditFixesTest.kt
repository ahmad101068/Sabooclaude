package ir.sabou.core

import ir.sabou.inventory.ApproveStockCount
import ir.sabou.inventory.CountEntry
import ir.sabou.inventory.CreateItem
import ir.sabou.inventory.CreateLocation
import ir.sabou.inventory.LineReason
import ir.sabou.inventory.ReceiptLine
import ir.sabou.inventory.RecordOpeningStock
import ir.sabou.inventory.RecordWaste
import ir.sabou.inventory.StockUnit
import ir.sabou.inventory.SubmitStockCount
import ir.sabou.inventory.VarianceReason
import ir.sabou.inventory.WasteReason
import ir.sabou.kernel.BranchId
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.JalaliCalendar
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.payroll.SetClockPin
import ir.sabou.payroll.StatutoryPolicy
import ir.sabou.payroll.TaxBracket
import ir.sabou.persistence.JdbcSqlDatabase
import ir.sabou.platform.Role
import ir.sabou.platform.memory.InMemoryAnchorStore
import ir.sabou.treasury.Direction
import ir.sabou.treasury.ImportBankStatement
import ir.sabou.treasury.OpenTreasuryAccount
import ir.sabou.treasury.PaymentPurpose
import ir.sabou.treasury.ReceiptPurpose
import ir.sabou.treasury.RecordPayment
import ir.sabou.treasury.RecordReceipt
import ir.sabou.treasury.ReverseTreasuryDocument
import ir.sabou.treasury.StatementLineInput
import ir.sabou.treasury.TreasuryKind
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Regressions for the independent audit of 2026-10-13 (F01–F12): each test states the correct behaviour and failed
 * before its fix.
 */
class AuditFixesTest {
    private fun id() = GlobalId.new()
    private fun m(v: Long) = Money.of(v)
    private val d = BusinessDate(20_000)

    private fun run(block: (SabouCore, Scope.Branch, GlobalId) -> Unit) {
        val dir = Files.createTempDirectory("audit")
        val db = JdbcSqlDatabase(DriverManager.getConnection("jdbc:sqlite:${dir.resolve("x.db")}"))
        try {
            val c = SabouCore.open(db, InMemoryAnchorStore(), Clock { 1_700_000_000_000L })
            c.bootstrap("شعبه", "مالک", "owner", "123456".toCharArray())
            val b = Scope.Branch(c.overview.branches().single().id)
            val bank = c.treasury.openAccount(OpenTreasuryAccount(id(), b, "بانک", TreasuryKind.BANK)).resultId
            block(c, b, bank)
        } finally {
            db.close(); dir.toFile().deleteRecursively()
        }
    }

    private fun line(ref: String, date: BusinessDate = d, value: Long = 100, direction: Direction = Direction.RECEIPT) =
        StatementLineInput(date, ref, direction, m(value), null, ref)

    @Test fun f01RecordingALineMatchesThatLineAndCannotBeRepeated() = run { c, b, bank ->
        val s = c.bankStatements.import(ImportBankStatement(id(), b, bank, "two", listOf(line("A"), line("B")), m(200))).resultId
        c.bankRec.record(id(), s, 1, StatementAction.Receive(ReceiptPurpose.OTHER_INCOME))
        val after = c.bankRec.reconciliation(s)
        // B is the line that was recorded; A stays open.
        assertEquals(listOf(0), after.statementOnly.map { it.index })
        assertTrue(after.statement.lines[1].movementId != null)
        // B again: refused, nothing more in the books.
        assertEquals("INVALID_STATE:STATEMENT_LINE:ALREADY_MATCHED",
            assertFailsWith<DomainException> { c.bankRec.record(id(), s, 1, StatementAction.Receive(ReceiptPurpose.OTHER_INCOME)) }.error.code)
        assertEquals(100L, c.bankRec.reconciliation(s).bookBalance)
        // A retry of the same command (same id) answers with its first result and records nothing new.
        val cmd = id()
        val first = c.bankRec.record(cmd, s, 0, StatementAction.Receive(ReceiptPurpose.OTHER_INCOME))
        assertEquals(first.resultId, c.bankStatements.recordLine(ir.sabou.treasury.RecordStatementLine(cmd, b, s, 0, ir.sabou.treasury.StatementPosting.Receive(ReceiptPurpose.OTHER_INCOME))).resultId)
        assertEquals(200L, c.bankRec.reconciliation(s).bookBalance)
        assertEquals(0L, c.bankRec.reconciliation(s).unexplained)
    }

    @Test fun f02TheMonthChecklistIsOnlyForThoseWhoSeeEveryBranch() = run { c, b, _ ->
        val other = Scope.Branch(c.identity.createBranch("محرمانه ب"))
        c.inventory.createLocation(CreateLocation(id(), other, "انبار محرمانه ب"))
        c.identity.createUser("mgr", "مدیر الف", Role.MANAGER, setOf(b.branchId), "654321".toCharArray())
        c.identity.createUser("acc", "حسابدار الف", Role.ACCOUNTANT, setOf(b.branchId), "654321".toCharArray())
        val date = JalaliCalendar.of(d)
        c.identity.logout(); c.identity.login("mgr", "654321".toCharArray())
        assertTrue(assertFailsWith<DomainException> { c.closing.checklist(date.year, date.month) }.error.code.startsWith("PERMISSION_DENIED"))
        c.identity.logout(); c.identity.login("acc", "654321".toCharArray())
        assertEquals("INVALID_STATE:PERIOD:NEEDS_ALL_BRANCHES", assertFailsWith<DomainException> { c.closing.checklist(date.year, date.month) }.error.code)
        c.identity.logout(); c.identity.login("owner", "123456".toCharArray())
        c.closing.checklist(date.year, date.month)
    }

    @Test fun f03AnOpenTaxBandIsOnlyAllowedLast() {
        assertFailsWith<IllegalArgumentException> {
            StatutoryPolicy("x", d, d, 1000, 100, 0, 0, 0, m(10_000), 0, 1,
                listOf(TaxBracket(null, 0), TaxBracket(m(100), 1000), TaxBracket(null, 2000)))
        }
        val ok = StatutoryPolicy("x", d, d, 1000, 100, 0, 0, 0, m(10_000), 0, 1,
            listOf(TaxBracket(m(100), 0), TaxBracket(m(500), 1000), TaxBracket(null, 2000)))
        assertEquals(40L + 100L, ok.progressiveTax(m(1000)).rial)   // 400 × 10 % + 500 × 20 %
    }

    @Test fun f04AVeryUnevenHistoryStillForecastsWithALowOfZero() = run { c, _, _ ->
        val method = c.forecasts.javaClass.declaredMethods.single { it.name.startsWith("day-") || it.name == "day" }
        method.isAccessible = true
        val history = (1..8).associate { w -> d.plusDays(-7L * w) to if (w == 8) 1_000_000L else 1_000L }
        val f = method.invoke(c.forecasts, d.epochDay, history, null) as DayForecast
        assertEquals(0L, f.low)
        assertTrue(f.expected > 0 && f.high > f.expected)
    }

    @Test fun f05ALineWhoseMovementWasReversedIsOpenAgain() = run { c, b, bank ->
        val receipt = c.treasury.receipt(RecordReceipt(id(), b, bank, ReceiptPurpose.OTHER_INCOME, m(100), d, "receipt")).resultId
        val s = c.bankStatements.import(ImportBankStatement(id(), b, bank, "one", listOf(line("A")), m(100))).resultId
        assertEquals(1, c.bankRec.reconciliation(s).matched)
        c.treasury.reverse(ReverseTreasuryDocument(id(), b, "TREASURY_RECEIPT", receipt, d, "correction"))
        val r = c.bankRec.reconciliation(s)
        assertEquals(0, r.matched)
        assertEquals(listOf(0), r.statementOnly.map { it.index })
        assertEquals(0L, r.unexplained)          // the line explains itself: the bank has it, the books no longer do
        assertEquals(1, c.bankRec.statements(bank).single().unmatched.size)
        // It can be recorded (or matched) again.
        c.bankRec.record(id(), s, 0, StatementAction.Receive(ReceiptPurpose.OTHER_INCOME))
        assertEquals(1, c.bankRec.reconciliation(s).matched)
    }

    @Test fun f06AnOverlappingFileAddsOnlyItsNewLines() = run { c, b, bank ->
        c.bankStatements.import(ImportBankStatement(id(), b, bank, "first", listOf(line("A"), line("B")), m(200)))
        // Wholly inside the first: already imported.
        assertEquals("INVALID_STATE:BANK_STATEMENT:ALREADY_IMPORTED",
            assertFailsWith<DomainException> { c.bankStatements.import(ImportBankStatement(id(), b, bank, "again", listOf(line("A")), m(100))) }.error.code)
        // Partly new: only C comes in.
        val s2 = c.bankStatements.import(ImportBankStatement(id(), b, bank, "next", listOf(line("B"), line("C", d.plusDays(1))), m(300))).resultId
        assertEquals(listOf("C"), c.bankRec.reconciliation(s2).statement.lines.map { it.reference })
        // Two genuinely equal transactions in a later file are both kept when only one was imported before.
        val s3 = c.bankStatements.import(ImportBankStatement(id(), b, bank, "twins", listOf(line("C", d.plusDays(1)), line("C", d.plusDays(1))), m(400))).resultId
        assertEquals(1, c.bankRec.reconciliation(s3).statement.lines.size)
    }

    @Test fun f07AnOldUnclearedPaymentIsOutstandingNotUnexplained() = run { c, b, bank ->
        c.treasury.receipt(RecordReceipt(id(), b, bank, ReceiptPurpose.OWNER_CAPITAL, m(1000), d.plusDays(-20), "capital"))
        c.bankStatements.import(ImportBankStatement(id(), b, bank, "last month", listOf(line("K", d.plusDays(-20), 1000)), m(1000)))
        c.treasury.payment(RecordPayment(id(), b, bank, PaymentPurpose.OTHER_EXPENSE, m(100), d.plusDays(-10), "cheque not yet cleared"))
        c.treasury.receipt(RecordReceipt(id(), b, bank, ReceiptPurpose.OTHER_INCOME, m(1), d, "deposit"))
        val s = c.bankStatements.import(ImportBankStatement(id(), b, bank, "current", listOf(line("C", d, 1)), m(1001))).resultId
        val r = c.bankRec.reconciliation(s)
        assertEquals(listOf(100L), r.bookOnly.map { it.movement.amount.rial })
        assertEquals(0L, r.unexplained)
    }

    @Test fun f08AReversedDepreciationRunDoesNotCountForTheMonth() = run { c, b, _ ->
        val date = JalaliCalendar.of(d)
        val end = JalaliCalendar.date(date.year, date.month, JalaliCalendar.monthLength(date.year, date.month))
        c.fixedAssets.acquire(ir.sabou.assets.AcquireAsset(id(), b, "یخچال", "تجهیزات", m(100_000), m(0), d,
            ir.sabou.assets.DepreciationMethod.STRAIGHT_LINE, 60, null, ir.sabou.assets.Funding.Existing(m(0), null)))
        val run = c.fixedAssets.depreciate(ir.sabou.assets.RunDepreciation(id(), b, end)).resultId
        assertEquals(CloseCheck.Severity.OK, c.closing.checklist(date.year, date.month).checks.single { it.key == "DEPRECIATION" }.severity)
        c.fixedAssets.reverseRun(ir.sabou.assets.ReverseDepreciationRun(id(), b, run, end, "correction"))
        assertEquals(CloseCheck.Severity.WARN, c.closing.checklist(date.year, date.month).checks.single { it.key == "DEPRECIATION" }.severity)
    }

    @Test fun f09ACountOvertakenByLaterUsageIsShownStaleAndRefusedClearly() = run { c, b, _ ->
        val loc = c.overview.locations(b).single().id
        val item = c.inventory.createItem(CreateItem(id(), "کالا", StockUnit.KILOGRAM, Quantity.ZERO)).resultId
        c.inventory.openingStock(RecordOpeningStock(id(), b, loc, listOf(ReceiptLine(item, Quantity.units(10), m(1000))), d))
        val count = c.counts.submit(SubmitStockCount(id(), b, loc, d, listOf(CountEntry(item, Quantity.units(2))))).resultId
        c.inventory.waste(RecordWaste(id(), b, loc, item, Quantity.units(5), WasteReason.SPOILAGE, "", d))
        c.identity.createUser("mgr", "مدیر", Role.MANAGER, setOf(b.branchId), "654321".toCharArray())
        c.identity.logout(); c.identity.login("mgr", "654321".toCharArray())
        assertEquals(listOf("کالا"), c.overview.stockCount(count).stale)
        val e = assertFailsWith<DomainException> {
            c.counts.approve(ApproveStockCount(id(), b, count, mapOf(item to LineReason(VarianceReason.MISSING))))
        }
        assertEquals("INVALID_STATE:STOCK_COUNT:STALE", e.error.code)
        assertTrue(Messages.of(e).contains("دوباره بشمارید"))
        assertEquals(Quantity.units(5), c.overview.stock(loc).single().quantity)
    }

    @Test fun f10FingerprintsCoverEveryEffectiveField() {
        val b = Scope.Branch(BranchId(id()))
        val a = ImportBankStatement(id(), b, id(), "x", listOf(line("A")), m(100))
        assertNotEquals(a.fingerprint(), a.copy(lines = listOf(a.lines.single().copy(description = "different"))).fingerprint())
        assertNotEquals(a.fingerprint(), a.copy(lines = listOf(a.lines.single().copy(balance = m(999)))).fingerprint())
        val pin = SetClockPin(id(), b, id(), "123456")
        assertNotEquals(pin.fingerprint(), pin.copy(pin = "654321").fingerprint())
        assertEquals(pin.fingerprint(), pin.copy().fingerprint())
        assertTrue("123456" !in pin.fingerprint())
    }

    @Test fun f12ProfitTotalsRefuseToOverflow() {
        assertFailsWith<ArithmeticException> { PnlTotals(Long.MAX_VALUE, 0, 0) + PnlTotals(1, 0, 0) }
        assertFailsWith<ArithmeticException> { PnlTotals(Long.MIN_VALUE + 1, Long.MAX_VALUE, 0).grossProfit }
    }
}
