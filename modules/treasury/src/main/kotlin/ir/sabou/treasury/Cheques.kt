package ir.sabou.treasury

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Scope
import ir.sabou.kernel.ensure

/**
 * Cheques live in two kinds of treasury account (ADR-0013):
 * - a **cheque box** ([TreasuryKind.RECEIVED_CHEQUES], GL 1105): every receipt into it is a customer's
 *   cheque; it leaves when collected, bounced or endorsed to a supplier;
 * - a **cheque book** ([TreasuryKind.ISSUED_CHEQUES], GL 2107, a liability): every payment from it is one
 *   of our cheques; it is settled when the bank clears it (money leaves the bank) or it bounces.
 * The status always follows the money: it changes only together with a treasury movement (or a deposit
 * note), and reversing that movement's document puts the cheque back where it was.
 */
enum class ChequeDirection { RECEIVED, ISSUED }

enum class ChequeStatus {
    /** Received, in our box. */
    IN_HAND,
    /** Received, handed to the bank for collection (still ours until collected). */
    DEPOSITED,
    /** Received and paid into our bank. */
    COLLECTED,
    /** Received and given to a supplier (or used for an expense). */
    ENDORSED,
    /** Bounced (received: the drawer owes us; issued: we owe the payee). */
    BOUNCED,
    /** A bounced cheque settled in cash or by transfer. */
    SETTLED,
    /** Issued, not yet cleared. */
    ISSUED,
    /** Issued and paid by our bank. */
    CLEARED,
    /** The document that created it was reversed. */
    VOID,
}

data class ChequeDetails(
    val number: String,
    val bank: String,
    /** The 16-digit Sayad id printed on new cheques; optional. */
    val sayadId: String,
    val dueDate: BusinessDate,
    /** Drawer (received) or payee (issued). */
    val counterparty: String,
    val note: String = "",
    /** For our own cheques: the bank account they are drawn on (same branch as the cheque book). */
    val bankAccountId: GlobalId? = null,
) {
    fun fingerprint() = "$number|$bank|$sayadId|${dueDate.epochDay}|$counterparty|$note|$bankAccountId"

    fun validate() {
        ensure(number.isNotBlank() && number.length <= 30) { DomainError.InvalidInput("chequeNumber", "شماره چک الزامی است.") }
        ensure(bank.isNotBlank() && bank.length <= 60) { DomainError.InvalidInput("chequeBank", "نام بانک الزامی است.") }
        ensure(sayadId.isBlank() || (sayadId.length == 16 && sayadId.all { it in '0'..'9' })) { DomainError.InvalidInput("sayadId", "شناسه صیادی ۱۶ رقم است.") }
        ensure(counterparty.isNotBlank() && counterparty.length <= 120) { DomainError.InvalidInput("counterparty", "نام صاحب حساب یا گیرنده الزامی است.") }
        ensure(note.length <= 300) { DomainError.InvalidInput("note", "توضیح طولانی است.") }
    }
}

/** One step of a cheque's life; [reversed] when the document behind it was reversed. */
data class ChequeEvent(
    val status: ChequeStatus,
    val date: BusinessDate,
    val sourceType: String,
    val sourceId: GlobalId,
    val accountId: GlobalId?,
    val note: String = "",
    val reversed: Boolean = false,
)

data class Cheque(
    val id: GlobalId,
    val direction: ChequeDirection,
    /** The cheque box or cheque book. */
    val accountId: GlobalId,
    val scope: Scope,
    val amount: Money,
    val details: ChequeDetails,
    val status: ChequeStatus,
    val events: List<ChequeEvent>,
    /** Bank it was deposited to (received) or is drawn on (issued). */
    val bankAccountId: GlobalId?,
) {
    val effective: List<ChequeEvent> get() = events.filter { !it.reversed }
    val dueDate: BusinessDate get() = details.dueDate
    /** Still open: a received cheque we hold or an issued cheque not yet cleared. */
    val pending: Boolean get() = status in setOf(ChequeStatus.IN_HAND, ChequeStatus.DEPOSITED, ChequeStatus.ISSUED)
}

/** What a treasury movement does to a cheque: a new one into a box / out of a book, or a step of an existing one. */
sealed interface ChequeInstruction {
    data class New(val details: ChequeDetails) : ChequeInstruction
    data class Move(val chequeId: GlobalId, val to: ChequeStatus) : ChequeInstruction
}

interface ChequeStore {
    fun byId(id: GlobalId): Cheque?
    fun all(): List<Cheque>
    fun save(cheque: Cheque)
}

internal object ChequeRules {
    /** Which status a step may start from and which account side it uses. */
    fun requireMove(cheque: Cheque, account: TreasuryAccount, direction: Direction, amount: Money, to: ChequeStatus) {
        ensure(cheque.amount == amount) { DomainError.InvalidInput("amount", "مبلغ با مبلغ چک برابر نیست.") }
        val ok = when (cheque.direction) {
            ChequeDirection.RECEIVED -> when (to) {
                ChequeStatus.COLLECTED, ChequeStatus.BOUNCED, ChequeStatus.ENDORSED ->
                    account.id == cheque.accountId && direction == Direction.PAYMENT && cheque.status in setOf(ChequeStatus.IN_HAND, ChequeStatus.DEPOSITED)
                ChequeStatus.SETTLED -> account.kind.isOrdinary && direction == Direction.RECEIPT && cheque.status == ChequeStatus.BOUNCED
                else -> false
            }
            ChequeDirection.ISSUED -> when (to) {
                ChequeStatus.CLEARED, ChequeStatus.BOUNCED -> account.id == cheque.accountId && direction == Direction.RECEIPT && cheque.status == ChequeStatus.ISSUED
                ChequeStatus.SETTLED -> account.kind.isOrdinary && direction == Direction.PAYMENT && cheque.status == ChequeStatus.BOUNCED
                else -> false
            }
        }
        ensure(ok) { DomainError.InvalidState("CHEQUE", cheque.status.name) }
    }

    fun notFound(): Nothing = throw DomainException(DomainError.NotFound("CHEQUE"))
}
