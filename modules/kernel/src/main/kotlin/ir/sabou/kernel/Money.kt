package ir.sabou.kernel

import java.math.BigInteger

/**
 * A non-negative amount of Iranian Rial. All money in the system is an exact integer number of
 * Rial; there is no floating point anywhere in the financial path.
 */
@JvmInline
value class Money private constructor(val rial: Long) : Comparable<Money>, java.io.Serializable {
    operator fun plus(other: Money): Money = of(Math.addExact(rial, other.rial))

    /** Fails instead of producing a negative amount. Use [SignedAmount] for balances. */
    operator fun minus(other: Money): Money {
        if (other.rial > rial) throw DomainException(DomainError.InvalidInput("amount", "نتیجه مبلغ منفی می‌شود."))
        return Money(rial - other.rial)
    }

    /** Amount × quantity, where quantity is in micro-units. Half-up rounding to the nearest Rial. */
    fun times(quantity: Quantity): Money = of(Ratio.mulDiv(rial, quantity.micros, Quantity.SCALE, Rounding.HALF_UP))

    val isZero: Boolean get() = rial == 0L
    fun toSigned(): SignedAmount = SignedAmount(rial)
    override fun compareTo(other: Money): Int = rial.compareTo(other.rial)
    override fun toString(): String = "$rial IRR"

    companion object {
        /** Upper bound keeps every sum of a realistic document far away from Long overflow. */
        const val MAX_RIAL: Long = 1_000_000_000_000_000L
        val ZERO = Money(0)

        fun of(rial: Long): Money {
            if (rial < 0 || rial > MAX_RIAL) {
                throw DomainException(DomainError.InvalidInput("amount", "مبلغ باید بین صفر و سقف مجاز باشد."))
            }
            return Money(rial)
        }

        fun sum(values: Iterable<Money>): Money = values.fold(ZERO, Money::plus)
    }
}

/** A signed Rial balance (for example the balance of an account). Arithmetic is overflow-checked. */
@JvmInline
value class SignedAmount(val rial: Long) : Comparable<SignedAmount> {
    operator fun plus(other: SignedAmount) = SignedAmount(Math.addExact(rial, other.rial))
    operator fun minus(other: SignedAmount) = SignedAmount(Math.subtractExact(rial, other.rial))
    operator fun unaryMinus() = SignedAmount(Math.negateExact(rial))
    val isNegative: Boolean get() = rial < 0
    override fun compareTo(other: SignedAmount): Int = rial.compareTo(other.rial)
    override fun toString(): String = "$rial IRR"

    companion object {
        val ZERO = SignedAmount(0)
    }
}

enum class Rounding { DOWN, HALF_UP }

/** Exact integer ratio arithmetic used for money × quantity and proportional allocation. */
object Ratio {
    fun mulDiv(value: Long, multiplier: Long, divisor: Long, rounding: Rounding = Rounding.HALF_UP): Long {
        require(value >= 0 && multiplier >= 0) { "ratio_negative_operand" }
        require(divisor > 0) { "ratio_divisor_not_positive" }
        val numerator = BigInteger.valueOf(value).multiply(BigInteger.valueOf(multiplier))
        val d = BigInteger.valueOf(divisor)
        val (whole, remainder) = numerator.divideAndRemainder(d)
        val rounded = if (rounding == Rounding.HALF_UP && remainder.shiftLeft(1) >= d) whole + BigInteger.ONE else whole
        require(rounded.bitLength() < 64) { "ratio_overflow" }
        return rounded.toLong()
    }

    /**
     * Relative change from [previous] to [now] in basis points (+ = up), exact (no intermediate overflow) and
     * rounded half away from zero; null when [previous] is not positive (there is nothing to compare with).
     * The one place a percentage change is computed: prices, sales against last month, budgets.
     */
    fun changeBp(previous: Long, now: Long): Long? {
        if (previous <= 0) return null
        val p = BigInteger.valueOf(previous)
        val diff = BigInteger.valueOf(now).subtract(p).multiply(BigInteger.valueOf(10_000))
        val (whole, remainder) = diff.abs().divideAndRemainder(p)
        val magnitude = if (remainder.shiftLeft(1) >= p) whole + BigInteger.ONE else whole
        val limited = magnitude.min(BigInteger.valueOf(Long.MAX_VALUE)).toLong()
        return if (diff.signum() < 0) -limited else limited
    }

    /**
     * Splits [total] across [weights] proportionally. The result always sums exactly to [total];
     * the rounding remainder goes to the last non-zero weight so allocation is deterministic.
     */
    fun allocate(total: Long, weights: List<Long>): List<Long> {
        require(total >= 0 && weights.isNotEmpty() && weights.all { it >= 0 }) { "allocation_invalid" }
        val weightSum = weights.fold(0L) { a, b -> Math.addExact(a, b) }
        if (weightSum == 0L) return weights.map { 0L }
        val last = weights.indexOfLast { it > 0 }
        var allocated = 0L
        return weights.mapIndexed { index, weight ->
            val share = if (index == last) total - allocated else mulDiv(total, weight, weightSum, Rounding.DOWN)
            allocated = Math.addExact(allocated, share)
            share
        }
    }
}
