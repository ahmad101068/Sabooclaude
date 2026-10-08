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

    data class JalaliDate(val year: Int, val month: Int, val day: Int)

    val monthNames = listOf("فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور", "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند")
    private val weekdayNames = listOf("شنبه", "یکشنبه", "دوشنبه", "سه‌شنبه", "چهارشنبه", "پنجشنبه", "جمعه")

    fun jalali(date: BusinessDate): JalaliDate = Jalali.fromJdn(date.epochDay + EPOCH_JDN)
    fun fromJalali(year: Int, month: Int, day: Int): BusinessDate = BusinessDate(Jalali.toJdn(year, month, day) - EPOCH_JDN)

    fun weekday(date: BusinessDate): String = weekdayNames[Math.floorMod(date.epochDay + 5, 7L).toInt()]

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
        else -> if (Jalali.isLeap(year)) 30 else 29
    }

    private const val EPOCH_JDN = 2_440_588L   // Julian day number of 1970-01-01

    /** Borkowski's algorithm as published in jalaali-js (MIT); valid for 1–3177 AP. */
    private object Jalali {
        private val breaks = intArrayOf(-61, 9, 38, 199, 426, 686, 756, 818, 1111, 1181, 1210, 1635, 2060, 2097, 2192, 2262, 2324, 2394, 2456, 3178)

        private class Cal(val leap: Int, val gy: Int, val march: Int)

        private fun cal(jy: Int): Cal {
            require(jy >= breaks.first() && jy < breaks.last()) { "jalali_out_of_range:$jy" }
            val gy = jy + 621
            var leapJ = -14
            var jp = breaks[0]
            var jump = 0
            for (i in 1 until breaks.size) {
                val jm = breaks[i]
                jump = jm - jp
                if (jy < jm) break
                leapJ += jump / 33 * 8 + (jump % 33) / 4
                jp = jm
            }
            var n = jy - jp
            leapJ += n / 33 * 8 + (n % 33 + 3) / 4
            if (jump % 33 == 4 && jump - n == 4) leapJ += 1
            val leapG = gy / 4 - (gy / 100 + 1) * 3 / 4 - 150
            val march = 20 + leapJ - leapG
            if (jump - n < 6) n = n - jump + (jump + 4) / 33 * 33
            var leap = ((n + 1) % 33 - 1) % 4
            if (leap == -1) leap = 4
            return Cal(leap, gy, march)
        }

        fun isLeap(jy: Int) = cal(jy).leap == 0

        private fun g2d(gy: Int, gm: Int, gd: Int): Long {
            var d = ((gy + (gm - 8) / 6 + 100100).toLong() * 1461) / 4 + (153 * ((gm + 9) % 12) + 2) / 5 + gd - 34840408
            d = d - ((gy + 100100 + (gm - 8) / 6) / 100) * 3 / 4 + 752
            return d
        }

        private fun d2gYear(jdn: Long): Int {
            var j = 4 * jdn + 139361631
            j += (4 * jdn + 183187720) / 146097 * 3 / 4 * 4 - 3908
            val i = (j % 1461) / 4 * 5 + 308
            val gm = ((i / 153) % 12 + 1).toInt()
            return (j / 1461 - 100100 + (8 - gm) / 6).toInt()
        }

        fun fromJdn(jdn: Long): JalaliDate {
            val gy = d2gYear(jdn)
            var jy = gy - 621
            val r = cal(jy)
            var k = (jdn - g2d(gy, 3, r.march)).toInt()
            if (k >= 0) {
                if (k <= 185) return JalaliDate(jy, 1 + k / 31, k % 31 + 1)
                k -= 186
            } else {
                jy -= 1
                k += 179
                if (r.leap == 1) k += 1
            }
            return JalaliDate(jy, 7 + k / 30, k % 30 + 1)
        }

        fun toJdn(jy: Int, jm: Int, jd: Int): Long {
            val r = cal(jy)
            return g2d(r.gy, 3, r.march) + (jm - 1) * 31 - jm / 7 * (jm - 7) + jd - 1
        }
    }
}
