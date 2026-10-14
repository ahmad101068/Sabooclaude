package ir.sabou.core

import ir.sabou.kernel.Money
import ir.sabou.payroll.StatutoryPolicy
import ir.sabou.payroll.TaxBracket

/**
 * Suggested legal payroll values for a year, as published (ADR-0049). They only fill the form: the owner saves them
 * after a tax/insurance advisor confirms them, because secondary sources were all that could be read here.
 */
object PayrollPresets {
    data class Preset(val year: Int, val policy: StatutoryPolicy, val minimumDailyWage: Money, val sources: List<String>)

    /**
     * 1405: minimum daily wage 5,541,850 rial (wage circular 1405); insurable ceiling = 7 × the minimum wage
     * (30 days); shares 7 % employee, 20 % employer, 3 % unemployment; salary tax per the budget law 1405 —
     * exempt up to 400,000,000 rial a month, then 10 % to 800,000,000, 15 % to 1,000,000,000, 20 % to 1,200,000,000,
     * 25 % to 1,400,000,000 and 30 % above.
     */
    fun y1405(): Preset {
        val minimumDaily = Money.of(5_541_850)
        return Preset(
            1405,
            StatutoryPolicy(
                version = "1405",
                from = Fa.fromJalali(1405, 1, 1),
                to = Fa.fromJalali(1405, 12, Fa.monthLength(1405, 12)),
                standardMonthlyMinutes = 192 * 60,
                overtimeMultiplierPercent = 140,
                employeeInsuranceBp = 700,
                employerInsuranceBp = 2_000,
                unemploymentInsuranceBp = 300,
                maxInsurableMonthly = Money.of(minimumDaily.rial * 30 * 7),
                insuranceTaxExemptNumerator = 2,
                insuranceTaxExemptDenominator = 7,
                taxBrackets = listOf(
                    TaxBracket(Money.of(400_000_000), 0),
                    TaxBracket(Money.of(800_000_000), 1_000),
                    TaxBracket(Money.of(1_000_000_000), 1_500),
                    TaxBracket(Money.of(1_200_000_000), 2_000),
                    TaxBracket(Money.of(1_400_000_000), 2_500),
                    TaxBracket(null, 3_000),
                ),
            ),
            minimumDaily,
            listOf(
                "حداقل مزد روزانه ۱۴۰۵: بخشنامه‌ی مزد ۱۴۰۵ (به نقل از zharfban.com/blog/salary-benefits-1405)",
                "پله‌های مالیات حقوق ۱۴۰۵: قانون بودجه‌ی ۱۴۰۵ (به نقل از pishdadacc.com/salary-tax)",
                "سقف دستمزد مشمول بیمه: هفت برابر حداقل مزد (رویه‌ی سازمان تأمین اجتماعی)",
            ),
        )
    }

    fun forYear(year: Int): Preset? = if (year == 1405) y1405() else null
}
