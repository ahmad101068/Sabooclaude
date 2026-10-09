package ir.sabou.kernel

/** A non-negative quantity in micro-units (1 unit = 1,000,000 micros). */
@JvmInline
value class Quantity private constructor(val micros: Long) : Comparable<Quantity>, java.io.Serializable {
    operator fun plus(other: Quantity) = of(Math.addExact(micros, other.micros))
    operator fun minus(other: Quantity): Quantity {
        if (other.micros > micros) throw DomainException(DomainError.InvalidInput("quantity", "نتیجه مقدار منفی می‌شود."))
        return Quantity(micros - other.micros)
    }
    val isZero: Boolean get() = micros == 0L
    override fun compareTo(other: Quantity): Int = micros.compareTo(other.micros)
    override fun toString(): String = "${micros}µ"

    companion object {
        const val SCALE: Long = 1_000_000L
        val ZERO = Quantity(0)
        fun of(micros: Long): Quantity {
            if (micros < 0) throw DomainException(DomainError.InvalidInput("quantity", "مقدار نمی‌تواند منفی باشد."))
            return Quantity(micros)
        }
        fun units(units: Long): Quantity = of(Math.multiplyExact(units, SCALE))
    }
}
