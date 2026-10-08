package ir.sabou.inventory

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.ledger.SourceDocument

enum class StockUnit { GRAM, KILOGRAM, MILLILITER, LITER, PIECE, PACK }

data class Item(
    val id: GlobalId,
    val name: String,
    val unit: StockUnit,
    val minimumStock: Quantity,
    val isActive: Boolean = true,
)

/** A storeroom or kitchen. Always inside one branch: stock always has an owner (AUD-011). */
data class Location(val id: GlobalId, val name: String, val scope: Scope.Branch, val isActive: Boolean = true)

/** Quantity and carrying value of one item at one location; the average cost is value / quantity. */
data class StockBalance(val itemId: GlobalId, val locationId: GlobalId, val quantity: Quantity, val value: Money) {
    init {
        // No value without quantity, and no quantity without value except for zero-cost items.
        require(!quantity.isZero || value.isZero) { "stock_value_without_quantity" }
    }
}

enum class MovementKind { RECEIPT, ISSUE, TRANSFER_OUT, TRANSFER_IN, WASTE, COUNT_GAIN, COUNT_LOSS, OPENING }

/** Immutable stock movement. Signed deltas; corrections are reversal movements. */
data class StockMovement(
    val id: GlobalId,
    val itemId: GlobalId,
    val locationId: GlobalId,
    val kind: MovementKind,
    val quantityDelta: Long,
    val valueDelta: Long,
    val date: BusinessDate,
    val journalId: GlobalId?,
    val source: SourceDocument,
    val reversalOf: GlobalId?,
    val recordedAtEpochMillis: Long,
)

data class RecipeLine(val itemId: GlobalId, val quantityPerPortion: Quantity)

/** Immutable recipe version. A new version starts on [effectiveFrom]; history never changes. */
data class RecipeVersion(
    val id: GlobalId,
    val menuItemId: GlobalId,
    val version: Int,
    val effectiveFrom: BusinessDate,
    val lines: List<RecipeLine>,
)

data class MenuItem(val id: GlobalId, val name: String, val isActive: Boolean = true)

interface ItemStore {
    fun byId(id: GlobalId): Item?
    fun all(): List<Item>
    fun save(item: Item)
}

interface LocationStore {
    fun byId(id: GlobalId): Location?
    fun all(): List<Location>
    fun save(location: Location)
}

interface StockStore {
    fun balance(itemId: GlobalId, locationId: GlobalId): StockBalance
    /** Compare-and-set: fails if the stored balance is no longer [expected] (concurrent change). */
    fun replace(expected: StockBalance, next: StockBalance)
    fun insertMovement(movement: StockMovement)
    fun movementsBySource(type: String, id: GlobalId): List<StockMovement>
    fun reversalOf(id: GlobalId): StockMovement?
    fun balances(locationId: GlobalId): List<StockBalance>
}

interface RecipeStore {
    fun menuItem(id: GlobalId): MenuItem?
    fun saveMenuItem(item: MenuItem)
    fun versions(menuItemId: GlobalId): List<RecipeVersion>
    fun saveVersion(version: RecipeVersion)
}
