package ir.sabou.posimport

import ir.sabou.kernel.JalaliCalendar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Bank statement exports read into rows, oldest first, with their running balance checked (ADR-0035). */
class BankStatementFileTest {
    private fun csv(text: String) = Sheets.read("s.csv", text.toByteArray(Charsets.UTF_8))

    @Test fun newestFirstWithDebitAndCreditColumnsIsReadOldestFirst() {
        val f = BankStatementFile.parse(csv(
            "بانک ملت - صورت‌حساب\n" +
            "ردیف,تاریخ,شرح تراکنش,شماره پیگیری,بدهکار,بستانکار,مانده\n" +
            "3,۱۴۰۵/۰۷/۰۳,کارمزد,۷۷۷,۵۰٬۰۰۰,,۷۹٬۹۵۰٬۰۰۰\n" +
            "2,1405/07/02,پرداخت اجاره,555,\"20,000,000\",,\"80,000,000\"\n" +
            "1,1405/07/01,واریز,444,,\"100,000,000\",\"100,000,000\"\n" +
            ",,جمع,,\"20,050,000\",\"100,000,000\",\n"))
        assertEquals(emptyList(), f.problems)
        assertEquals(listOf(JalaliCalendar.date(1405, 7, 1), JalaliCalendar.date(1405, 7, 2), JalaliCalendar.date(1405, 7, 3)), f.rows.map { it.date })
        assertEquals(100_000_000L, f.rows[0].deposit); assertNull(f.rows[0].withdrawal)
        assertEquals(50_000L, f.rows[2].withdrawal)
        assertEquals("777", f.rows[2].reference)
        assertEquals("کارمزد", f.rows[2].description)
        assertEquals(79_950_000L, f.closingBalance)
    }

    @Test fun aSignedAmountColumnInTomanAndABrokenBalanceAreHandled() {
        val f = BankStatementFile.parse(csv(
            "تاریخ,توضیحات,مبلغ (تومان),مانده (تومان)\n" +
            "1405/07/01,واریز,1000,1000\n" +
            "1405/07/02,برداشت,-300,800\n"))                 // should be 700
        assertEquals(listOf(10_000L, null), f.rows.map { it.deposit })
        assertEquals(3_000L, f.rows[1].withdrawal)
        assertTrue(f.problems.single().contains("جور نیست"))
    }

    @Test fun aFileWithoutTheColumnsSaysSo() {
        assertTrue(BankStatementFile.parse(csv("نام,قیمت\nپیتزا,100\n")).problems.single().contains("پیدا نشد"))
    }
}
