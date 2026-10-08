package ir.sabou.kernel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MoneyTest {
    @Test fun negativeAndOverMaxAreRejected() {
        assertFailsWith<DomainException> { Money.of(-1) }
        assertFailsWith<DomainException> { Money.of(Money.MAX_RIAL + 1) }
        assertFailsWith<DomainException> { Money.of(5) - Money.of(6) }
    }

    @Test fun signedArithmeticIsOverflowChecked() {
        assertFailsWith<ArithmeticException> { SignedAmount(Long.MAX_VALUE) + SignedAmount(1) }
    }

    @Test fun moneyTimesQuantityRoundsHalfUp() {
        // 1,000 Rial per unit × 0.0015 units = 1.5 Rial -> 2
        assertEquals(2, Money.of(1_000).times(Quantity.of(1_500)).rial)
        assertEquals(1, Money.of(1_000).times(Quantity.of(1_499)).rial)
        assertEquals(250_000, Money.of(100_000).times(Quantity.of(2_500_000)).rial)
    }

    @Test fun allocationAlwaysSumsToTotal() {
        val shares = Ratio.allocate(100, listOf(1, 1, 1))
        assertEquals(listOf(33L, 33L, 34L), shares)
        assertEquals(100, shares.sum())
        assertEquals(listOf(0L, 7L, 0L), Ratio.allocate(7, listOf(0, 5, 0)))
    }

    @Test fun globalIdIsValidated() {
        assertFailsWith<DomainException> { GlobalId.parse("not-an-id") }
        val id = GlobalId.new()
        assertEquals(id, GlobalId.parse(id.value.uppercase()))
    }
}
