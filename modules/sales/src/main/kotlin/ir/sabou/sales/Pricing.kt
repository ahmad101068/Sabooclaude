package ir.sabou.sales

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Scope

/**
 * One version of a menu item's price, in Rial per portion (ADR-0019). Versions are never changed: a new price is a
 * new version effective from its date, so a past day is always priced as it was.
 *
 * [scope] is [Scope.Organization] for the price every branch uses, or one branch for that branch's own price,
 * which wins over the organization's from its date. A branch version with a null [unitPrice] ends the branch's
 * own price: from that date the branch follows the organization's price again.
 */
data class MenuPrice(
    val id: GlobalId,
    val menuItemId: GlobalId,
    val scope: Scope,
    val effectiveFrom: BusinessDate,
    val unitPrice: Money?,
    /** Recording order; the later of two versions with the same date wins. */
    val sequence: Long,
)

interface MenuPriceStore {
    /** Every version of [menuItemId], in recording order. */
    fun versions(menuItemId: GlobalId): List<MenuPrice>
    fun all(): List<MenuPrice>
    fun save(price: MenuPrice)
    fun nextSequence(): Long
}

/** The price list: which price applies to a menu item, in a branch, on a day. */
class PriceList(private val prices: MenuPriceStore) {
    /** The version in force, or null when the item has no price there on [date]. */
    fun priceOn(menuItemId: GlobalId, branch: Scope.Branch, date: BusinessDate): MenuPrice? = resolve(prices.versions(menuItemId), branch, date)

    fun unitPriceOn(menuItemId: GlobalId, branch: Scope.Branch, date: BusinessDate): Money? = priceOn(menuItemId, branch, date)?.unitPrice

    companion object {
        fun resolve(versions: List<MenuPrice>, branch: Scope.Branch, date: BusinessDate): MenuPrice? {
            fun latest(scope: Scope) = versions.filter { it.scope == scope && it.effectiveFrom <= date }
                .maxWithOrNull(compareBy<MenuPrice> { it.effectiveFrom }.thenBy { it.sequence })
            val own = latest(branch)
            return if (own?.unitPrice != null) own else latest(Scope.Organization)?.takeIf { it.unitPrice != null }
        }
    }
}
