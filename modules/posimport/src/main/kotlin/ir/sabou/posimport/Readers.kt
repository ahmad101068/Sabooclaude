package ir.sabou.posimport

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** A worksheet as rows of cell texts (null = empty), in reading order. Merged cells keep their value top-left. */
data class Sheet(val rows: List<List<String?>>)

/** Why a file could not be read; the message is for the person. */
class ImportFileException(message: String) : Exception(message)

/** Reads the first worksheet of a point-of-sale export, whatever its format. */
object Sheets {
    const val MAX_FILE_BYTES = 10L * 1024 * 1024
    const val MAX_ROWS = 50_000

    fun read(fileName: String, bytes: ByteArray): Sheet {
        if (bytes.size > MAX_FILE_BYTES) throw ImportFileException("فایل بیش از ۱۰ مگابایت است.")
        val lower = fileName.lowercase()
        return when {
            bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() -> Xlsx.read(bytes)
            // The old binary .xls (BIFF) format: Excel or LibreOffice saves it as .xlsx in one step.
            bytes.size >= 8 && bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte() ->
                throw ImportFileException("فایل به قالب قدیمی .xls است. در اکسل آن را با «ذخیره به‌عنوان» به قالب .xlsx ذخیره کنید و دوباره انتخاب کنید.")
            lower.endsWith(".csv") || lower.endsWith(".txt") -> Csv.read(bytes)
            else -> throw ImportFileException("قالب فایل شناخته نشد؛ فایل .xlsx یا .csv انتخاب کنید.")
        }
    }
}

/** Office Open XML spreadsheet (.xlsx): the first sheet, shared strings, inline strings and numbers. */
internal object Xlsx {
    private const val MAX_ENTRY_BYTES = 60L * 1024 * 1024   // a zip bomb stops here

    fun read(bytes: ByteArray): Sheet {
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var total = 0L
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name.trimStart('/')
                if (name != "xl/workbook.xml" && name != "xl/_rels/workbook.xml.rels" && name != "xl/sharedStrings.xml" && !name.startsWith("xl/worksheets/sheet")) continue
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = zip.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_ENTRY_BYTES) throw ImportFileException("فایل بیش از اندازه بزرگ است.")
                    out.write(buf, 0, n)
                }
                entries[name] = out.toByteArray()
            }
        }
        val sheetPath = firstSheetPath(entries) ?: throw ImportFileException("در فایل برگه‌ای پیدا نشد.")
        val shared = entries["xl/sharedStrings.xml"]?.let(::sharedStrings).orEmpty()
        val sheet = entries[sheetPath] ?: throw ImportFileException("در فایل برگه‌ای پیدا نشد.")
        return Sheet(cells(sheet, shared))
    }

    private fun firstSheetPath(entries: Map<String, ByteArray>): String? {
        val workbook = entries["xl/workbook.xml"]?.let(::xml) ?: return entries.keys.filter { it.startsWith("xl/worksheets/sheet") }.minOrNull()
        val first = workbook.getElementsByTagNameNS("*", "sheet").item(0) as? Element ?: return null
        val rid = first.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id").ifEmpty { first.getAttribute("r:id") }
        val rels = entries["xl/_rels/workbook.xml.rels"]?.let(::xml) ?: return "xl/worksheets/sheet1.xml"
        val list = rels.getElementsByTagNameNS("*", "Relationship")
        for (i in 0 until list.length) {
            val r = list.item(i) as Element
            if (r.getAttribute("Id") == rid) {
                val target = r.getAttribute("Target").removePrefix("/")
                return if (target.startsWith("xl/")) target else "xl/$target"
            }
        }
        return "xl/worksheets/sheet1.xml"
    }

    private fun sharedStrings(bytes: ByteArray): List<String> {
        val doc = xml(bytes)
        val items = doc.getElementsByTagNameNS("*", "si")
        return (0 until items.length).map { i -> texts(items.item(i) as Element) }
    }

    /** All text runs of an element (rich text has several <t>). */
    private fun texts(e: Element): String {
        val ts = e.getElementsByTagNameNS("*", "t")
        return (0 until ts.length).joinToString("") { ts.item(it).textContent }
    }

    private fun cells(bytes: ByteArray, shared: List<String>): List<List<String?>> {
        val doc = xml(bytes)
        val rowNodes = doc.getElementsByTagNameNS("*", "row")
        if (rowNodes.length > Sheets.MAX_ROWS) throw ImportFileException("فایل بیش از ${Sheets.MAX_ROWS} سطر دارد.")
        val rows = ArrayList<List<String?>>()
        for (i in 0 until rowNodes.length) {
            val row = rowNodes.item(i) as Element
            val index = row.getAttribute("r").toIntOrNull()?.minus(1) ?: rows.size
            while (rows.size < index) rows.add(emptyList())      // skipped (empty) rows keep their place
            val values = ArrayList<String?>()
            val cs = row.getElementsByTagNameNS("*", "c")
            for (j in 0 until cs.length) {
                val c = cs.item(j) as Element
                val col = column(c.getAttribute("r")) ?: values.size
                while (values.size < col) values.add(null)
                val type = c.getAttribute("t")
                val v = c.getElementsByTagNameNS("*", "v").item(0)?.textContent
                val text = when (type) {
                    "s" -> v?.toIntOrNull()?.let { shared.getOrNull(it) }
                    "inlineStr" -> (c.getElementsByTagNameNS("*", "is").item(0) as? Element)?.let(::texts)
                    "b" -> if (v == "1") "TRUE" else "FALSE"
                    else -> v
                }
                values.add(text?.takeIf { it.isNotEmpty() })
            }
            rows.add(values)
        }
        return rows
    }

    /** "C12" → 2. */
    private fun column(ref: String): Int? {
        val letters = ref.takeWhile { it.isLetter() }.uppercase()
        if (letters.isEmpty()) return null
        return letters.fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) } - 1
    }

    private fun xml(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        // An export is untrusted input: no DTDs, no external entities. (Parsers that do not know a feature,
        // such as Android's, do not resolve external entities at all.)
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { isXIncludeAware = false }
        isExpandEntityReferences = false
    }.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement
}

/** Comma, semicolon or tab separated text in UTF-8 (with or without BOM) or Windows-1256. */
internal object Csv {
    fun read(bytes: ByteArray): Sheet {
        val text = decode(bytes).removePrefix("﻿")
        val lines = text.split("\r\n", "\n", "\r")
        if (lines.size > Sheets.MAX_ROWS) throw ImportFileException("فایل بیش از ${Sheets.MAX_ROWS} سطر دارد.")
        val sample = lines.take(20).joinToString("\n")
        val delimiter = listOf('\t', ';', ',').maxBy { d -> sample.count { it == d } }
        return Sheet(lines.map { split(it, delimiter).map { c -> c.takeIf { it.isNotBlank() } } })
    }

    private fun decode(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        String(bytes, Charset.forName("windows-1256"))   // older Windows exports
    }

    private fun split(line: String, d: Char): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                quoted && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                ch == d && !quoted -> { out += cur.toString(); cur.clear() }
                else -> cur.append(ch)
            }
            i++
        }
        out += cur.toString()
        return out
    }
}
