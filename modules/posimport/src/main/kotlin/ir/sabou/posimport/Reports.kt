package ir.sabou.posimport

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.JalaliCalendar
import java.math.BigDecimal

/**
 * The money unit a file is written in. A point-of-sale export may be in Toman; the product keeps Rial (ADR-0018),
 * so this is the one edge where Toman is converted — and only when the file says so or the person confirms it.
 */
enum class FileCurrency(val rialPerUnit: Long, val label: String) {
    RIAL(1, "ریال"), TOMAN(10, "تومان");

    companion object {
        /** Asked when a file does not say its money unit. */
        const val UNKNOWN = "فایل نمی‌گوید مبالغ به ریال است یا تومان؛ واحد را انتخاب کنید."
    }
}

/** A problem that stops the import until it is fixed (row numbers are as the person sees them in Excel). */
data class Problem(val row: Int?, val text: String) {
    override fun toString() = (row?.let { "سطر $it: " } ?: "") + text
}

/** One line of a «sales by item» report. Amounts are in [ItemSalesReport.currency] units. */
data class ItemLine(
    val row: Int,
    val code: String?,
    val name: String,
    val group: String?,
    /** Micro-units (6 decimals). */
    val quantityMicros: Long,
    val unitPrice: Long?,
    val gross: Long,
    val discount: Long,
    val tax: Long,
    val net: Long,
)

data class ItemSalesReport(
    /** The day the report covers, if the file says it. */
    val date: BusinessDate?,
    /** As the file says; null = it does not say and the person must choose. */
    val currency: FileCurrency?,
    val lines: List<ItemLine>,
    /** Totals the file itself printed and that the lines were checked against. */
    val controlsChecked: Int,
    val problems: List<Problem>,
)

enum class InvoiceStatus { NORMAL, VOID, RETURN }

/** One bill of an «invoices» report. A return carries negative amounts. */
data class PosInvoice(
    val row: Int,
    val number: String,
    val date: BusinessDate?,
    val time: String?,
    val saleType: String?,
    val customer: String?,
    val total: Long,
    val discount: Long,
    val service: Long,
    val tax: Long,
    val payable: Long,
    val cash: Long?,
    val card: Long?,
    val credit: Long?,
    val status: InvoiceStatus,
)

data class InvoiceReport(
    val date: BusinessDate?,
    val currency: FileCurrency?,
    val invoices: List<PosInvoice>,
    val problems: List<Problem>,
) {
    /** Bills that count (voided ones are left out; returns subtract). */
    val counted: List<PosInvoice> get() = invoices.filter { it.status != InvoiceStatus.VOID }
}

/** What kind of report a sheet is. */
sealed interface Parsed {
    data class Items(val report: ItemSalesReport) : Parsed
    data class Invoices(val report: InvoiceReport) : Parsed
}

/**
 * Reads a point-of-sale report without depending on its layout (ADR-0026): the header row is found by known
 * column names; title, group, subtotal, repeated header, page and footer rows are recognised and skipped; the
 * file's own subtotals and totals are checked against its lines; nothing is guessed — a missing date or money
 * unit is left for the person to choose.
 */
object PosReports {
    private enum class ItemCol(vararg val names: String) {
        ROW("ردیف"), CODE("کد کالا", "کد", "کد کالا/خدمت", "کد محصول"),
        NAME("نام کالا", "شرح کالا", "نام محصول", "عنوان کالا", "عنوان", "شرح", "نام"),
        GROUP("گروه کالا", "گروه"), UNIT("واحد"),
        QTY("تعداد", "مقدار", "تعداد فروش"), UNIT_PRICE("فی", "قیمت واحد", "فی واحد", "قیمت"),
        GROSS("مبلغ کل", "مبلغ", "جمع مبلغ", "مبلغ ناخالص"), DISCOUNT("تخفیف"),
        TAX("مالیات و عوارض", "مالیات", "ارزش افزوده", "مالیات بر ارزش افزوده"),
        NET("خالص فروش", "مبلغ خالص", "خالص"),
    }

    private enum class InvCol(vararg val names: String) {
        NUMBER("شماره فاکتور", "شماره سند", "شماره"), DATE("تاریخ"), TIME("ساعت"), TYPE("نوع فروش"), TABLE("میز"),
        CASHIER("صندوقدار", "کاربر"), CUSTOMER("مشتری", "طرف حساب"),
        TOTAL("جمع کل", "مبلغ کل", "جمع"), DISCOUNT("تخفیف"), SERVICE("سرویس", "حق سرویس"),
        TAX("مالیات", "مالیات و عوارض", "ارزش افزوده"), PAYABLE("قابل پرداخت", "مبلغ نهایی", "خالص"),
        CASH("نقدی", "نقد"), CARD("کارتخوان", "پوز", "کارت"), CREDIT("اعتباری", "نسیه"), STATUS("وضعیت"),
    }

    fun parse(sheet: Sheet): Parsed {
        val items = header(sheet, ItemCol.entries) { it.names.toList() }
        val invoices = header(sheet, InvCol.entries) { it.names.toList() }
        val itemOk = items != null && ItemCol.NAME in items.second && ItemCol.QTY in items.second &&
            (ItemCol.GROSS in items.second || ItemCol.NET in items.second || ItemCol.UNIT_PRICE in items.second)
        val invOk = invoices != null && InvCol.NUMBER in invoices.second && (InvCol.PAYABLE in invoices.second || InvCol.TOTAL in invoices.second)
        return when {
            invOk && (!itemOk || invoices!!.second.size >= items!!.second.size) -> Parsed.Invoices(parseInvoices(sheet, invoices!!.first, invoices.second))
            itemOk -> Parsed.Items(parseItems(sheet, items!!.first, items.second))
            else -> throw ImportFileException("سطر عنوان ستون‌ها پیدا نشد. فایل باید گزارش «فروش کالا» (نام کالا، تعداد، مبلغ) یا «ریز فاکتورها» (شماره فاکتور، قابل پرداخت) باشد.")
        }
    }

    // ---------------------------------------------------------------- Header

    private fun cleanHeader(raw: String?): String = Text.key(Text.normalize(raw).replace(Regex("\\(.*?\\)"), ""))

    /** The row (in the first 60) that names the most known columns, and which column each field is in. */
    private fun <C> header(sheet: Sheet, cols: List<C>, names: (C) -> List<String>): Pair<Int, Map<C, Int>>? {
        val keys = cols.associateWith { c -> names(c).map(Text::key) }
        var best: Pair<Int, Map<C, Int>>? = null
        sheet.rows.take(60).forEachIndexed { r, row ->
            val found = LinkedHashMap<C, Int>()
            row.forEachIndexed { i, cell ->
                val k = cleanHeader(cell)
                if (k.isEmpty()) return@forEachIndexed
                // The field whose name matches exactly; a longer name wins («مبلغ کل» over «مبلغ»).
                cols.filter { c -> c !in found && keys.getValue(c).any { it == k } }.firstOrNull()?.let { found[it] = i }
            }
            if (found.size >= 3 && found.size > (best?.second?.size ?: 0)) best = r to found
        }
        return best
    }

    private fun currency(sheet: Sheet, headerRow: Int, moneyCols: Collection<Int>): Pair<FileCurrency?, Problem?> {
        val texts = sheet.rows.take(headerRow).flatten().map(Text::normalize) + moneyCols.map { Text.normalize(sheet.rows[headerRow].getOrNull(it)) }
        val rial = texts.any { "ریال" in it }
        val toman = texts.any { "تومان" in it }
        return when {
            rial && toman -> null to Problem(headerRow + 1, "در فایل هم «ریال» آمده هم «تومان»؛ واحد مبالغ را خودتان تعیین کنید.")
            rial -> FileCurrency.RIAL to null
            toman -> FileCurrency.TOMAN to null
            else -> null to null
        }
    }

    private fun isRepeatedHeader(row: List<String?>, header: List<String?>): Boolean {
        val a = row.map(::cleanHeader).filter { it.isNotEmpty() }
        val b = header.map(::cleanHeader).filter { it.isNotEmpty() }
        return a.isNotEmpty() && a == b
    }

    private fun firstText(row: List<String?>) = row.map(Text::normalize).firstOrNull { it.isNotEmpty() }.orEmpty()

    private fun isFooter(row: List<String?>): Boolean {
        val t = firstText(row)
        return t.startsWith("صفحه") || t.startsWith("تاریخ چاپ") || t.startsWith("کاربر") || t.startsWith("چاپ") || t.startsWith("گزارش")
    }

    private fun isTotal(text: String) = text.startsWith("جمع") || text.startsWith("مجموع") || text == "کل"

    // ---------------------------------------------------------------- Dates

    private val dateRegex = Regex("(1[34][0-9]{2})[/\\-.](\\d{1,2})[/\\-.](\\d{1,2})")

    fun jalali(text: String?): BusinessDate? {
        val m = dateRegex.find(Text.normalize(text)) ?: return null
        val (y, mo, d) = m.destructured
        val month = mo.toInt(); val day = d.toInt()
        if (month !in 1..12 || day !in 1..31) return null
        return runCatching { JalaliCalendar.date(y.toInt(), month, day) }.getOrNull()
    }

    /** «از تاریخ : … تا تاریخ : …» (or a single date) above the header. */
    private fun titleDates(sheet: Sheet, headerRow: Int): List<BusinessDate> =
        sheet.rows.take(headerRow).flatten().flatMap { cell -> dateRegex.findAll(Text.normalize(cell)).mapNotNull { jalali(it.value) }.toList() }

    // ---------------------------------------------------------------- Sales by item

    private fun parseItems(sheet: Sheet, headerRow: Int, cols: Map<ItemCol, Int>): ItemSalesReport {
        val problems = ArrayList<Problem>()
        val header = sheet.rows[headerRow]
        val money = listOf(ItemCol.UNIT_PRICE, ItemCol.GROSS, ItemCol.DISCOUNT, ItemCol.TAX, ItemCol.NET).mapNotNull { cols[it] }
        val (currency, currencyProblem) = currency(sheet, headerRow, money)
        currencyProblem?.let(problems::add)
        val dates = titleDates(sheet, headerRow).distinct()
        if (dates.size > 1) problems += Problem(null, "گزارش بیش از یک روز است (${dates.size} تاریخ)؛ فروش کالا را برای یک روز بگیرید.")

        fun cell(row: List<String?>, c: ItemCol) = cols[c]?.let { row.getOrNull(it) }
        val lines = ArrayList<ItemLine>()
        var group: String? = null
        var groupStart = 0
        var controls = 0
        val byCol = cols.entries.associate { (k, v) -> v to k }

        /** A printed (sub)total: each number in it must equal the sum of that column over [lines]. */
        fun control(row: List<String?>, r: Int, over: List<ItemLine>, label: String) {
            row.forEachIndexed { i, raw ->
                val field = byCol[i] ?: return@forEachIndexed
                val printed = Text.decimal(raw) ?: return@forEachIndexed
                val sum: BigDecimal = when (field) {
                    ItemCol.QTY -> BigDecimal.valueOf(over.sumOf { it.quantityMicros }).movePointLeft(6)
                    ItemCol.GROSS -> BigDecimal.valueOf(over.sumOf { it.gross })
                    ItemCol.DISCOUNT -> BigDecimal.valueOf(over.sumOf { it.discount })
                    ItemCol.TAX -> BigDecimal.valueOf(over.sumOf { it.tax })
                    ItemCol.NET -> BigDecimal.valueOf(over.sumOf { it.net })
                    else -> return@forEachIndexed
                }
                controls++
                if (printed.compareTo(sum) != 0) problems += Problem(r + 1, "$label در فایل ${printed.toPlainString()} است ولی جمع سطرها ${sum.toPlainString()}.")
            }
        }

        for (r in headerRow + 1 until sheet.rows.size) {
            val row = sheet.rows[r]
            if (row.all { Text.normalize(it).isEmpty() } || isRepeatedHeader(row, header) || isFooter(row)) continue
            val name = Text.normalize(cell(row, ItemCol.NAME)).ifEmpty { firstText(row) }
            if (name.startsWith("گروه") && cols[ItemCol.QTY]?.let { Text.decimal(row.getOrNull(it)) } == null) {
                group = name.substringAfter(':', name.removePrefix("گروه")).trim(); groupStart = lines.size; continue
            }
            if (isTotal(name)) {
                val isGrand = name.contains("کل") || name == "جمع" || name.startsWith("مجموع")
                if (isGrand && !name.startsWith("جمع گروه")) control(row, r, lines, "«$name»")
                else control(row, r, lines.subList(groupStart, lines.size), "«$name»")
                continue
            }
            val qty = Text.micros(cell(row, ItemCol.QTY))
            if (qty == null) { problems += Problem(r + 1, "تعداد «$name» خوانده نشد."); continue }
            val unitPrice = Text.amount(cell(row, ItemCol.UNIT_PRICE))
            val discount = Text.amount(cell(row, ItemCol.DISCOUNT)) ?: 0
            val tax = Text.amount(cell(row, ItemCol.TAX)) ?: 0
            val grossRead = Text.amount(cell(row, ItemCol.GROSS))
            val netRead = Text.amount(cell(row, ItemCol.NET))
            val gross = grossRead ?: unitPrice?.let { BigDecimal.valueOf(it).multiply(BigDecimal.valueOf(qty).movePointLeft(6)).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact() }
                ?: netRead?.let { it + discount - tax }
            if (gross == null) { problems += Problem(r + 1, "مبلغ «$name» خوانده نشد."); continue }
            val net = netRead ?: (gross - discount + tax)
            if (unitPrice != null && grossRead != null) {
                val expected = BigDecimal.valueOf(unitPrice).multiply(BigDecimal.valueOf(qty).movePointLeft(6))
                if (expected.subtract(BigDecimal.valueOf(grossRead)).abs() > BigDecimal.ONE) problems += Problem(r + 1, "«$name»: تعداد × فی با مبلغ نمی‌خواند.")
            }
            if (net != gross - discount + tax) problems += Problem(r + 1, "«$name»: مبلغ خالص با مبلغ − تخفیف + مالیات نمی‌خواند.")
            lines += ItemLine(r + 1, Text.normalize(cell(row, ItemCol.CODE)).ifEmpty { null }, name, Text.normalize(cell(row, ItemCol.GROUP)).ifEmpty { null } ?: group,
                qty, unitPrice, gross, discount, tax, net)
        }
        if (lines.isEmpty()) problems += Problem(null, "در فایل سطر فروشی پیدا نشد.")
        return ItemSalesReport(dates.singleOrNull(), currency, lines, controls, problems)
    }

    // ---------------------------------------------------------------- Invoices

    private fun parseInvoices(sheet: Sheet, headerRow: Int, cols: Map<InvCol, Int>): InvoiceReport {
        val problems = ArrayList<Problem>()
        val header = sheet.rows[headerRow]
        val money = listOf(InvCol.TOTAL, InvCol.DISCOUNT, InvCol.SERVICE, InvCol.TAX, InvCol.PAYABLE, InvCol.CASH, InvCol.CARD, InvCol.CREDIT).mapNotNull { cols[it] }
        val (currency, currencyProblem) = currency(sheet, headerRow, money)
        currencyProblem?.let(problems::add)
        fun cell(row: List<String?>, c: InvCol) = cols[c]?.let { row.getOrNull(it) }
        val invoices = ArrayList<PosInvoice>()
        for (r in headerRow + 1 until sheet.rows.size) {
            val row = sheet.rows[r]
            if (row.all { Text.normalize(it).isEmpty() } || isRepeatedHeader(row, header) || isFooter(row)) continue
            if (row.map(Text::normalize).any { isTotal(it) }) continue
            val number = Text.normalize(cell(row, InvCol.NUMBER)).removeSuffix(".0")
            if (number.isEmpty()) continue
            val statusText = Text.normalize(cell(row, InvCol.STATUS))
            val payableRead = Text.amount(cell(row, InvCol.PAYABLE))
            val total = Text.amount(cell(row, InvCol.TOTAL)) ?: payableRead
            if (total == null) { problems += Problem(r + 1, "مبلغ فاکتور $number خوانده نشد."); continue }
            val discount = Text.amount(cell(row, InvCol.DISCOUNT)) ?: 0
            val service = Text.amount(cell(row, InvCol.SERVICE)) ?: 0
            val tax = Text.amount(cell(row, InvCol.TAX)) ?: 0
            val payable = payableRead ?: (total - discount + service + tax)
            val status = when {
                "ابطال" in statusText || "باطل" in statusText || "لغو" in statusText -> InvoiceStatus.VOID
                "برگشت" in statusText || "مرجوع" in statusText || payable < 0 -> InvoiceStatus.RETURN
                else -> InvoiceStatus.NORMAL
            }
            val cash = Text.amount(cell(row, InvCol.CASH)); val card = Text.amount(cell(row, InvCol.CARD)); val credit = Text.amount(cell(row, InvCol.CREDIT))
            if (status != InvoiceStatus.VOID) {
                if (payable != total - discount + service + tax) problems += Problem(r + 1, "فاکتور $number: قابل پرداخت با جمع − تخفیف + سرویس + مالیات نمی‌خواند.")
                if (cash != null || card != null || credit != null) {
                    if ((cash ?: 0) + (card ?: 0) + (credit ?: 0) != payable) problems += Problem(r + 1, "فاکتور $number: نقد + کارت + اعتباری با قابل پرداخت برابر نیست.")
                }
            }
            invoices += PosInvoice(r + 1, number, jalali(cell(row, InvCol.DATE)), Text.normalize(cell(row, InvCol.TIME)).ifEmpty { null },
                Text.normalize(cell(row, InvCol.TYPE)).ifEmpty { null }, Text.normalize(cell(row, InvCol.CUSTOMER)).ifEmpty { null },
                total, discount, service, tax, payable, cash, card, credit, status)
        }
        val numbers = invoices.groupBy { it.number }.filter { it.value.size > 1 }.keys
        if (numbers.isNotEmpty()) problems += Problem(null, "شماره فاکتور تکراری در فایل: ${numbers.joinToString("، ")}")
        val dates = (invoices.mapNotNull { it.date } + titleDates(sheet, headerRow)).distinct()
        if (dates.size > 1) problems += Problem(null, "فاکتورها از بیش از یک روز است؛ گزارش را برای یک روز بگیرید.")
        if (invoices.isEmpty()) problems += Problem(null, "در فایل فاکتوری پیدا نشد.")
        return InvoiceReport(dates.singleOrNull(), currency, invoices, problems)
    }
}
