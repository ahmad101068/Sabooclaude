package ir.sabou.core

import ir.sabou.inventory.CreateItem
import ir.sabou.inventory.CreateLocation
import ir.sabou.inventory.DefineMenuItem
import ir.sabou.inventory.PublishRecipe
import ir.sabou.inventory.RecipeLine
import ir.sabou.inventory.StockBalance
import ir.sabou.inventory.StockUnit
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.ledger.StandardAccounts
import ir.sabou.payroll.ApprovePayroll
import ir.sabou.payroll.CalculatePayroll
import ir.sabou.payroll.DefinePayrollPolicy
import ir.sabou.payroll.PaySalary
import ir.sabou.payroll.RecordAttendance
import ir.sabou.payroll.RegisterEmployee
import ir.sabou.payroll.StatutoryPolicy
import ir.sabou.payroll.TaxBracket
import ir.sabou.persistence.JdbcSqlDatabase
import ir.sabou.platform.Role
import ir.sabou.platform.StartupVerdict
import ir.sabou.platform.memory.InMemoryAnchorStore
import ir.sabou.purchasing.ImmediatePayment
import ir.sabou.purchasing.InvoiceLine
import ir.sabou.purchasing.PostPurchaseInvoice
import ir.sabou.purchasing.RegisterSupplier
import ir.sabou.sales.CollectReceivable
import ir.sabou.sales.CustomerType
import ir.sabou.sales.PostDailySale
import ir.sabou.sales.RegisterCustomer
import ir.sabou.sales.SaleLine
import ir.sabou.sales.SaveSaleDraft
import ir.sabou.sales.Settlement
import ir.sabou.treasury.OpenTreasuryAccount
import ir.sabou.treasury.ReceiptPurpose
import ir.sabou.treasury.RecordReceipt
import ir.sabou.treasury.TreasuryKind
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * End-to-end on a real SQLite file: the whole composition root, every SQL store, restart,
 * rollback, idempotency, and the books-vs-sub-ledger invariants.
 */
class SabouCoreTest {
    private val dir: Path = Files.createTempDirectory("sabou")
    private val file = dir.resolve("sabou.db")
    private val anchors = InMemoryAnchorStore()
    private var now = 1_700_000_000_000L
    private val clock = Clock { now }
    private val day = BusinessDate(20_000)
    private val open = mutableListOf<JdbcSqlDatabase>()
    private val policy = StatutoryPolicy(
        version = "TEST", from = BusinessDate(19_000), to = BusinessDate(21_000), standardMonthlyMinutes = 11_520,
        overtimeMultiplierPercent = 140, employeeInsuranceBp = 700, employerInsuranceBp = 2_000, unemploymentInsuranceBp = 300,
        maxInsurableMonthly = Money.of(300_000_000), insuranceTaxExemptNumerator = 2, insuranceTaxExemptDenominator = 7,
        taxBrackets = listOf(TaxBracket(Money.of(10_000_000), 0), TaxBracket(Money.of(20_000_000), 1_000), TaxBracket(null, 2_000)),
    )

    private fun boot(path: Path = file, newEpoch: String? = null): SabouCore {
        val db = JdbcSqlDatabase(DriverManager.getConnection("jdbc:sqlite:$path")).also { open += it }
        return SabouCore.open(db, anchors, clock, emptyList(), newEpoch)
    }

    @AfterTest fun close() { open.forEach { it.close() }; dir.toFile().deleteRecursively() }

    private fun rial(v: Long) = Money.of(v)
    private fun id() = GlobalId.new()

    private class World(val branch: Scope.Branch, val cash: GlobalId, val kitchen: GlobalId, val cheese: GlobalId, val pizza: GlobalId, val customer: GlobalId)

    private fun setUp(core: SabouCore): World {
        core.bootstrap("شعبه ونک", "مالک", "owner", "123456".toCharArray())
        val branch = Scope.Branch(core.overview.branches().single().id)
        val cash = core.overview.treasury().single().account.id
        core.treasury.receipt(RecordReceipt(id(), branch, cash, ReceiptPurpose.OWNER_CAPITAL, rial(200_000_000), day, "آورده"))
        val cheese = core.inventory.createItem(CreateItem(id(), "پنیر", StockUnit.KILOGRAM, Quantity.units(2))).resultId
        val kitchen = core.overview.locations(branch).single().id
        val pizza = core.inventory.defineMenuItem(DefineMenuItem(id(), "پیتزا")).resultId
        core.inventory.publishRecipe(PublishRecipe(id(), pizza, BusinessDate(19_000), listOf(RecipeLine(cheese, Quantity.of(200_000)))))
        val supplier = core.purchasing.registerSupplier(RegisterSupplier(id(), "لبنیات پگاه", "021")).resultId
        core.purchasing.postInvoice(
            PostPurchaseInvoice(id(), branch, supplier, "۱۲۳", kitchen, day, day.plusDays(30),
                listOf(InvoiceLine(cheese, Quantity.units(10), rial(30_000_000))), ImmediatePayment(cash, rial(10_000_000))),
        )
        val customer = core.salesOps.registerCustomer(RegisterCustomer(id(), branch, "شرکت آرین", CustomerType.COMPANY, "021", rial(50_000_000))).resultId
        return World(branch, cash, kitchen, cheese, pizza, customer)
    }

    /** Every control account equals its sub-ledger and the trial balance is zero. */
    private fun assertBooksAgree(core: SabouCore, w: World) {
        val total = core.accounts.all().sumOf { core.journals.netDebit(it.code, null, null) }
        assertEquals(0, total, "trial balance")
        val stockValue = core.locations.all().sumOf { l -> core.stock.balances(l.id).sumOf { it.value.rial } }
        assertEquals(stockValue, core.ledger.balance(StandardAccounts.INVENTORY).rial, "inventory GL = stock")
        assertEquals(core.overview.payables().rial, -core.ledger.balance(StandardAccounts.PAYABLE).rial, "AP GL = invoices")
        assertEquals(core.overview.receivables().rial, core.ledger.balance(StandardAccounts.RECEIVABLE).rial, "AR GL = receivables")
        assertEquals(core.treasuryGateway.balance(w.cash), core.ledger.balance(StandardAccounts.CASH, w.branch).rial, "cash GL = cash box")
        assertEquals(0, core.ledger.balance(StandardAccounts.SALES_CLEARING).rial, "sales clearing")
    }

    @Test fun aFullBusinessDayPersistsAcrossRestartWithBooksInAgreement() {
        val core = boot()
        assertEquals(StartupVerdict.Healthy, core.verifyStartup())
        val w = setUp(core)
        val draft = core.salesOps.saveDraft(
            SaveSaleDraft(id(), w.branch, day, w.kitchen, listOf(SaleLine(w.pizza, Quantity.units(20), rial(12_000_000))),
                rial(1_000_000), rial(0), rial(990_000),
                listOf(Settlement.Liquid(w.cash, rial(8_990_000)), Settlement.Credit(w.customer, rial(3_000_000), day.plusDays(10)))),
        ).resultId
        core.salesOps.post(PostDailySale(id(), w.branch, draft))
        val receivable = core.sales.receivablesOfSale(draft).single()
        core.salesOps.collect(CollectReceivable(id(), w.branch, receivable.id, w.cash, rial(1_000_000), day))
        assertBooksAgree(core, w)

        // Restart on the same file: everything is still there and the audit chain verifies.
        val again = boot()
        assertEquals(StartupVerdict.Healthy, again.verifyStartup())
        assertEquals(core.epoch, again.epoch)
        again.identity.login("owner", "123456".toCharArray())
        assertEquals(Quantity.units(6), again.inventoryGateway.balance(w.cheese, w.kitchen).quantity)   // 10 − 20 × 0.2
        assertEquals(18_000_000, again.inventoryGateway.balance(w.cheese, w.kitchen).value.rial)        // 6 kg at the 3,000,000/kg average
        assertEquals(200_000_000 - 10_000_000 + 8_990_000 + 1_000_000, again.treasuryGateway.balance(w.cash))
        assertEquals(2_000_000, again.overview.receivables().rial)
        assertEquals(20_000_000, again.overview.payables().rial)
        assertTrue(again.overview.lowStock().isEmpty())
        assertBooksAgree(again, w)
    }

    @Test fun aFailedCommandLeavesNoTraceAndAReplayIsRecognisedAfterRestart() {
        val core = boot()
        val w = setUp(core)
        val journalsBefore = core.journals.all().size
        val auditBefore = core.db.query("SELECT COUNT(*) AS n FROM audit_events").single().long("n")
        val tooBig = core.salesOps.saveDraft(
            SaveSaleDraft(id(), w.branch, day, w.kitchen, listOf(SaleLine(w.pizza, Quantity.units(60), rial(6_000_000))),
                rial(0), rial(0), rial(0), listOf(Settlement.Liquid(w.cash, rial(6_000_000)))),
        ).resultId
        val auditWithDraft = auditBefore + 1
        val e = assertFailsWith<DomainException> { core.salesOps.post(PostDailySale(id(), w.branch, tooBig)) }   // needs 12 kg, has 10
        assertTrue(e.error.code.startsWith("INSUFFICIENT_STOCK"))
        assertEquals(journalsBefore, core.journals.all().size)
        assertEquals(auditWithDraft, core.db.query("SELECT COUNT(*) AS n FROM audit_events").single().long("n"))
        assertEquals(Quantity.units(10), core.inventoryGateway.balance(w.cheese, w.kitchen).quantity)
        assertEquals(StartupVerdict.Healthy, core.verifyStartup())

        val receiptCommand = RecordReceipt(id(), w.branch, w.cash, ReceiptPurpose.OTHER_INCOME, rial(500_000), day, "انعام")
        val first = core.treasury.receipt(receiptCommand)
        val again = boot()
        again.identity.login("owner", "123456".toCharArray())
        val replay = again.treasury.receipt(receiptCommand)
        assertTrue(replay.replayed); assertEquals(first.resultId, replay.resultId)
        assertEquals("IDEMPOTENCY_CONFLICT", assertFailsWith<DomainException> { again.treasury.receipt(receiptCommand.copy(amount = rial(600_000))) }.error.code)
    }

    @Test fun payrollRunsEndToEndWithSegregationOfDuties() {
        val core = boot()
        val w = setUp(core)
        core.payrollPolicies.define(DefinePayrollPolicy(id(), policy))
        assertEquals(listOf(policy), boot().payrollPolicies.policies())     // stored and read back exactly
        core.identity.createUser("acc", "حسابدار", Role.ACCOUNTANT, setOf(w.branch.branchId), "654321".toCharArray())
        val chef = core.payroll.registerEmployee(RegisterEmployee(id(), w.branch, "سرآشپز", "0084575948", rial(30_000_000))).resultId
        core.payroll.recordAttendance(RecordAttendance(id(), w.branch, chef, BusinessDate(20_001), 480, 0, 0))
        core.identity.login("acc", "654321".toCharArray())
        val run = core.payroll.calculate(CalculatePayroll(id(), w.branch, BusinessDate(20_000), BusinessDate(20_029))).resultId
        // The accountant who calculated cannot approve; the owner (a different person) can.
        assertEquals("PERMISSION_DENIED:PAYROLL_APPROVE", assertFailsWith<DomainException> { core.payroll.approve(ApprovePayroll(id(), w.branch, run)) }.error.code)
        core.identity.login("owner", "123456".toCharArray())
        core.payroll.approve(ApprovePayroll(id(), w.branch, run))
        val net = core.payrollStore.run(run)!!.payslips.single().net
        core.payroll.pay(PaySalary(id(), w.branch, run, chef, w.cash, net, BusinessDate(20_030)))
        assertEquals(0, core.ledger.balance(StandardAccounts.PAYROLL_PAYABLE, w.branch).rial)
        assertBooksAgree(core, w)
        assertEquals(StartupVerdict.Healthy, boot().verifyStartup())
    }

    @Test fun tamperingAndRollbackAreDetectedAtStartup() {
        val core = boot()
        val w = setUp(core)
        assertEquals(StartupVerdict.Healthy, core.verifyStartup())
        // Keep an old copy, then continue working and pass another startup (new checkpoint).
        val old = dir.resolve("old.db")
        core.db.execute("PRAGMA wal_checkpoint(FULL)")
        Files.copy(file, old, StandardCopyOption.REPLACE_EXISTING)
        core.treasury.receipt(RecordReceipt(id(), w.branch, w.cash, ReceiptPurpose.OTHER_INCOME, rial(1), day, "x"))
        assertEquals(StartupVerdict.Healthy, boot().verifyStartup())
        // Swapping in the old copy is detected.
        assertIs<StartupVerdict.RollbackDetected>(boot(old).verifyStartup())

        // Editing an audit row behind the app's back (dropping the trigger first): an edit after the last
        // anchored checkpoint is caught at startup; an older one is caught by the full verification.
        core.db.execute("DROP TRIGGER audit_events_no_update")
        val last = core.db.query("SELECT MAX(position) AS p FROM audit_events").single().long("p")
        core.db.execute("UPDATE audit_events SET detail = 'forged' WHERE position = 2")
        assertEquals(StartupVerdict.Healthy, boot().verifyStartup())
        val full = core.verifyAuditFull()
        assertIs<StartupVerdict.RollbackDetected>(full)
        assertTrue(full.detail.contains("AUDIT_EVENT_TAMPERED"))
        core.treasury.receipt(RecordReceipt(id(), w.branch, w.cash, ReceiptPurpose.OTHER_INCOME, rial(2), day, "y"))
        core.db.execute("UPDATE audit_events SET detail = 'forged' WHERE position = ?", last + 1)
        assertIs<StartupVerdict.RollbackDetected>(boot().verifyStartup())
    }

    @Test fun aFactoryResetAnnouncedBeforehandStartsCleanButASilentSwapDoesNot() {
        val core = boot()
        setUp(core)
        assertEquals(StartupVerdict.Healthy, core.verifyStartup())
        // A database appearing without an announcement is a rollback/swap.
        assertIs<StartupVerdict.RollbackDetected>(boot(dir.resolve("other.db")).verifyStartup())
        // The legitimate path: announce the new epoch, then create the new database with it.
        val epoch = SabouCore.newEpoch()
        core.acceptReplacement(epoch, "FACTORY_RESET")
        val fresh = boot(dir.resolve("fresh.db"), epoch)
        assertEquals(StartupVerdict.Healthy, fresh.verifyStartup())
        assertTrue(fresh.identity.needsBootstrap())
        // The replaced database is no longer accepted.
        assertIs<StartupVerdict.RollbackDetected>(boot().verifyStartup())
    }

    @Test fun stockCompareAndSetRejectsAStaleWrite() {
        val core = boot()
        val w = setUp(core)
        val current = core.stock.balance(w.cheese, w.kitchen)
        val stale = StockBalance(w.cheese, w.kitchen, Quantity.units(9), rial(27_000_000))
        assertEquals("CONCURRENT_MODIFICATION:STOCK_BALANCE",
            assertFailsWith<DomainException> { core.stock.replace(stale, current) }.error.code)
        assertEquals(current, core.stock.balance(w.cheese, w.kitchen))
    }

    @Test fun readsNeedThePermissionAndStayInsideTheGrantedBranches() {
        val core = boot()
        val w = setUp(core)
        val other = Scope.Branch(core.identity.createBranch("شعبه تجریش"))
        core.treasury.openAccount(OpenTreasuryAccount(id(), other, "صندوق تجریش", TreasuryKind.CASH))
        core.treasury.receipt(RecordReceipt(id(), other, core.overview.treasury().first { it.account.scope == other }.account.id,
            ReceiptPurpose.OWNER_CAPITAL, rial(7_000_000), day, "آورده"))
        core.identity.createUser("cashier", "صندوقدار", Role.CASHIER, setOf(w.branch.branchId), "111111".toCharArray())
        core.identity.createUser("mgr", "مدیر", Role.MANAGER, setOf(w.branch.branchId), "222222".toCharArray())
        core.identity.login("cashier", "111111".toCharArray())
        fun denied(block: () -> Unit) = assertTrue(assertFailsWith<DomainException> { block() }.error.code.startsWith("PERMISSION_DENIED"))
        denied { core.overview.employees(w.branch) }
        denied { core.overview.payroll(w.branch) }
        denied { core.overview.invoices() }
        denied { core.overview.trialBalance() }
        denied { core.overview.stock(w.kitchen) }
        assertTrue(assertFailsWith<DomainException> { core.overview.salesDay(other, day) }.error.code.startsWith("SCOPE_DENIED"))
        core.identity.login("mgr", "222222".toCharArray())
        // The manager's trial balance covers only their branch: capital of the other branch is not in it.
        val capital = core.overview.trialBalance().first { it.first.code == StandardAccounts.CAPITAL }.second
        assertEquals(-core.ledger.balance(StandardAccounts.CAPITAL, w.branch).rial, -capital)
        assertEquals(0, core.overview.trialBalance().sumOf { it.second })
        assertTrue(assertFailsWith<DomainException> { core.overview.employees(other) }.error.code.startsWith("SCOPE_DENIED"))
        assertEquals("PERMISSION_DENIED:BACKUP_CREATE", assertFailsWith<DomainException> { core.authorizeBackup() }.error.code)
        assertEquals("PERMISSION_DENIED:FACTORY_RESET", assertFailsWith<DomainException> { core.acceptReplacement(SabouCore.newEpoch(), "FACTORY_RESET") }.error.code)
    }

    @Test fun bootstrapIsAllOrNothing() {
        val core = boot()
        assertEquals("INVALID_INPUT:name", assertFailsWith<DomainException> { core.bootstrap("x", "مالک", "owner", "123456".toCharArray()) }.error.code)
        assertTrue(core.identity.needsBootstrap())
        assertEquals("INVALID_INPUT:pin", assertFailsWith<DomainException> { core.bootstrap("شعبه ونک", "مالک", "owner", "12".toCharArray()) }.error.code)
        assertTrue(core.identity.needsBootstrap())
        core.bootstrap("شعبه ونک", "مالک", "owner", "123456".toCharArray())
        assertEquals(1, core.overview.branches().size)
        assertEquals(1, core.overview.treasury().size)
        assertEquals(1, core.overview.locations(Scope.Branch(core.overview.branches().single().id)).size)
    }

    @Test fun localEventsAreKeptForALimitedTime() {
        val core = boot()
        setUp(core)
        assertTrue(core.db.query("SELECT COUNT(*) AS n FROM domain_events").single().long("n") > 0)
        now += (SabouCore.LOCAL_EVENT_DAYS + 1) * 86_400_000L
        boot().also { it.identity.login("owner", "123456".toCharArray()) }.verifyStartup()
        assertEquals(0, core.db.query("SELECT COUNT(*) AS n FROM domain_events").single().long("n"))
    }

    @Test fun readsRespectBranchGrants() {
        val core = boot()
        val w = setUp(core)
        val other = Scope.Branch(core.identity.createBranch("شعبه تجریش"))
        core.treasury.openAccount(OpenTreasuryAccount(id(), other, "صندوق تجریش", TreasuryKind.CASH))
        core.identity.createUser("cashier", "صندوقدار", Role.CASHIER, setOf(w.branch.branchId), "111111".toCharArray())
        core.identity.login("cashier", "111111".toCharArray())
        assertEquals(listOf("صندوق شعبه ونک"), core.overview.treasury().map { it.account.name })
        assertEquals(listOf(w.branch.branchId), core.overview.branches().map { it.id })
        assertTrue(assertFailsWith<DomainException> { core.overview.payables() }.error.code.startsWith("PERMISSION_DENIED"))
    }
}
