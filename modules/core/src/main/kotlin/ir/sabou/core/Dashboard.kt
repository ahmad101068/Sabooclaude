package ir.sabou.core

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.JalaliCalendar
import ir.sabou.kernel.Ratio
import ir.sabou.kernel.Scope
import ir.sabou.platform.Permission
import ir.sabou.treasury.TreasuryKind

/** One slice of the cost donut: an account (or «سایر» for the rest) and its amount in Rial. */
data class CostSlice(val label: String, val amount: Long, val code: String?)

/** Revenue of one day, for the week chart. */
data class DaySales(val date: BusinessDate, val revenue: Long)

/**
 * The manager's home at a glance (owner, manager, accountant): month to date against the same days of the previous
 * month, where the money went, the last seven days and the cash at hand. Every figure comes from the books (the
 * same journals as the profit and loss), so the home and the reports cannot disagree.
 */
data class Dashboard(
    val from: BusinessDate,
    val to: BusinessDate,
    val previousFrom: BusinessDate,
    val previousTo: BusinessDate,
    val revenue: Long,
    val previousRevenue: Long,
    val costs: Long,
    val profit: Long,
    val ratios: CostRatios,
    /** Cost of sales and expenses by account, largest first; at most [Dashboards.SLICES] plus «سایر». */
    val slices: List<CostSlice>,
    val week: List<DaySales>,
    /** Cash boxes and petty cash. */
    val cash: Long,
    /** Bank accounts and card terminals (settled to the bank). */
    val bank: Long,
) {
    /** Revenue change against the previous period in basis points; null without a previous figure. */
    val revenueChangeBp: Long? get() {
        if (previousRevenue <= 0 || revenue < 0) return null
        val change = Ratio.mulDiv(kotlin.math.abs(revenue - previousRevenue), 10_000, previousRevenue)
        return if (revenue < previousRevenue) -change else change
    }
}

class Dashboards internal constructor(private val core: SabouCore) {
    /** Month to date of [today]'s Jalali month, for [branch] or (null) everything the person may see. */
    fun of(today: BusinessDate, branch: Scope.Branch? = null): Dashboard {
        val actor = core.session.currentActor() ?: throw DomainException(DomainError.AuthenticationRequired)
        if (!actor.role.allows(Permission.LEDGER_VIEW)) throw DomainException(DomainError.PermissionDenied(Permission.LEDGER_VIEW.name))
        val (from, previousFrom, previousTo) = periods(today)
        val pnl = core.reports.profitAndLoss(from, today, branch)
        val previous = core.reports.profitAndLoss(previousFrom, previousTo, branch)
        val weekFrom = today.plusDays(-6)
        val weekPnl = core.reports.profitAndLoss(weekFrom, today, branch).byDay.toMap()

        val costLines = (pnl.costOfSales + pnl.expenses).filter { it.amount > 0 }.sortedByDescending { it.amount }
        val slices = costLines.take(SLICES).map { CostSlice(it.account.name, it.amount, it.account.code.value) } +
            costLines.drop(SLICES).sumOf { it.amount }.takeIf { it > 0 }?.let { listOf(CostSlice("سایر", it, null)) }.orEmpty()

        val balances = if (actor.role.allows(Permission.TREASURY_VIEW)) {
            core.treasuryAccounts.all().filter { it.isActive && actor.canAccess(it.scope) && (branch == null || it.scope == branch) }
                .map { it.kind to core.treasuryGateway.balance(it.id) }
        } else emptyList()

        return Dashboard(
            from = from, to = today, previousFrom = previousFrom, previousTo = previousTo,
            revenue = pnl.totals.revenue, previousRevenue = previous.totals.revenue,
            costs = pnl.totals.cogs + pnl.totals.expenses, profit = pnl.totals.profit, ratios = pnl.ratios,
            slices = slices,
            week = (0L..6L).map { d -> weekFrom.plusDays(d).let { day -> DaySales(day, weekPnl[day]?.revenue ?: 0) } },
            cash = balances.filter { it.first == TreasuryKind.CASH || it.first == TreasuryKind.PETTY_CASH }.sumOf { it.second },
            bank = balances.filter { it.first == TreasuryKind.BANK || it.first == TreasuryKind.CARD_TERMINAL }.sumOf { it.second },
        )
    }

    companion object {
        const val SLICES = 5

        /**
         * This month from its first day to [today], and the same number of days at the start of the previous month
         * (cut at that month's end: Esfand of a common year has 29 days).
         */
        fun periods(today: BusinessDate): Triple<BusinessDate, BusinessDate, BusinessDate> {
            val t = JalaliCalendar.of(today)
            val from = JalaliCalendar.date(t.year, t.month, 1)
            val (py, pm) = if (t.month == 1) t.year - 1 to 12 else t.year to t.month - 1
            val previousFrom = JalaliCalendar.date(py, pm, 1)
            val previousTo = JalaliCalendar.date(py, pm, minOf(t.day, monthLength(py, pm)))
            return Triple(from, previousFrom, previousTo)
        }

        fun monthLength(year: Int, month: Int): Int = when {
            month <= 6 -> 31
            month <= 11 -> 30
            JalaliCalendar.isLeap(year) -> 30
            else -> 29
        }
    }
}
