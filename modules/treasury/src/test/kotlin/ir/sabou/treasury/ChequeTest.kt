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
import ir.sabou.treasury.memory.InMemoryChequeStore
import ir.sabou.treasury.memory.InMemoryMovementStore
import ir.sabou.treasury.memory.InMemoryTreasuryAccountStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChequeTest {
    private val branch = Scope.Branch(BranchId(GlobalId.new()))
    private val session = MutableSession(Actor(GlobalId.new(), "owner", Role.OWNER, emptySet()))
    private val uow = InMemoryUnitOfWork()
    private val journals = InMemoryJournalStore()
    private val movements = InMemoryMovementStore()
    private val accounts = InMemoryTreasuryAccountStore()
    private val cheques = InMemoryChequeStore()
    private val bus = CommandBus(session, uow, InMemoryIdempotencyStore().also { uow.register(it) },
        InMemoryAuditStore().also { uow.register(it) }, InMemoryEventLog(), Clock { 1L }) { "e1" }
    private val registry = LedgerAccessRegistry()
    private val ledger = Ledger(registry, InMemoryAccountStore(StandardAccounts.chart()), journals, InMemoryPeriodStore())
    private val cap = registry.issue(ModuleId.TREASURY)
    private val gateway = TreasuryGateway(ledger, cap, accounts, movements, cheques)
    private val ops = TreasuryOperations(bus, gateway, cap, accounts)
    private val chequeOps = ChequeOperations(bus, gateway, cap)
    private val day = BusinessDate(20_000)

    init { uow.register(journals, movements, accounts, cheques) }

    private fun code(block: () -> Unit) = assertFailsWith<DomainException> { block() }.error.code
    private fun rial(v: Long) = Money.of(v)
    private fun open(kind: TreasuryKind, name: String) = ops.openAccount(OpenTreasuryAccount(GlobalId.new(), branch, name, kind)).resultId
    private val box = open(TreasuryKind.RECEIVED_CHEQUES, "صندوق چک")
    private val book = open(TreasuryKind.ISSUED_CHEQUES, "دسته‌چک ملت")
    private val bank = open(TreasuryKind.BANK, "بانک ملت")
    private val cash = open(TreasuryKind.CASH, "صندوق")
    private fun details(no: String = "123456", bankAccount: GlobalId? = null) =
        ChequeDetails(no, "ملت", "", day.plusDays(30), "آقای رضایی", bankAccountId = bankAccount)

    private fun receive(amount: Long = 5_000_000, no: String = "123456"): GlobalId {
        ops.receipt(RecordReceipt(GlobalId.new(), branch, box, ReceiptPurpose.OTHER_INCOME, rial(amount), day, "چک دریافتی", details(no)))
        return cheques.all().single { it.details.number == no }.id
    }

    private fun gl(code: ir.sabou.ledger.AccountCode) = ledger.balance(code, branch).rial

    @Test fun aChequeBoxOnlyTakesChequesAndTheirDetails() {
        assertEquals("INVALID_INPUT:cheque", code { ops.receipt(RecordReceipt(GlobalId.new(), branch, box, ReceiptPurpose.OTHER_INCOME, rial(1), day, "x")) })
        assertEquals("INVALID_INPUT:cheque", code {
            ops.receipt(RecordReceipt(GlobalId.new(), branch, cash, ReceiptPurpose.OTHER_INCOME, rial(1), day, "x", details()))
        })
        assertEquals("INVALID_INPUT:sayadId", code {
            ops.receipt(RecordReceipt(GlobalId.new(), branch, box, ReceiptPurpose.OTHER_INCOME, rial(1), day, "x", details().copy(sayadId = "12")))
        })
        assertEquals("INVALID_INPUT:account", code { ops.transfer(TransferFunds(GlobalId.new(), branch, box, bank, rial(1), day, "")) })
    }

    @Test fun receivedChequeIsDepositedAndCollectedIntoTheBank() {
        val id = receive()
        assertEquals(5_000_000, gl(StandardAccounts.CHEQUES_RECEIVABLE))
        assertEquals(5_000_000, gateway.balance(box))
        chequeOps.deposit(DepositCheque(GlobalId.new(), branch, id, bank, day))
        assertEquals(ChequeStatus.DEPOSITED, cheques.byId(id)!!.status)
        assertEquals(0, gateway.balance(bank))                                    // nothing moves until it is collected
        val collection = chequeOps.collect(CollectCheque(GlobalId.new(), branch, id, bank, day.plusDays(30))).resultId
        assertEquals(ChequeStatus.COLLECTED, cheques.byId(id)!!.status)
        assertEquals(5_000_000, gateway.balance(bank))
        assertEquals(0, gateway.balance(box))
        assertEquals(0, gl(StandardAccounts.CHEQUES_RECEIVABLE))
        // Reversing the collection puts it back where it was (deposited) and takes the money out of the bank.
        ops.reverse(ReverseTreasuryDocument(GlobalId.new(), branch, TreasuryOperations.CHEQUE_COLLECT, collection, day.plusDays(30), "اشتباه"))
        assertEquals(ChequeStatus.DEPOSITED, cheques.byId(id)!!.status)
        assertEquals(0, gateway.balance(bank))
        assertEquals(5_000_000, gateway.balance(box))
    }

    @Test fun bouncedChequeBecomesAClaimUntilSettled() {
        val id = receive(2_000_000)
        assertEquals("INVALID_STATE:CHEQUE", code { chequeOps.settleBounced(SettleBouncedCheque(GlobalId.new(), branch, id, cash, day)) }.substringBeforeLast(':'))
        chequeOps.bounce(BounceCheque(GlobalId.new(), branch, id, day, "کسری موجودی"))
        assertEquals(ChequeStatus.BOUNCED, cheques.byId(id)!!.status)
        assertEquals(2_000_000, gl(StandardAccounts.BOUNCED_CHEQUES_RECEIVABLE))
        assertEquals(0, gateway.balance(box))
        chequeOps.settleBounced(SettleBouncedCheque(GlobalId.new(), branch, id, cash, day))
        assertEquals(ChequeStatus.SETTLED, cheques.byId(id)!!.status)
        assertEquals(0, gl(StandardAccounts.BOUNCED_CHEQUES_RECEIVABLE))
        assertEquals(2_000_000, gateway.balance(cash))
        assertEquals("INVALID_STATE:CHEQUE", code { chequeOps.bounce(BounceCheque(GlobalId.new(), branch, id, day, "دوباره")) }.substringBeforeLast(':'))
    }

    @Test fun aHeldChequeCanPayAnExpenseOnlyForItsExactAmount() {
        val id = receive(1_000_000)
        assertEquals("INVALID_INPUT:amount", code {
            ops.payment(RecordPayment(GlobalId.new(), branch, box, PaymentPurpose.RENT, rial(999_999), day, "اجاره", chequeId = id))
        })
        assertEquals("INVALID_INPUT:cheque", code { ops.payment(RecordPayment(GlobalId.new(), branch, box, PaymentPurpose.RENT, rial(1_000_000), day, "اجاره")) })
        ops.payment(RecordPayment(GlobalId.new(), branch, box, PaymentPurpose.RENT, rial(1_000_000), day, "اجاره", chequeId = id))
        assertEquals(ChequeStatus.ENDORSED, cheques.byId(id)!!.status)
        assertEquals(1_000_000, gl(StandardAccounts.RENT))
        assertEquals(0, gateway.balance(box))
    }

    @Test fun ourChequeIsALiabilityUntilTheBankClearsIt() {
        ops.receipt(RecordReceipt(GlobalId.new(), branch, bank, ReceiptPurpose.OWNER_CAPITAL, rial(10_000_000), day, "آورده"))
        assertEquals("INVALID_INPUT:bankAccount", code {
            ops.payment(RecordPayment(GlobalId.new(), branch, book, PaymentPurpose.RENT, rial(3_000_000), day, "اجاره", cheque = details("900001")))
        })
        val doc = ops.payment(RecordPayment(GlobalId.new(), branch, book, PaymentPurpose.RENT, rial(3_000_000), day, "اجاره", cheque = details("900001", bank))).resultId
        val id = cheques.all().single().id
        assertEquals(ChequeStatus.ISSUED, cheques.byId(id)!!.status)
        assertEquals(-3_000_000, gl(StandardAccounts.CHEQUES_PAYABLE))
        assertEquals(-3_000_000, gateway.balance(book))
        assertEquals(10_000_000, gateway.balance(bank))                            // still in the bank until cleared
        val clear = chequeOps.clear(ClearIssuedCheque(GlobalId.new(), branch, id, day.plusDays(30))).resultId
        assertEquals(ChequeStatus.CLEARED, cheques.byId(id)!!.status)
        assertEquals(7_000_000, gateway.balance(bank))
        assertEquals(0, gl(StandardAccounts.CHEQUES_PAYABLE))
        // The payment that issued it can no longer be reversed: the cheque has moved on.
        assertTrue(code {
            ops.reverse(ReverseTreasuryDocument(GlobalId.new(), branch, TreasuryOperations.PAYMENT, doc, day.plusDays(30), "اشتباه"))
        }.startsWith("INVALID_STATE:CHEQUE:MOVED_ON"))
        ops.reverse(ReverseTreasuryDocument(GlobalId.new(), branch, TreasuryOperations.CHEQUE_CLEAR, clear, day.plusDays(30), "پاس نشده بود"))
        ops.reverse(ReverseTreasuryDocument(GlobalId.new(), branch, TreasuryOperations.PAYMENT, doc, day.plusDays(30), "صادر نشد"))
        assertEquals(ChequeStatus.VOID, cheques.byId(id)!!.status)
        assertEquals(0, gl(StandardAccounts.CHEQUES_PAYABLE))
        assertEquals(10_000_000, gateway.balance(bank))
    }

    @Test fun ourBouncedChequeStaysOwedUntilPaid() {
        ops.receipt(RecordReceipt(GlobalId.new(), branch, cash, ReceiptPurpose.OWNER_CAPITAL, rial(5_000_000), day, "آورده"))
        ops.payment(RecordPayment(GlobalId.new(), branch, book, PaymentPurpose.UTILITIES, rial(400_000), day, "قبض", cheque = details("900002", bank)))
        val id = cheques.all().single().id
        chequeOps.bounce(BounceCheque(GlobalId.new(), branch, id, day, "کسری موجودی"))
        assertEquals(-400_000, gl(StandardAccounts.BOUNCED_CHEQUES_PAYABLE))
        assertEquals(0, gl(StandardAccounts.CHEQUES_PAYABLE))
        chequeOps.settleBounced(SettleBouncedCheque(GlobalId.new(), branch, id, cash, day))
        assertEquals(0, gl(StandardAccounts.BOUNCED_CHEQUES_PAYABLE))
        assertEquals(4_600_000, gateway.balance(cash))
        assertEquals(ChequeStatus.SETTLED, cheques.byId(id)!!.status)
    }

    @Test fun reversingTheReceiptVoidsAnUntouchedChequeOnly() {
        ops.receipt(RecordReceipt(GlobalId.new(), branch, box, ReceiptPurpose.OTHER_INCOME, rial(700_000), day, "چک", details("A")))
        val receipt = cheques.all().single().events.single().sourceId
        val id = cheques.all().single().id
        chequeOps.deposit(DepositCheque(GlobalId.new(), branch, id, bank, day))
        assertTrue(code { ops.reverse(ReverseTreasuryDocument(GlobalId.new(), branch, TreasuryOperations.RECEIPT, receipt, day, "اشتباه")) }.startsWith("INVALID_STATE:CHEQUE:MOVED_ON"))
        chequeOps.recall(RecallCheque(GlobalId.new(), branch, id, day, "پس گرفتیم"))
        assertEquals(ChequeStatus.IN_HAND, cheques.byId(id)!!.status)
        ops.reverse(ReverseTreasuryDocument(GlobalId.new(), branch, TreasuryOperations.RECEIPT, receipt, day, "اشتباه"))
        assertEquals(ChequeStatus.VOID, cheques.byId(id)!!.status)
        assertEquals(0, gateway.balance(box))
        assertEquals(0, gl(StandardAccounts.CHEQUES_RECEIVABLE))
    }
}
