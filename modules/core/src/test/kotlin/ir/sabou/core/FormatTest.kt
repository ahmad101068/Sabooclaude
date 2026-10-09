package ir.sabou.core

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FormatTest {
    private fun day(y: Int, m: Int, d: Int) = BusinessDate(LocalDate.of(y, m, d).toEpochDay())

    @Test fun jalaliMatchesKnownDates() {
        assertEquals(Fa.JalaliDate(1405, 7, 16), Fa.jalali(day(2026, 10, 8)))
        assertEquals("پنجشنبه ۱۶ مهر", Fa.dayTitle(day(2026, 10, 8)))
        assertEquals(Fa.JalaliDate(1405, 1, 1), Fa.jalali(day(2026, 3, 21)))     // Nowruz 1405
        assertEquals(Fa.JalaliDate(1403, 12, 30), Fa.jalali(day(2025, 3, 20)))   // 1403 is leap
        assertEquals(Fa.JalaliDate(1348, 10, 12), Fa.jalali(BusinessDate(1)))      // 1970-01-02
        assertEquals("۱۴۰۵/۰۷/۱۶", Fa.date(day(2026, 10, 8)))
        assertEquals(30, Fa.monthLength(1403, 12)); assertEquals(29, Fa.monthLength(1404, 12))
    }

    @Test fun jalaliRoundTripsEveryDayForEightyYears() {
        var d = day(1971, 1, 1).epochDay
        val end = day(2050, 12, 31).epochDay
        var previous: Fa.JalaliDate? = null
        while (d <= end) {
            val j = Fa.jalali(BusinessDate(d))
            assertEquals(d, Fa.fromJalali(j.year, j.month, j.day).epochDay)
            previous?.let { p -> assert(j.day == p.day + 1 || (j.day == 1 && (j.month == p.month + 1 || (j.month == 1 && j.year == p.year + 1)))) }
            previous = j; d++
        }
        assertEquals(day(2026, 10, 8), Fa.parseDate("۱۴۰۵/۷/۱۶"))
        assertNull(Fa.parseDate("1404/12/30"))
    }

    @Test fun numbersAndMoney() {
        assertEquals("۴۸٬۶۵۰٬۰۰۰", Fa.number(48_650_000))
        assertEquals("−۱٬۰۰۰", Fa.number(-1_000))
        assertEquals("۱۲٬۴۰۰٬۰۰۰", Fa.toman(124_000_000))
        assertEquals("۱۲٫۵", Fa.toman(125))
        assertEquals("۱۴٫۸ م", Fa.tomanShort(148_000_000))
        assertEquals("۲۱۲٫۵ م", Fa.tomanShort(2_125_000_000))
        assertEquals("۲ م", Fa.tomanShort(20_000_000))
        assertEquals(Money.of(124_000_000), Fa.parseToman("۱۲٬۴۰۰٬۰۰۰"))
        assertEquals(Money.of(50), Fa.parseToman("٥"))
        assertNull(Fa.parseToman("12a")); assertNull(Fa.parseToman(""))
        assertEquals(Quantity.of(2_500_000), Fa.parseQuantity("۲٫۵"))
        assertEquals(Quantity.of(250_000), Fa.parseQuantity("0.25"))
        assertNull(Fa.parseQuantity("1.1234567"))
        assertNull(Fa.parseQuantity("")); assertNull(Fa.parseQuantity("  "))
        assertEquals(Quantity.ZERO, Fa.parseQuantity("0"))
        assertEquals("۲٫۵", Fa.quantity(Quantity.of(2_500_000)))
        assertEquals("123456", Fa.latinDigits("۱۲۳۴۵۶"))
    }

    @Test fun errorsBecomeClearPersianSentences() {
        assertEquals("جمع روش‌های تسویه با مبلغ قابل تسویه برابر نیست.",
            Messages.of(ir.sabou.kernel.DomainError.InvalidState("DAILY_SALE", "SETTLEMENT_MISMATCH:40000")))
        assertEquals("موجودی حساب کافی نیست (موجود: ۱٬۰۰۰ تومان).",
            Messages.of(ir.sabou.kernel.DomainError.InsufficientFunds("x", 10_000, 20_000)))
        assertTrue(Messages.of(IllegalStateException("boom")).startsWith("عملیات انجام نشد"))
    }
}
