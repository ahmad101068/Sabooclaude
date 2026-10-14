package ir.sabou.core

import ir.sabou.kernel.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The suggested 1405 payroll values are a valid policy and tax the way the budget law's table reads (ADR-0049). */
class PayrollPresetsTest {
    @Test fun the1405PresetIsAValidPolicyWithTheLawsBrackets() {
        val p = PayrollPresets.y1405()
        assertEquals(1_163_788_500L, p.policy.maxInsurableMonthly.rial)          // 7 × 30 × 5,541,850
        assertEquals(Fa.fromJalali(1405, 1, 1), p.policy.from)
        assertEquals(0L, p.policy.progressiveTax(Money.of(400_000_000)).rial)
        assertEquals(10_000_000L, p.policy.progressiveTax(Money.of(500_000_000)).rial)
        // 40m×10% + 20m×15% + 20m×20% + 20m×25% + 10m×30% (toman) = 4 + 3 + 4 + 5 + 3 = 19m toman.
        assertEquals(190_000_000L, p.policy.progressiveTax(Money.of(1_500_000_000)).rial)
        assertEquals(3, p.sources.size)
        assertNull(PayrollPresets.forYear(1404))
    }
}
