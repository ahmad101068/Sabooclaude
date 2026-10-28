package ir.sabou.posimport

// Regression for SABOU-AUDIT-010 (independent audit 1405/08/06): a tiny file cannot make the reader fill memory
// through the row and column numbers it declares; it is refused as invalid.

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class XlsxIndexTest {
    private fun xlsx(sheet: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml")); z.write(sheet.toByteArray()); z.closeEntry()
        }
        return out.toByteArray()
    }

    @Test fun hugeDeclaredRowsAndColumnsAreRefused() {
        val ns = "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
        assertEquals(1, Sheets.read("pos.xlsx", xlsx("<worksheet $ns><sheetData><row r=\"1\"><c r=\"A1\"><v>1</v></c></row></sheetData></worksheet>")).rows.size)
        assertFailsWith<ImportFileException> { Sheets.read("pos.xlsx", xlsx("<worksheet $ns><sheetData><row r=\"2000000000\"><c r=\"A1\"><v>1</v></c></row></sheetData></worksheet>")) }
        assertFailsWith<ImportFileException> { Sheets.read("pos.xlsx", xlsx("<worksheet $ns><sheetData><row r=\"1\"><c r=\"FXSHRXW1\"><v>1</v></c></row></sheetData></worksheet>")) }
    }
}
