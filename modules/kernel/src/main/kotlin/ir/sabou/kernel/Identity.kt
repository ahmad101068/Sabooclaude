package ir.sabou.kernel

import java.util.UUID

/**
 * Globally unique identifier. Every business record carries one so that a later server can merge
 * data from many devices without id collisions (ADR-0001).
 */
@JvmInline
value class GlobalId private constructor(val value: String) : java.io.Serializable {
    override fun toString(): String = value

    companion object {
        private val PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        fun new(): GlobalId = GlobalId(UUID.randomUUID().toString())
        fun parse(raw: String): GlobalId {
            val normalized = raw.trim().lowercase()
            if (!PATTERN.matches(normalized)) throw DomainException(DomainError.InvalidInput("id", "شناسه معتبر نیست."))
            return GlobalId(normalized)
        }
    }
}

/** A calendar business day (days since 1970-01-01). Time zone conversion happens at the UI edge. */
@JvmInline
value class BusinessDate(val epochDay: Long) : Comparable<BusinessDate>, java.io.Serializable {
    init {
        require(epochDay > 0) { "business_date_invalid" }
    }
    override fun compareTo(other: BusinessDate): Int = epochDay.compareTo(other.epochDay)
    fun plusDays(days: Long) = BusinessDate(Math.addExact(epochDay, days))
}

/** Branch identity. The organization itself is not a branch; see [Scope]. */
@JvmInline
value class BranchId(val value: GlobalId) : java.io.Serializable

/** Accounting / data ownership scope of a record. */
sealed interface Scope : java.io.Serializable {
    data object Organization : Scope
    data class Branch(val branchId: BranchId) : Scope
}

fun interface Clock {
    fun nowEpochMillis(): Long

    companion object {
        val SYSTEM = Clock { System.currentTimeMillis() }
    }
}
