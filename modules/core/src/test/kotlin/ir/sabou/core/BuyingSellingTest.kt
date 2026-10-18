package ir.sabou.core

import ir.sabou.persistence.DevDatabases
import ir.sabou.persistence.ClosableSqlDatabase

import ir.sabou.inventory.CreateItem
import ir.sabou.inventory.StockUnit
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.ledger.StandardAccounts
import ir.sabou.platform.memory.InMemoryAnchorStore
import ir.sabou.purchasing.ApplySupplierAdvance
import ir.sabou.purchasing.CreatePurchaseOrder
import ir.sabou.purchasing.InvoiceLine
import ir.sabou.purchasing.OrderLine
import ir.sabou.purchasing.OrderStatus
import ir.sabou.purchasing.PaySupplier
import ir.sabou.purchasing.PaySupplierAdvance
import ir.sabou.purchasing.PostPurchaseInvoice
import ir.sabou.purchasing.RegisterSupplier
import ir.sabou.purchasing.ReverseSupplierAdvance
import ir.sabou.purchasing.UpdateSupplier
import ir.sabou.sales.ApplyCustomerDeposit
import ir.sabou.sales.CloseSalesDay
import ir.sabou.sales.CountCashShift
import ir.sabou.sales.CustomerType
import ir.sabou.sales.PostDailySale
import ir.sabou.sales.ReceiveCustomerDeposit
import ir.sabou.sales.RegisterCustomer
import ir.sabou.sales.SaleLineInput
import ir.sabou.sales.SaveSaleDraft
import ir.sabou.sales.Settlement
import ir.sabou.treasury.OpenTreasuryAccount
import ir.sabou.treasury.ReceiptPurpose
import ir.sabou.treasury.RecordReceipt
import ir.sabou.treasury.TreasuryKind
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Review of the other modules, stage 3 (ADR-0045): purchasing and sales additions. */
class BuyingSellingTest {
    private val dir = Files.createTempDirectory("buysell")
    private val db = DevDatabases.open(dir.resolve("b.db"))
    private val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { 1_700_000_000_000L }, emptyList(), null)
    private val day = BusinessDate(20_000)
    private fun id() = GlobalId.new()
    private fun rial(v: Long) = Money.of(v)
    private fun code(block: () -> Unit) = assertFailsWith<DomainException> { block() }.error.code
    private var branch = Scope.Branch(ir.sabou.kernel.BranchId(GlobalId.new()))
    private var kitchen = GlobalId.new()
    private var cash = GlobalId.new()
    private var cheese = GlobalId.new()
    private var supplier = GlobalId.new()

    @AfterTest fun close() { db.close(); dir.toFile().deleteRecursively() }

    private fun start() {
        core.bootstrap("شعبه ونک", "مالک", "owner", "123456".toCharArray())
        branch = Scope.Branch(core.overview.branches().single().id)
        kitchen = core.overview.locations(branch).single().id
        cash = core.overview.treasury().single().account.id
        core.treasury.receipt(RecordReceipt(id(), branch, cash, ReceiptPurpose.OWNER_CAPITAL, rial(100_000_000), day.plusDays(-10), "آورده"))
        cheese = core.inventory.createItem(CreateItem(id(), "پنیر", StockUnit.KILOGRAM, Quantity.ZERO)).resultId
        supplier = core.purchasing.registerSupplier(RegisterSupplier(id(), "لبنیات", "")).resultId
    }

    @Test fun anInvoicesDiscountAndFreightGoIntoTheGoodsAndItsVatIsACredit() {
        start()
        core.purchasing.postInvoice(PostPurchaseInvoice(id(), branch, supplier, "1", kitchen, day, day,
            listOf(InvoiceLine(cheese, Quantity.units(3), rial(3_000_000))), vat = rial(270_000), discount = rial(300_000), freight = rial(100_000)))
        assertEquals(2_800_000L, core.stock.balance(cheese, kitchen).value.rial)
        assertEquals(270_000L, core.ledger.balance(StandardAccounts.VAT_RECEIVABLE, branch).rial)
        assertEquals(-3_070_000L, core.ledger.balance(StandardAccounts.PAYABLE, branch).rial)
    }

    @Test fun severalInvoicesArePaidAtOnceAndAnAdvanceIsSetAgainstALaterOne() {
        start()
        val a = core.purchasing.postInvoice(PostPurchaseInvoice(id(), branch, supplier, "1", kitchen, day, day, listOf(InvoiceLine(cheese, Quantity.units(1), rial(1_000_000))))).resultId
        val b = core.purchasing.postInvoice(PostPurchaseInvoice(id(), branch, supplier, "2", kitchen, day, day.plusDays(5), listOf(InvoiceLine(cheese, Quantity.units(2), rial(2_000_000))))).resultId
        assertEquals("INVALID_STATE:SUPPLIER:PAYMENT_EXCEEDS_PAYABLE", code { core.purchasing.paySupplier(PaySupplier(id(), branch, supplier, cash, rial(4_000_000), day)) })
        core.purchasing.paySupplier(PaySupplier(id(), branch, supplier, cash, rial(1_500_000), day))
        assertEquals(Money.ZERO, core.purchasing.outstanding(a))
        assertEquals(rial(1_500_000), core.purchasing.outstanding(b))
        val adv = core.purchasing.payAdvance(PaySupplierAdvance(id(), branch, supplier, cash, rial(1_000_000), day, "برای هفته‌ی بعد")).resultId
        assertEquals(1_000_000L, core.ledger.balance(StandardAccounts.SUPPLIER_ADVANCES, branch).rial)
        core.purchasing.applyAdvance(ApplySupplierAdvance(id(), branch, adv, b, rial(600_000), day))
        assertEquals(rial(900_000), core.purchasing.outstanding(b))
        assertEquals(rial(400_000), core.purchasing.advanceLeft(adv))
        assertEquals("INVALID_STATE:SUPPLIER_ADVANCE:IN_USE", code { core.purchasing.reverseAdvance(ReverseSupplierAdvance(id(), branch, adv, day, "اشتباه")) })
        // We owe 900,000 and the supplier holds 400,000 of ours: 500,000 net.
        assertEquals(500_000L, core.purchasing.supplierBalance(supplier, branch).rial)
        assertEquals(500_000L, core.ledgerReads.supplierStatement(supplier, day, day).closing)
        core.purchasing.updateSupplier(UpdateSupplier(id(), supplier, "لبنیات", "021", true, emptySet(), null, 1, "", taxId = "0012345678", address = "تهران", sheba = "IR030170000000110000000000"))
        assertEquals("تهران", core.suppliers.byId(supplier)!!.address)
        assertEquals("INVALID_INPUT:sheba", code { core.purchasing.updateSupplier(UpdateSupplier(id(), supplier, "لبنیات", "021", true, emptySet(), null, 1, "", sheba = "IR000000000000000000000000")) })
    }

    @Test fun anOrderDeliveredInPartsStaysOpenUntilEverythingCame() {
        start()
        val order = core.orders.create(CreatePurchaseOrder(id(), branch, supplier, kitchen, day, day.plusDays(1), listOf(OrderLine(cheese, Quantity.units(10), rial(1_000_000))))).resultId
        core.purchasing.postInvoice(PostPurchaseInvoice(id(), branch, supplier, "1", kitchen, day, day, listOf(InvoiceLine(cheese, Quantity.units(4), rial(4_000_000))), orderId = order, orderPartial = true))
        val first = core.purchases.order(order)!!
        assertEquals(OrderStatus.OPEN, first.status)
        val inv2 = core.purchasing.postInvoice(PostPurchaseInvoice(id(), branch, supplier, "2", kitchen, day.plusDays(1), day.plusDays(1),
            listOf(InvoiceLine(cheese, Quantity.units(6), rial(6_000_000))), orderId = order, orderPartial = true)).resultId
        assertEquals(OrderStatus.RECEIVED, core.purchases.order(order)!!.status)
        assertEquals(0, core.purchases.invoice(inv2)!!.discrepancies.size)
    }

    @Test fun aDepositIsSetAgainstTheEventSaleAndTheShiftAndTerminalAreChecked() {
        start()
        val pizza = core.inventory.defineMenuItem(ir.sabou.inventory.DefineMenuItem(id(), "پیتزا")).resultId
        val customer = core.salesOps.registerCustomer(RegisterCustomer(id(), branch, "شرکت آرین", CustomerType.COMPANY, "021", rial(50_000_000))).resultId
        val deposit = core.salesOps.receiveDeposit(ReceiveCustomerDeposit(id(), branch, customer, cash, rial(5_000_000), day, "مراسم")).resultId
        assertEquals(-5_000_000L, core.ledger.balance(StandardAccounts.CUSTOMER_ADVANCES, branch).rial)
        val terminal = core.treasury.openAccount(OpenTreasuryAccount(id(), branch, "کارت‌خوان", TreasuryKind.CARD_TERMINAL)).resultId
        val sale = core.salesOps.saveDraft(SaveSaleDraft(id(), branch, day, kitchen, listOf(SaleLineInput(pizza, Quantity.units(10), rial(1_000_000))),
            Money.ZERO, Money.ZERO, Money.ZERO, listOf(Settlement.Credit(customer, rial(8_000_000), day.plusDays(10)), Settlement.Liquid(terminal, rial(2_000_000))))).resultId
        core.salesOps.post(PostDailySale(id(), branch, sale))
        val r = core.sales.receivablesOfSale(sale).single()
        core.salesOps.applyDeposit(ApplyCustomerDeposit(id(), branch, deposit, r.id, rial(5_000_000), day))
        assertEquals(rial(3_000_000), core.salesOps.outstanding(r.id))
        assertEquals(0L, core.ledger.balance(StandardAccounts.CUSTOMER_ADVANCES, branch).rial)
        assertEquals(3_000_000L, core.ledgerReads.customerStatement(customer, day, day).closing)
        // The cashier's shift count books a shortage at once; the day's close then starts from it.
        core.salesOps.countShift(CountCashShift(id(), branch, cash, rial(104_900_000), day, "شیفت صبح"))
        assertEquals(-100_000L, core.salesOps.shiftCounts(branch, day).single().difference)
        core.salesOps.closeDay(CloseSalesDay(id(), branch, day, cash, rial(104_900_000), mapOf(terminal to rial(1_950_000))))
        val closed = core.sales.day(branch, day)!!
        assertEquals(0L, closed.difference)
        assertEquals(-50_000L, closed.terminalDifferences[terminal])
    }
}
