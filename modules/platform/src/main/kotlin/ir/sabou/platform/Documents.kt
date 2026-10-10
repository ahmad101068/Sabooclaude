package ir.sabou.platform

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.JalaliCalendar

/**
 * Number series of the documents a user sees, prints and refers to. Every series restarts at 1 in each fiscal
 * (Jalali) year; [perBranch] series count separately per branch, the accounting journal counts for the whole
 * organization (legal books). Numbers are issued only when a document becomes final, inside its own
 * transaction: without gaps, never reused, never changed.
 */
enum class DocumentSeries(val code: String, val title: String, val perBranch: Boolean) {
    JOURNAL("سح", "سند حسابداری", false),
    RECEIPT("در", "دریافت", true),
    PAYMENT("پر", "پرداخت", true),
    TRANSFER("او", "انتقال وجه", true),
    CASH_COUNT("شص", "شمارش صندوق", true),
    CHEQUE("چک", "عملیات چک", true),
    DAILY_SALE("فر", "فروش روز", true),
    PURCHASE_INVOICE("فخ", "فاکتور خرید", true),
    PURCHASE_RETURN("مخ", "مرجوعی خرید", true),
    PURCHASE_ORDER("سخ", "سفارش خرید", true),
    CREDIT_SETTLEMENT("تا", "تسویه با اعتبار", true),
    OPENING_STOCK("اد", "موجودی اول دوره", true),
    WASTE("ضا", "ضایعات", true),
    STOCK_TRANSFER("اک", "انتقال کالا", true),
    PRODUCTION("تو", "تولید", true),
    STOCK_COUNT("اگ", "انبارگردانی", true),
    PAYROLL("لح", "لیست حقوق", true),
    ASSET("دث", "خرید دارایی ثابت", true),
    DEPRECIATION("اس", "استهلاک", true),
    ASSET_DISPOSAL("کد", "واگذاری دارایی", true),
    REVERSAL("بر", "سند برگشت", true),
}

/** «فخ-1405-00012»: series code, fiscal year, sequence. The UI shows it with Persian digits. */
data class DocumentNumber(val series: DocumentSeries, val fiscalYear: Int, val scope: String, val sequence: Long) {
    val text: String get() = "${series.code}-$fiscalYear-${sequence.toString().padStart(5, '0')}"
    override fun toString(): String = text
}

/** One issued number: which document carries it, and what it reverses (for a reversal document). */
data class NumberedDocument(
    val number: DocumentNumber,
    val documentId: GlobalId,
    val date: BusinessDate,
    val commandId: String,
    val reverses: DocumentNumber? = null,
)

/** Persistence of number series. [next] must be called inside the command's transaction. */
interface DocumentNumberStore {
    fun next(series: DocumentSeries, fiscalYear: Int, scope: String): Long
    fun record(document: NumberedDocument)
    fun of(series: DocumentSeries, documentId: GlobalId): NumberedDocument?
    /** Every number carried by [documentId] (normally one). */
    fun ofDocument(documentId: GlobalId): List<NumberedDocument>
}

/** The fiscal year of a business date. Today the Jalali calendar year; a configurable start comes with fiscal periods. */
object FiscalYear {
    fun of(date: BusinessDate): Int = JalaliCalendar.yearOf(date)
}

/**
 * Declares that a command issues a final document of [series]: the command bus refuses (and rolls back) a
 * successful execution that did not number it. Every command carries this or [NoDocument]; an architecture
 * test checks it, so a new command cannot silently skip numbering.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class IssuesDocument(val series: DocumentSeries)

/** Declares that a command changes data without issuing a numbered document (master data, approvals, states). */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class NoDocument
