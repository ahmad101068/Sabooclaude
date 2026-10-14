package ir.sabou.kernel

/** Iranian mobile numbers (for SMS): stored as «09» + 9 digits. */
object Mobile {
    /** «09xxxxxxxxx» from the usual ways people type it (+98, 0098, 98, 9…, Persian digits, spaces, dashes), or null. */
    fun normalize(raw: String): String? {
        var s = NationalId.normalize(raw).filter { it.isDigit() || it == '+' }
        s = when {
            s.startsWith("+98") -> "0" + s.drop(3)
            s.startsWith("0098") -> "0" + s.drop(4)
            s.startsWith("98") && s.length == 12 -> "0" + s.drop(2)
            s.startsWith("9") && s.length == 10 -> "0$s"
            else -> s
        }
        return s.takeIf { it.matches(Regex("09\\d{9}")) }
    }

    fun isValid(raw: String): Boolean = normalize(raw) != null
}
