package ir.sabou.core

import ir.sabou.inventory.Item
import ir.sabou.inventory.Location
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.platform.Actor
import ir.sabou.platform.Branch
import ir.sabou.platform.Permission
import ir.sabou.purchasing.InvoiceStatus
import ir.sabou.sales.DailySale
import ir.sabou.treasury.TreasuryAccount
import ir.sabou.treasury.TreasuryMovement

data class AccountBalance(val account: TreasuryAccount, val balance: Long)
data class LowStock(val item: Item, val location: Location, val quantity: Quantity)
data class BranchDay(val branch: Branch, val sale: DailySale?, val closed: Boolean)

/**
 * Read models for the screens. Reads go through the same rules as writes: the signed-in actor's
 * permission and branch grants decide what is visible (AUD-003). Nothing here writes.
 */
class Overview internal constructor(private val core: SabouCore) {
    private fun actor(permission: Permission): Actor {
        val actor = core.session.currentActor() ?: throw DomainException(DomainError.AuthenticationRequired)
        if (!actor.role.allows(permission)) throw DomainException(DomainError.PermissionDenied(permission.name))
        return actor
    }

    fun branches(): List<Branch> {
        val actor = core.session.currentActor() ?: throw DomainException(DomainError.AuthenticationRequired)
        return core.identity.accessibleBranches(actor)
    }

    fun treasury(): List<AccountBalance> {
        val actor = actor(Permission.TREASURY_VIEW)
        return core.treasuryAccounts.all().filter { it.isActive && actor.canAccess(it.scope) }
            .map { AccountBalance(it, core.treasuryGateway.balance(it.id)) }
    }

    fun recentMovements(limit: Int = 20): List<TreasuryMovement> {
        val actor = actor(Permission.TREASURY_VIEW)
        return core.treasuryAccounts.all().filter { actor.canAccess(it.scope) }
            .flatMap { core.treasuryMovements.byAccount(it.id, limit) }
            .sortedByDescending { it.recordedAtEpochMillis }.take(limit)
    }

    fun today(date: BusinessDate): List<BranchDay> {
        actor(Permission.SALES_VIEW)
        return branches().map { b ->
            val scope = Scope.Branch(b.id)
            BranchDay(b, core.sales.activeSale(scope, date), core.sales.day(scope, date)?.closed == true)
        }
    }

    fun lowStock(): List<LowStock> {
        val actor = actor(Permission.INVENTORY_VIEW)
        val items = core.items.all().associateBy { it.id }
        return core.locations.all().filter { it.isActive && actor.canAccess(it.scope) }.flatMap { location ->
            val stocked = core.stock.balances(location.id).associateBy { it.itemId }
            items.values.filter { it.isActive && !it.minimumStock.isZero }.mapNotNull { item ->
                val q = stocked[item.id]?.quantity ?: Quantity.ZERO
                if (q < item.minimumStock) LowStock(item, location, q) else null
            }
        }
    }

    /** What the visible branches owe suppliers (equals GL 2101 for those branches). */
    fun payables(): Money {
        val actor = actor(Permission.PURCHASE_VIEW)
        return Money.sum(
            core.purchases.invoices().filter { it.status == InvoiceStatus.POSTED && actor.canAccess(it.scope) }
                .map { core.purchasing.outstanding(it.id) },
        )
    }

    /** What customers of the visible branches owe (equals GL 1201 for those branches). */
    fun receivables(): Money {
        val actor = actor(Permission.SALES_VIEW)
        return Money.sum(
            core.customers.all().flatMap { core.sales.receivablesOfCustomer(it.id) }
                .filter { !it.voided && actor.canAccess(it.scope) }
                .map { core.salesOps.outstanding(it.id) },
        )
    }
}
