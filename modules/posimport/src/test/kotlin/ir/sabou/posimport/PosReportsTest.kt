package ir.sabou.posimport

import ir.sabou.kernel.JalaliCalendar
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The three predicted Sepand exports (ADR-0026, SEPAND_IMPORT_SPEC). */
class PosReportsTest {
    private fun fixture(name: String): ByteArray {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        return File(root, "modules/posimport/src/test/resources/sepand/$name").readBytes()
    }

    private fun items(name: String, bytes: ByteArray = fixture(name)) = (PosReports.parse(Sheets.read(name, bytes)) as Parsed.Items).report

    @Test fun aCleanTableInRial() {
        val r = items("F1_sepand_item_sales_clean.xlsx")
        assertEquals(emptyList(), r.problems)
        assertEquals(FileCurrency.RIAL, r.currency)
        assertEquals(5, r.lines.size)
        assertEquals(598_890_000L, r.lines.sumOf { it.net })
        val burger = r.lines.single { it.code == "201" }
        assertEquals("برگر کلاسیک", burger.name)                         // Arabic ي/ك become Persian
        assertEquals(55_000_000L, burger.quantityMicros)
        assertEquals("پیش غذا", r.lines.last().group)                     // zero-width non-joiner normalized
        assertNull(r.date)                                                // the file does not say: the person chooses
    }

    @Test fun aPrintedReportWithTitlesGroupsPagesAndPersianDigitsInToman() {
        val r = items("F2_sepand_item_sales_report_style.xlsx")
        assertEquals(emptyList(), r.problems)
        assertEquals(FileCurrency.TOMAN, r.currency)
        assertEquals(JalaliCalendar.date(1405, 7, 18), r.date)
        assertEquals(5, r.lines.size)                                     // no titles, groups, totals, repeated header or footer
        assertEquals(listOf("پیتزا", "پیتزا", "ساندویچ", "نوشیدنی", "پیش غذا"), r.lines.map { it.group })
        assertEquals(59_889_000L, r.lines.sumOf { it.net })
        assertEquals(5, r.controlsChecked)                                // four group totals and the grand total
        assertEquals(450_000L, r.lines.first().unitPrice)
        // Same day as F1 in Toman: ×10 is the same as the Rial file.
        assertEquals(items("F1_sepand_item_sales_clean.xlsx").lines.sumOf { it.net }, r.lines.sumOf { it.net } * FileCurrency.TOMAN.rialPerUnit)
    }

    @Test fun aTotalThatDoesNotAddUpStopsTheImport() {
        val tampered = replaceInSheet(fixture("F2_sepand_item_sales_report_style.xlsx"), "۱۷٬۰۱۹٬۰۰۰", "۱۷٬۰۱۰٬۰۰۰")
        val r = items("F2.xlsx", tampered)
        assertTrue(r.problems.isNotEmpty())
    }

    @Test fun invoicesWithVoidReturnMixedPaymentsAndCredit() {
        val r = (PosReports.parse(Sheets.read("F3.xlsx", fixture("F3_sepand_invoices.xlsx"))) as Parsed.Invoices).report
        assertEquals(emptyList(), r.problems)
        assertNull(r.currency)                                            // not stated: the person confirms
        assertEquals(JalaliCalendar.date(1405, 7, 18), r.date)
        assertEquals(6, r.invoices.size)
        assertEquals(InvoiceStatus.VOID, r.invoices.single { it.number == "10234" }.status)
        assertEquals(InvoiceStatus.RETURN, r.invoices.single { it.number == "10236" }.status)
        val counted = r.counted
        assertEquals(5, counted.size)
        assertEquals(8_500_000L + 4_200_000 + 12_400_000 + 7_700_000 - 3_100_000, counted.sumOf { it.payable })
        assertEquals(4_200_000L + 2_000_000 - 3_100_000, counted.sumOf { it.cash ?: 0 })
        assertEquals(8_500_000L + 5_700_000, counted.sumOf { it.card ?: 0 })
        assertEquals("شرکت آرین", counted.single { (it.credit ?: 0) > 0 }.customer)
    }

    @Test fun csvInWindows1256AndTextNumbers() {
        val csv = "نام کالا;تعداد;مبلغ کل (ریال)\nپيتزا;2;1,000,000\nسالاد;(1);-500,000\n"
        val r = items("x.csv", csv.toByteArray(charset("windows-1256")))
        assertEquals(listOf("پیتزا", "سالاد"), r.lines.map { it.name })
        assertEquals(listOf(1_000_000L, -500_000L), r.lines.map { it.gross })
        assertEquals(-1_000_000L, r.lines[1].quantityMicros)
    }

    @Test fun numbersAsExportsWriteThem() {
        assertEquals(18_900_000L, Text.amount("۱۸٬۹۰۰٬۰۰۰"))
        assertEquals(0L, Text.amount("-"))
        assertEquals(-3_100_000L, Text.amount("(۳٬۱۰۰٬۰۰۰)"))
        assertEquals(-3_100_000L, Text.amount("3,100,000-"))
        assertEquals(2_500_000L, Text.micros("۲٫۵"))
        assertEquals(189_000_000L, Text.amount("1.89E8"))
        assertNull(Text.amount("۲٫۵"))                                    // a fraction is not an amount
        assertNull(Text.amount("abc"))
    }

    @Test fun unreadableFilesSayWhy() {
        assertTrue(assertFailsWith<ImportFileException> { Sheets.read("old.xls", byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0, 0, 0, 0)) }.message!!.contains(".xlsx"))
        assertFailsWith<ImportFileException> { Sheets.read("x.pdf", "%PDF-1.4".toByteArray()) }
        assertFailsWith<ImportFileException> { PosReports.parse(Sheets.read("x.csv", "a,b\n1,2\n".toByteArray())) }
    }

    @Test fun anXmlBombOrExternalEntityIsNotFollowed() {
        val evil = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><worksheet><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>&e;</t></is></c></row></sheetData></worksheet>"""
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> z.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml")); z.write(evil.toByteArray(Charsets.UTF_8)); z.closeEntry() }
        val r = runCatching { Sheets.read("e.xlsx", out.toByteArray()) }
        assertTrue(r.isFailure || r.getOrThrow().rows.flatten().none { it?.contains("root") == true })
    }

    /** The same workbook with one text replaced in its shared strings. */
    private fun replaceInSheet(xlsx: ByteArray, from: String, to: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zo ->
            ZipInputStream(xlsx.inputStream()).use { zi ->
                while (true) {
                    val e = zi.nextEntry ?: break
                    var bytes = zi.readBytes()
                    if (e.name.endsWith(".xml")) {
                        // Either as text or as character references (&#1777;…), as writers differ.
                        fun refs(t: String) = t.map { if (it.code < 128) it.toString() else "&#${it.code};" }.joinToString("")
                        bytes = String(bytes, Charsets.UTF_8).replace(from, to).replace(refs(from), refs(to)).toByteArray(Charsets.UTF_8)
                    }
                    zo.putNextEntry(ZipEntry(e.name)); zo.write(bytes); zo.closeEntry()
                }
            }
        }
        return out.toByteArray()
    }
}
