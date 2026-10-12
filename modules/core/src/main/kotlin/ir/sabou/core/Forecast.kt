package ir.sabou.core

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.JalaliCalendar
import ir.sabou.kernel.Ratio
import ir.sabou.kernel.Scope
import ir.sabou.platform.Actor
import ir.sabou.platform.Permission
import ir.sabou.sales.SaleStatus
import ir.sabou.sales.SpecialDay

/** Expected net sales of one day with a range, and how it was made. */
data class DayForecast(val date: BusinessDate, val expected: Long, val low: Long, val high: Long, val samples: Int, val special: SpecialDay?)

data class SalesForecast(
    val days: List<DayForecast>,
    /** Last 4 weeks over the 4 before, for information (basis points, 10,000 = flat). */
    val trendBp: Long,
    /** Mean absolute percentage error of the same method over the last 28 days (back-tested), or null without history. */
    val errorBp: Long?,
)

/** Sales forecast per day (ADR-0041): the weekday pattern of recent weeks × the days the owner marked. */
class Forecasts internal constructor(private val core: SabouCore) {
    private fun actor(vararg anyOf: Permission): Actor {
        val a = core.session.currentActor() ?: throw DomainException(DomainError.AuthenticationRequired)
        if (anyOf.none { a.role.allows(it) }) throw DomainException(DomainError.PermissionDenied(anyOf.first().name))
        return a
    }

    fun sales(branch: Scope.Branch, from: BusinessDate, days: Int = 14): SalesForecast {
        val a = actor(Permission.SALES_VIEW, Permission.SHIFT_MANAGE, Permission.LEDGER_VIEW)
        if (!a.canAccess(branch)) throw DomainException(DomainError.ScopeDenied(ir.sabou.platform.CommandContext.scopeLabel(branch)))
        val history = dailyNet(branch, from.plusDays(-HISTORY_DAYS.toLong()), from.plusDays(-1))
        val special = core.specialDays.of(branch).associateBy { it.date }
        val trend = trend(history, from)
        val forecast = (0 until days).map { i -> day(from.plusDays(i.toLong()), history, special[from.plusDays(i.toLong())]) }
        return SalesForecast(forecast, trend, backtest(branch, from))
    }

    /** The forecast of one day from [history] (net sales by day, only days that had sales). */
    /**
     * The weighted mean already follows a recent change of level (last week weighs 8 times the eighth), so the trend
     * is shown, not multiplied in again.
     */
    private fun day(date: BusinessDate, history: Map<BusinessDate, Long>, special: SpecialDay?): DayForecast {
        val samples = (1..WEEKS).mapNotNull { w -> history[date.plusDays(-7L * w)]?.let { w to it } }
        if (samples.isEmpty()) return DayForecast(date, 0, 0, 0, 0, special)
        // Newer weeks weigh more: weight 8 for last week … 1 for eight weeks ago.
        val weights = samples.map { (w, v) -> (WEEKS + 1 - w).toLong() to v }
        val total = weights.sumOf { it.first }
        // One division at the end: rounding each term would drift by a few Rial.
        val mean = weights.sumOf { (wt, v) -> Math.multiplyExact(v, wt) } / total
        val deviation = weights.sumOf { (wt, v) -> Math.multiplyExact(kotlin.math.abs(v - mean), wt) } / total
        val percent = when (special?.kind) { SpecialDay.Kind.CLOSED -> 0; null -> 100; else -> special.percent }
        fun scaled(v: Long) = Ratio.mulDiv(v, percent.toLong(), 100)
        val expected = scaled(mean)
        return DayForecast(date, expected, maxOf(0, scaled(mean - deviation)), scaled(mean + deviation), samples.size, special)
    }

    /** Last 28 days over the 28 before, clamped to −20 % … +25 %; flat without enough days in both. */
    private fun trend(history: Map<BusinessDate, Long>, from: BusinessDate): Long {
        val recent = history.filterKeys { it >= from.plusDays(-28) }
        val before = history.filterKeys { it < from.plusDays(-28) && it >= from.plusDays(-56) }
        if (recent.size < 14 || before.size < 14) return 10_000
        val r = recent.values.sum() / recent.size; val b = before.values.sum() / before.size
        if (b <= 0) return 10_000
        return Ratio.mulDiv(r, 10_000, b).coerceIn(8_000, 12_500)
    }

    /** How far the same method was off over the last 28 days with sales, forecasting each day from what came before. */
    private fun backtest(branch: Scope.Branch, from: BusinessDate): Long? {
        val all = dailyNet(branch, from.plusDays(-28L - HISTORY_DAYS), from.plusDays(-1))
        val errors = (1..28).mapNotNull { back ->
            val date = from.plusDays(-back.toLong())
            val actual = all[date] ?: return@mapNotNull null
            val past = all.filterKeys { it < date }
            val f = day(date, past, null)
            if (f.samples == 0 || actual <= 0) null else Ratio.mulDiv(kotlin.math.abs(actual - f.expected), 10_000, actual)
        }
        return if (errors.size < 7) null else errors.sum() / errors.size
    }

    /** Net sales (before service and tax) of each day with posted sales. */
    private fun dailyNet(branch: Scope.Branch, from: BusinessDate, to: BusinessDate): Map<BusinessDate, Long> =
        (from.epochDay..to.epochDay).map { BusinessDate(it) }.mapNotNull { d ->
            val posted = core.sales.salesOn(branch, d).filter { it.status == SaleStatus.POSTED }
            if (posted.isEmpty()) null else d to posted.sumOf { it.netFood.rial }
        }.toMap()

    companion object {
        const val WEEKS = 8
        const val HISTORY_DAYS = WEEKS * 7
    }
}

/** «تعطیل رسمی» etc.: the Jalali day title with its note. */
fun SpecialDay.title(): String = "${Fa.dayTitle(date)} ${Fa.digits(JalaliCalendar.of(date).year.toString())}" + if (note.isNotBlank()) " · $note" else ""
