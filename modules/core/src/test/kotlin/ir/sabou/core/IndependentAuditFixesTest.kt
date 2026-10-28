package ir.sabou.core

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
import ir.sabou.persistence.DevDatabases
import ir.sabou.platform.memory.InMemoryAnchorStore
import ir.sabou.purchasing.CorrectPurchaseInvoice
import ir.sabou.purchasing.InvoiceLine
import ir.sabou.purchasing.InvoiceLineValue
import ir.sabou.purchasing.PostPurchaseInvoice
import ir.sabou.purchasing.RegisterSupplier
import ir.sabou.purchasing.ReversePurchaseInvoice
import ir.sabou.sales.CloseSalesDay
import ir.sabou.sales.PostDailySale
import ir.sabou.sales.SaleLineInput
import ir.sabou.sales.SaveSaleDraft
import ir.sabou.sales.Settlement
import ir.sabou.treasury.PaymentPurpose
import ir.sabou.treasury.ReceiptPurpose
import ir.sabou.treasury.RecordPayment
import ir.sabou.treasury.RecordReceipt
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The independent audit of 1405/08/06 (main @ 1abb2aa), each finding reproduced by its probe and kept here as the
 * behaviour the owner can rely on: SABOU-AUDIT-002 (correcting a purchase price after the goods were used), 003 (the
 * date rule on reversals and stock-only documents), 004 (closed cash days), 009 (counts and later shortages).
 */
class IndependentAuditFixesTest {
    private val today = Fa.fromJalali(1405, 8, 6)
    private val now = today.epochDay * 86_400_000L + 9 * 3_600_000L          // 12:30 in Tehran
    private val dir = Files.createTempDirectory("sabou-audit2")
    private val db = DevDatabases.open(dir.resolve("a.db"))
    private val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { now }, emptyList(), null, datePolicy = SabouCore.restaurantDatePolicy())
    private fun id() = GlobalId.new()
    private fun code(block: () -> Unit) = assertFailsWith<DomainException> { block() }.error.code

    @AfterTest fun close() { db.close(); dir.toFile().deleteRecursively() }

    private val branch: Scope.Branch
    private val kitchen: GlobalId
    private val cash: GlobalId
    private val meat: GlobalId
    private val kebab: GlobalId
    private val supplier: GlobalId

    init {
        core.bootstrap("شعبه", "مالک", "owner", "123456".toCharArray())
        branch = Scope.Branch(core.overview.branches().single().id)
        kitchen = core.overview.locations(branch).single().id
        cash = core.overview.treasury().single().account.id
        meat = core.inventory.createItem(CreateItem(id(), "گوشت", StockUnit.KILOGRAM, Quantity.units(0))).resultId
        kebab = core.inventory.defineMenuItem(DefineMenuItem(id(), "کباب")).resultId
        core.inventory.publishRecipe(PublishRecipe(id(), kebab, BusinessDate(19_000), listOf(RecipeLine(meat, Quantity.units(1)))))
        supplier = core.purchasing.registerSupplier(RegisterSupplier(id(), "قصابی", "021")).resultId
    }

    private fun sell(date: BusinessDate, kebabs: Long) {
        val s = core.salesOps.saveDraft(SaveSaleDraft(id(), branch, date, kitchen, listOf(SaleLineInput(kebab, Quantity.units(kebabs), Money.of(5_000_000))),
            Money.ZERO, Money.ZERO, Money.ZERO, listOf(Settlement.Liquid(cash, Money.of(5_000_000 * kebabs))))).resultId
        core.salesOps.post(PostDailySale(id(), branch, s))
    }

    private fun booksAgree() {
        assertEquals(0L, core.overview.trialBalance().sumOf { it.second }, "trial balance")
        assertEquals(core.stock.balance(meat, kitchen).value.rial, core.ledger.balance(StandardAccounts.INVENTORY, branch).rial, "inventory account = stock value")
        val open = core.overview.invoices().filter { it.invoice.status == ir.sabou.purchasing.InvoiceStatus.POSTED }.sumOf { it.outstanding.rial }
        assertEquals(open, -core.ledger.balance(StandardAccounts.PAYABLE, branch).rial, "payables = open invoices")
    }

    // ------------------------------------------------------------ SABOU-AUDIT-002

    @Test fun aPriceTypedWrongIsCorrectedAfterTheGoodsWereUsed() {
        // 10 kg keyed at 200,000,000 instead of 20,000,000; then all of it is sold.
        val inv = core.purchasing.postInvoice(PostPurchaseInvoice(id(), branch, supplier, "F-77", kitchen, today.plusDays(-2), today.plusDays(28),
            listOf(InvoiceLine(meat, Quantity.units(10), Money.of(200_000_000))))).resultId
        sell(today.plusDays(-1), 10)
        core.purchasing.correctInvoice(CorrectPurchaseInvoice(id(), branch, inv, listOf(InvoiceLineValue(meat, Money.of(20_000_000))), today, "یک صفر اضافه"))
        assertEquals(20_000_000, core.purchasing.outstanding(inv).rial)
        assertEquals(20_000_000, -core.ledger.balance(StandardAccounts.PAYABLE, branch).rial)
        assertEquals(20_000_000, core.ledger.balance(StandardAccounts.COGS, branch).rial)       // the cost of the 10 kebabs, as it truly was
        assertEquals(20_000_000, core.overview.invoice(inv).invoice.total.rial)
        booksAgree()
    }

    @Test fun whatIsStillOnTheShelfTakesItsShareOfTheCorrection() {
        val inv = core.purchasing.postInvoice(PostPurchaseInvoice(id(), branch, supplier, "F-78", kitchen, today.plusDays(-2), today.plusDays(28),
            listOf(InvoiceLine(meat, Quantity.units(10), Money.of(200_000_000))))).resultId
        sell(today.plusDays(-1), 4)                                                         // 6 kg left
        core.purchasing.correctInvoice(CorrectPurchaseInvoice(id(), branch, inv, listOf(InvoiceLineValue(meat, Money.of(20_000_000))), today, "یک صفر اضافه"))
        assertEquals(12_000_000, core.stock.balance(meat, kitchen).value.rial)              // 6 kg × 2,000,000
        assertEquals(8_000_000, core.ledger.balance(StandardAccounts.COGS, branch).rial)     // 4 kg × 2,000,000
        booksAgree()
        // A corrected invoice can still be reversed while its goods are on the shelf: everything comes back to zero.
        val untouched = core.purchasing.postInvoice(PostPurchaseInvoice(id(), branch, supplier, "F-79", kitchen, today, today.plusDays(28),
            listOf(InvoiceLine(meat, Quantity.units(5), Money.of(50_000_000))))).resultId
        core.purchasing.correctInvoice(CorrectPurchaseInvoice(id(), branch, untouched, listOf(InvoiceLineValue(meat, Money.of(10_000_000))), today, "قیمت"))
        core.purchasing.reverseInvoice(ReversePurchaseInvoice(id(), branch, untouched, today, "تکراری"))
        assertEquals(0, core.purchasing.outstanding(untouched).rial)
        booksAgree()
    }

    @Test fun aCorrectionCannotGoBelowWhatWasPaid() {
        val inv = core.purchasing.postInvoice(PostPurchaseInvoice(id(), branch, supplier, "F-80", kitchen, today.plusDays(-2), today.plusDays(28),
            listOf(InvoiceLine(meat, Quantity.units(10), Money.of(20_000_000))))).resultId
        core.treasury.receipt(RecordReceipt(id(), branch, cash, ReceiptPurpose.OWNER_CAPITAL, Money.of(30_000_000), today.plusDays(-2), "آورده"))
        core.purchasing.payInvoice(ir.sabou.purchasing.PaySupplierInvoice(id(), branch, inv, cash, Money.of(15_000_000), today))
        assertEquals("INVALID_STATE:PURCHASE_INVOICE:CORRECTION_BELOW_PAID",
            code { core.purchasing.correctInvoice(CorrectPurchaseInvoice(id(), branch, inv, listOf(InvoiceLineValue(meat, Money.of(10_000_000))), today, "تخفیف")) })
        core.purchasing.correctInvoice(CorrectPurchaseInvoice(id(), branch, inv, listOf(InvoiceLineValue(meat, Money.of(16_000_000))), today, "تخفیف"))
        assertEquals(1_000_000, core.purchasing.outstanding(inv).rial)
        booksAgree()
    }

    // ------------------------------------------------------------ SABOU-AUDIT-003

    @Test fun reversalsAndStockOnlyDocumentsFollowTheDateRule() {
        core.purchasing.postInvoice(PostPurchaseInvoice(id(), branch, supplier, "F-81", kitchen, today, today, listOf(InvoiceLine(meat, Quantity.units(10), Money.of(20_000_000)))))
        val waste = core.inventory.waste(ir.sabou.inventory.RecordWaste(id(), branch, kitchen, meat, Quantity.units(1), ir.sabou.inventory.WasteReason.SPOILAGE, "", today)).resultId
        assertEquals("INVALID_INPUT:date", code {
            core.inventory.reverseDocument(ir.sabou.inventory.ReverseInventoryDocument(id(), branch, ir.sabou.inventory.InventoryOperations.WASTE, waste, today.plusDays(400), "اشتباه"))
        })
        core.inventory.reverseDocument(ir.sabou.inventory.ReverseInventoryDocument(id(), branch, ir.sabou.inventory.InventoryOperations.WASTE, waste, today, "اشتباه"))
        booksAgree()
    }

    // ------------------------------------------------------------ SABOU-AUDIT-004

    @Test fun aClosedCashDayIsNotChangedByAnEarlierDate() {
        val d = today.plusDays(-3)
        core.treasury.receipt(RecordReceipt(id(), branch, cash, ReceiptPurpose.OTHER_INCOME, Money.of(10_000_000), d.plusDays(-2), "تنخواه"))
        core.salesOps.closeDay(CloseSalesDay(id(), branch, d, cash, Money.of(10_000_000)))
        assertEquals("INVALID_STATE:SALES_DAY:CLOSED", code {
            core.treasury.payment(RecordPayment(id(), branch, cash, PaymentPurpose.OTHER_EXPENSE, Money.of(3_000_000), d.plusDays(-1), "جاافتاده"))
        })
        assertEquals(10_000_000, core.treasuryMovements.balanceAsOf(cash, d))
        core.treasury.payment(RecordPayment(id(), branch, cash, PaymentPurpose.OTHER_EXPENSE, Money.of(3_000_000), today, "امروز"))   // after the closed day: fine
    }

    @Test fun moneyPaidOnAPastDayMustHaveBeenThereThen() {
        core.treasury.receipt(RecordReceipt(id(), branch, cash, ReceiptPurpose.OTHER_INCOME, Money.of(10_000_000), today, "فروش امروز"))
        // Ten days ago the box was empty: a payment dated then would leave it negative on every day until today.
        assertEquals("INSUFFICIENT_FUNDS", code {
            core.treasury.payment(RecordPayment(id(), branch, cash, PaymentPurpose.OTHER_EXPENSE, Money.of(5_000_000), today.plusDays(-10), "قدیمی"))
        }.substringBefore(':'))
        core.treasury.payment(RecordPayment(id(), branch, cash, PaymentPurpose.OTHER_EXPENSE, Money.of(5_000_000), today, "امروز"))
    }
}
