package ir.sabou.core

import ir.sabou.kernel.sumOfExact
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Ratio
import ir.sabou.kernel.Scope
import ir.sabou.ledger.AccountCode
import ir.sabou.ledger.StandardAccounts
import ir.sabou.sales.SaleStatus

/** One cause of the change in profit (ADR-0053): [amount] is its effect on profit, positive raises it. */
data class BridgeStep(val kind: Kind, val amount: Long, val details: List<BridgeDetail> = emptyList()) {
    enum class Kind {
        /** More or fewer portions sold, at the base period's mix and margins. */
        VOLUME,
        /** A different mix of dishes: more of the high-margin ones raises profit. */
        MIX,
        /** Selling prices of the dishes. */
        PRICES,
        /** Actual ingredient cost per portion (purchase prices, recipes). */
        INGREDIENT_COSTS,
        /** Discounts given on sales. */
        DISCOUNTS,
        /** Cost of sales the dish lines do not explain (sales before line costs, shortages settled later). */
        UNTRACED_COST,
        /** Waste, staff meals and comps, stock count differences. */
        FOOD_LOSSES,
        LABOR,
        /** Rent, utilities and every other expense. */
        OTHER_COSTS,
        /** Service charge and other income. */
        OTHER_INCOME,
        /** What exact Rial rounding of per-portion figures leaves; a few Rial. */
        ROUNDING,
    }
}

/** A dish or an account behind a step, with its part of the step's amount. */
data class BridgeDetail(val name: String, val amount: Long)

data class BridgePeriod(
    val from: BusinessDate,
    val to: BusinessDate,
    val profit: Long,
    /** Food and beverage sales in the books (4101). */
    val foodSales: Long,
    val portions: Quantity,
    /** Share of the portions that carry their actual cost (ADR-0050), basis points; null without sales. */
    val costedBp: Long?,
)

data class ProfitBridge(
    val branch: Scope.Branch,
    val branchName: String,
    val base: BridgePeriod,
    val current: BridgePeriod,
    /** In a fixed order; they add up exactly to [change]. */
    val steps: List<BridgeStep>,
    /** A shortage of either period is still open: its food cost is not booked yet (ADR-0022). */
    val provisional: Boolean,
    /** The causes in plain Persian, biggest first, from the figures above only. */
    val summary: List<String>,
) {
    val change: Long get() = current.profit - base.profit
    fun step(kind: BridgeStep.Kind): BridgeStep = steps.single { it.kind == kind }
}

/**
 * Why profit changed between two periods (ADR-0053). The change in each P&L account comes from the books; the
 * change in dish margin (food sales less cost of sales) is split into volume, mix, prices and ingredient costs from
 * the posted sale lines and their actual costs. Every step is exact Rial arithmetic and the steps add up to the
 * change in profit to the Rial; whatever the dish lines do not explain is a step of its own, never spread.
 */
class ProfitBridges internal constructor(private val core: SabouCore) {

    /** The period of the same length just before [from]…[to]. */
    fun previous(from: BusinessDate, to: BusinessDate): Pair<BusinessDate, BusinessDate> {
        val days = to.epochDay - from.epochDay + 1
        return from.plusDays(-days) to from.plusDays(-1)
    }

    fun of(branch: Scope.Branch, from: BusinessDate, to: BusinessDate, baseFrom: BusinessDate, baseTo: BusinessDate): ProfitBridge {
        val p1 = core.reports.profitAndLoss(from, to, branch)            // checks LEDGER_VIEW and the branch
        val p0 = core.reports.profitAndLoss(baseFrom, baseTo, branch)
        val names = core.recipes.menuItems().associate { it.id to it.name }
        val d0 = dishes(branch, baseFrom, baseTo); val d1 = dishes(branch, from, to)
        val ids = (d0.keys + d1.keys).sortedBy { names[it].orEmpty() }

        // Per-portion price and actual cost of each dish in each period; a dish missing from one period takes the
        // other's, so its whole effect is volume and mix rather than a price "change" from nothing.
        fun price(d: Dish?) = d?.takeIf { it.q > 0 }?.let { Ratio.mulDiv(it.gross, Quantity.SCALE, it.q) }
        fun cost(d: Dish?) = d?.takeIf { it.costedQ > 0 }?.let { Ratio.mulDiv(it.cost, Quantity.SCALE, it.costedQ) }
        val rows = ids.map { id ->
            val a = d0[id]; val b = d1[id]
            val p0 = price(a) ?: price(b) ?: 0L; val p1 = price(b) ?: p0
            val c0 = cost(a) ?: cost(b) ?: 0L; val c1 = cost(b) ?: c0
            Row(id, a?.q ?: 0L, b?.q ?: 0L, p0, p1, c0, c1)
        }
        fun r(q: Long, perPortion: Long) = Ratio.mulDivSigned(q, perPortion, Quantity.SCALE)
        val q0 = rows.sumOf { it.q0 }; val q1 = rows.sumOf { it.q1 }
        val margin0 = rows.sumOf { r(it.q0, it.m0) }                    // base dish margin at base mix and margins
        val atBase = rows.sumOf { r(it.q1, it.m0) }                     // current portions at base margins
        val margin1 = rows.sumOf { r(it.q1, it.m1) }
        val volume = if (q0 > 0) Ratio.mulDivSigned(margin0, q1 - q0, q0) else atBase - margin0
        val mix = atBase - margin0 - volume
        val prices = rows.map { it to r(it.q1, it.p1 - it.p0) }
        val costs = rows.map { it to -r(it.q1, it.c1 - it.c0) }
        val mixBy = rows.map { it to (r(it.q1, it.m0) - if (q0 > 0) Ratio.mulDivSigned(r(it.q0, it.m0), q1, q0) else 0L) }

        // The books: each account's change, by group.
        fun accounts(p: ProfitAndLoss): Map<AccountCode, Pair<String, Long>> =
            (p.revenue.map { it.account.code to (it.account.name to it.amount) } +
                (p.costOfSales + p.expenses).map { it.account.code to (it.account.name to -it.amount) }).toMap()   // + raises profit
        val a0 = accounts(p0); val a1 = accounts(p1)
        fun delta(codes: Collection<AccountCode>) = codes.sumOf { (a1[it]?.second ?: 0L) - (a0[it]?.second ?: 0L) }
        fun details(codes: Collection<AccountCode>) = codes.map { c -> BridgeDetail((a1[c] ?: a0[c])!!.first, (a1[c]?.second ?: 0L) - (a0[c]?.second ?: 0L)) }
            .filter { it.amount != 0L }.sortedByDescending { kotlin.math.abs(it.amount) }
        val all = a0.keys + a1.keys
        val food = setOf(StandardAccounts.FOOD_SALES, StandardAccounts.COGS)
        val losses = all.filter { it in FOOD_COST_ACCOUNTS && it != StandardAccounts.COGS }
        val labor = all.filter { it in LABOR_ACCOUNTS }
        val revenueCodes = (p0.revenue + p1.revenue).map { it.account.code }.toSet()
        val income = all.filter { it in revenueCodes && it !in food }
        val other = all.filter { it !in food && it !in losses && it !in labor && it !in income }

        // Dish margin in the books against the dish lines: discounts, then cost the lines do not explain.
        val dishBook = delta(food)                                       // Δ(food sales − cost of sales)
        val discounts = -(discountOf(branch, from, to) - discountOf(branch, baseFrom, baseTo))
        fun traced(cost: (Row) -> Long, q: (Row) -> Long) = rows.sumOf { r(q(it), cost(it)) }
        val cogs0 = -(a0[StandardAccounts.COGS]?.second ?: 0L); val cogs1 = -(a1[StandardAccounts.COGS]?.second ?: 0L)
        val untraced = -((cogs1 - traced({ it.c1 }, { it.q1 })) - (cogs0 - traced({ it.c0 }, { it.q0 })))
        val rounding = dishBook - (volume + mix + prices.sumOf { it.second } + costs.sumOf { it.second } + discounts + untraced)

        fun top(list: List<Pair<Row, Long>>) = list.filter { it.second != 0L }.sortedByDescending { kotlin.math.abs(it.second) }.take(TOP)
            .map { BridgeDetail(names[it.first.id].orEmpty(), it.second) }
        val steps = listOf(
            BridgeStep(BridgeStep.Kind.VOLUME, volume),
            BridgeStep(BridgeStep.Kind.MIX, mix, top(mixBy)),
            BridgeStep(BridgeStep.Kind.PRICES, prices.sumOf { it.second }, top(prices)),
            BridgeStep(BridgeStep.Kind.INGREDIENT_COSTS, costs.sumOf { it.second }, top(costs)),
            BridgeStep(BridgeStep.Kind.DISCOUNTS, discounts),
            BridgeStep(BridgeStep.Kind.UNTRACED_COST, untraced),
            BridgeStep(BridgeStep.Kind.FOOD_LOSSES, delta(losses), details(losses)),
            BridgeStep(BridgeStep.Kind.LABOR, delta(labor), details(labor)),
            BridgeStep(BridgeStep.Kind.OTHER_COSTS, delta(other), details(other).take(TOP)),
            BridgeStep(BridgeStep.Kind.OTHER_INCOME, delta(income), details(income)),
            BridgeStep(BridgeStep.Kind.ROUNDING, rounding),
        )
        fun period(p: ProfitAndLoss, d: Map<GlobalId, Dish>) = BridgePeriod(p.from, p.to, p.totals.profit, p.ratios.sales,
            Quantity.of(d.values.sumOf { it.q }), d.values.sumOf { it.q }.takeIf { it > 0 }?.let { q -> Ratio.mulDiv(d.values.sumOf { it.costedQ }, 10_000, q) })
        val base = period(p0, d0); val current = period(p1, d1)
        val provisional = p0.provisional || p1.provisional
        val name = core.branches.all().firstOrNull { it.id == branch.branchId }?.name ?: "شعبه"
        return ProfitBridge(branch, name, base, current, steps, provisional, summary(base, current, steps, provisional))
    }

    /** Plain sentences from the figures: the change, the three biggest causes, and what makes it uncertain. */
    private fun summary(base: BridgePeriod, current: BridgePeriod, steps: List<BridgeStep>, provisional: Boolean): List<String> = buildList {
        val change = current.profit - base.profit
        val pct = if (base.profit > 0) " (${if (change >= 0) "+" else "−"}${Fa.percent(Ratio.mulDiv(kotlin.math.abs(change), 10_000, base.profit))})" else ""
        add("سود از ${Fa.rial(base.profit)} به ${Fa.rial(current.profit)} ریال رسید؛ ${if (change >= 0) "افزایش" else "کاهش"} ${Fa.rial(kotlin.math.abs(change))} ریال$pct.")
        steps.filter { it.kind != BridgeStep.Kind.ROUNDING && it.amount != 0L }.sortedByDescending { kotlin.math.abs(it.amount) }.take(3).forEach { s ->
            val who = s.details.firstOrNull { (it.amount > 0) == (s.amount > 0) }?.let { " — بیشترین سهم: «${it.name}» ${signed(it.amount)}" } ?: ""
            add("${stepName(s.kind)}: ${signed(s.amount)} ریال$who.")
        }
        current.costedBp?.takeIf { it < 10_000 }?.let { add("بهای واقعی برای ${Fa.percent(it)} پرس‌های این دوره ثبت شده است؛ بقیه با بهای همان غذا در پرس‌های ثبت‌شده برآورد شده‌اند.") }
        base.costedBp?.takeIf { it < 10_000 }?.let { add("در دوره‌ی مبنا بهای واقعی برای ${Fa.percent(it)} پرس‌ها ثبت شده است.") }
        if (provisional) add("کسری انبارِ تعیین‌تکلیف‌نشده هست؛ بهای مواد و سود هنوز قطعی نیست.")
    }

    private fun signed(v: Long) = (if (v > 0) "+" else if (v < 0) "−" else "") + Fa.rial(kotlin.math.abs(v))

    // ------------------------------------------------------------ dish lines

    /** A dish in both periods: portions (micro-units), price and actual cost per portion (Rial). */
    private class Row(val id: GlobalId, val q0: Long, val q1: Long, val p0: Long, val p1: Long, val c0: Long, val c1: Long) {
        val m0: Long get() = p0 - c0
        val m1: Long get() = p1 - c1
    }

    private class Dish { var q = 0L; var gross = 0L; var costedQ = 0L; var cost = 0L }

    private fun discountOf(branch: Scope.Branch, from: BusinessDate, to: BusinessDate): Long =
        core.sales.sales(branch, from, to).filter { it.status == SaleStatus.POSTED }.sumOfExact { it.discount.rial }

    /** Posted lines of [branch] in the period by dish. */
    private fun dishes(branch: Scope.Branch, from: BusinessDate, to: BusinessDate): Map<GlobalId, Dish> {
        val out = LinkedHashMap<GlobalId, Dish>()
        core.sales.sales(branch, from, to).filter { it.status == SaleStatus.POSTED }.forEach { s ->
            s.lines.forEach { l ->
                val d = out.getOrPut(l.menuItemId) { Dish() }
                d.q += l.portions.micros; d.gross += l.gross.rial
                l.cost?.let { d.costedQ += l.portions.micros; d.cost += it.rial }
            }
        }
        return out
    }

    companion object {
        const val TOP = 5

        fun stepName(k: BridgeStep.Kind) = when (k) {
            BridgeStep.Kind.VOLUME -> "تعداد پرس فروخته‌شده"
            BridgeStep.Kind.MIX -> "ترکیب غذاهای فروخته‌شده"
            BridgeStep.Kind.PRICES -> "قیمت فروش غذاها"
            BridgeStep.Kind.INGREDIENT_COSTS -> "بهای مواد هر پرس"
            BridgeStep.Kind.DISCOUNTS -> "تخفیف‌ها"
            BridgeStep.Kind.UNTRACED_COST -> "بهای فروشِ بی‌ردیف (کسری‌ها و فروش‌های قدیمی)"
            BridgeStep.Kind.FOOD_LOSSES -> "ضایعات، پذیرایی و مغایرت انبار"
            BridgeStep.Kind.LABOR -> "حقوق و بیمه"
            BridgeStep.Kind.OTHER_COSTS -> "سایر هزینه‌ها"
            BridgeStep.Kind.OTHER_INCOME -> "سرویس و سایر درآمدها"
            BridgeStep.Kind.ROUNDING -> "گرد کردن"
        }
    }
}
