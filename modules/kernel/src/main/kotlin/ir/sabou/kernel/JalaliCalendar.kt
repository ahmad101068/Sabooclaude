package ir.sabou.kernel

/**
 * The Iranian (Solar Hijri) calendar. In the kernel because business rules depend on it: the fiscal year of
 * a document (its number series restarts every year) and, later, fiscal periods and holidays.
 */
object JalaliCalendar {
    data class Ymd(val year: Int, val month: Int, val day: Int)

    private const val EPOCH_JDN = 2_440_588L   // Julian day number of 1970-01-01

    fun of(date: BusinessDate): Ymd = Algorithm.fromJdn(date.epochDay + EPOCH_JDN)
    fun date(year: Int, month: Int, day: Int): BusinessDate = BusinessDate(Algorithm.toJdn(year, month, day) - EPOCH_JDN)
    fun isLeap(year: Int): Boolean = Algorithm.isLeap(year)
    fun yearOf(date: BusinessDate): Int = of(date).year


    /** Borkowski's algorithm as published in jalaali-js (MIT); valid for 1–3177 AP. */
    private object Algorithm {
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

        fun fromJdn(jdn: Long): Ymd {
            val gy = d2gYear(jdn)
            var jy = gy - 621
            val r = cal(jy)
            var k = (jdn - g2d(gy, 3, r.march)).toInt()
            if (k >= 0) {
                if (k <= 185) return Ymd(jy, 1 + k / 31, k % 31 + 1)
                k -= 186
            } else {
                jy -= 1
                k += 179
                if (r.leap == 1) k += 1
            }
            return Ymd(jy, 7 + k / 30, k % 30 + 1)
        }

        fun toJdn(jy: Int, jm: Int, jd: Int): Long {
            val r = cal(jy)
            return g2d(r.gy, 3, r.march) + (jm - 1) * 31 - jm / 7 * (jm - 7) + jd - 1
        }
    }

}
