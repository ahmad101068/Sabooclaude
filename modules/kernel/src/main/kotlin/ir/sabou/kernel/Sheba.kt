package ir.sabou.kernel

/** Iranian bank account number (شبا, IBAN «IR» + 24 digits) with its ISO 13616 check digits. */
object Sheba {
    /** «IR» + 24 Latin digits, spaces and dashes removed; Persian digits accepted. */
    fun normalize(raw: String): String {
        val s = NationalId.normalize(raw).uppercase().filter { it != ' ' && it != '-' }
        return if (s.matches(Regex("\\d{24}"))) "IR$s" else s
    }

    fun isValid(raw: String): Boolean {
        val s = normalize(raw)
        if (!s.matches(Regex("IR\\d{24}"))) return false
        // Move the country code and check digits to the end; letters become numbers (I = 18, R = 27); mod 97 must be 1.
        val digits = s.substring(4) + "1827" + s.substring(2, 4)
        return digits.fold(0) { acc, c -> (acc * 10 + (c - '0')) % 97 } == 1
    }
}
