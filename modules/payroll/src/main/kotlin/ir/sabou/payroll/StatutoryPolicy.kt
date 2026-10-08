package ir.sabou.payroll

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.Money
import ir.sabou.kernel.Ratio
import ir.sabou.kernel.Rounding

data class TaxBracket(val upToMonthly: Money?, val rateBasisPoints: Int)

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
) {
    init {
        require(standardMonthlyMinutes > 0 && overtimeMultiplierPercent >= 100)
        require(listOf(employeeInsuranceBp, employerInsuranceBp, unemploymentInsuranceBp).all { it in 0..10_000 })
        require(insuranceTaxExemptDenominator > 0 && insuranceTaxExemptNumerator in 0..insuranceTaxExemptDenominator)
        require(taxBrackets.isNotEmpty() && taxBrackets.last().upToMonthly == null)
        val bounds = taxBrackets.mapNotNull { it.upToMonthly?.rial }
        require(bounds == bounds.sorted() && bounds.distinct() == bounds)
    }

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

    fun calculate(employeeId: ir.sabou.kernel.GlobalId, salary: Money, absentMinutes: Long, overtimeMinutes: Long): Payslip {
        val absence = Money.of(minOf(salary.rial, Ratio.mulDiv(salary.rial, absentMinutes, standardMonthlyMinutes.toLong())))
        val overtime = Money.of(Ratio.mulDiv(salary.rial, Math.multiplyExact(overtimeMinutes, overtimeMultiplierPercent.toLong()), standardMonthlyMinutes * 100L))
        val gross = salary - absence + overtime
        val insurable = minOf(gross, maxInsurableMonthly)
        val employeeIns = percent(insurable, employeeInsuranceBp)
        val exempt = Money.of(Ratio.mulDiv(employeeIns.rial, insuranceTaxExemptNumerator.toLong(), insuranceTaxExemptDenominator.toLong(), Rounding.DOWN))
        val taxable = gross - exempt
        val tax = progressiveTax(taxable)
        return Payslip(
            employeeId = employeeId, baseSalary = salary, absenceDeduction = absence, overtimePay = overtime, gross = gross,
            insurableBase = insurable, employeeInsurance = employeeIns, employerInsurance = percent(insurable, employerInsuranceBp),
            unemploymentInsurance = percent(insurable, unemploymentInsuranceBp), taxableIncome = taxable, incomeTax = tax,
            net = gross - employeeIns - tax,
        )
    }
}

class StatutoryPolicyRegistry(private val policies: List<StatutoryPolicy>) {
    fun forPeriod(from: BusinessDate, to: BusinessDate): StatutoryPolicy =
        policies.singleOrNull { from >= it.from && to <= it.to }
            ?: throw DomainException(DomainError.InvalidState("PAYROLL_POLICY", "NOT_CONFIGURED"))
}

/** Iranian national id (کد ملی) check-digit validation. */
object NationalId {
    fun isValid(raw: String): Boolean {
        val digits = raw.trim().map { ch ->
            when (ch) { in '۰'..'۹' -> '0' + (ch - '۰'); in '٠'..'٩' -> '0' + (ch - '٠'); else -> ch }
        }.joinToString("")
        if (!digits.matches(Regex("\\d{10}")) || digits.toSet().size == 1) return false
        val sum = (0 until 9).sumOf { (digits[it] - '0') * (10 - it) }
        val remainder = sum % 11
        val check = digits[9] - '0'
        return if (remainder < 2) check == remainder else check == 11 - remainder
    }
}
