package ir.sabou.core

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity

/**
 * Persian presentation rules in one tested place (the UI only calls these):
 * Persian digits, "٬" thousands separator, amounts shown in Toman while stored in Rial, and the
 * Solar Hijri (Jalali) calendar.
 */
object Fa {
    private const val DIGITS = "۰۱۲۳۴۵۶۷۸۹"

    fun digits(text: String): String = buildString(text.length) {
        for (c in text) append(if (c in '0'..'9') DIGITS[c - '0'] else c)
    }

    fun number(value: Long): String {
        val negative = value < 0
        val abs = if (negative) value.toString().drop(1) else value.toString()
        val grouped = abs.reversed().chunked(3).joinToString("٬").reversed()
        return (if (negative) "−" else "") + digits(grouped)
    }

    /** Toman for display: 10 Rial = 1 Toman. A leftover Rial shows as one decimal place. */
    fun toman(rial: Long): String {
        val whole = number(rial / 10)
        val rest = kotlin.math.abs(rial % 10)
        return if (rest == 0L) whole else whole + "٫" + digits(rest.toString())
    }

    fun toman(money: Money): String = toman(money.rial)

    /** Basis points as a percentage with one decimal: 1234 → «۱۲٫۳٪». */
    fun percent(bp: Long): String {
        val tenths = (kotlin.math.abs(bp) + 5) / 10
        val text = if (tenths % 10 == 0L) number(tenths / 10) else number(tenths / 10) + "٫" + digits((tenths % 10).toString())
        return (if (bp < 0) "−" else "") + text + "٪"
    }

    /** Compact form for cards: ۱۴٫۸ م (million Toman) / ۲٫۱ ب (billion Toman). */
    fun tomanShort(rial: Long): String {
        val toman = rial / 10
        val abs = kotlin.math.abs(toman)
        val sign = if (toman < 0) "−" else ""
        return when {
            abs >= 1_000_000_000L -> sign + digits(oneDecimal(abs, 1_000_000_000L)) + " ب"
            abs >= 1_000_000L -> sign + digits(oneDecimal(abs, 1_000_000L)) + " م"
            else -> number(toman)
        }
    }

    private fun oneDecimal(value: Long, unit: Long): String {
        val tenths = (value * 10 + unit / 2) / unit
        return if (tenths % 10 == 0L) (tenths / 10).toString() else "${tenths / 10}٫${tenths % 10}"
    }

    fun quantity(q: Quantity): String {
        val whole = q.micros / Quantity.SCALE
        val frac = q.micros % Quantity.SCALE
        if (frac == 0L) return number(whole)
        val decimals = frac.toString().padStart(6, '0').trimEnd('0')
        return number(whole) + "٫" + digits(decimals)
    }

    /** Accepts Persian, Arabic or Latin digits with any separators; null if not a whole number. */
    fun parseLong(text: String): Long? {
        val normalized = buildString {
            for (c in text.trim()) when (c) {
                in '۰'..'۹' -> append('0' + (c - '۰'))
                in '٠'..'٩' -> append('0' + (c - '٠'))
                in '0'..'9' -> append(c)
                ',', '٬', '،', ' ', '‌' -> Unit
                else -> return null
            }
        }
        if (normalized.isEmpty() || normalized.length > 18) return null
        return normalized.toLongOrNull()
    }

    /** Toman typed by the user → Rial. */
    fun parseToman(text: String): Money? = parseLong(text)?.let { if (it > Money.MAX_RIAL / 10) null else Money.of(it * 10) }

    /** Decimal quantity such as "۲٫۵" or "0.25" → micro-units. */
    fun parseQuantity(text: String): Quantity? {
        val t = text.trim().replace('٫', '.').replace('/', '.')
        if (t.isEmpty() || t == ".") return null   // empty means "not entered", never zero
        val parts = t.split('.')
        if (parts.size > 2) return null
        val whole = if (parts[0].isEmpty()) 0L else parseLong(parts[0]) ?: return null
        val fracText = if (parts.size == 2) parts[1] else ""
        if (fracText.length > 6) return null
        val frac = if (fracText.isEmpty()) 0L else (parseLong(fracText) ?: return null) * pow10(6 - fracText.length)
        if (whole > Long.MAX_VALUE / Quantity.SCALE - 1) return null
        return Quantity.of(whole * Quantity.SCALE + frac)
    }

    private fun pow10(n: Int): Long = (1..n).fold(1L) { a, _ -> a * 10 }

    /** Normalizes a PIN or code typed with Persian/Arabic digits to Latin digits. */
    fun latinDigits(text: String): String = buildString {
        for (c in text) append(
            when (c) {
                in '۰'..'۹' -> '0' + (c - '۰')
                in '٠'..'٩' -> '0' + (c - '٠')
                else -> c
            },
        )
    }

    // ---------------------------------------------------------------- Jalali calendar

    /** Kept as an alias: the calendar itself lives in the kernel (the fiscal year needs it). */
    data class JalaliDate(val year: Int, val month: Int, val day: Int)

    val monthNames = listOf("فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور", "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند")
    private val weekdayNames = listOf("شنبه", "یکشنبه", "دوشنبه", "سه‌شنبه", "چهارشنبه", "پنجشنبه", "جمعه")

    fun jalali(date: BusinessDate): JalaliDate = ir.sabou.kernel.JalaliCalendar.of(date).let { JalaliDate(it.year, it.month, it.day) }
    fun fromJalali(year: Int, month: Int, day: Int): BusinessDate = ir.sabou.kernel.JalaliCalendar.date(year, month, day)

    /** 0 = Saturday … 6 = Friday (the Persian week). */
    fun weekdayIndex(date: BusinessDate): Int = Math.floorMod(date.epochDay + 5, 7L).toInt()

    fun weekday(date: BusinessDate): String = weekdayNames[weekdayIndex(date)]

    /** «پنجشنبه ۱۶ مهر» */
    fun dayTitle(date: BusinessDate): String = jalali(date).let { "${weekday(date)} ${digits(it.day.toString())} ${monthNames[it.month - 1]}" }

    /** «۱۴۰۵/۰۷/۱۶» */
    fun date(date: BusinessDate): String = jalali(date).let {
        digits("${it.year}/${it.month.toString().padStart(2, '0')}/${it.day.toString().padStart(2, '0')}")
    }

    fun parseDate(text: String): BusinessDate? {
        val parts = latinDigits(text.trim()).split('/', '-', '.')
        if (parts.size != 3) return null
        val (y, m, d) = parts.map { it.toIntOrNull() ?: return null }
        if (y !in 1300..1600 || m !in 1..12 || d !in 1..monthLength(y, m)) return null
        return fromJalali(y, m, d)
    }

    fun monthLength(year: Int, month: Int): Int = when {
        month <= 6 -> 31
        month <= 11 -> 30
        else -> if (ir.sabou.kernel.JalaliCalendar.isLeap(year)) 30 else 29
    }

    private val ones = listOf("", "یک", "دو", "سه", "چهار", "پنج", "شش", "هفت", "هشت", "نه", "ده", "یازده", "دوازده", "سیزده", "چهارده",
        "پانزده", "شانزده", "هفده", "هجده", "نوزده")
    private val tens = listOf("", "", "بیست", "سی", "چهل", "پنجاه", "شصت", "هفتاد", "هشتاد", "نود")
    private val hundreds = listOf("", "صد", "دویست", "سیصد", "چهارصد", "پانصد", "ششصد", "هفتصد", "هشتصد", "نهصد")
    private val scales = listOf("", "هزار", "میلیون", "میلیارد", "هزار میلیارد", "میلیون میلیارد")

    private fun belowThousand(n: Int): String = listOfNotNull(
        hundreds[n / 100].ifEmpty { null },
        (n % 100).let { r -> if (r == 0) null else if (r < 20) ones[r] else listOfNotNull(tens[r / 10], ones[r % 10].ifEmpty { null }).joinToString(" و ") },
    ).joinToString(" و ")

    /** «یک میلیون و دویست هزار» — for cheques and printed amounts. */
    fun inWords(value: Long): String {
        if (value == 0L) return "صفر"
        if (value < 0) return "منفی " + inWords(-value)
        val groups = ArrayList<Int>()
        var v = value
        while (v > 0) { groups += (v % 1000).toInt(); v /= 1000 }
        return groups.indices.reversed().filter { groups[it] != 0 }
            .joinToString(" و ") { i -> listOf(belowThousand(groups[i]), scales[i]).filter { it.isNotEmpty() }.joinToString(" ") }
    }

    /** «پانزدهم مهر یک هزار و چهارصد و پنج» — the date line of a cheque. */
    fun dateInWords(date: BusinessDate): String {
        val j = jalali(date)
        val w = inWords(j.day.toLong())
        val ordinal = when {
            w.endsWith("سه") -> w.dropLast(2) + "سوم"
            w.endsWith("سی") -> w + "\u200cام"
            else -> w + "م"
        }
        return "$ordinal ${monthNames[j.month - 1]} ${inWords(j.year.toLong())}"
    }
}
