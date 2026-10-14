package ir.sabou.core

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.JalaliCalendar
import ir.sabou.kernel.Money
import ir.sabou.kernel.Scope
import ir.sabou.ledger.AccountCode
import ir.sabou.ledger.DefineAccount
import ir.sabou.ledger.StandardAccounts
import ir.sabou.ledger.UpdateAccount
import ir.sabou.persistence.JdbcSqlDatabase
import ir.sabou.platform.memory.InMemoryAnchorStore
import ir.sabou.purchasing.PaySupplierInvoice
import ir.sabou.purchasing.RecordSupplierOpeningBalance
import ir.sabou.purchasing.RegisterSupplier
import ir.sabou.sales.CollectReceivable
import ir.sabou.sales.CustomerType
import ir.sabou.sales.RecordCustomerOpeningBalance
import ir.sabou.sales.RegisterCustomer
import ir.sabou.sales.ReverseCustomerOpeningBalance
import ir.sabou.treasury.PaymentPurpose
import ir.sabou.treasury.ReceiptPurpose
import ir.sabou.treasury.RecordPayment
import ir.sabou.treasury.RecordReceipt
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Review of the other modules, stage 2 (ADR-0045): own accounts, opening balances, accounting reports. */
class AccountingTest {
    private val dir = Files.createTempDirectory("acct")
    private val db = JdbcSqlDatabase(DriverManager.getConnection("jdbc:sqlite:${dir.resolve("a.db")}"))
    private val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { 1_790_000_000_000L }, emptyList(), null)
    private val day = JalaliCalendar.date(1405, 7, 1)
    private fun id() = GlobalId.new()
    private fun rial(v: Long) = Money.of(v)
    private fun code(block: () -> Unit) = assertFailsWith<DomainException> { block() }.error.code
    private var branch = Scope.Branch(ir.sabou.kernel.BranchId(GlobalId.new()))
    private var cash = GlobalId.new()

    @AfterTest fun close() { db.close(); dir.toFile().deleteRecursively() }

    private fun start() {
        core.bootstrap("شعبه ونک", "مالک", "owner", "123456".toCharArray())
        branch = Scope.Branch(core.overview.branches().single().id)
        cash = core.overview.treasury().single().account.id
        core.treasury.receipt(RecordReceipt(id(), branch, cash, ReceiptPurpose.OWNER_CAPITAL, rial(100_000_000), JalaliCalendar.date(1404, 12, 1), "آورده"))
    }

    @Test fun ownAccountsAreDefinedAndUsedForPaymentsAndReceipts() {
        start()
        core.accounting.defineAccount(DefineAccount(id(), "۶۲۰۱", "تعمیر هود"))
        val hood = AccountCode.of("6201")
        assertEquals(ir.sabou.ledger.AccountType.EXPENSE, core.accounts.byCode(hood)!!.type)
        assertEquals("INVALID_STATE:ACCOUNT:DUPLICATE_CODE", code { core.accounting.defineAccount(DefineAccount(id(), "6201", "دیگر")) })
        core.treasury.payment(RecordPayment(id(), branch, cash, PaymentPurpose.OTHER_EXPENSE, rial(2_000_000), day, "تعمیر هود", counterAccount = hood))
        core.treasury.receipt(RecordReceipt(id(), branch, cash, ReceiptPurpose.OTHER_INCOME, rial(30_000_000), day, "وام بانک ملت", counterAccount = StandardAccounts.LOANS_PAYABLE))
        assertEquals(2_000_000L, core.ledger.balance(hood, branch).rial)
        assertEquals(-30_000_000L, core.ledger.balance(StandardAccounts.LOANS_PAYABLE, branch).rial)
        // Never a module's control account.
        assertTrue(code { core.treasury.payment(RecordPayment(id(), branch, cash, PaymentPurpose.OTHER_EXPENSE, rial(1), day, "x", counterAccount = StandardAccounts.PAYABLE)) }.startsWith("CONTROL_ACCOUNT"))
        assertEquals("INVALID_STATE:ACCOUNT:HAS_BALANCE", code { core.accounting.updateAccount(UpdateAccount(id(), hood, "تعمیرات هود", false)) })
        core.accounting.updateAccount(UpdateAccount(id(), hood, "تعمیرات هود", true))
        assertEquals("INVALID_STATE:ACCOUNT:SYSTEM", code { core.accounting.updateAccount(UpdateAccount(id(), StandardAccounts.RENT, "x", true)) })
    }

    @Test fun openingBalancesOfSuppliersAndCustomersArePaidCollectedAndStated() {
        start()
        val supplier = core.purchasing.registerSupplier(RegisterSupplier(id(), "لبنیات", "")).resultId
        val inv = core.purchasing.openingBalance(RecordSupplierOpeningBalance(id(), branch, supplier, "مانده ۱۴۰۵", rial(5_000_000), day, day.plusDays(10))).resultId
        assertEquals(5_000_000L, -core.ledger.balance(StandardAccounts.PAYABLE, branch).rial)
        core.purchasing.payInvoice(PaySupplierInvoice(id(), branch, inv, cash, rial(2_000_000), day.plusDays(5)))
        val st = core.ledgerReads.supplierStatement(supplier, day, day.plusDays(30))
        assertEquals(listOf(5_000_000L, 3_000_000L), st.rows.map { it.balance })
        val customer = core.salesOps.registerCustomer(RegisterCustomer(id(), branch, "شرکت آرین", CustomerType.COMPANY, "021", rial(0))).resultId
        val r = core.salesOps.customerOpeningBalance(RecordCustomerOpeningBalance(id(), branch, customer, rial(4_000_000), day, day.plusDays(30))).resultId
        core.salesOps.collect(CollectReceivable(id(), branch, r, cash, rial(1_000_000), day.plusDays(2)))
        assertEquals("INVALID_STATE:RECEIVABLE:HAS_COLLECTIONS", code { core.salesOps.reverseCustomerOpening(ReverseCustomerOpeningBalance(id(), branch, r, day.plusDays(3), "اشتباه")) })
        assertEquals(3_000_000L, core.ledgerReads.customerStatement(customer, day, day.plusDays(30)).closing)
        // Aging: the customer's 3,000,000 is 40 days late, the supplier's 3,000,000 is 35 days late.
        val asOf = day.plusDays(70)
        assertEquals(3_000_000L, core.ledgerReads.receivablesAging(asOf).single().buckets[2])
        assertEquals(3_000_000L, core.ledgerReads.payablesAging(asOf.plusDays(-25)).single().buckets[2])
    }

    @Test fun trialBalanceBalanceSheetAndJournalBookAgree() {
        start()
        core.treasury.payment(RecordPayment(id(), branch, cash, PaymentPurpose.RENT, rial(10_000_000), day, "اجاره مهر"))
        val rows = core.ledgerReads.trialBalance(day, day.plusDays(29))
        assertEquals(0L, rows.sumOf { it.closing })
        assertEquals(10_000_000L, rows.single { it.account.code == StandardAccounts.RENT }.debit)
        assertEquals(100_000_000L, rows.single { it.account.code == StandardAccounts.CASH }.opening)
        val sheet = core.ledgerReads.balanceSheet(day.plusDays(29))
        assertEquals(sheet.totalAssets, sheet.totalLiabilities + sheet.totalEquity)
        assertEquals(-10_000_000L, sheet.yearProfit)
        assertEquals(1, core.ledgerReads.journalBook(day, day.plusDays(29)).size)
        // Last year's capital stays in last year; this year's loss is this year's.
        assertEquals(0L, sheet.earlierProfit)
    }
}
