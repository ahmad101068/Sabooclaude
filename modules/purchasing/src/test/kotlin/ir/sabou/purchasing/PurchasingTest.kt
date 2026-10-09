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
import ir.sabou.purchasing.memory.InMemoryApprovalRuleStore
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
import kotlin.test.assertContentEquals
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
    private val cheques = ir.sabou.treasury.memory.InMemoryChequeStore()
    private val treasury = TreasuryGateway(ledger, treasuryCap, tAccounts, movements, cheques)
    private val treasuryOps = TreasuryOperations(bus, treasury, treasuryCap, tAccounts)
    private val attachments = ir.sabou.platform.memory.InMemoryAttachmentStore()
    private val rules = InMemoryApprovalRuleStore()
    private val ops = PurchasingOperations(bus, ledger, registry.issue(ModuleId.PURCHASING), inventory, treasury, suppliers, purchases, attachments, rules)
    private val approvals = ApprovalOperations(bus, rules, purchases)
    private val orders = OrderOperations(bus, inventory, suppliers, purchases)
    private val day = BusinessDate(20_000)

    init { uow.register(journals, items, locations, stock, recipes, tAccounts, movements, suppliers, purchases, attachments, rules, cheques) }

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

    @Test fun tinyUnitPricesStillLetEveryUnitBeReturned() {
        val inv = invoice(no = "T-1", qty = 8, value = 5)
        repeat(8) { ops.returnGoods(ReturnToSupplier(GlobalId.new(), branchA, inv, listOf(IssueLine(cheese, kg(1))), day, "خراب")) }
        assertEquals(0, ops.outstanding(inv).rial)
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

    // ------------------------------------------------------------ Stage B: richer invoices


    @Test fun accountLinesBookExpensesAndOtherBranchShares() {
        val inv = ops.postInvoice(PostPurchaseInvoice(GlobalId.new(), branchA, supplier, "M-1", storeA, day, day.plusDays(10),
            listOf(InvoiceLine(cheese, kg(2), rial(400_000))),
            accountLines = listOf(AccountLine(StandardAccounts.RENT, rial(300_000), null, "اجاره انبار"),
                AccountLine(StandardAccounts.UTILITIES, rial(100_000), branchB, "قبض گاز شعبه B")))).resultId
        assertEquals(800_000, ops.outstanding(inv).rial)
        assertEquals(-800_000, ledger.balance(StandardAccounts.PAYABLE, branchA).rial)
        assertEquals(300_000, ledger.balance(StandardAccounts.RENT, branchA).rial)
        assertEquals(100_000, ledger.balance(StandardAccounts.UTILITIES, branchB).rial)
        assertEquals(0, ledger.balance(StandardAccounts.UTILITIES, branchA).rial)
        assertEquals(0, ledger.balance(StandardAccounts.INTER_BRANCH).rial)     // nets out across branches
        apMatchesSubLedger()
        ops.reverseInvoice(ReversePurchaseInvoice(GlobalId.new(), branchA, inv, day, "ورود اشتباه"))
        assertEquals(0, ledger.balance(StandardAccounts.UTILITIES, branchB).rial)
        assertEquals(0, ledger.balance(StandardAccounts.PAYABLE).rial)
        assertEquals(0, ledger.balance(StandardAccounts.INVENTORY).rial)
    }

    @Test fun accountLinesMayOnlyUseOpenExpenseAccounts() {
        for (code in listOf(StandardAccounts.SALARIES, StandardAccounts.INVENTORY, StandardAccounts.CASH)) {
            assertEquals("INVALID_INPUT:account", code {
                ops.postInvoice(PostPurchaseInvoice(GlobalId.new(), branchA, supplier, "X-$code", null, day, day, emptyList(),
                    accountLines = listOf(AccountLine(code, rial(1_000)))))
            })
        }
    }

    @Test fun anInvoiceWithoutGoodsNeedsNoLocation() {
        val inv = ops.postInvoice(PostPurchaseInvoice(GlobalId.new(), branchA, supplier, "S-1", null, day, day,
            emptyList(), accountLines = listOf(AccountLine(StandardAccounts.OTHER_EXPENSE, rial(250_000), memo = "تعمیر یخچال")))).resultId
        assertEquals(250_000, ops.outstanding(inv).rial)
        assertEquals("INVALID_INPUT:location", code {
            ops.postInvoice(PostPurchaseInvoice(GlobalId.new(), branchA, supplier, "S-2", null, day, day, listOf(InvoiceLine(cheese, kg(1), rial(1)))))
        })
        apMatchesSubLedger()
    }

    @Test fun unknownLinesAreHeldUntilAssignedToAnItemOrAnAccount() {
        val inv = ops.postInvoice(PostPurchaseInvoice(GlobalId.new(), branchA, supplier, "R-1", null, day, day, emptyList(),
            reviewLines = listOf(ReviewLine("پنیر پیتزا رنده ۲ کیلویی", "۲ بسته", rial(600_000)), ReviewLine("دستکش", "", rial(50_000))))).resultId
        assertEquals(650_000, ledger.balance(StandardAccounts.PURCHASES_PENDING_REVIEW, branchA).rial)
        assertEquals(650_000, ops.outstanding(inv).rial)
        assertEquals("INVALID_INPUT:target", code {
            ops.resolveReviewLine(ResolveReviewLine(GlobalId.new(), branchA, inv, 0, cheese, kg(1), storeA, StandardAccounts.RENT, day))
        })
        ops.resolveReviewLine(ResolveReviewLine(GlobalId.new(), branchA, inv, 0, cheese, kg(4), storeA, null, day))
        assertEquals(4, inventory.balance(cheese, storeA).quantity.micros / Quantity.SCALE)
        assertEquals(600_000, inventory.balance(cheese, storeA).value.rial)
        assertEquals(cheese, suppliers.aliases(supplier)[SupplierNames.normalize("پنير  پیتزا رنده ۲ كیلویی")])  // Arabic letters, extra space
        ops.resolveReviewLine(ResolveReviewLine(GlobalId.new(), branchA, inv, 1, null, null, null, StandardAccounts.OTHER_EXPENSE, day))
        assertEquals(0, ledger.balance(StandardAccounts.PURCHASES_PENDING_REVIEW, branchA).rial)
        assertEquals(50_000, ledger.balance(StandardAccounts.OTHER_EXPENSE, branchA).rial)
        assertEquals("INVALID_STATE:REVIEW_LINE:ALREADY_RESOLVED", code {
            ops.resolveReviewLine(ResolveReviewLine(GlobalId.new(), branchA, inv, 1, null, null, null, StandardAccounts.RENT, day))
        })
        assertEquals("INVALID_STATE:PURCHASE_INVOICE:HAS_RESOLVED_LINES", code {
            ops.reverseInvoice(ReversePurchaseInvoice(GlobalId.new(), branchA, inv, day, "اشتباه"))
        })
        apMatchesSubLedger()
    }

    @Test fun supplierItemNamesOnGoodsLinesAreRemembered() {
        ops.postInvoice(PostPurchaseInvoice(GlobalId.new(), branchA, supplier, "N-1", storeA, day, day,
            listOf(InvoiceLine(cheese, kg(1), rial(100_000), supplierItemName = "Mozzarella 1kg"))))
        assertEquals(cheese, suppliers.aliases(supplier)["mozzarella 1kg"])
    }

    @Test fun returnCreditSettlesOtherInvoicesThenStaysAsSupplierCredit() {
        fund(cashA, branchA, 10_000_000)
        val paid = invoice(no = "P-1", qty = 10, value = 2_000_000, payNow = ImmediatePayment(cashA, rial(2_000_000)))
        val open = invoice(no = "P-2", qty = 1, value = 500_000)
        ops.returnGoods(ReturnToSupplier(GlobalId.new(), branchA, paid, listOf(IssueLine(cheese, kg(10))), day, "کیفیت نامناسب"))
        assertEquals(0, ops.outstanding(paid).rial)
        assertEquals(0, ops.outstanding(open).rial)                      // credit settled the other open invoice
        assertEquals(1_500_000, ops.unappliedCredit(supplier, branchA).rial)
        assertEquals(-1_500_000, ops.supplierBalance(supplier, branchA).rial)
        apMatchesSubLedger()
        val later = invoice(no = "P-3", qty = 1, value = 1_000_000)
        assertEquals("INVALID_STATE:SUPPLIER_CREDIT:EXCEEDS_AVAILABLE", code {
            ops.applyCredit(ApplySupplierCredit(GlobalId.new(), branchA, later, rial(1_500_001), day))
        })
        val applied = ops.applyCredit(ApplySupplierCredit(GlobalId.new(), branchA, later, rial(1_000_000), day)).resultId
        assertEquals(0, ops.outstanding(later).rial)
        assertEquals(500_000, ops.unappliedCredit(supplier, branchA).rial)
        apMatchesSubLedger()
        assertEquals("INVALID_STATE:PURCHASE_INVOICE:HAS_CREDITS", code {
            ops.reverseInvoice(ReversePurchaseInvoice(GlobalId.new(), branchA, later, day, "اشتباه"))
        })
        ops.releaseAllocation(ReleaseCreditAllocation(GlobalId.new(), branchA, applied, "اصلاح فاکتور"))
        ops.reverseInvoice(ReversePurchaseInvoice(GlobalId.new(), branchA, later, day, "اشتباه"))
        assertEquals(1_500_000, ops.unappliedCredit(supplier, branchA).rial)
        apMatchesSubLedger()
    }

    @Test fun ordersCheckApprovedSuppliersAndCloseWhenTheInvoiceArrives() {
        val other = ops.registerSupplier(RegisterSupplier(GlobalId.new(), "کاله", "")).resultId
        inventoryOps.updateItem(ir.sabou.inventory.UpdateItem(GlobalId.new(), cheese, "پنیر", kg(1), kg(5), "", "", supplier, setOf(supplier), true))
        assertEquals("INVALID_STATE:ITEM:SUPPLIER_NOT_APPROVED", code {
            orders.create(CreatePurchaseOrder(GlobalId.new(), branchA, other, storeA, day, day.plusDays(1), listOf(OrderLine(cheese, kg(3), rial(200_000)))))
        }.substringBeforeLast(':'))
        val order = orders.create(CreatePurchaseOrder(GlobalId.new(), branchA, supplier, storeA, day, day.plusDays(1), listOf(OrderLine(cheese, kg(3), rial(200_000))))).resultId
        assertEquals(600_000, purchases.order(order)!!.total.rial)
        assertEquals(1, purchases.order(order)!!.number)
        val inv = ops.postInvoice(PostPurchaseInvoice(GlobalId.new(), branchA, supplier, "O-1", storeA, day, day,
            listOf(InvoiceLine(cheese, kg(3), rial(630_000))), orderId = order)).resultId
        assertEquals(OrderStatus.RECEIVED, purchases.order(order)!!.status)
        assertEquals(inv, purchases.order(order)!!.invoiceId)
        assertEquals("INVALID_STATE:PURCHASE_ORDER:RECEIVED", code {
            ops.postInvoice(PostPurchaseInvoice(GlobalId.new(), branchA, supplier, "O-2", storeA, day, day, listOf(InvoiceLine(cheese, kg(1), rial(1))), orderId = order))
        })
        ops.reverseInvoice(ReversePurchaseInvoice(GlobalId.new(), branchA, inv, day, "مبلغ اشتباه"))
        assertEquals(OrderStatus.OPEN, purchases.order(order)!!.status)       // open again for the corrected invoice
        orders.cancel(CancelPurchaseOrder(GlobalId.new(), branchA, order, "تأمین‌کننده نیاورد"))
        assertEquals(OrderStatus.CANCELLED, purchases.order(order)!!.status)
        assertEquals("INVALID_STATE:PURCHASE_ORDER:CANCELLED", code { orders.cancel(CancelPurchaseOrder(GlobalId.new(), branchA, order, "دوباره")) })
    }

    @Test fun storekeeperMayOrderButNotForAnotherBranch() {
        session.actor = Actor(GlobalId.new(), "store", Role.STOREKEEPER, setOf(branchA.branchId))
        orders.create(CreatePurchaseOrder(GlobalId.new(), branchA, supplier, storeA, day, day, listOf(OrderLine(cheese, kg(1), rial(1)))))
        assertTrue(code { orders.create(CreatePurchaseOrder(GlobalId.new(), branchB, supplier, storeB, day, day, listOf(OrderLine(cheese, kg(1), rial(1))))) }.startsWith("SCOPE_DENIED"))
        session.actor = Actor(GlobalId.new(), "cashier", Role.CASHIER, setOf(branchA.branchId))
        assertEquals("PERMISSION_DENIED:PURCHASE_ORDER", code { orders.create(CreatePurchaseOrder(GlobalId.new(), branchA, supplier, storeA, day, day, listOf(OrderLine(cheese, kg(1), rial(1))))) })
    }

    @Test fun nextDeliveryRespectsLeadTimeAndCutoff() {
        val saturday = generateSequence(day) { it.plusDays(1) }.first { Weekdays.index(it) == 0 }
        val s = Supplier(GlobalId.new(), "x", "", deliveryDays = setOf(1, 4), cutoffMinutes = 14 * 60, leadDays = 1)   // Sunday, Wednesday
        assertEquals(saturday.plusDays(1), s.nextDelivery(saturday, 13 * 60)!!.date)      // order Saturday before 14:00 for Sunday
        assertEquals(saturday.plusDays(4), s.nextDelivery(saturday, 15 * 60)!!.date)      // too late: next is Wednesday
        assertEquals(saturday.plusDays(3), s.nextDelivery(saturday, 15 * 60)!!.orderBy)
        assertEquals(null, s.copy(deliveryDays = emptySet()).nextDelivery(saturday, 0))
    }

    @Test fun attachmentsAreStoredOnceAndChecked() {
        val photo = ir.sabou.platform.AttachmentInput("invoice.jpg", "image/jpeg", ByteArray(2_000) { it.toByte() })
        val inv = ops.postInvoice(PostPurchaseInvoice(GlobalId.new(), branchA, supplier, "A-1", storeA, day, day,
            listOf(InvoiceLine(cheese, kg(1), rial(1_000))), attachments = listOf(photo, photo))).resultId
        assertEquals(1, attachments.of(PurchasingOperations.INVOICE, inv).size)
        ops.attach(AttachToInvoice(GlobalId.new(), branchA, inv, listOf(photo)))                // same file again: nothing new
        assertEquals(1, attachments.of(PurchasingOperations.INVOICE, inv).size)
        assertEquals("INVALID_INPUT:attachment", code {
            ops.attach(AttachToInvoice(GlobalId.new(), branchA, inv, listOf(ir.sabou.platform.AttachmentInput("x.exe", "application/octet-stream", ByteArray(10)))))
        })
        assertEquals("INVALID_INPUT:attachment", code {
            ops.attach(AttachToInvoice(GlobalId.new(), branchA, inv, listOf(ir.sabou.platform.AttachmentInput("big.pdf", "application/pdf", ByteArray(ir.sabou.platform.Attachments.MAX_BYTES + 1)))))
        })
        assertContentEquals(photo.bytes, attachments.content(attachments.of(PurchasingOperations.INVOICE, inv).single().id))
    }

    // ------------------------------------------------------------ Stage C: approvals and cheques

    @Test fun invoicesMatchingARuleNeedApprovalsByOtherPeopleBeforePayment() {
        fund(cashA, branchA, 10_000_000)
        approvals.saveRule(SaveApprovalRule(GlobalId.new(), null, "بالای ۱ میلیون", branchA, null, null, rial(1_000_000), 2))
        approvals.saveRule(SaveApprovalRule(GlobalId.new(), null, "کالا", null, supplier, InvoiceCategory.GOODS, rial(0), 1))
        val small = invoice(no = "S", value = 500_000)
        assertEquals(1, purchases.invoice(small)!!.requiredApprovals)               // only the supplier rule
        val big = invoice(no = "B", value = 2_000_000)
        assertEquals(2, purchases.invoice(big)!!.requiredApprovals)                 // the strictest rule wins
        assertTrue(code { ops.payInvoice(PaySupplierInvoice(GlobalId.new(), branchA, big, cashA, rial(1), day)) }.startsWith("INVALID_STATE:PURCHASE_INVOICE:NOT_APPROVED"))
        assertTrue(code { invoice(no = "C", value = 3_000_000, payNow = ImmediatePayment(cashA, rial(1))) }.startsWith("INVALID_STATE:PURCHASE_INVOICE:NOT_APPROVED"))
        val clerk = Actor(GlobalId.new(), "clerk", Role.STOREKEEPER, setOf(branchA.branchId))
        val m1 = Actor(GlobalId.new(), "manager-1", Role.MANAGER, setOf(branchA.branchId))
        val m2 = Actor(GlobalId.new(), "manager-2", Role.MANAGER, setOf(branchA.branchId))
        session.actor = clerk
        val clerkInvoice = invoice(no = "K", value = 2_000_000)
        assertEquals("PERMISSION_DENIED:PURCHASE_APPROVE", code { approvals.approve(ApproveInvoice(GlobalId.new(), branchA, clerkInvoice)) })
        session.actor = m1
        approvals.approve(ApproveInvoice(GlobalId.new(), branchA, clerkInvoice))
        assertEquals("INVALID_STATE:PURCHASE_INVOICE:SAME_APPROVER", code { approvals.approve(ApproveInvoice(GlobalId.new(), branchA, clerkInvoice)) })
        assertEquals("PERMISSION_DENIED:PURCHASE_UNAPPROVE", code { approvals.unapprove(UnapproveInvoice(GlobalId.new(), branchA, clerkInvoice, "اشتباه")) })
        val own = invoice(no = "M", value = 2_000_000)
        assertEquals("INVALID_STATE:PURCHASE_INVOICE:RECORDER_CANNOT_APPROVE", code { approvals.approve(ApproveInvoice(GlobalId.new(), branchA, own)) })
        session.actor = m2
        approvals.approve(ApproveInvoice(GlobalId.new(), branchA, clerkInvoice))
        assertEquals("INVALID_STATE:PURCHASE_INVOICE:ALREADY_APPROVED", code { approvals.approve(ApproveInvoice(GlobalId.new(), branchA, clerkInvoice)) })
        ops.payInvoice(PaySupplierInvoice(GlobalId.new(), branchA, clerkInvoice, cashA, rial(500_000), day))
        session.actor = Actor(GlobalId.new(), "owner2", Role.OWNER, emptySet())
        assertEquals("INVALID_STATE:PURCHASE_INVOICE:HAS_ACTIVE_PAYMENTS", code { approvals.unapprove(UnapproveInvoice(GlobalId.new(), branchA, clerkInvoice, "اشتباه")) })
        // Rules apply to invoices recorded after them: an inactive rule stops applying.
        rules.all().forEach { approvals.saveRule(SaveApprovalRule(GlobalId.new(), it.id, it.name, it.branch, it.supplierId, it.category, it.minAmount, it.steps, false)) }
        assertEquals(0, purchases.invoice(invoice(no = "Z", value = 5_000_000))!!.requiredApprovals)
        assertEquals("PERMISSION_DENIED:APPROVAL_RULES", run {
            session.actor = m1
            code { approvals.saveRule(SaveApprovalRule(GlobalId.new(), null, "x", null, null, null, rial(0), 1)) }
        })
    }

    @Test fun suppliersArePaidWithOurChequeOrACustomersChequeAndReversalVoidsIt() {
        val book = treasuryOps.openAccount(OpenTreasuryAccount(GlobalId.new(), branchA, "دسته‌چک", TreasuryKind.ISSUED_CHEQUES)).resultId
        val bankA = treasuryOps.openAccount(OpenTreasuryAccount(GlobalId.new(), branchA, "بانک A", TreasuryKind.BANK)).resultId
        val box = treasuryOps.openAccount(OpenTreasuryAccount(GlobalId.new(), branchA, "صندوق چک", TreasuryKind.RECEIVED_CHEQUES)).resultId
        val inv = invoice(value = 2_000_000)
        val ours = ir.sabou.treasury.ChequeDetails("777", "ملت", "", day.plusDays(20), "لبنیات پگاه", bankAccountId = bankA)
        val payment = ops.payInvoice(PaySupplierInvoice(GlobalId.new(), branchA, inv, book, rial(1_500_000), day, cheque = ours)).resultId
        assertEquals(500_000, ops.outstanding(inv).rial)
        assertEquals(-1_500_000, ledger.balance(StandardAccounts.CHEQUES_PAYABLE, branchA).rial)
        apMatchesSubLedger()
        treasuryOps.receipt(RecordReceipt(GlobalId.new(), branchA, box, ReceiptPurpose.OTHER_INCOME, rial(500_000), day, "چک مشتری",
            ir.sabou.treasury.ChequeDetails("55", "صادرات", "", day.plusDays(5), "مشتری")))
        val held = cheques.all().single { it.direction == ir.sabou.treasury.ChequeDirection.RECEIVED }.id
        ops.payInvoice(PaySupplierInvoice(GlobalId.new(), branchA, inv, box, rial(500_000), day, chequeId = held))
        assertEquals(0, ops.outstanding(inv).rial)
        assertEquals(ir.sabou.treasury.ChequeStatus.ENDORSED, treasury.cheque(held).status)
        ops.reversePayment(ReverseSupplierPayment(GlobalId.new(), branchA, payment, day, "چک باطل شد"))
        assertEquals(ir.sabou.treasury.ChequeStatus.VOID, cheques.all().single { it.details.number == "777" }.status)
        assertEquals(0, ledger.balance(StandardAccounts.CHEQUES_PAYABLE, branchA).rial)
        assertEquals(1_500_000, ops.outstanding(inv).rial)
        apMatchesSubLedger()
    }
}
