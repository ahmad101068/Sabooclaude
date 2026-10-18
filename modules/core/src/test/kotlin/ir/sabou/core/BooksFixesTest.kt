package ir.sabou.core

import ir.sabou.persistence.DevDatabases
import ir.sabou.persistence.ClosableSqlDatabase

import ir.sabou.inventory.CreateItem
import ir.sabou.inventory.DefineMenuItem
import ir.sabou.inventory.PublishRecipe
import ir.sabou.inventory.RecipeLine
import ir.sabou.inventory.StockUnit
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.ledger.StandardAccounts
import ir.sabou.platform.Role
import ir.sabou.platform.memory.InMemoryAnchorStore
import ir.sabou.purchasing.InvoiceLine
import ir.sabou.purchasing.PostPurchaseInvoice
import ir.sabou.purchasing.RegisterSupplier
import ir.sabou.purchasing.ResolveReviewLine
import ir.sabou.purchasing.ReversePurchaseInvoice
import ir.sabou.purchasing.ReviewLine
import ir.sabou.sales.CloseSalesDay
import ir.sabou.sales.CustomerType
import ir.sabou.sales.PaySalesTax
import ir.sabou.sales.PostDailySale
import ir.sabou.sales.RegisterCustomer
import ir.sabou.sales.ReverseDailySale
import ir.sabou.sales.SaleLineInput
import ir.sabou.sales.SaveSaleDraft
import ir.sabou.sales.Settlement
import ir.sabou.sales.UpdateCustomer
import ir.sabou.sales.WriteOffReceivable
import ir.sabou.treasury.OpenTreasuryAccount
import ir.sabou.treasury.PaymentPurpose
import ir.sabou.treasury.ReceiptPurpose
import ir.sabou.treasury.RecordPayment
import ir.sabou.treasury.RecordReceipt
import ir.sabou.treasury.TreasuryKind
import ir.sabou.treasury.UpdateTreasuryAccount
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Review of the other modules, stage 1 (ADR-0045): defects. */
class BooksFixesTest {
    private val dir = Files.createTempDirectory("booksfix")
    private val db = DevDatabases.open(dir.resolve("b.db"))
    private val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { 1_700_000_000_000L }, emptyList(), null)
    private val day = BusinessDate(20_000)
    private fun id() = GlobalId.new()
    private fun rial(v: Long) = Money.of(v)
    private fun code(block: () -> Unit) = assertFailsWith<DomainException> { block() }.error.code
    private var branch = Scope.Branch(ir.sabou.kernel.BranchId(GlobalId.new()))
    private var kitchen = GlobalId.new()
    private var cash = GlobalId.new()
    private var pizza = GlobalId.new()
    private var cheese = GlobalId.new()

    @AfterTest fun close() { db.close(); dir.toFile().deleteRecursively() }

    private fun start() {
        core.bootstrap("شعبه ونک", "مالک", "owner", "123456".toCharArray())
        branch = Scope.Branch(core.overview.branches().single().id)
        kitchen = core.overview.locations(branch).single().id
        cash = core.overview.treasury().single().account.id
        cheese = core.inventory.createItem(CreateItem(id(), "پنیر", StockUnit.KILOGRAM, Quantity.ZERO)).resultId
        pizza = core.inventory.defineMenuItem(DefineMenuItem(id(), "پیتزا")).resultId
        core.inventory.publishRecipe(PublishRecipe(id(), pizza, BusinessDate(19_000), listOf(RecipeLine(cheese, Quantity.of(200_000)))))
    }

    private fun sell(date: BusinessDate, amount: Long, tax: Long = 0, discount: Long = 0, reason: String = "", menuItem: GlobalId = pizza, settlement: Settlement? = null): GlobalId {
        val sale = core.salesOps.saveDraft(SaveSaleDraft(id(), branch, date, kitchen, listOf(SaleLineInput(menuItem, Quantity.units(1), rial(amount))),
            rial(discount), Money.ZERO, rial(tax), listOf(settlement ?: Settlement.Liquid(cash, rial(amount - discount + tax))), discountReason = reason)).resultId
        core.salesOps.post(PostDailySale(id(), branch, sale))
        return sale
    }

    @Test fun closingAPastDayCountsTheBoxAsItWasAtTheEndOfThatDay() {
        start()
        sell(day, 1_000_000)
        sell(day.plusDays(1), 3_000_000)
        // Closed the next morning, after today's sale: the box held 1,000,000 at the end of yesterday.
        core.salesOps.closeDay(CloseSalesDay(id(), branch, day, cash, rial(1_000_000)))
        assertEquals(0L, core.sales.day(branch, day)!!.difference)
        // The closed day's box no longer changes: no payment and no reversal dated that day.
        assertEquals("INVALID_STATE:SALES_DAY:CLOSED", code { core.treasury.payment(RecordPayment(id(), branch, cash, PaymentPurpose.OTHER_EXPENSE, rial(10_000), day, "خرید نان")) })
        val today = sell(day.plusDays(1), 500_000)
        assertEquals("INVALID_STATE:SALES_DAY:CLOSED", code { core.salesOps.reverse(ReverseDailySale(id(), branch, today, day, "اشتباه")) })
    }

    @Test fun vatOwedBySalesIsPaidToTheTaxOffice() {
        start()
        sell(day, 10_000_000, tax = 1_000_000)
        assertEquals(rial(1_000_000), core.salesOps.salesTaxOwed(branch))
        assertEquals("INVALID_STATE:SALES_TAX:MORE_THAN_OWED", code { core.salesOps.paySalesTax(PaySalesTax(id(), branch, cash, rial(2_000_000), day, "")) })
        core.salesOps.paySalesTax(PaySalesTax(id(), branch, cash, rial(1_000_000), day, "فصل پاییز"))
        assertEquals(0L, core.ledger.balance(StandardAccounts.SALES_TAX_PAYABLE, branch).rial)
        assertEquals(10_000_000L, core.treasuryGateway.balance(cash))
    }

    @Test fun aDiscountNeedsItsReasonAndARightToPost() {
        start()
        assertEquals("INVALID_INPUT:discountReason", code { sell(day, 1_000_000, discount = 100_000) })
        core.identity.createUser("cashier", "صندوقدار", Role.CASHIER, setOf(branch.branchId), "111111".toCharArray())
        core.identity.login("cashier", "111111".toCharArray())
        // The cashier records it; posting is the manager's.
        core.salesOps.saveDraft(SaveSaleDraft(id(), branch, day, kitchen, listOf(SaleLineInput(pizza, Quantity.units(1), rial(1_000_000))),
            rial(100_000), Money.ZERO, Money.ZERO, listOf(Settlement.Liquid(cash, rial(900_000))), discountReason = "مشتری دائمی"))
        core.identity.login("owner", "123456".toCharArray())
        sell(day, 1_000_000, discount = 100_000, reason = "مشتری دائمی")
        assertEquals("مشتری دائمی", core.sales.salesOn(branch, day).last().discountReason)
    }

    @Test fun aDishWithoutARecipeIsSoldWithoutTakingStock() {
        start()
        val tea = core.inventory.defineMenuItem(DefineMenuItem(id(), "چای")).resultId
        sell(day, 200_000, menuItem = tea)
        assertEquals(1, core.sales.salesOn(branch, day).count { it.status == ir.sabou.sales.SaleStatus.POSTED })
    }

    @Test fun customersAreEditedSellOnCreditInAnyBranchAndBadDebtIsWrittenOff() {
        start()
        val customer = core.salesOps.registerCustomer(RegisterCustomer(id(), branch, "شرکت آرین", CustomerType.COMPANY, "021", rial(5_000_000))).resultId
        core.salesOps.updateCustomer(UpdateCustomer(id(), branch, customer, "شرکت آرین نو", CustomerType.COMPANY, "0912", rial(20_000_000), true))
        assertEquals("شرکت آرین نو", core.customers.byId(customer)!!.name)
        val other = Scope.Branch(core.identity.createBranch("شعبه دو"))
        val otherKitchen = core.overview.locations(other).firstOrNull()?.id
            ?: core.inventory.createLocation(ir.sabou.inventory.CreateLocation(id(), other, "آشپزخانه")).resultId
        val sale = core.salesOps.saveDraft(SaveSaleDraft(id(), other, day, otherKitchen, listOf(SaleLineInput(pizza, Quantity.units(1), rial(3_000_000))),
            Money.ZERO, Money.ZERO, Money.ZERO, listOf(Settlement.Credit(customer, rial(3_000_000), day.plusDays(30))))).resultId
        core.salesOps.post(PostDailySale(id(), other, sale))
        val receivable = core.sales.receivablesOfSale(sale).single()
        core.salesOps.writeOff(WriteOffReceivable(id(), other, receivable.id, rial(1_000_000), day.plusDays(60), "مشتری ورشکست شد"))
        assertEquals(rial(2_000_000), core.salesOps.outstanding(receivable.id))
        assertEquals(1_000_000L, core.ledger.balance(StandardAccounts.BAD_DEBTS, other).rial)
        assertEquals("INVALID_STATE:DAILY_SALE:HAS_WRITE_OFFS", code { core.salesOps.reverse(ReverseDailySale(id(), other, sale, day.plusDays(60), "اشتباه")) })
    }

    @Test fun aTreasuryAccountIsRenamedAndClosedOnlyWhenEmpty() {
        start()
        val bank = core.treasury.openAccount(OpenTreasuryAccount(id(), branch, "بانک ملت", TreasuryKind.BANK)).resultId
        core.treasury.receipt(RecordReceipt(id(), branch, bank, ReceiptPurpose.OWNER_CAPITAL, rial(1_000_000), day, "آورده"))
        assertEquals("INVALID_STATE:TREASURY_ACCOUNT:HAS_BALANCE", code { core.treasury.updateAccount(UpdateTreasuryAccount(id(), branch, bank, "بانک ملت", false)) })
        core.treasury.payment(RecordPayment(id(), branch, bank, PaymentPurpose.OWNER_WITHDRAWAL, rial(1_000_000), day, "برداشت"))
        core.treasury.updateAccount(UpdateTreasuryAccount(id(), branch, bank, "بانک ملت مرکزی", false))
        assertTrue(core.treasuryAccounts.byId(bank)!!.let { !it.isActive && it.name == "بانک ملت مرکزی" })
        core.treasury.updateAccount(UpdateTreasuryAccount(id(), branch, bank, "بانک ملت مرکزی", true))
        core.identity.renameBranch(branch.branchId, "شعبه ونک جدید")
        assertEquals("شعبه ونک جدید", core.overview.branches().single().name)
    }

    @Test fun aReversedInvoiceFreesItsNumberAndTakesItsAssignedLinesBack() {
        start()
        val supplier = core.purchasing.registerSupplier(RegisterSupplier(id(), "لبنیات", "")).resultId
        val invoice = core.purchasing.postInvoice(PostPurchaseInvoice(id(), branch, supplier, "77", kitchen, day, day,
            listOf(InvoiceLine(cheese, Quantity.units(1), rial(3_000_000))), reviewLines = listOf(ReviewLine("خامه", "۲ بسته", rial(400_000))))).resultId
        core.purchasing.resolveReviewLine(ResolveReviewLine(id(), branch, invoice, 0, null, null, null, StandardAccounts.OTHER_EXPENSE, day))
        core.purchasing.reverseInvoice(ReversePurchaseInvoice(id(), branch, invoice, day, "مبلغ اشتباه"))
        assertEquals(0L, core.ledger.balance(StandardAccounts.OTHER_EXPENSE, branch).rial)
        assertEquals(0L, core.ledger.balance(StandardAccounts.PURCHASES_PENDING_REVIEW, branch).rial)
        core.purchasing.postInvoice(PostPurchaseInvoice(id(), branch, supplier, "77", kitchen, day, day, listOf(InvoiceLine(cheese, Quantity.units(1), rial(2_800_000)))))
    }
}
