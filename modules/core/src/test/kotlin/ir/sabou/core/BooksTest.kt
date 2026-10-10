package ir.sabou.core

import ir.sabou.assets.AcquireAsset
import ir.sabou.assets.DepreciationMethod
import ir.sabou.assets.Funding
import ir.sabou.assets.RunDepreciation
import ir.sabou.inventory.CreateItem
import ir.sabou.inventory.DefineMenuItem
import ir.sabou.inventory.PublishRecipe
import ir.sabou.inventory.RecipeLine
import ir.sabou.inventory.StockUnit
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.Clock
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.ledger.BudgetPeriod
import ir.sabou.ledger.SetBudget
import ir.sabou.ledger.StandardAccounts
import ir.sabou.persistence.JdbcSqlDatabase
import ir.sabou.platform.Role
import ir.sabou.platform.memory.InMemoryAnchorStore
import ir.sabou.purchasing.ApproveInvoice
import ir.sabou.purchasing.InvoiceLine
import ir.sabou.purchasing.PaySupplierInvoice
import ir.sabou.purchasing.PostPurchaseInvoice
import ir.sabou.purchasing.RegisterSupplier
import ir.sabou.purchasing.SaveApprovalRule
import ir.sabou.sales.CollectReceivable
import ir.sabou.sales.CustomerType
import ir.sabou.sales.PostDailySale
import ir.sabou.sales.RegisterCustomer
import ir.sabou.sales.SaleLineInput
import ir.sabou.sales.SaveSaleDraft
import ir.sabou.sales.Settlement
import ir.sabou.treasury.ChequeDetails
import ir.sabou.treasury.ChequeDirection
import ir.sabou.treasury.ChequeStatus
import ir.sabou.treasury.ClearIssuedCheque
import ir.sabou.treasury.CollectCheque
import ir.sabou.treasury.DepositCheque
import ir.sabou.treasury.OpenTreasuryAccount
import ir.sabou.treasury.PaymentPurpose
import ir.sabou.treasury.ReceiptPurpose
import ir.sabou.treasury.RecordPayment
import ir.sabou.treasury.RecordReceipt
import ir.sabou.treasury.TreasuryKind
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BooksTest {
    private val dir = Files.createTempDirectory("sabou-books")
    private val file = dir.resolve("b.db")
    private val anchors = InMemoryAnchorStore()
    private val open = mutableListOf<JdbcSqlDatabase>()
    private fun boot(): SabouCore {
        val db = JdbcSqlDatabase(DriverManager.getConnection("jdbc:sqlite:$file")).also { open += it }
        return SabouCore.open(db, anchors, Clock { 1_700_000_000_000L }, emptyList(), null)
    }
    private val day = BusinessDate(20_000)
    private fun id() = GlobalId.new()
    private fun rial(v: Long) = Money.of(v)

    @AfterTest fun close() { open.forEach { it.close() }; dir.toFile().deleteRecursively() }

    private fun SabouCore.account(scope: Scope, name: String, kind: TreasuryKind) = treasury.openAccount(OpenTreasuryAccount(id(), scope, name, kind)).resultId

    @Test fun chequesFromCustomersAndToSuppliersFollowTheMoneyAndSurviveARestart() {
        val core = boot()
        core.bootstrap("شعبه ونک", "مالک", "owner", "123456".toCharArray())
        val branch = Scope.Branch(core.overview.branches().single().id)
        val kitchen = core.overview.locations(branch).single().id
        val box = core.account(branch, "صندوق چک", TreasuryKind.RECEIVED_CHEQUES)
        val book = core.account(branch, "دسته‌چک ملت", TreasuryKind.ISSUED_CHEQUES)
        val bank = core.account(branch, "بانک ملت", TreasuryKind.BANK)
        core.treasury.receipt(RecordReceipt(id(), branch, bank, ReceiptPurpose.OWNER_CAPITAL, rial(50_000_000), day, "آورده"))
        // A credit sale, then the customer pays with a cheque.
        val cheese = core.inventory.createItem(CreateItem(id(), "پنیر", StockUnit.KILOGRAM, Quantity.ZERO)).resultId
        val pizza = core.inventory.defineMenuItem(DefineMenuItem(id(), "پیتزا")).resultId
        core.inventory.publishRecipe(PublishRecipe(id(), pizza, BusinessDate(19_000), listOf(RecipeLine(cheese, Quantity.of(200_000)))))
        val supplier = core.purchasing.registerSupplier(RegisterSupplier(id(), "لبنیات", "")).resultId
        // Every invoice of this supplier needs one approval.
        core.approvals.saveRule(SaveApprovalRule(id(), null, "همه‌ی فاکتورها", null, null, null, rial(0), 1))
        val invoice = core.purchasing.postInvoice(PostPurchaseInvoice(id(), branch, supplier, "1", kitchen, day, day, listOf(InvoiceLine(cheese, Quantity.units(1), rial(3_000_000))))).resultId
        assertEquals(1, core.books.pendingApprovals().size)
        val customer = core.salesOps.registerCustomer(RegisterCustomer(id(), branch, "شرکت آلفا", CustomerType.COMPANY, "", rial(100_000_000))).resultId
        val sale = core.salesOps.saveDraft(SaveSaleDraft(id(), branch, day, kitchen, listOf(SaleLineInput(pizza, Quantity.units(1), rial(8_000_000))),
            rial(0), rial(0), rial(0), listOf(Settlement.Credit(customer, rial(8_000_000), day.plusDays(10))))).resultId
        core.salesOps.post(PostDailySale(id(), branch, sale))
        val receivable = core.overview.openReceivables().single().receivable.id
        core.salesOps.collect(CollectReceivable(id(), branch, receivable, box, rial(8_000_000), day,
            ChequeDetails("445566", "صادرات", "1234567890123456", day.plusDays(20), "شرکت آلفا")))
        val received = core.books.cheques(ChequeDirection.RECEIVED).single().cheque
        assertEquals(ChequeStatus.IN_HAND, received.status)
        assertEquals(listOf(received.id), core.books.chequesDue(day, 30).map { it.cheque.id })
        assertTrue(core.books.chequesDue(day, 10).isEmpty())
        // Our cheque to the supplier, after approval.
        core.approvals.approve(ApproveInvoice(id(), branch, invoice))
        core.purchasing.payInvoice(PaySupplierInvoice(id(), branch, invoice, book, rial(3_000_000), day,
            cheque = ChequeDetails("900100", "ملت", "", day.plusDays(15), "لبنیات", bankAccountId = bank)))
        val issued = core.books.cheques(ChequeDirection.ISSUED).single().cheque
        assertEquals(bank, issued.bankAccountId)
        assertEquals(0, core.purchasing.outstanding(invoice).rial)

        // Restart: everything reads back from the database.
        val again = boot()
        again.identity.login("owner", "123456".toCharArray())
        assertEquals(received, again.books.cheque(received.id).cheque)
        assertEquals(1, again.overview.invoice(invoice).invoice.approvals.size)
        again.chequeOps.deposit(DepositCheque(id(), branch, received.id, bank, day.plusDays(1)))
        again.chequeOps.collect(CollectCheque(id(), branch, received.id, bank, day.plusDays(20)))
        again.chequeOps.clear(ClearIssuedCheque(id(), branch, issued.id, day.plusDays(15)))
        val balances = again.overview.treasury().associate { it.account.id to it.balance }
        assertEquals(50_000_000 + 8_000_000 - 3_000_000, balances[bank])
        assertEquals(0L, balances[box])
        assertEquals(0L, balances[book])
        assertEquals(ChequeStatus.COLLECTED, again.books.cheque(received.id).cheque.status)
        assertEquals(ChequeStatus.CLEARED, again.books.cheque(issued.id).cheque.status)
        assertEquals(0, again.ledger.balance(StandardAccounts.CHEQUES_RECEIVABLE, branch).rial)
        assertEquals(0, again.ledger.balance(StandardAccounts.CHEQUES_PAYABLE, branch).rial)
    }

    @Test fun budgetAgainstActualCountsPartPeriodsByDaysAndAssetsDepreciate() {
        val core = boot()
        core.bootstrap("شعبه ونک", "مالک", "owner", "123456".toCharArray())
        val branch = Scope.Branch(core.overview.branches().single().id)
        val cash = core.overview.treasury().single().account.id
        core.treasury.receipt(RecordReceipt(id(), branch, cash, ReceiptPurpose.OWNER_CAPITAL, rial(500_000_000), day, "آورده"))
        core.budgets.set(SetBudget(id(), branch, StandardAccounts.RENT, listOf(BudgetPeriod(day, day.plusDays(29), rial(30_000_000)))))
        core.treasury.payment(RecordPayment(id(), branch, cash, PaymentPurpose.RENT, rial(10_000_000), day.plusDays(3), "اجاره"))
        val report = core.books.budgetVsActual(day, day.plusDays(14), branch)
        val rent = report.lines.single { it.account.code == StandardAccounts.RENT }
        assertEquals(15_000_000, rent.budget)                          // half of the month
        assertEquals(10_000_000, rent.actual)
        assertEquals(6_667, rent.usedBp)

        val oven = core.fixedAssets.acquire(AcquireAsset(id(), branch, "فر", "تجهیزات", rial(120_000_000), rial(0), day,
            DepreciationMethod.STRAIGHT_LINE, 120, null, Funding.Paid(cash))).resultId
        assertTrue(core.books.assets(branch, day.plusDays(29)).single().nextDepreciation.rial > 0)
        core.fixedAssets.depreciate(RunDepreciation(id(), branch, day.plusDays(29)))
        val asset = core.books.asset(oven).asset
        assertEquals(asset.accumulated.rial, core.ledger.balance(StandardAccounts.DEPRECIATION, branch).rial)
        assertEquals(1, core.books.depreciationRuns(branch).size)
        // A cashier sees none of it.
        core.identity.createUser("cashier", "صندوقدار", Role.CASHIER, setOf(branch.branchId), "654321".toCharArray())
        core.identity.logout(); core.identity.login("cashier", "654321".toCharArray())
        assertEquals("PERMISSION_DENIED", runCatching { core.books.assets() }.exceptionOrNull()!!.let { (it as ir.sabou.kernel.DomainException).error.code.substringBefore(':') })
        assertEquals("PERMISSION_DENIED", runCatching { core.books.budgetVsActual(day, day) }.exceptionOrNull()!!.let { (it as ir.sabou.kernel.DomainException).error.code.substringBefore(':') })
    }

    @Test fun amountsAndDatesInWords() {
        assertEquals("یک میلیون و دویست و پنجاه هزار", Fa.inWords(1_250_000))
        assertEquals("سیصد و یازده", Fa.inWords(311))
        assertEquals("دو میلیارد و یک", Fa.inWords(2_000_000_001))
        assertEquals("صفر", Fa.inWords(0))
        val d = Fa.fromJalali(1405, 7, 23)!!
        assertEquals("بیست و سوم مهر یک هزار و چهارصد و پنج", Fa.dateInWords(d))
        assertEquals("سی‌ام مهر یک هزار و چهارصد و پنج", Fa.dateInWords(Fa.fromJalali(1405, 7, 30)!!))
        assertEquals("یکم مهر یک هزار و چهارصد و پنج", Fa.dateInWords(Fa.fromJalali(1405, 7, 1)!!))
    }

    @Test fun customersChequesSettleTheDaysSaleAndReversingTheSaleVoidsThem() {
        val core = boot()
        core.bootstrap("شعبه ونک", "مالک", "owner", "123456".toCharArray())
        val branch = Scope.Branch(core.overview.branches().single().id)
        val kitchen = core.overview.locations(branch).single().id
        val cash = core.overview.treasury().single().account.id
        val box = core.account(branch, "صندوق چک", TreasuryKind.RECEIVED_CHEQUES)
        val cheese = core.inventory.createItem(CreateItem(id(), "پنیر", StockUnit.KILOGRAM, Quantity.ZERO)).resultId
        val pizza = core.inventory.defineMenuItem(DefineMenuItem(id(), "پیتزا")).resultId
        core.inventory.publishRecipe(PublishRecipe(id(), pizza, BusinessDate(19_000), listOf(RecipeLine(cheese, Quantity.of(200_000)))))
        val supplier = core.purchasing.registerSupplier(RegisterSupplier(id(), "لبنیات", "")).resultId
        core.purchasing.postInvoice(PostPurchaseInvoice(id(), branch, supplier, "1", kitchen, day, day, listOf(InvoiceLine(cheese, Quantity.units(1), rial(1_000_000)))))
        assertTrue(core.overview.salesDay(branch, day).accounts.any { it.id == box })
        fun cheque(no: String) = ChequeDetails(no, "ملت", "", day.plusDays(7), "مهمان")
        val settlements = listOf(Settlement.Liquid(cash, rial(1_000_000)), Settlement.Liquid(box, rial(3_000_000), cheque("1")), Settlement.Liquid(box, rial(4_000_000), cheque("2")))
        assertEquals("INVALID_INPUT:cheque", runCatching {
            core.salesOps.saveDraft(SaveSaleDraft(id(), branch, day, kitchen, listOf(SaleLineInput(pizza, Quantity.units(1), rial(8_000_000))),
                rial(0), rial(0), rial(0), listOf(Settlement.Liquid(box, rial(8_000_000)))))
        }.exceptionOrNull()!!.let { (it as ir.sabou.kernel.DomainException).error.code })
        val sale = core.salesOps.saveDraft(SaveSaleDraft(id(), branch, day, kitchen, listOf(SaleLineInput(pizza, Quantity.units(1), rial(8_000_000))),
            rial(0), rial(0), rial(0), settlements)).resultId
        assertEquals(settlements, core.overview.salesDay(branch, day).sale!!.settlements)     // read back with the cheques
        core.salesOps.post(PostDailySale(id(), branch, sale))
        assertEquals(listOf(3_000_000L, 4_000_000L), core.books.cheques(ChequeDirection.RECEIVED).map { it.cheque.amount.rial }.sorted())
        assertEquals(7_000_000, core.overview.treasury().single { it.account.id == box }.balance)
        core.salesOps.reverse(ir.sabou.sales.ReverseDailySale(id(), branch, sale, day, "ثبت اشتباه"))
        assertTrue(core.books.cheques(ChequeDirection.RECEIVED).all { it.cheque.status == ChequeStatus.VOID })
        assertEquals(0, core.overview.treasury().single { it.account.id == box }.balance)
    }

    @Test fun theCounterCountsBlindAndTheReviewSurvivesARestart() {
        val core = boot()
        core.bootstrap("شعبه ونک", "مالک", "owner", "123456".toCharArray())
        val branch = Scope.Branch(core.overview.branches().single().id)
        val kitchen = core.overview.locations(branch).single().id
        val cheese = core.inventory.createItem(CreateItem(id(), "پنیر", StockUnit.KILOGRAM, Quantity.ZERO)).resultId
        val supplier = core.purchasing.registerSupplier(RegisterSupplier(id(), "لبنیات", "")).resultId
        core.purchasing.postInvoice(PostPurchaseInvoice(id(), branch, supplier, "1", kitchen, day, day, listOf(InvoiceLine(cheese, Quantity.units(10), rial(3_000_000)))))
        core.identity.createUser("store", "انباردار", Role.STOREKEEPER, setOf(branch.branchId), "222222".toCharArray())
        core.identity.logout(); core.identity.login("store", "222222".toCharArray())
        assertEquals(null, core.overview.countSheet(kitchen).single { it.item.id == cheese }.book)       // blind
        val count = core.counts.submit(ir.sabou.inventory.SubmitStockCount(id(), branch, kitchen, day, listOf(ir.sabou.inventory.CountEntry(cheese, Quantity.units(9))))).resultId
        val seenByCounter = core.overview.stockCount(count)
        assertEquals(false, seenByCounter.showsBook)
        assertEquals(0, seenByCounter.count.lines.single().difference)
        core.identity.logout()

        val again = boot()
        again.identity.login("owner", "123456".toCharArray())
        assertEquals(Quantity.units(10), again.overview.countSheet(kitchen).single { it.item.id == cheese }.book)
        val pending = again.overview.stockCount(count)
        assertEquals(-1_000_000, pending.count.lines.single().difference)
        assertEquals("انباردار", pending.count.countedByName)
        again.counts.approve(ir.sabou.inventory.ApproveStockCount(id(), branch, count, mapOf(cheese to ir.sabou.inventory.LineReason(ir.sabou.inventory.VarianceReason.UNRECORDED_USE, "تست پیتزای جدید"))))
        val done = again.overview.stockCounts(branch).single().count
        assertEquals(ir.sabou.inventory.CountStatus.POSTED, done.status)
        assertEquals(-300_000L, done.lines.single().postedValue)
        assertEquals("تست پیتزای جدید", done.lines.single().reasonNote)
        assertEquals(300_000, again.ledger.balance(StandardAccounts.INVENTORY_VARIANCE, branch).rial)
    }
}
