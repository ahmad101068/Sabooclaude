package ir.sabou.app.export

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import ir.sabou.app.R
import ir.sabou.core.Fa
import ir.sabou.treasury.Cheque
import java.io.OutputStream

/**
 * One of our cheques as a PDF page the size of a cheque leaf (Sayad cheques: about 18 × 8.5 cm), to print
 * onto the leaf: date in figures and words, amount in rial in figures and words, payee. Banks differ by a few
 * millimetres; [Template] holds the positions so they can be adjusted after a test print.
 */
object ChequePrint {
    /** Positions in millimetres from the top-right corner of the leaf (the text runs right to left). */
    data class Template(
        val widthMm: Float = 180f,
        val heightMm: Float = 85f,
        val dateFigures: Pair<Float, Float> = 22f to 14f,
        val dateWords: Pair<Float, Float> = 30f to 24f,
        val amountWords: Pair<Float, Float> = 30f to 38f,
        val payee: Pair<Float, Float> = 30f to 50f,
        val amountFigures: Pair<Float, Float> = 125f to 62f,
        val lineWidthMm: Float = 120f,
    )

    private const val PT_PER_MM = 72f / 25.4f

    fun write(context: Context, cheque: Cheque, out: OutputStream, template: Template = Template()) {
        val regular = ResourcesCompat.getFont(context, R.font.vazirmatn_regular) ?: Typeface.DEFAULT
        val bold = ResourcesCompat.getFont(context, R.font.vazirmatn_bold) ?: Typeface.DEFAULT_BOLD
        val w = (template.widthMm * PT_PER_MM).toInt()
        val h = (template.heightMm * PT_PER_MM).toInt()
        val doc = PdfDocument()
        try {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, 1).create())
            val canvas = page.canvas
            val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = regular; textSize = 10f; color = 0xFF111111.toInt() }
            val strong = TextPaint(text).apply { typeface = bold; textSize = 11f }
            fun put(s: String, at: Pair<Float, Float>, paint: TextPaint, widthMm: Float = template.lineWidthMm) {
                val width = (widthMm * PT_PER_MM).toInt()
                val layout = StaticLayout.Builder.obtain(s, 0, s.length, paint, width)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setTextDirection(TextDirectionHeuristics.RTL).build()
                canvas.save()
                // x is measured from the right edge: the layout's right side sits at that point.
                canvas.translate(w - at.first * PT_PER_MM - width, at.second * PT_PER_MM)
                layout.draw(canvas)
                canvas.restore()
            }
            // Cheques are written in rial, as the leaf asks.
            val rial = cheque.amount.rial
            put(Fa.date(cheque.dueDate), template.dateFigures, strong, 40f)
            put(Fa.dateInWords(cheque.dueDate), template.dateWords, text)
            put("${Fa.inWords(rial)} ریال", template.amountWords, text)
            put(cheque.details.counterparty, template.payee, text)
            put("${Fa.number(rial)} ریال", template.amountFigures, strong, 45f)
            doc.finishPage(page)
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }
}
