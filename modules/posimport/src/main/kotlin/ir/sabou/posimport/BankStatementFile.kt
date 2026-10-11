package ir.sabou.posimport

import ir.sabou.kernel.BusinessDate

/** One row of a bank statement, oldest first. Exactly one of [deposit] / [withdrawal] is set, in Rial. */
data class BankRow(val date: BusinessDate, val description: String, val deposit: Long?, val withdrawal: Long?, val balance: Long?, val reference: String)

/** A bank statement file read into rows (ADR-0035); [closingBalance] is the balance after the last row, if shown. */
data class BankFile(val rows: List<BankRow>, val closingBalance: Long?, val problems: List<String>)

/**
 * Reads the statement exports of Iranian banks (Excel or CSV): a header row with «تاریخ», a description («شرح»,
 * «توضیحات»), money out («برداشت», «بدهکار») and in («واریز», «بستانکار») — or one signed «مبلغ» —, optionally
 * «مانده» and a reference («شماره پیگیری», «سند», «مرجع»). Rows newest-first are put oldest-first. When balances
 * are shown, each row's balance must follow from the previous one; a break means the columns were misread.
 */
object BankStatementFile {
    fun parse(sheet: Sheet): BankFile {
        val problems = ArrayList<String>()
        val headerRow = sheet.rows.take(40).indexOfFirst { row -> row.any { key(it).contains("تاریخ") } && row.any { c -> AMOUNT.any { key(c).contains(it) } } }
        if (headerRow < 0) return BankFile(emptyList(), null, listOf("سطر عنوان صورت‌حساب پیدا نشد (ستون‌های «تاریخ» و «برداشت/واریز» یا «مبلغ»)."))
        val header = sheet.rows[headerRow].map(::key)
        fun col(vararg names: String, except: List<String> = emptyList()) = header.indexOfFirst { h -> names.any { h.contains(it) } && except.none { h.contains(it) } }
        val date = col("تاریخ")
        val out = col("برداشت", "بدهکار", "debit")
        val inn = col("واریز", "بستانکار", "credit")
        val signed = if (out < 0 && inn < 0) col("مبلغ", "گردش", except = listOf("مانده")) else -1
        val balance = col("مانده", "balance")
        val text = col("شرح", "توضیحات", "بابت", "عنوان", "description")
        val ref = col("پیگیری", "مرجع", "شمارهسند", "سند", "ارجاع", "تراکنش", except = listOf("تاریخ", "مبلغ", "شرح", "توضیح"))
        if (out < 0 && inn < 0 && signed < 0) return BankFile(emptyList(), null, listOf("ستون مبلغ (برداشت/واریز) پیدا نشد."))
        val toman = sheet.rows[headerRow].any { Text.normalize(it).contains("تومان") }
        fun money(v: Long?) = v?.let { if (toman) Math.multiplyExact(it, 10L) else it }

        val rows = ArrayList<BankRow>()
        sheet.rows.drop(headerRow + 1).forEachIndexed { i, row ->
            fun cell(c: Int) = if (c < 0) null else row.getOrNull(c)
            val d = PosReports.jalali(cell(date)) ?: return@forEachIndexed            // totals, blank and footer rows
            var dep = money(Text.amount(cell(inn))?.takeIf { it != 0L })
            var wd = money(Text.amount(cell(out))?.takeIf { it != 0L })
            if (signed >= 0) Text.amount(cell(signed))?.let { v -> if (v > 0) dep = money(v) else if (v < 0) wd = money(-v) }
            if (dep != null && dep < 0) { wd = -dep; dep = null }
            if (wd != null && wd < 0) { dep = -wd; wd = null }
            if ((dep == null) == (wd == null)) {
                if (dep != null) problems += "سطر ${i + headerRow + 2}: هم برداشت و هم واریز دارد."
                return@forEachIndexed
            }
            rows += BankRow(d, Text.normalize(cell(text)), dep, wd, money(Text.amount(cell(balance))), Text.normalize(cell(ref)))
        }
        if (rows.isEmpty()) return BankFile(emptyList(), null, problems + "سطری با تاریخ و مبلغ در فایل نیست.")
        // Newest first? Then reverse, keeping the order within a day.
        val ordered = if (rows.first().date > rows.last().date) rows.reversed() else rows
        val withBalance = ordered.filter { it.balance != null }
        if (withBalance.size >= 2) {
            val breaks = ordered.zipWithNext().count { (a, b) -> a.balance != null && b.balance != null && a.balance + (b.deposit ?: 0) - (b.withdrawal ?: 0) != b.balance }
            if (breaks > 0) problems += "مانده‌ی ${breaks} سطر با سطر قبل و مبلغش جور نیست؛ ستون‌ها درست خوانده نشده یا فایل کامل نیست."
        }
        return BankFile(ordered, ordered.last().balance, problems)
    }

    private val AMOUNT = listOf("برداشت", "بدهکار", "واریز", "بستانکار", "مبلغ", "debit", "credit")

    private fun key(raw: String?): String = Text.key(raw)
}
