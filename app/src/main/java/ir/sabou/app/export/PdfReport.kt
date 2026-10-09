package ir.sabou.app.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import ir.sabou.app.R
import ir.sabou.core.Cell
import ir.sabou.core.Fa
import ir.sabou.core.ReportTable
import java.io.OutputStream

/**
 * Renders [ReportTable]s to an A4 PDF, right to left, in Vazirmatn. Each table starts on a new page
 * (a payslip per page); a table that does not fit continues on the next page with its header repeated.
 * Wide tables (more than six columns) use landscape pages.
 */
object PdfReport {
    private const val MARGIN = 32f
    private const val CELL_PAD = 4f

    fun write(context: Context, tables: List<ReportTable>, out: OutputStream) {
        val regular = ResourcesCompat.getFont(context, R.font.vazirmatn_regular) ?: Typeface.DEFAULT
        val bold = ResourcesCompat.getFont(context, R.font.vazirmatn_bold) ?: Typeface.DEFAULT_BOLD
        val doc = PdfDocument()
        try {
            var pageNo = 0
            tables.forEach { table ->
                val landscape = table.headers.size > 6
                val w = if (landscape) 842 else 595
                val h = if (landscape) 595 else 842
                Renderer(doc, w, h, regular, bold) { ++pageNo }.render(table)
            }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    private class Renderer(
        private val doc: PdfDocument,
        private val width: Int,
        private val height: Int,
        regular: Typeface,
        bold: Typeface,
        private val nextPageNumber: () -> Int,
    ) {
        private val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = regular; textSize = 8.5f; color = INK }
        private val strong = TextPaint(body).apply { typeface = bold }
        private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bold; textSize = 15f; color = DARK }
        private val subPaint = TextPaint(body).apply { textSize = 9.5f; color = MUTED }
        private val notePaint = TextPaint(body).apply { textSize = 8f; color = MUTED }
        private val grid = Paint().apply { color = GRID; strokeWidth = 0.6f }
        private val headFill = Paint().apply { color = HEAD; style = Paint.Style.FILL }
        private val inner = width - 2 * MARGIN

        private var page: PdfDocument.Page? = null
        private var canvas: Canvas? = null
        private var y = 0f

        fun render(t: ReportTable) {
            val widths = columnWidths(t)
            newPage(t, first = true)
            drawRow(t.headers.map { Cell.of(it) }, widths, header = true, t)
            t.rows.forEach { drawRow(it, widths, header = false, t) }
            t.footer?.let { drawRow(it, widths, header = false, t, footer = true) }
            y += 10f
            t.notes.forEach { note ->
                val l = layout(note, notePaint, inner)
                ensureRoom(l.height.toFloat(), t, repeatHeader = false, widths)
                draw(l, MARGIN, y)
                y += l.height + 2f
            }
            finish()
        }

        /** Column widths from content, scaled to the page; at least 36 pt each. */
        private fun columnWidths(t: ReportTable): FloatArray {
            val n = t.headers.size
            val natural = FloatArray(n) { c ->
                val samples = listOf(t.headers[c]) + t.rows.take(200).map { it[c].text } + listOfNotNull(t.footer?.get(c)?.text)
                (samples.maxOf { body.measureText(it) } + 2 * CELL_PAD).coerceIn(36f, inner * 0.45f)
            }
            val total = natural.sum()
            return FloatArray(n) { natural[it] * inner / total }
        }

        private fun drawRow(cells: List<Cell>, widths: FloatArray, header: Boolean, t: ReportTable, footer: Boolean = false) {
            val paint = if (header || footer) strong else body
            val layouts = cells.mapIndexed { i, c -> layout(c.text, paint, widths[i] - 2 * CELL_PAD) }
            val rowHeight = layouts.maxOf { it.height } + 2 * CELL_PAD
            if (!header) ensureRoom(rowHeight, t, repeatHeader = true, widths)
            val c = canvas!!
            // Right to left: the first column is at the right edge.
            var right = width - MARGIN
            if (header || footer) c.drawRect(MARGIN, y, width - MARGIN, y + rowHeight, headFill)
            layouts.forEachIndexed { i, l ->
                val left = right - widths[i]
                draw(l, left + CELL_PAD, y + CELL_PAD)
                c.drawLine(left, y, left, y + rowHeight, grid)
                right = left
            }
            c.drawLine(width - MARGIN, y, width - MARGIN, y + rowHeight, grid)
            c.drawLine(MARGIN, y, width - MARGIN, y, grid)
            c.drawLine(MARGIN, y + rowHeight, width - MARGIN, y + rowHeight, grid)
            y += rowHeight
        }

        private fun ensureRoom(needed: Float, t: ReportTable, repeatHeader: Boolean, widths: FloatArray) {
            if (y + needed <= height - MARGIN - 18f) return
            finish()
            newPage(t, first = false)
            if (repeatHeader) drawRow(t.headers.map { Cell.of(it) }, widths, header = true, t)
        }

        private fun newPage(t: ReportTable, first: Boolean) {
            val number = nextPageNumber()
            val p = doc.startPage(PdfDocument.PageInfo.Builder(width, height, number).create())
            page = p
            canvas = p.canvas
            y = MARGIN
            val title = layout(if (first) t.title else "${t.title} (ادامه)", titlePaint, inner)
            draw(title, MARGIN, y); y += title.height + 2f
            if (t.subtitle.isNotBlank()) { val s = layout(t.subtitle, subPaint, inner); draw(s, MARGIN, y); y += s.height }
            y += 10f
            val foot = layout("سابو · صفحه ${Fa.number(number.toLong())}", notePaint, inner, Layout.Alignment.ALIGN_CENTER)
            draw(foot, MARGIN, height - MARGIN + 4f)
        }

        private fun finish() {
            page?.let { doc.finishPage(it) }
            page = null; canvas = null
        }

        private fun layout(text: String, paint: TextPaint, width: Float, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(8))
                .setAlignment(align)
                .setTextDirection(TextDirectionHeuristics.RTL)
                .setIncludePad(false)
                .build()

        private fun draw(l: StaticLayout, x: Float, top: Float) {
            val c = canvas ?: return
            c.save(); c.translate(x, top); l.draw(c); c.restore()
        }
    }

    private const val INK = 0xFF1D2A26.toInt()
    private const val DARK = 0xFF14352D.toInt()
    private const val MUTED = 0xFF6A7672.toInt()
    private const val GRID = 0xFFD5DEDA.toInt()
    private const val HEAD = 0xFFEAF1EE.toInt()
}
