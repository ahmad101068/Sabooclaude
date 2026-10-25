package ir.sabou.ledger

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.JalaliCalendar
import ir.sabou.kernel.ensure
import ir.sabou.platform.CommandContext
import ir.sabou.platform.Permission

/**
 * Which dates a document may carry (ADR-0065, audit SABOU-AUDIT-002). Checked where every financial document
 * passes — the ledger — so no module can forget it:
 * - never after today (in Iran's time), except period-end documents ([monthEndSources]: payroll, depreciation)
 *   which may carry the end of the current Jalali month;
 * - not before today − [pastDays], unless the person holds [Permission.BACKDATE].
 * Closed periods stay closed regardless (the ledger's own check).
 */
data class DatePolicy(
    val pastDays: Long = DEFAULT_PAST_DAYS,
    val monthEndSources: Set<String> = emptySet(),
    val utcOffsetMinutes: Long = IRAN_OFFSET_MINUTES,
) {
    init { require(pastDays in 0..3650) { "date_policy_past_days" } }

    fun today(nowEpochMillis: Long): BusinessDate = BusinessDate(Math.floorDiv(nowEpochMillis + utcOffsetMinutes * 60_000L, 86_400_000L))

    /** The latest date a document of [sourceType] may carry today. */
    fun latest(nowEpochMillis: Long, sourceType: String): BusinessDate {
        val today = today(nowEpochMillis)
        if (sourceType !in monthEndSources) return today
        val j = JalaliCalendar.of(today)
        return JalaliCalendar.date(j.year, j.month, JalaliCalendar.monthLength(j.year, j.month))
    }

    /** The earliest date [context]'s person may post; null when they may post any (open-period) date. */
    fun earliest(context: CommandContext): BusinessDate? =
        if (context.actor.role.allows(Permission.BACKDATE)) null else today(context.nowEpochMillis).plusDays(-pastDays)

    fun allows(context: CommandContext, date: BusinessDate, sourceType: String): Boolean =
        date <= latest(context.nowEpochMillis, sourceType) && earliest(context)?.let { date >= it } != false

    fun check(context: CommandContext, date: BusinessDate, sourceType: String) {
        ensure(date <= latest(context.nowEpochMillis, sourceType)) { DomainError.InvalidInput("date", "تاریخ سند در آینده است؛ تاریخ را بررسی کنید.") }
        earliest(context)?.let { first ->
            ensure(date >= first) {
                DomainError.InvalidInput("date", "تاریخ سند بیش از $pastDays روز گذشته است؛ ثبت آن مجوز «ثبت با تاریخ گذشته» (مالک یا مدیر) می‌خواهد.")
            }
        }
    }

    companion object {
        const val DEFAULT_PAST_DAYS = 45L
        const val IRAN_OFFSET_MINUTES = 210L
    }
}
