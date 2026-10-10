package ir.sabou.core

import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One value in an exported table: what is shown, and the number behind it for spreadsheets. */
sealed interface Cell {
    val text: String

    data class Text(override val text: String) : Cell
    /** Rial, shown and exported as Rial. */
    data class Amount(val rial: Long) : Cell { override val text: String get() = Fa.rial(rial) }
    /** Micro-units of stock or portions. */
    data class Qty(val micros: Long) : Cell { override val text: String get() = (if (micros < 0) "−" else "") + Fa.quantity(ir.sabou.kernel.Quantity.of(kotlin.math.abs(micros))) }
    /** Basis points (1/100 of a percent). */
    data class Percent(val bp: Long) : Cell { override val text: String get() = Fa.percent(bp) }
    data class Count(val value: Long) : Cell { override val text: String get() = Fa.number(value) }

    companion object {
        val EMPTY = Text("")
        fun of(text: String) = Text(text)
    }
}

/**
 * A report as a table, independent of the format: the same table is written to Excel ([Xlsx]) and to
 * PDF (Android). [notes] are short lines printed under the table (assumptions, units).
 */
data class ReportTable(
    val title: String,
    val subtitle: String,
    val headers: List<String>,
    val rows: List<List<Cell>>,
    val footer: List<Cell>? = null,
    val notes: List<String> = emptyList(),
) {
    init {
        require(rows.all { it.size == headers.size } && (footer == null || footer.size == headers.size)) { "report_row_width" }
    }
}

/**
 * Minimal Office Open XML spreadsheet writer (no library): one right-to-left sheet per table, inline
 * strings, real numbers for amounts (Rial), quantities and percentages so they can be summed in Excel.
 */
object Xlsx {
    fun write(tables: List<ReportTable>): ByteArray {
        require(tables.isNotEmpty())
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, xml: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(xml.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
            val names = sheetNames(tables.map { it.title })
            put("[Content_Types].xml", buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
                append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>""")
                append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
                append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
                tables.indices.forEach { append("""<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""") }
                append("</Types>")
            })
            put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            put("xl/workbook.xml", buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
                names.forEachIndexed { i, n -> append("""<sheet name="${esc(n)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""") }
                append("</sheets></workbook>")
            })
            put("xl/_rels/workbook.xml.rels", buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
                tables.indices.forEach { append("""<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""") }
                append("""<Relationship Id="rId${tables.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
            })
            put("xl/styles.xml", STYLES)
            tables.forEachIndexed { i, t -> put("xl/worksheets/sheet${i + 1}.xml", sheet(t)) }
        }
        return out.toByteArray()
    }

    // Styles: 0 normal · 1 bold · 2 title · 3 amount #,##0.# · 4 quantity #,##0.### · 5 percent 0.0% · 6 count #,##0 · 7-10 bold variants
    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="3"><numFmt numFmtId="164" formatCode="#,##0.#"/><numFmt numFmtId="165" formatCode="#,##0.###"/><numFmt numFmtId="166" formatCode="0.0%"/></numFmts><fonts count="3"><font><sz val="11"/><name val="Tahoma"/></font><font><b/><sz val="11"/><name val="Tahoma"/></font><font><b/><sz val="14"/><name val="Tahoma"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFEAF1EE"/></patternFill></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="11"><xf/><xf fontId="1" fillId="2" applyFont="1" applyFill="1"/><xf fontId="2" applyFont="1"/><xf numFmtId="164" applyNumberFormat="1"/><xf numFmtId="165" applyNumberFormat="1"/><xf numFmtId="166" applyNumberFormat="1"/><xf numFmtId="3" applyNumberFormat="1"/><xf numFmtId="164" fontId="1" applyNumberFormat="1" applyFont="1"/><xf numFmtId="165" fontId="1" applyNumberFormat="1" applyFont="1"/><xf numFmtId="166" fontId="1" applyNumberFormat="1" applyFont="1"/><xf numFmtId="3" fontId="1" applyNumberFormat="1" applyFont="1"/></cellXfs></styleSheet>"""

    private fun sheet(t: ReportTable): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        append("""<sheetViews><sheetView rightToLeft="1" workbookViewId="0"/></sheetViews>""")
        append("<cols>")
        t.headers.forEachIndexed { i, h ->
            val longest = maxOf(h.length, t.rows.maxOfOrNull { it[i].text.length } ?: 0, t.footer?.get(i)?.text?.length ?: 0)
            append("""<col min="${i + 1}" max="${i + 1}" width="${(longest + 4).coerceIn(10, 60)}" customWidth="1"/>""")
        }
        append("</cols><sheetData>")
        var r = 0
        fun row(cells: List<String>) { r++; append("""<row r="$r">"""); cells.forEach { append(it) }; append("</row>") }
        row(listOf(text(r + 1, 0, t.title, 2)))
        if (t.subtitle.isNotBlank()) row(listOf(text(r + 1, 0, t.subtitle, 0)))
        r++                                                  // blank line
        row(t.headers.mapIndexed { c, h -> text(r + 1, c, h, 1) })
        t.rows.forEach { cells -> row(cells.mapIndexed { c, cell -> cell(r + 1, c, cell, bold = false) }) }
        t.footer?.let { f -> row(f.mapIndexed { c, cell -> cell(r + 1, c, cell, bold = true) }) }
        if (t.notes.isNotEmpty()) r++
        t.notes.forEach { n -> row(listOf(text(r + 1, 0, n, 0))) }
        append("</sheetData></worksheet>")
    }

    private fun ref(row: Int, col: Int): String {
        var c = col + 1
        val letters = StringBuilder()
        while (c > 0) { val m = (c - 1) % 26; letters.insert(0, ('A' + m)); c = (c - 1) / 26 }
        return "$letters$row"
    }

    private fun text(row: Int, col: Int, value: String, style: Int) =
        """<c r="${ref(row, col)}" t="inlineStr" s="$style"><is><t xml:space="preserve">${esc(value)}</t></is></c>"""

    private fun number(row: Int, col: Int, value: String, style: Int) = """<c r="${ref(row, col)}" s="$style"><v>$value</v></c>"""

    private fun cell(row: Int, col: Int, cell: Cell, bold: Boolean): String = when (cell) {
        is Cell.Text -> text(row, col, cell.text, if (bold) 1 else 0)
        // Whole numbers use the integer format: "#,##0.#" would show a trailing decimal point in Excel.
        is Cell.Amount -> number(row, col, cell.rial.toString(),
            if (cell.rial % 10 == 0L) (if (bold) 10 else 6) else (if (bold) 7 else 3))
        is Cell.Qty -> number(row, col, BigDecimal.valueOf(cell.micros).movePointLeft(6).stripTrailingZeros().toPlainString(),
            if (cell.micros % 1_000_000 == 0L) (if (bold) 10 else 6) else (if (bold) 8 else 4))
        is Cell.Percent -> number(row, col, BigDecimal.valueOf(cell.bp).movePointLeft(4).stripTrailingZeros().toPlainString(), if (bold) 9 else 5)
        is Cell.Count -> number(row, col, cell.value.toString(), if (bold) 10 else 6)
    }

    /** Sheet names: at most 31 characters, none of []:*?/\, unique. */
    internal fun sheetNames(titles: List<String>): List<String> {
        val used = HashSet<String>()
        return titles.mapIndexed { i, t ->
            // Excel also rejects a name that starts or ends with an apostrophe.
            val base = t.replace(Regex("""[\[\]:*?/\\]"""), " ").trim().trim('\'').trim().take(28).trimEnd('\'').trim().ifEmpty { "Sheet${i + 1}" }
            var name = base
            var n = 2
            while (!used.add(name.lowercase())) name = "${base.take(25)} ${n++}"
            name
        }
    }

    private fun esc(s: String) = buildString(s.length) {
        for (ch in s) when {
            ch == '&' -> append("&amp;")
            ch == '<' -> append("&lt;")
            ch == '>' -> append("&gt;")
            ch == '"' -> append("&quot;")
            ch.code < 0x20 && ch != '\t' && ch != '\n' -> Unit       // not allowed in XML 1.0
            else -> append(ch)
        }
    }
}
