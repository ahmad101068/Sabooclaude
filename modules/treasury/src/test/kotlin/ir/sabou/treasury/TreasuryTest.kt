package ir.sabou.treasury

import ir.sabou.kernel.BranchId
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Scope
import ir.sabou.ledger.Ledger
import ir.sabou.ledger.LedgerAccessRegistry
import ir.sabou.ledger.LineDraft
import ir.sabou.ledger.StandardAccounts
import ir.sabou.ledger.memory.InMemoryAccountStore
import ir.sabou.ledger.memory.InMemoryJournalStore
import ir.sabou.ledger.memory.InMemoryPeriodStore
import ir.sabou.platform.Actor
import ir.sabou.platform.Command
import ir.sabou.platform.CommandBus
import ir.sabou.platform.ModuleId
import ir.sabou.platform.Permission
import ir.sabou.platform.Role
import ir.sabou.platform.memory.InMemoryAuditStore
import ir.sabou.platform.memory.InMemoryEventLog
import ir.sabou.platform.memory.InMemoryDocumentNumberStore
import ir.sabou.platform.memory.InMemoryIdempotencyStore
import ir.sabou.platform.memory.InMemoryUnitOfWork
import ir.sabou.platform.memory.MutableSession
import ir.sabou.treasury.memory.InMemoryMovementStore
import ir.sabou.treasury.memory.InMemoryTreasuryAccountStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Each test maps to an audit finding of the previous code base; the old behaviour must be impossible here.
 */
class TreasuryTest {
    private val branchA = BranchId(GlobalId.new())
    private val branchB = BranchId(GlobalId.new())
    private val owner = Actor(GlobalId.new(), "owner", Role.OWNER, emptySet())
    private val session = MutableSession(owner)
    private val uow = InMemoryUnitOfWork()
    private val journals = InMemoryJournalStore()
    private val movements = InMemoryMovementStore()
    private val treasuryAccounts = InMemoryTreasuryAccountStore()
    private val numbers = InMemoryDocumentNumberStore().also { uow.register(it) }
    private val bus = CommandBus(session, uow, InMemoryIdempotencyStore().also { uow.register(it) },
        InMemoryAuditStore().also { uow.register(it) }, InMemoryEventLog(), Clock { 1L }, numbers) { "e1" }
    private val registry = LedgerAccessRegistry()
    private val ledger = Ledger(registry, InMemoryAccountStore(StandardAccounts.chart()), journals, InMemoryPeriodStore())
    private val treasuryCap = registry.issue(ModuleId.TREASURY)
    private val purchasingCap = registry.issue(ModuleId.PURCHASING)
    private val gateway = TreasuryGateway(ledger, treasuryCap, treasuryAccounts, movements)
    private val ops = TreasuryOperations(bus, gateway, treasuryCap, treasuryAccounts)
    private val day = BusinessDate(20_000)

    init { uow.register(journals, movements, treasuryAccounts) }

    private fun code(block: () -> Unit) = assertFailsWith<DomainException> { block() }.error.code
    private fun rial(v: Long) = Money.of(v)
    private fun open(scope: Scope, kind: TreasuryKind, name: String) =
        ops.openAccount(OpenTreasuryAccount(GlobalId.new(), scope, name, kind)).resultId
    private fun capital(account: GlobalId, scope: Scope, amount: Long) =
        ops.receipt(RecordReceipt(GlobalId.new(), scope, account, ReceiptPurpose.OWNER_CAPITAL, rial(amount), day, "آورده"))

    /** A minimal stand-in for the purchasing module paying a supplier invoice. */
    private fun payInvoice(account: GlobalId, invoiceId: GlobalId, amount: Long, scope: Scope) =
        bus.execute(ModuleId.PURCHASING, Simple(scope, Permission.PURCHASE_PAY)) { _, ctx ->
            gateway.settle(ctx, purchasingCap, account, Direction.PAYMENT, rial(amount), day, "PURCHASE_INVOICE", invoiceId,
                "پرداخت فاکتور", listOf(LineDraft(StandardAccounts.PAYABLE, debit = rial(amount), by = purchasingCap))).movement.id
        }

    @Test fun eachBranchHasItsOwnCashAndCanDepositToTheOrganizationBank_AUD008() {
        val cashA = open(Scope.Branch(branchA), TreasuryKind.CASH, "صندوق A")
        val bank = open(Scope.Organization, TreasuryKind.BANK, "بانک ملت")
        capital(cashA, Scope.Branch(branchA), 5_000_000)
        ops.transfer(TransferFunds(GlobalId.new(), Scope.Branch(branchA), cashA, bank, rial(4_000_000), day, "واریز به بانک"))
        assertEquals(1_000_000, gateway.balance(cashA))
        assertEquals(4_000_000, gateway.balance(bank))
        // Each scope's books stay balanced through the inter-branch account.
        assertEquals(4_000_000, ledger.balance(StandardAccounts.INTER_BRANCH, Scope.Branch(branchA)).rial)
        assertEquals(-4_000_000, ledger.balance(StandardAccounts.INTER_BRANCH, Scope.Organization).rial)
        assertEquals(0, ledger.balance(StandardAccounts.INTER_BRANCH).rial)
    }

    @Test fun overdraftIsRejected() {
        val cashA = open(Scope.Branch(branchA), TreasuryKind.CASH, "صندوق A")
        capital(cashA, Scope.Branch(branchA), 100)
        assertTrue(code {
            ops.payment(RecordPayment(GlobalId.new(), Scope.Branch(branchA), cashA, PaymentPurpose.RENT, rial(101), day, "اجاره"))
        }.startsWith("INSUFFICIENT_FUNDS"))
    }

    @Test fun reversingAReceiptCannotDriveCashNegative_AUD009() {
        val cashA = open(Scope.Branch(branchA), TreasuryKind.CASH, "صندوق A")
        val receipt = capital(cashA, Scope.Branch(branchA), 1_000).resultId
        ops.payment(RecordPayment(GlobalId.new(), Scope.Branch(branchA), cashA, PaymentPurpose.RENT, rial(1_000), day, "اجاره"))
        assertTrue(code {
            ops.reverse(ReverseTreasuryDocument(GlobalId.new(), Scope.Branch(branchA), TreasuryOperations.RECEIPT, receipt, day, "اشتباه"))
        }.startsWith("INSUFFICIENT_FUNDS"))
        assertEquals(0, gateway.balance(cashA))
    }

    @Test fun treasuryCannotReverseAPurchasePayment_AUD002() {
        val cashA = open(Scope.Branch(branchA), TreasuryKind.CASH, "صندوق A")
        capital(cashA, Scope.Branch(branchA), 3_000_000)
        val invoice = GlobalId.new()
        payInvoice(cashA, invoice, 2_000_000, Scope.Branch(branchA))
        assertTrue(code {
            ops.reverse(ReverseTreasuryDocument(GlobalId.new(), Scope.Branch(branchA), "PURCHASE_INVOICE", invoice, day, "برگشت"))
        }.startsWith("OWNED_BY"))
        // Only the owning module can reverse it, and then cash and AP are both restored together.
        bus.execute(ModuleId.PURCHASING, Simple(Scope.Branch(branchA), Permission.PURCHASE_REVERSE)) { _, ctx ->
            gateway.reverseDocument(ctx, purchasingCap, "PURCHASE_INVOICE", invoice, day, "پرداخت اشتباه").first().movement.id
        }
        assertEquals(3_000_000, gateway.balance(cashA))
        assertEquals(0, ledger.balance(StandardAccounts.PAYABLE).rial)
        assertEquals(3_000_000, ledger.balance(StandardAccounts.CASH).rial)
    }

    @Test fun treasuryHasNoWayToSettleSuppliersOrCustomers_AUD003() {
        // Purpose enums only map to open accounts; AP/AR settlement is not expressible here.
        val counterAccounts = ReceiptPurpose.entries.map { it.counterAccount } + PaymentPurpose.entries.map { it.counterAccount }
        assertTrue(StandardAccounts.PAYABLE !in counterAccounts && StandardAccounts.RECEIVABLE !in counterAccounts)
        // And treasury's capability cannot write the AP control account even if someone tries.
        val cashA = open(Scope.Branch(branchA), TreasuryKind.CASH, "صندوق A")
        capital(cashA, Scope.Branch(branchA), 1_000)
        assertEquals("CONTROL_ACCOUNT:2101", code {
            bus.execute(ModuleId.TREASURY, Simple(Scope.Branch(branchA), Permission.TREASURY_PAYMENT)) { _, ctx ->
                gateway.settle(ctx, treasuryCap, cashA, Direction.PAYMENT, rial(1_000), day, "FAKE", GlobalId.new(), "x",
                    listOf(LineDraft(StandardAccounts.PAYABLE, debit = rial(1_000), by = treasuryCap))).movement.id
            }
        })
    }

    @Test fun branchManagerCannotTouchAnotherBranchsCash_AUD004() {
        val cashA = open(Scope.Branch(branchA), TreasuryKind.CASH, "صندوق A")
        val cashB = open(Scope.Branch(branchB), TreasuryKind.CASH, "صندوق B")
        capital(cashB, Scope.Branch(branchB), 1_000)
        session.actor = Actor(GlobalId.new(), "manager-A", Role.MANAGER, setOf(branchA))
        assertTrue(code {
            ops.payment(RecordPayment(GlobalId.new(), Scope.Branch(branchB), cashB, PaymentPurpose.RENT, rial(10), day, "اجاره"))
        }.startsWith("SCOPE_DENIED"))
        // Declaring an allowed scope but pointing at another branch's account is also refused.
        assertTrue(code {
            ops.payment(RecordPayment(GlobalId.new(), Scope.Branch(branchA), cashB, PaymentPurpose.RENT, rial(10), day, "اجاره"))
        }.startsWith("INVALID_INPUT"))
        assertTrue(code {
            ops.transfer(TransferFunds(GlobalId.new(), Scope.Branch(branchA), cashA, cashB, rial(1), day, "انتقال"))
        }.startsWith("SCOPE_DENIED"))
    }

    @Test fun reconciliationBooksDifferenceToCashOverShort() {
        val cashA = open(Scope.Branch(branchA), TreasuryKind.CASH, "صندوق A")
        capital(cashA, Scope.Branch(branchA), 10_000)
        ops.reconcile(ReconcileAccount(GlobalId.new(), Scope.Branch(branchA), cashA, rial(9_700), day, "شمارش پایان روز"))
        assertEquals(9_700, gateway.balance(cashA))
        assertEquals(300, ledger.balance(StandardAccounts.CASH_OVER_SHORT).rial)
    }

    @Test fun retriedPaymentIsRecordedOnce() {
        val cashA = open(Scope.Branch(branchA), TreasuryKind.CASH, "صندوق A")
        capital(cashA, Scope.Branch(branchA), 10_000)
        val pay = RecordPayment(GlobalId.new(), Scope.Branch(branchA), cashA, PaymentPurpose.UTILITIES, rial(4_000), day, "قبض برق")
        ops.payment(pay); ops.payment(pay)
        assertEquals(6_000, gateway.balance(cashA))
    }

    private class Simple(override val scope: Scope, override val requiredPermission: Permission) : Command {
        override val commandId: GlobalId = GlobalId.new()
        override fun fingerprint() = "simple"
    }
}
