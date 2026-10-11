package ir.sabou.kernel

/** Iranian national id (کد ملی) check-digit validation; one rule for personnel, customers and the tax profile. */
object NationalId {
    /** Persian/Arabic digits → Latin, surrounding spaces removed; the stored and compared form. */
    fun normalize(raw: String): String = raw.trim().map { ch ->
        when (ch) { in '۰'..'۹' -> '0' + (ch - '۰'); in '٠'..'٩' -> '0' + (ch - '٠'); else -> ch }
    }.joinToString("")

    fun isValid(raw: String): Boolean {
        val digits = normalize(raw)
        if (!digits.matches(Regex("\\d{10}")) || digits.toSet().size == 1) return false
        val sum = (0 until 9).sumOf { (digits[it] - '0') * (10 - it) }
        val remainder = sum % 11
        val check = digits[9] - '0'
        return if (remainder < 2) check == remainder else check == 11 - remainder
    }
}
