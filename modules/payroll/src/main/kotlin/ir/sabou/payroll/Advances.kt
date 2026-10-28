package ir.sabou.payroll

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Scope
import ir.sabou.platform.Command
import ir.sabou.platform.DocumentSeries
import ir.sabou.platform.IssuesDocument
import ir.sabou.platform.Permission

/**
 * Money paid to an employee ahead of salary (مساعده) or lent (وام), paid back from salary in [installments]
 * (an advance: 1, from the next payroll) and, if wanted, in cash ([repayments]). What is still owed when the person
 * leaves is taken from the last payslip, as far as it allows.
 */
data class Advance(
    val id: GlobalId,
    val scope: Scope.Branch,
    val employeeId: GlobalId,
    val amount: Money,
    val date: BusinessDate,
    val installments: Int,
    val note: String,
    val treasuryAccountId: GlobalId,
    val reversed: Boolean = false,
    val repayments: List<AdvanceRepayment> = emptyList(),
) {
    val isLoan: Boolean get() = installments > 1
    /** The most taken from one payslip. */
    val installment: Money get() = Money.of((amount.rial + installments - 1) / installments)
    val code: String get() = "ADVANCE:${id.value}"
    fun repaid(): Money = Money.sum(repayments.filter { !it.reversed }.map { it.amount })
}

data class AdvanceRepayment(val id: GlobalId, val amount: Money, val date: BusinessDate, val treasuryAccountId: GlobalId, val reversed: Boolean = false)

@IssuesDocument(DocumentSeries.PAYMENT)
data class GiveAdvance(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val employeeId: GlobalId,
    val treasuryAccountId: GlobalId,
    val amount: Money,
    val date: BusinessDate,
    /** 1 = an advance taken from the next salary; more = a loan paid back in that many months. */
    val installments: Int,
    val note: String,
) : Command {
    override val requiredPermission get() = Permission.PAYROLL_PAY
    override fun fingerprint() = "$scope|$employeeId|$treasuryAccountId|${amount.rial}|${date.epochDay}|$installments|$note"
}

/** The employee pays back part of an advance in cash. */
@IssuesDocument(DocumentSeries.RECEIPT)
data class RepayAdvance(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val advanceId: GlobalId,
    val treasuryAccountId: GlobalId,
    val amount: Money,
    val date: BusinessDate,
) : Command {
    override val requiredPermission get() = Permission.PAYROLL_PAY
    override fun fingerprint() = "$scope|$advanceId|$treasuryAccountId|${amount.rial}|${date.epochDay}"
}

/** Cancels an advance recorded by mistake, before anything of it was paid back. */
@IssuesDocument(DocumentSeries.REVERSAL)
data class ReverseAdvance(override val commandId: GlobalId, override val scope: Scope.Branch, val advanceId: GlobalId, val date: BusinessDate, val reason: String) : Command {
    override val requiredPermission get() = Permission.PAYROLL_PAY
    override fun fingerprint() = "$scope|$advanceId|${date.epochDay}|$reason"
}

/** An advance with what is still owed. */
data class AdvanceView(val advance: Advance, val deducted: Money, val outstanding: Money)
