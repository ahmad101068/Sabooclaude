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
import ir.sabou.kernel.BusinessDate
import ir.sabou.treasury.Cheque
import org.json.JSONObject
import java.io.OutputStream

/**
 * One of our cheques as a PDF page the size of a cheque leaf (Sayad cheques: about 18 × 8.5 cm), to print
 * onto the leaf: date in figures and words, amount in rial in figures and words, payee. Banks differ by a few
 * millimetres and printers by their margin: [Template] holds the positions per bank, adjusted on the calibration
 * page after a test print on plain paper (which also draws the leaf's outline and a millimetre ruler).
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
        /** Moves everything: + left / + down (a printer's own margin). */
        val offsetX: Float = 0f,
        val offsetY: Float = 0f,
        val fontPt: Float = 10f,
    ) {
        fun toJson(): String = JSONObject().apply {
            put("w", widthMm.toDouble()); put("h", heightMm.toDouble()); put("line", lineWidthMm.toDouble())
            put("ox", offsetX.toDouble()); put("oy", offsetY.toDouble()); put("font", fontPt.toDouble())
            Field.entries.forEach { f -> f.of(this@Template).let { (x, y) -> put(f.key, "$x,$y") } }
        }.toString()

        companion object {
            fun fromJson(text: String): Template = runCatching {
                val o = JSONObject(text)
                fun at(f: Field, d: Template) = o.optString(f.key).split(',').takeIf { it.size == 2 }?.let { it[0].toFloat() to it[1].toFloat() } ?: f.of(d)
                val d = Template()
                Template(
                    o.optDouble("w", d.widthMm.toDouble()).toFloat(), o.optDouble("h", d.heightMm.toDouble()).toFloat(),
                    at(Field.DATE_FIGURES, d), at(Field.DATE_WORDS, d), at(Field.AMOUNT_WORDS, d), at(Field.PAYEE, d), at(Field.AMOUNT_FIGURES, d),
                    o.optDouble("line", d.lineWidthMm.toDouble()).toFloat(), o.optDouble("ox", 0.0).toFloat(), o.optDouble("oy", 0.0).toFloat(),
                    o.optDouble("font", d.fontPt.toDouble()).toFloat(),
                )
            }.getOrDefault(Template())
        }
    }

    /** The printed fields, with their names on the calibration page. */
    enum class Field(val key: String, val title: String) {
        DATE_FIGURES("dateFigures", "تاریخ (عدد)"),
        DATE_WORDS("dateWords", "تاریخ (حروف)"),
        AMOUNT_WORDS("amountWords", "مبلغ (حروف)"),
        PAYEE("payee", "در وجه"),
        AMOUNT_FIGURES("amountFigures", "مبلغ (عدد)");

        fun of(t: Template): Pair<Float, Float> = when (this) {
            DATE_FIGURES -> t.dateFigures; DATE_WORDS -> t.dateWords; AMOUNT_WORDS -> t.amountWords; PAYEE -> t.payee; AMOUNT_FIGURES -> t.amountFigures
        }

        fun set(t: Template, at: Pair<Float, Float>): Template = when (this) {
            DATE_FIGURES -> t.copy(dateFigures = at); DATE_WORDS -> t.copy(dateWords = at); AMOUNT_WORDS -> t.copy(amountWords = at)
            PAYEE -> t.copy(payee = at); AMOUNT_FIGURES -> t.copy(amountFigures = at)
        }
    }

    /** What is printed. */
    data class Fields(val dueDate: BusinessDate, val amountRial: Long, val payee: String)

    private const val PT_PER_MM = 72f / 25.4f

    fun write(context: Context, cheque: Cheque, out: OutputStream, template: Template = Template()) =
        write(context, Fields(cheque.dueDate, cheque.amount.rial, cheque.details.counterparty), out, template, guides = false)

    /** [guides]: also the leaf's outline, a millimetre ruler and each field's name — for a test on plain paper. */
    fun write(context: Context, fields: Fields, out: OutputStream, template: Template, guides: Boolean) {
        val regular = ResourcesCompat.getFont(context, R.font.vazirmatn_regular) ?: Typeface.DEFAULT
        val bold = ResourcesCompat.getFont(context, R.font.vazirmatn_bold) ?: Typeface.DEFAULT_BOLD
        val w = (template.widthMm * PT_PER_MM).toInt()
        val h = (template.heightMm * PT_PER_MM).toInt()
        val doc = PdfDocument()
        try {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, 1).create())
            val canvas = page.canvas
            val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = regular; textSize = template.fontPt; color = 0xFF111111.toInt() }
            val strong = TextPaint(text).apply { typeface = bold; textSize = template.fontPt + 1f }
            val guide = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = regular; textSize = 5f; color = 0xFF888888.toInt(); strokeWidth = 0.4f; style = Paint.Style.STROKE }
            val guideText = TextPaint(guide).apply { style = Paint.Style.FILL }
            if (guides) {
                canvas.drawRect(0.5f, 0.5f, w - 0.5f, h - 0.5f, guide)
                // A tick every 5 mm along the top and the right edge, numbered every 10 mm (from the top-right corner).
                var mm = 0
                while (mm <= template.widthMm) {
                    val x = w - mm * PT_PER_MM
                    canvas.drawLine(x, 0f, x, if (mm % 10 == 0) 8f else 4f, guide)
                    if (mm % 10 == 0 && mm > 0) canvas.drawText(Fa.number(mm.toLong()), x - 3f, 14f, guideText)
                    mm += 5
                }
                mm = 0
                while (mm <= template.heightMm) {
                    val y = mm * PT_PER_MM
                    canvas.drawLine(w.toFloat(), y, w - (if (mm % 10 == 0) 8f else 4f), y, guide)
                    if (mm % 10 == 0 && mm > 0) canvas.drawText(Fa.number(mm.toLong()), w - 20f, y + 2f, guideText)
                    mm += 5
                }
            }
            fun put(s: String, field: Field, paint: TextPaint, widthMm: Float = template.lineWidthMm) {
                val at = field.of(template)
                val x = at.first + template.offsetX
                val y = at.second + template.offsetY
                val width = (widthMm * PT_PER_MM).toInt()
                val layout = StaticLayout.Builder.obtain(s, 0, s.length, paint, width)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setTextDirection(TextDirectionHeuristics.RTL).build()
                canvas.save()
                // x is measured from the right edge: the layout's right side sits at that point.
                canvas.translate(w - x * PT_PER_MM - width, y * PT_PER_MM)
                layout.draw(canvas)
                canvas.restore()
                if (guides) {
                    val px = w - x * PT_PER_MM
                    val py = y * PT_PER_MM
                    canvas.drawCircle(px, py, 1.5f, guide)
                    canvas.drawText("${field.title} (${Fa.number(x.toLong())}، ${Fa.number(y.toLong())})", px - 60f, py - 2f, guideText)
                }
            }
            // Cheques are written in rial, as the leaf asks.
            put(Fa.date(fields.dueDate), Field.DATE_FIGURES, strong, 40f)
            put(Fa.dateInWords(fields.dueDate), Field.DATE_WORDS, text)
            put("${Fa.inWords(fields.amountRial)} ریال", Field.AMOUNT_WORDS, text)
            put(fields.payee, Field.PAYEE, text)
            put("${Fa.number(fields.amountRial)} ریال", Field.AMOUNT_FIGURES, strong, 45f)
            doc.finishPage(page)
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }
}

/** Each bank's cheque positions on this phone (the bank name as written on the cheque book); a default for others. */
object ChequeTemplates {
    private const val PREFS = "cheque_print"
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(bank: String) = "bank:" + bank.trim()

    fun forBank(c: Context, bank: String): ChequePrint.Template =
        (prefs(c).getString(key(bank), null) ?: prefs(c).getString(key(""), null))?.let(ChequePrint.Template::fromJson) ?: ChequePrint.Template()

    /** Whether [bank] has its own positions (otherwise the default ones are used). */
    fun hasOwn(c: Context, bank: String) = prefs(c).contains(key(bank))

    fun save(c: Context, bank: String, template: ChequePrint.Template) = prefs(c).edit().putString(key(bank), template.toJson()).apply()

    fun reset(c: Context, bank: String) = prefs(c).edit().remove(key(bank)).apply()
}
