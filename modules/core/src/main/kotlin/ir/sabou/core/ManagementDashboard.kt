package ir.sabou.core

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.JalaliCalendar
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Ratio
import ir.sabou.kernel.Scope
import ir.sabou.platform.Permission
import ir.sabou.sales.SaleStatus
import ir.sabou.sales.Settlement
import ir.sabou.treasury.TreasuryKind

/** A headline figure and how it moved against the comparable period; [value] null: not measured (and why in [note]). */
data class DashKpi(val title: String, val value: Long?, val unit: KpiUnit, val changeBp: Long?, val better: KpiTarget.Better, val note: String = "") {
    /** Whether the change is good news (up for sales, down for food cost); null without a change. */
    val good: Boolean? get() = changeBp?.takeIf { it != 0L }?.let { if (better == KpiTarget.Better.HIGHER) it > 0 else it < 0 }
}

data class DashDay(val date: BusinessDate, val sales: Long, val previous: Long)
data class DashSlice(val name: String, val amount: Long, val shareBp: Long)
data class DashMonth(val year: Int, val month: Int, val labor: Long, val materials: Long, val profit: Long)
data class DashItem(val name: String, val portions: Quantity, val sales: Long, val marginBp: Long?, val menuClass: MenuClass?)
data class DashSale(val date: BusinessDate, val dishes: String, val payable: Long, val status: SaleStatus)

/**
 * The manager's dashboard (ADR-0064): headline figures against the comparable period, the week against the week
 * before, sales by category and by way of payment, six months of costs and profit, the best-selling dishes, the
 * scorecard's suggested actions and area scores, and the latest sales. Every figure comes from the books and records,
 * as in the reports it summarizes; what the app does not record is shown as such, never invented.
 */
data class ManagementDashboard(
    val branchName: String,
    val from: BusinessDate,
    val to: BusinessDate,
    val baseFrom: BusinessDate,
    val baseTo: BusinessDate,
    val kpis: List<DashKpi>,
    val week: List<DashDay>,
    val categories: List<DashSlice>,
    val payments: List<DashSlice>,
    val months: List<DashMonth>,
    val topItems: List<DashItem>,
    val suggestions: List<Finding>,
    val operations: List<AreaResult>,
    val latestSales: List<DashSale>,
)

class ManagementDashboards internal constructor(private val core: SabouCore) {

    fun of(branch: Scope.Branch, from: BusinessDate, to: BusinessDate): ManagementDashboard {
        val actor = core.session.currentActor() ?: throw DomainException(DomainError.AuthenticationRequired)
        if (!actor.role.allows(Permission.LEDGER_VIEW)) throw DomainException(DomainError.PermissionDenied(Permission.LEDGER_VIEW.name))
        if (!actor.canAccess(branch)) throw DomainException(DomainError.PermissionDenied("BRANCH"))
        if (to < from) throw DomainException(DomainError.InvalidState("PERIOD", "TO_BEFORE_FROM"))
        val (baseFrom, baseTo) = core.profitBridges.previous(from, to)
        val pnl = core.reports.profitAndLoss(from, to, branch)
        val base = core.reports.profitAndLoss(baseFrom, baseTo, branch)
        val day = core.reports.profitAndLoss(to, to, branch)
        val dayBefore = core.reports.profitAndLoss(to.plusDays(-1), to.plusDays(-1), branch)
        val assessment = core.assessments.of(branch, from, to)

        // ---- headline figures
        fun satisfaction(f: BusinessDate, t: BusinessDate): Long? = core.assessments.recordsIn(branch, FieldRecord.Kind.FEEDBACK, f, t)
            .takeIf { it.isNotEmpty() }?.let { l -> Ratio.mulDiv(l.count { (it.value ?: 0) >= 4 }.toLong(), 10_000, l.size.toLong()) }
        val happy = satisfaction(from, to); val happyBefore = satisfaction(baseFrom, baseTo)
        val alerts = runCatching { core.alerts.forUser(actor.userId, to).size.toLong() }.getOrNull()
        val kpis = listOf(
            DashKpi("فروش ${Fa.dayTitle(to)}", day.ratios.sales, KpiUnit.RIAL, Ratio.changeBp(dayBefore.ratios.sales, day.ratios.sales), KpiTarget.Better.HIGHER,
                "در برابر روز قبل"),
            DashKpi("سود ناخالص دوره", pnl.totals.grossProfit, KpiUnit.RIAL, Ratio.changeBp(base.totals.grossProfit, pnl.totals.grossProfit), KpiTarget.Better.HIGHER,
                if (pnl.provisional) "کسری انبار باز هست؛ قطعی نیست" else "در برابر دوره‌ی قبل"),
            DashKpi("درصد بهای مواد", pnl.ratios.foodBp, KpiUnit.PERCENT,
                pnl.ratios.foodBp?.let { now -> base.ratios.foodBp?.let { before -> now - before } }, KpiTarget.Better.LOWER, "تغییر به واحد درصد"),
            DashKpi("رضایت مشتری", happy, KpiUnit.PERCENT, happy?.let { h -> happyBefore?.let { h - it } }, KpiTarget.Better.HIGHER,
                if (happy == null) "نظر مشتری ثبت نشده (ثبت‌های روزانه)" else "سهم امتیاز ۴ و ۵"),
            DashKpi("میانگین زمان آماده‌سازی", null, KpiUnit.COUNT, null, KpiTarget.Better.LOWER, "از خروجی سپند وارد نمی‌شود"),
            DashKpi("هشدارهای باز", alerts, KpiUnit.COUNT, null, KpiTarget.Better.LOWER, if (assessment.critical.isNotEmpty()) "${Fa.number(assessment.critical.size.toLong())} بحرانی" else ""),
        )

        // ---- the last seven days against the seven before
        val weekFrom = to.plusDays(-6)
        val now7 = core.reports.profitAndLoss(weekFrom, to, branch).byDay.toMap()
        val before7 = core.reports.profitAndLoss(weekFrom.plusDays(-7), to.plusDays(-7), branch).byDay.toMap()
        val week = (0L..6L).map { i -> weekFrom.plusDays(i).let { d -> DashDay(d, now7[d]?.revenue ?: 0, before7[d.plusDays(-7)]?.revenue ?: 0) } }

        // ---- sales by category (the owner's menu categories) and by way of payment
        val mix = runCatching { core.reports.productMix(branch, from, to) }.getOrNull()
        val categoryOf = core.recipes.menuItems().associate { it.id to it.category.ifBlank { "بدون دسته" } }
        val byCategory = mix?.rows.orEmpty().groupBy { categoryOf[it.menuItemId] ?: "بدون دسته" }.mapValues { (_, r) -> r.sumOf { it.gross.rial } }
        val categoryTotal = byCategory.values.sum()
        val categories = byCategory.entries.sortedByDescending { it.value }.map { DashSlice(it.key, it.value, share(it.value, categoryTotal)) }

        val kinds = core.treasuryAccounts.all().associate { it.id to it.kind }
        val paid = HashMap<String, Long>()
        core.sales.sales(branch, from, to).filter { it.status == SaleStatus.POSTED }.flatMap { it.settlements }.forEach { s ->
            val name = when (s) {
                is Settlement.Credit -> "نسیه"
                is Settlement.Liquid -> if (s.cheque != null) "چک" else when (kinds[s.treasuryAccountId]) {
                    TreasuryKind.CASH, TreasuryKind.PETTY_CASH -> "نقدی"
                    TreasuryKind.CARD_TERMINAL -> "کارت"
                    TreasuryKind.BANK -> "واریز و آنلاین"
                    else -> "سایر"
                }
            }
            paid.merge(name, s.amount.rial, Long::plus)
        }
        val paidTotal = paid.values.sum()
        val payments = paid.entries.sortedByDescending { it.value }.map { DashSlice(it.key, it.value, share(it.value, paidTotal)) }

        // ---- six Jalali months: personnel, materials, operating profit
        val months = (5 downTo 0).map { back ->
            val j = JalaliCalendar.of(to)
            var y = j.year; var m = j.month - back
            while (m < 1) { m += 12; y -= 1 }
            val start = JalaliCalendar.date(y, m, 1)
            val end = minOf(JalaliCalendar.date(y, m, JalaliCalendar.monthLength(y, m)), to)
            val p = core.reports.profitAndLoss(start, end, branch)
            DashMonth(y, m, p.ratios.labor, p.ratios.foodCost, p.totals.profit)
        }

        // ---- best sellers, with their actual margin and menu-engineering class
        val classes = runCatching { core.reports.menuEngineering(branch, from, to) }.getOrNull()?.rows.orEmpty().associate { it.mix.menuItemId to it.menuClass }
        val topItems = mix?.rows.orEmpty().sortedByDescending { it.gross.rial }.take(TOP).map { r ->
            val margin = r.actualCost?.takeIf { r.costedGross.rial > 0 }?.let { Ratio.mulDivSigned(r.costedGross.rial - it.rial, 10_000, r.costedGross.rial) }
            DashItem(r.name, r.portions, r.gross.rial, margin, classes[r.menuItemId])
        }

        // ---- the latest sales recorded
        val names = core.recipes.menuItems().associate { it.id to it.name }
        val latest = core.sales.sales(branch, to.plusDays(-30), to).filter { it.status == SaleStatus.POSTED || it.status == SaleStatus.REVERSED }
            .sortedWith(compareByDescending<ir.sabou.sales.DailySale> { it.date.epochDay }).take(TOP).map { s ->
                DashSale(s.date, s.lines.sortedByDescending { it.gross.rial }.take(2).joinToString("، ") { names[it.menuItemId].orEmpty() } +
                    if (s.lines.size > 2) " و ${Fa.number((s.lines.size - 2).toLong())} قلم دیگر" else "", s.payable.rial, s.status)
            }

        val branchName = core.branches.all().firstOrNull { it.id == branch.branchId }?.name.orEmpty()
        return ManagementDashboard(branchName, from, to, baseFrom, baseTo, kpis, week, categories, payments, months, topItems,
            assessment.findings.take(4), assessment.areas, latest)
    }

    private fun share(part: Long, total: Long) = if (total <= 0) 0 else Ratio.mulDiv(part, 10_000, total)

    companion object { const val TOP = 5 }
}
