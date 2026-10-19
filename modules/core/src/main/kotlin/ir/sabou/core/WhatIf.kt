package ir.sabou.core

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Ratio
import ir.sabou.kernel.Scope
import ir.sabou.platform.Permission

/** One dish under a what-if: per portion, now and after. [suggestedPrice] keeps today's food-cost share of the price. */
data class WhatIfDish(
    val menuItemId: GlobalId,
    val name: String,
    val portions: Quantity,
    val priceNow: Long,
    val priceNew: Long,
    val costNow: Long,
    val costNew: Long,
    val suggestedPrice: Long?,
) {
    val marginNow: Long get() = priceNow - costNow
    val marginNew: Long get() = priceNew - costNew
    /** The change in profit over the period's portions, exact. */
    val profitChange: Long get() = Ratio.mulDivSigned(marginNew - marginNow, portions.micros, Quantity.SCALE)
}

/**
 * «What if» on prices (ADR-0062): ingredient prices and menu prices change by the given basis points, and the
 * period's sales are replayed at the same portions. Nothing is recorded; demand is assumed unchanged, which the
 * report says.
 */
data class WhatIf(
    val branchName: String,
    val from: BusinessDate,
    val to: BusinessDate,
    val itemChanges: Map<GlobalId, Long>,
    val menuChanges: Map<GlobalId, Long>,
    val dishes: List<WhatIfDish>,
    /** Dishes sold in the period without a recipe or with an unknown cost: left out. */
    val leftOut: List<String>,
) {
    val salesNow: Long get() = dishes.sumOf { Ratio.mulDiv(it.priceNow, it.portions.micros, Quantity.SCALE) }
    val salesNew: Long get() = dishes.sumOf { Ratio.mulDiv(it.priceNew, it.portions.micros, Quantity.SCALE) }
    val costNow: Long get() = dishes.sumOf { Ratio.mulDiv(it.costNow, it.portions.micros, Quantity.SCALE) }
    val costNew: Long get() = dishes.sumOf { Ratio.mulDiv(it.costNew, it.portions.micros, Quantity.SCALE) }
    val profitChange: Long get() = dishes.sumOf { it.profitChange }
    val foodBpNow: Long? get() = if (salesNow <= 0) null else Ratio.mulDiv(costNow, 10_000, salesNow)
    val foodBpNew: Long? get() = if (salesNew <= 0) null else Ratio.mulDiv(costNew, 10_000, salesNew)
}

class WhatIfs internal constructor(private val core: SabouCore) {
    /**
     * [itemChanges]: ingredient → basis points (+1,500 = 15 % dearer). [menuChanges]: dish → basis points of its
     * average price in the period. The costs are today's (as the menu engineering report).
     */
    fun prices(branch: Scope.Branch, from: BusinessDate, to: BusinessDate, itemChanges: Map<GlobalId, Long>, menuChanges: Map<GlobalId, Long> = emptyMap()): WhatIf {
        val a = core.session.currentActor() ?: throw DomainException(DomainError.AuthenticationRequired)
        if (!a.role.allows(Permission.LEDGER_VIEW)) throw DomainException(DomainError.PermissionDenied(Permission.LEDGER_VIEW.name))
        (itemChanges.values + menuChanges.values).forEach { if (it <= -10_000 || it > 100_000) throw DomainException(DomainError.InvalidInput("change", "تغییر بین ۱۰۰٪- و ۱۰۰۰٪+ است.")) }
        val mix = core.reports.productMix(branch, from, to)
        val sold = mix.rows.filter { !it.portions.isZero && it.averagePrice != null }
        val breakdown = core.reports.portionBreakdown(branch, to, sold.map { it.menuItemId })
        fun up(v: Long, bp: Long?) = if (bp == null || bp == 0L) v else Ratio.mulDiv(v, 10_000 + bp, 10_000)
        val dishes = sold.mapNotNull { r ->
            val parts = breakdown[r.menuItemId] ?: return@mapNotNull null
            val costNow = parts.values.sum()
            val costNew = parts.entries.sumOf { (item, cost) -> up(cost, itemChanges[item]) }
            val priceNow = r.averagePrice!!.rial
            val priceNew = up(priceNow, menuChanges[r.menuItemId])
            val suggested = if (costNow <= 0) null else Ratio.mulDiv(costNew, priceNow, costNow)
            WhatIfDish(r.menuItemId, r.name, r.portions, priceNow, priceNew, costNow, costNew, suggested)
        }
        val leftOut = sold.filter { breakdown[it.menuItemId] == null }.map { it.name }
        return WhatIf(mix.branch, from, to, itemChanges, menuChanges, dishes.sortedBy { it.profitChange }, leftOut)
    }
}
