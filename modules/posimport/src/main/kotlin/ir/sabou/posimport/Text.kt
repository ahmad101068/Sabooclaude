package ir.sabou.posimport

import java.math.BigDecimal
import java.math.RoundingMode

/** The one place cell text is normalized (ADR-0026). */
object Text {
    /** Persian/Arabic digits → Latin, Arabic ي/ك → Persian ی/ک, no zero-width marks, single spaces. */
    fun normalize(raw: String?): String {
        if (raw == null) return ""
        val sb = StringBuilder(raw.length)
        for (c in raw) {
            sb.append(
                when (c) {
                    in '۰'..'۹' -> '0' + (c - '۰')
                    in '٠'..'٩' -> '0' + (c - '٠')
                    'ي', 'ى' -> 'ی'
                    'ك' -> 'ک'
                    '‌', '‍', '‎', '‏', ' ' -> ' '
                    else -> c
                },
            )
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    /** Text for matching names: normalized, no spaces at all, lower case. «برگر كلاسيك» = «برگرکلاسیک». */
    fun key(raw: String?): String = normalize(raw).replace(" ", "").lowercase()

    /**
     * A number as exports write it: «۱۸٬۹۰۰٬۰۰۰», «1,890,000», «-» (zero), «(3,100,000)» or «3,100,000-»
     * (negative), «۲٫۵» or «2.5» (decimal), «1.89E8». Null when it is not a number.
     */
    fun decimal(raw: String?): BigDecimal? {
        var t = normalize(raw)
        if (t.isEmpty()) return null
        if (t == "-" || t == "–" || t == "—") return BigDecimal.ZERO
        var negative = false
        if (t.startsWith("(") && t.endsWith(")")) { negative = true; t = t.substring(1, t.length - 1) }
        if (t.endsWith("-")) { negative = true; t = t.dropLast(1) }
        if (t.startsWith("-") || t.startsWith("−")) { negative = !negative; t = t.drop(1) }
        t = t.replace("٬", "").replace(",", "").replace("'", "").replace(" ", "").replace('٫', '.')
        if (t.isEmpty() || !t.matches(Regex("[0-9]+(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))) return null
        val v = BigDecimal(t)
        return if (negative) v.negate() else v
    }

    /** A whole amount (Rial or Toman as written); a fraction is not an amount. */
    fun amount(raw: String?): Long? = decimal(raw)?.let { d -> runCatching { d.setScale(0, RoundingMode.UNNECESSARY).longValueExact() }.getOrNull() }

    /** A quantity in micro-units (6 decimals), half-up. */
    fun micros(raw: String?): Long? = decimal(raw)?.let { d -> runCatching { d.movePointRight(6).setScale(0, RoundingMode.HALF_UP).longValueExact() }.getOrNull() }
}
