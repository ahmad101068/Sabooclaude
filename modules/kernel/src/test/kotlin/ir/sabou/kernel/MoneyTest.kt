package ir.sabou.kernel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

    @Test fun changeIsExactSaturatingAndUndefinedWithoutABase() {
        assertEquals(2_000L, Ratio.changeBp(300_000, 360_000))
        assertEquals(-10_000L, Ratio.changeBp(5, 0))
        assertEquals(5_000L, Ratio.changeBp(2_000_000_000_000_000, 3_000_000_000_000_000))
        assertEquals(Long.MAX_VALUE, Ratio.changeBp(1, Long.MAX_VALUE))
        assertEquals(17L, Ratio.changeBp(600, 601))                    // 16.67 bp rounds half away from zero
        assertEquals(-17L, Ratio.changeBp(600, 599))
        assertEquals(null, Ratio.changeBp(0, 10))
    }

    @Test fun signedMulDivMirrorsThePositiveOne() {
        assertEquals(3_333, Ratio.mulDivSigned(10_000, 1, 3))
        assertEquals(-3_333, Ratio.mulDivSigned(-10_000, 1, 3))
        assertEquals(-3_333, Ratio.mulDivSigned(10_000, -1, 3))
        assertEquals(3_333, Ratio.mulDivSigned(-10_000, -1, 3))
        assertEquals(-2, Ratio.mulDivSigned(-3, 1, 2))                  // half away from zero
        assertEquals(0, Ratio.mulDivSigned(0, -5, 7))
    }

    /** Audit P1-03: a positive amount over all-zero weights must not vanish. */
    @Test fun allocatingAPositiveAmountOverZeroWeightsFailsClosed() {
        val e = assertFailsWith<DomainException> { Ratio.allocate(100, listOf(0, 0)) }
        assertEquals("INVALID_STATE:ALLOCATION:ZERO_WEIGHTS", e.error.code)
        assertEquals(listOf(0L, 0L), Ratio.allocate(0, listOf(0, 0)))       // nothing to split: fine
        assertFailsWith<IllegalArgumentException> { Ratio.allocate(-1, listOf(1)) }
        assertFailsWith<IllegalArgumentException> { Ratio.allocate(1, emptyList()) }
        assertFailsWith<IllegalArgumentException> { Ratio.allocate(1, listOf(1, -1)) }
    }

    /**
     * Properties over 5,000 seeded random cases, edges included (zero total, one weight, huge weights, many zeros):
     * the shares add up exactly, none is negative, each is within one Rial of its exact proportion, zero weights
     * get nothing, and the same input always gives the same split.
     */
    @Test fun allocationProperties() {
        val r = java.util.Random(1405)
        repeat(5_000) { case ->
            val n = 1 + r.nextInt(12)
            val big = case % 7 == 0
            val weights = List(n) { if (r.nextInt(4) == 0) 0L else if (big) (r.nextLong() ushr 8) % 1_000_000_000_000L else r.nextInt(1_000).toLong() }
                .let { w -> if (w.all { it == 0L }) w.toMutableList().also { it[r.nextInt(n)] = 1L } else w }
            val total = if (case % 11 == 0) 0L else (r.nextLong() ushr 2) % 1_000_000_000_000_000L
            val shares = Ratio.allocate(total, weights)
            assertEquals(total, shares.sum(), "sum $total $weights")
            val sum = weights.fold(java.math.BigInteger.ZERO) { a, w -> a + java.math.BigInteger.valueOf(w) }
            shares.forEachIndexed { i, s ->
                assertTrue(s >= 0)
                if (weights[i] == 0L) assertEquals(0L, s)
                val exact = java.math.BigInteger.valueOf(total).multiply(java.math.BigInteger.valueOf(weights[i]))
                val lo = exact.divide(sum)
                // Each share is its floor; the last weighted share also takes the remainder (< number of shares).
                assertTrue(java.math.BigInteger.valueOf(s) >= lo, "share $i below floor")
                assertTrue(java.math.BigInteger.valueOf(s) <= lo + java.math.BigInteger.valueOf(n.toLong()), "share $i over")
            }
            assertEquals(shares, Ratio.allocate(total, weights))
        }
    }
}
