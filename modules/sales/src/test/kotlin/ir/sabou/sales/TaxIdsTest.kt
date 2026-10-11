package ir.sabou.sales

import ir.sabou.kernel.BusinessDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Identity checks and the invoice tax ID (ADR-0037). */
class TaxIdsTest {
    @Test fun nationalAndLegalIdsAreChecked() {
        assertTrue(TaxIds.isNationalId("0012345679"))
        assertFalse(TaxIds.isNationalId("0012345678"))
        assertFalse(TaxIds.isNationalId("1111111111"))                     // passes the sum, refused by rule
        assertTrue(TaxIds.isLegalId("10101614561"))
        assertFalse(TaxIds.isLegalId("10101614562"))
        assertTrue(TaxIds.isTaxpayerId("41100000000000"))                  // a 14-digit economic code
        assertFalse(TaxIds.isTaxpayerId("12345"))
    }

    @Test fun verhoeffAndTheTaxIdShape() {
        assertEquals(3, Verhoeff.checkDigit("236"))                        // the textbook example: 2363
        assertTrue(Verhoeff.isValid("2363"))
        assertFalse(Verhoeff.isValid("2364"))
        val id = TaxIds.taxId("A1B2C3", BusinessDate(20_000), 255)
        assertEquals(22, id.length)
        assertEquals("A1B2C3" + "04E20" + "00000000FF", id.dropLast(1))      // 20,000 days = 0x4E20; serial 255 = 0xFF
        // The check digit covers the decimal form: A=65, B=66, C=67, days padded to 6, serial to 12.
        assertEquals(Verhoeff.checkDigit("651662673" + "020000" + "000000000255").toString(), id.takeLast(1))
    }
}
