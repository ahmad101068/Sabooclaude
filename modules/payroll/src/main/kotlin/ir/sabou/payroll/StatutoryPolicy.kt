package ir.sabou.payroll

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.Money
import ir.sabou.kernel.Ratio
import ir.sabou.kernel.Rounding

data class TaxBracket(val upToMonthly: Money?, val rateBasisPoints: Int) : java.io.Serializable

/**
 * Legal payroll parameters, versioned by date. The values must be confirmed by a tax/insurance
 * professional for each year before use (open item carried from the audit: AUD-016). A period
 * with no configured policy fails closed instead of guessing.
 */
data class StatutoryPolicy(
    val version: String,
    val from: BusinessDate,
    val to: BusinessDate,
    val standardMonthlyMinutes: Int,
    val overtimeMultiplierPercent: Int,
    val employeeInsuranceBp: Int,
    val employerInsuranceBp: Int,
    val unemploymentInsuranceBp: Int,
    val maxInsurableMonthly: Money,
    /** Share of the employee's insurance excluded from taxable income, e.g. 2/7. */
    val insuranceTaxExemptNumerator: Int,
    val insuranceTaxExemptDenominator: Int,
    val taxBrackets: List<TaxBracket>,
    /** A partial month pays salary × days ÷ this (Iranian practice: 30), never more than the monthly salary. */
    val prorationDays: Int = 30,
) : java.io.Serializable {
    init {
        require(prorationDays in 28..31) { "proration_days_out_of_range" }
        require(standardMonthlyMinutes > 0 && overtimeMultiplierPercent >= 100)
        require(listOf(employeeInsuranceBp, employerInsuranceBp, unemploymentInsuranceBp).all { it in 0..10_000 })
        require(insuranceTaxExemptDenominator > 0 && insuranceTaxExemptNumerator in 0..insuranceTaxExemptDenominator)
        // Exactly one open-ended band, the last (audit F03): an open band in the middle would end the table there.
        require(taxBrackets.isNotEmpty() && taxBrackets.last().upToMonthly == null) { "last_tax_band_must_be_open" }
        require(taxBrackets.dropLast(1).all { it.upToMonthly != null && it.upToMonthly.rial > 0 }) { "only_the_last_tax_band_may_be_open" }
        require(taxBrackets.all { it.rateBasisPoints in 0..10_000 }) { "tax_rate_out_of_range" }
        require(employeeInsuranceBp + taxBrackets.maxOf { it.rateBasisPoints } <= 10_000) { "deductions_exceed_gross" }
        val bounds = taxBrackets.mapNotNull { it.upToMonthly?.rial }
        require(bounds == bounds.sorted() && bounds.distinct() == bounds)
    }

    fun statutoryOvertime(monthly: Money, minutes: Long): Money =
        Money.of(Ratio.mulDiv(monthly.rial, Math.multiplyExact(minutes, overtimeMultiplierPercent.toLong()), standardMonthlyMinutes * 100L))

    fun percent(base: Money, bp: Int): Money = Money.of(Ratio.mulDiv(base.rial, bp.toLong(), 10_000))

    fun progressiveTax(taxable: Money): Money {
        var lower = 0L
        var tax = 0L
        for (bracket in taxBrackets) {
            val upper = bracket.upToMonthly?.rial ?: Long.MAX_VALUE
            if (taxable.rial <= lower) break
            val band = minOf(taxable.rial, upper) - lower
            if (band > 0) tax = Math.addExact(tax, Ratio.mulDiv(band, bracket.rateBasisPoints.toLong(), 10_000))
            lower = upper
        }
        return Money.of(tax)
    }

    /**
     * [salary] is the monthly salary; it also sets the minute rate for absence and overtime. With [payableDays]
     * (a partial month) the base is prorated and absence can never take more than that base.
     */
    fun calculate(
        employeeId: ir.sabou.kernel.GlobalId,
        monthlySalary: Money,
        absentMinutes: Long,
        overtimeMinutes: Long,
        payableDays: Int? = null,
        /** The month's base when it is not simply [monthlySalary] prorated (a salary change during the month). */
        base: Money? = null,
        /** Overtime priced another way than the statutory multiplier (restaurant settings); null = statutory. */
        overtimePay: Money? = null,
        /** Earnings, advance installments and deductions (see [PayLine]). */
        lines: List<PayLine> = emptyList(),
    ): Payslip {
        require(payableDays == null || payableDays >= 1)
        val monthly = monthlySalary
        val salary = base ?: if (payableDays == null) monthly
            else Money.of(minOf(monthly.rial, Ratio.mulDiv(monthly.rial, payableDays.toLong(), prorationDays.toLong())))
        val absence = Money.of(minOf(salary.rial, Ratio.mulDiv(monthly.rial, absentMinutes, standardMonthlyMinutes.toLong())))
        val overtime = overtimePay ?: statutoryOvertime(monthly, overtimeMinutes)
        val earnings = lines.filter { it.kind == PayLine.Kind.EARNING && !it.amount.isZero }
        val worked = salary - absence + overtime
        val gross = worked + Money.sum(earnings.map { it.amount })
        val insurable = minOf(worked + Money.sum(earnings.filter { it.insurable }.map { it.amount }), maxInsurableMonthly)
        val employeeIns = percent(insurable, employeeInsuranceBp)
        val exempt = Money.of(Ratio.mulDiv(employeeIns.rial, insuranceTaxExemptNumerator.toLong(), insuranceTaxExemptDenominator.toLong(), Rounding.DOWN))
        val taxable = Money.of(maxOf(0L, (worked + Money.sum(earnings.filter { it.taxable }.map { it.amount })).rial - exempt.rial))
        val tax = progressiveTax(taxable)
        // Advance installments and deductions, in order, as far as net pay allows.
        var left = gross - employeeIns - tax
        val taken = lines.filter { it.kind != PayLine.Kind.EARNING }.mapNotNull { l ->
            val amount = minOf(l.amount, left)
            left -= amount
            l.copy(amount = amount).takeIf { !amount.isZero }
        }
        return Payslip(
            employeeId = employeeId, baseSalary = salary, absenceDeduction = absence, overtimePay = overtime, gross = gross,
            insurableBase = insurable, employeeInsurance = employeeIns, employerInsurance = percent(insurable, employerInsuranceBp),
            unemploymentInsurance = percent(insurable, unemploymentInsuranceBp), taxableIncome = taxable, incomeTax = tax,
            net = left, payableDays = payableDays, lines = earnings + taken,
        )
    }
}

class StatutoryPolicyRegistry(private val source: () -> List<StatutoryPolicy>) {
    constructor(policies: List<StatutoryPolicy>) : this({ policies })

    fun forPeriod(from: BusinessDate, to: BusinessDate): StatutoryPolicy =
        source().singleOrNull { from >= it.from && to <= it.to }
            ?: throw DomainException(DomainError.InvalidState("PAYROLL_POLICY", "NOT_CONFIGURED"))
}

