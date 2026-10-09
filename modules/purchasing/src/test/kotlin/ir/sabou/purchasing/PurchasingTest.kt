package ir.sabou.purchasing

import ir.sabou.inventory.CreateItem
import ir.sabou.inventory.CreateLocation
import ir.sabou.inventory.InventoryGateway
import ir.sabou.inventory.InventoryOperations
import ir.sabou.inventory.IssueLine
import ir.sabou.inventory.StockUnit
import ir.sabou.inventory.memory.InMemoryItemStore
import ir.sabou.inventory.memory.InMemoryLocationStore
import ir.sabou.inventory.memory.InMemoryRecipeStore
import ir.sabou.inventory.memory.InMemoryStockStore
import ir.sabou.kernel.BranchId
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.ledger.Ledger
import ir.sabou.ledger.LedgerAccessRegistry
import ir.sabou.ledger.StandardAccounts
import ir.sabou.ledger.memory.InMemoryAccountStore
import ir.sabou.ledger.memory.InMemoryJournalStore
import ir.sabou.ledger.memory.InMemoryPeriodStore
import ir.sabou.platform.Actor
import ir.sabou.platform.CommandBus
import ir.sabou.platform.ModuleId
import ir.sabou.platform.Role
import ir.sabou.platform.memory.InMemoryAuditStore
import ir.sabou.platform.memory.InMemoryEventLog
import ir.sabou.platform.memory.InMemoryIdempotencyStore
import ir.sabou.platform.memory.InMemoryUnitOfWork
import ir.sabou.platform.memory.MutableSession
import ir.sabou.purchasing.memory.InMemoryPurchaseStore
import ir.sabou.purchasing.memory.InMemorySupplierStore
import ir.sabou.treasury.OpenTreasuryAccount
import ir.sabou.treasury.ReceiptPurpose
import ir.sabou.treasury.RecordReceipt
import ir.sabou.treasury.TreasuryGateway
import ir.sabou.treasury.TreasuryKind
import ir.sabou.treasury.TreasuryOperations
import ir.sabou.treasury.memory.InMemoryMovementStore
import ir.sabou.treasury.memory.InMemoryTreasuryAccountStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PurchasingTest {
    private val branchA = Scope.Branch(BranchId(GlobalId.new()))
    private val branchB = Scope.Branch(BranchId(GlobalId.new()))
    private val session = MutableSession(Actor(GlobalId.new(), "owner", Role.OWNER, emptySet()))
    private val uow = InMemoryUnitOfWork()
    private val journals = InMemoryJournalStore()
    private val items = InMemoryItemStore(); private val locations = InMemoryLocationStore()
    private val stock = InMemoryStockStore(); private val recipes = InMemoryRecipeStore()
    private val tAccounts = InMemoryTreasuryAccountStore(); private val movements = InMemoryMovementStore()
    private val suppliers = InMemorySupplierStore(); private val purchases = InMemoryPurchaseStore()
    private val bus = CommandBus(session, uow, InMemoryIdempotencyStore().also { uow.register(it) },
        InMemoryAuditStore().also { uow.register(it) }, InMemoryEventLog(), Clock { 1L }) { "e1" }
    private val registry = LedgerAccessRegistry()
    private val ledger = Ledger(registry, InMemoryAccountStore(StandardAccounts.chart()), journals, InMemoryPeriodStore())
    private val inventory = InventoryGateway(ledger, registry.issue(ModuleId.INVENTORY), items, locations, stock)
    private val inventoryOps = InventoryOperations(bus, inventory, items, locations, recipes)
    private val treasuryCap = registry.issue(ModuleId.TREASURY)
    private val treasury = TreasuryGateway(ledger, treasuryCap, tAccounts, movements)
    private val treasuryOps = TreasuryOperations(bus, treasury, treasuryCap, tAccounts)
    private val ops = PurchasingOperations(bus, ledger, registry.issue(ModuleId.PURCHASING), inventory, treasury, suppliers, purchases)
    private val day = BusinessDate(20_000)

    init { uow.register(journals, items, locations, stock, recipes, tAccounts, movements, suppliers, purchases) }

    private fun code(block: () -> Unit) = assertFailsWith<DomainException> { block() }.error.code
    private fun rial(v: Long) = Money.of(v)
    private fun kg(v: Long) = Quantity.units(v)
    private val cheese = inventoryOps.createItem(CreateItem(GlobalId.new(), "پنیر", StockUnit.KILOGRAM, kg(1))).resultId
    private val storeA = inventoryOps.createLocation(CreateLocation(GlobalId.new(), branchA, "انبار A")).resultId
    private val storeB = inventoryOps.createLocation(CreateLocation(GlobalId.new(), branchB, "انبار B")).resultId
    private val supplier = ops.registerSupplier(RegisterSupplier(GlobalId.new(), "لبنیات پگاه", "021")).resultId
    private val cashA = treasuryOps.openAccount(OpenTreasuryAccount(GlobalId.new(), branchA, "صندوق A", TreasuryKind.CASH)).resultId
    private val bank = treasuryOps.openAccount(OpenTreasuryAccount(GlobalId.new(), Scope.Organization, "بانک ملت", TreasuryKind.BANK)).resultId

    private fun fund(account: GlobalId, scope: Scope, amount: Long) =
        treasuryOps.receipt(RecordReceipt(GlobalId.new(), scope, account, ReceiptPurpose.OWNER_CAPITAL, rial(amount), day, "آورده"))

    private fun invoice(no: String = "1001", qty: Long = 10, value: Long = 2_000_000, scope: Scope.Branch = branchA, store: GlobalId = storeA, payNow: ImmediatePayment? = null) =
        ops.postInvoice(PostPurchaseInvoice(GlobalId.new(), scope, supplier, no, store, day, day.plusDays(30), listOf(InvoiceLine(cheese, kg(qty), rial(value))), payNow)).resultId

    private fun apMatchesSubLedger() {
        for (scope in listOf(branchA, branchB)) {
            assertEquals(ops.supplierBalance(supplier, scope).rial, -ledger.balance(StandardAccounts.PAYABLE, scope).rial, "AP vs sub-ledger in $scope")
        }
    }

    @Test fun invoiceRaisesStockAndPayableTogether() {
        invoice()
        assertEquals(2_000_000, inventory.balance(cheese, storeA).value.rial)
        assertEquals(2_000_000, ledger.balance(StandardAccounts.INVENTORY, branchA).rial)
        apMatchesSubLedger()
    }

    @Test fun sameSupplierInvoiceNumberCannotBeEnteredTwice() {
        invoice(no = "۱۰۰۱")
        assertEquals("INVALID_STATE:PURCHASE_INVOICE:DUPLICATE_NUMBER", code { invoice(no = " 10-01 ") })
    }

    @Test fun aReversedInvoiceFreesItsNumberForTheCorrectedOne() {
        val wrong = invoice(no = "X-9", value = 3_000_000)
        ops.reverseInvoice(ReversePurchaseInvoice(GlobalId.new(), branchA, wrong, day, "مبلغ اشتباه"))
        invoice(no = "X-9", value = 2_000_000)
        assertEquals("INVALID_STATE:PURCHASE_INVOICE:DUPLICATE_NUMBER", code { invoice(no = "X-9") })
        apMatchesSubLedger()
    }

    @Test fun returningEverythingUnitByUnitCreditsExactlyTheInvoice() {
        val inv = invoice(qty = 3, value = 200_000)
        repeat(3) { ops.returnGoods(ReturnToSupplier(GlobalId.new(), branchA, inv, listOf(IssueLine(cheese, kg(1))), day, "خراب")) }
        assertEquals(0, ops.outstanding(inv).rial)
        assertEquals(0, ledger.balance(StandardAccounts.PAYABLE, branchA).rial)
        assertEquals(0, ledger.balance(StandardAccounts.INVENTORY, branchA).rial)
        apMatchesSubLedger()
    }

    @Test fun payingWhileRecordingNeedsThePaymentPermission() {
        fund(cashA, branchA, 5_000_000)
        session.actor = Actor(GlobalId.new(), "store", Role.STOREKEEPER, setOf(branchA.branchId))
        assertEquals("PERMISSION_DENIED:PURCHASE_PAY", code { invoice(payNow = ImmediatePayment(cashA, rial(2_000_000))) })
        invoice()   // recording alone is allowed
        assertEquals(5_000_000, treasury.balance(cashA))
    }

    @Test fun partialPaymentsAndNoOverpayment() {
        fund(cashA, branchA, 5_000_000)
        val inv = invoice()
        ops.payInvoice(PaySupplierInvoice(GlobalId.new(), branchA, inv, cashA, rial(500_000), day))
        assertEquals(1_500_000, ops.outstanding(inv).rial)
        assertEquals("INVALID_STATE:PURCHASE_INVOICE:PAYMENT_EXCEEDS_OUTSTANDING", code {
            ops.payInvoice(PaySupplierInvoice(GlobalId.new(), branchA, inv, cashA, rial(1_500_001), day))
        })
        apMatchesSubLedger()
        assertEquals(4_500_000, treasury.balance(cashA))
    }

    @Test fun branchInvoicePaidFromOrganizationBankClearsTheBranchPayable() {
        fund(bank, Scope.Organization, 10_000_000)
        val inv = invoice()
        ops.payInvoice(PaySupplierInvoice(GlobalId.new(), branchA, inv, bank, rial(2_000_000), day))
        assertEquals(0, ledger.balance(StandardAccounts.PAYABLE, branchA).rial)
        assertEquals(0, ledger.balance(StandardAccounts.INTER_BRANCH).rial)
        assertEquals(8_000_000, treasury.balance(bank))
        apMatchesSubLedger()
    }

    @Test fun paymentReversalRestoresPayableAndCashOnce() {
        fund(bank, Scope.Organization, 10_000_000)
        val inv = invoice()
        val payment = ops.payInvoice(PaySupplierInvoice(GlobalId.new(), branchA, inv, bank, rial(2_000_000), day)).resultId
        ops.reversePayment(ReverseSupplierPayment(GlobalId.new(), branchA, payment, day, "پرداخت اشتباه"))
        assertEquals(2_000_000, ops.outstanding(inv).rial)
        assertEquals(10_000_000, treasury.balance(bank))
        assertEquals(0, ledger.balance(StandardAccounts.INTER_BRANCH).rial)
        apMatchesSubLedger()
        assertEquals("INVALID_STATE:SUPPLIER_PAYMENT:ALREADY_REVERSED", code {
            ops.reversePayment(ReverseSupplierPayment(GlobalId.new(), branchA, payment, day, "دوباره"))
        })
    }

    @Test fun invoiceWithActivePaymentCannotBeReversed() {
        fund(cashA, branchA, 5_000_000)
        val inv = invoice(payNow = ImmediatePayment(cashA, rial(2_000_000)))
        assertEquals(0, ops.outstanding(inv).rial)
        assertEquals("INVALID_STATE:PURCHASE_INVOICE:HAS_ACTIVE_PAYMENTS", code {
            ops.reverseInvoice(ReversePurchaseInvoice(GlobalId.new(), branchA, inv, day, "اشتباه"))
        })
        val payment = purchases.payments(inv).single().id
        ops.reversePayment(ReverseSupplierPayment(GlobalId.new(), branchA, payment, day, "برگشت پرداخت"))
        ops.reverseInvoice(ReversePurchaseInvoice(GlobalId.new(), branchA, inv, day, "فاکتور اشتباه"))
        assertEquals(0, inventory.balance(cheese, storeA).quantity.micros)
        assertEquals(0, ledger.balance(StandardAccounts.INVENTORY).rial)
        assertEquals(0, ledger.balance(StandardAccounts.PAYABLE).rial)
        apMatchesSubLedger()
    }

    @Test fun returnAtInvoicePriceKeepsStockAndBooksEqual() {
        invoice(no = "A1", qty = 10, value = 1_000_000)
        val inv2 = invoice(no = "A2", qty = 10, value = 2_000_000)   // average is now 150,000 per kg
        ops.returnGoods(ReturnToSupplier(GlobalId.new(), branchA, inv2, listOf(IssueLine(cheese, kg(2))), day, "کیفیت نامناسب"))
        assertEquals(1_600_000, ops.outstanding(inv2).rial)          // credited at invoice price 200,000/kg
        assertEquals(-100_000, ledger.balance(StandardAccounts.INVENTORY_VARIANCE, branchA).rial) // price above cost
        assertEquals(inventory.balance(cheese, storeA).value.rial, ledger.balance(StandardAccounts.INVENTORY, branchA).rial)
        apMatchesSubLedger()
        assertEquals("INVALID_INPUT:quantity", code {
            ops.returnGoods(ReturnToSupplier(GlobalId.new(), branchA, inv2, listOf(IssueLine(cheese, kg(9))), day, "بیش از خرید"))
        })
    }

    @Test fun managerOfBranchACannotTouchBranchBInvoices() {
        val invB = invoice(no = "B1", scope = branchB, store = storeB)
        session.actor = Actor(GlobalId.new(), "manager-A", Role.MANAGER, setOf(branchA.branchId))
        assertTrue(code { ops.payInvoice(PaySupplierInvoice(GlobalId.new(), branchB, invB, cashA, rial(1), day)) }.startsWith("SCOPE_DENIED"))
        assertTrue(code { ops.payInvoice(PaySupplierInvoice(GlobalId.new(), branchA, invB, cashA, rial(1), day)) }.startsWith("INVALID_INPUT:scope"))
    }
}
