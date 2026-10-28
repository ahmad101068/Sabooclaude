package ir.sabou.sales

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Scope
import ir.sabou.platform.Command
import ir.sabou.platform.DocumentSeries
import ir.sabou.platform.IssuesDocument
import ir.sabou.platform.Permission

/** Money a customer paid before the sale (a booked event, catering), held in 2109 until set against their debt (ADR-0045). */
data class CustomerDeposit(
    val id: GlobalId,
    val customerId: GlobalId,
    val scope: Scope.Branch,
    val treasuryAccountId: GlobalId,
    val amount: Money,
    val date: BusinessDate,
    val note: String,
    val reversed: Boolean = false,
)

/** A cash box counted at the end of a shift (by the cashier), with what the books said then. */
data class ShiftCount(
    val id: GlobalId,
    val scope: Scope.Branch,
    val date: BusinessDate,
    val cashAccountId: GlobalId,
    val counted: Money,
    val difference: Long,
    val countedBy: String,
    val note: String,
    val atEpochMillis: Long,
)

interface DepositStore {
    fun deposit(id: GlobalId): CustomerDeposit?
    fun depositsOf(customerId: GlobalId): List<CustomerDeposit>
    fun saveDeposit(deposit: CustomerDeposit)
    fun shiftCounts(scope: Scope.Branch, date: BusinessDate): List<ShiftCount>
    fun saveShiftCount(count: ShiftCount)
}

@IssuesDocument(DocumentSeries.RECEIPT)
data class ReceiveCustomerDeposit(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val customerId: GlobalId,
    val treasuryAccountId: GlobalId,
    val amount: Money,
    val date: BusinessDate,
    val note: String,
    val cheque: ir.sabou.treasury.ChequeDetails? = null,
) : Command {
    override val requiredPermission get() = Permission.RECEIVABLE_COLLECT
    override fun fingerprint() = "$scope|$customerId|$treasuryAccountId|${amount.rial}|${date.epochDay}|$note|${cheque?.fingerprint()}"
}

/** Sets (part of) a deposit against what the customer owes for a sale. */
@IssuesDocument(DocumentSeries.CREDIT_SETTLEMENT)
data class ApplyCustomerDeposit(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val depositId: GlobalId,
    val receivableId: GlobalId,
    val amount: Money,
    val date: BusinessDate,
) : Command {
    override val requiredPermission get() = Permission.RECEIVABLE_COLLECT
    override fun fingerprint() = "$scope|$depositId|$receivableId|${amount.rial}|${date.epochDay}"
}

/** Takes back a deposit (received by mistake, or given back to the customer) when none of it was used. */
@IssuesDocument(DocumentSeries.REVERSAL)
data class ReverseCustomerDeposit(override val commandId: GlobalId, override val scope: Scope.Branch, val depositId: GlobalId, val date: BusinessDate, val reason: String) : Command {
    override val requiredPermission get() = Permission.RECEIVABLE_REVERSE
    override fun fingerprint() = "$scope|$depositId|${date.epochDay}|$reason"
}

/** The cashier counts the cash box at the end of a shift; any shortage or overage is booked at once. */
@IssuesDocument(DocumentSeries.CASH_COUNT)
data class CountCashShift(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val cashAccountId: GlobalId,
    val counted: Money,
    val date: BusinessDate,
    val note: String,
) : Command {
    override val requiredPermission get() = Permission.SALES_RECORD
    override fun fingerprint() = "$scope|$cashAccountId|${counted.rial}|${date.epochDay}|$note"
}
