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
    /** Reorder point: below this the item is low and a purchase is suggested. */
    val minimumStock: Quantity,
    val isActive: Boolean = true,
    /** Order up to this level when reordering (0 = order up to the reorder point). */
    val parLevel: Quantity = Quantity.ZERO,
    /** Where it is kept (e.g. «یخچال ۱ · طبقه ۲»): count sheets follow this order. */
    val shelf: String = "",
    /** Free-text allergen note shown on recipes (e.g. «گلوتن، لبنیات»). */
    val allergens: String = "",
    /** Made in-house from a prep recipe (sauce, dough); produced with [RecordProduction]. */
    val prepared: Boolean = false,
    val preferredSupplierId: GlobalId? = null,
    /** Suppliers this item may be bought from; empty = any supplier. */
    val approvedSupplierIds: Set<GlobalId> = emptySet(),
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

enum class MovementKind { RECEIPT, ISSUE, TRANSFER_OUT, TRANSFER_IN, WASTE, COUNT_GAIN, COUNT_LOSS, OPENING, PRODUCTION_OUT, PRODUCTION_IN }

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

/**
 * [quantityPerPortion] is what ends up in the dish; [yieldPercent] is the usable share of what is taken
 * from stock (trimming, peeling, cooking loss). Stock is deducted as quantity × 100 / yield.
 */
data class RecipeLine(val itemId: GlobalId, val quantityPerPortion: Quantity, val yieldPercent: Int = 100) {
    /** What is taken from stock for [portions] portions. */
    fun grossFor(portions: Quantity): Quantity {
        val net = ir.sabou.kernel.Ratio.mulDiv(quantityPerPortion.micros, portions.micros, Quantity.SCALE)
        return Quantity.of(ir.sabou.kernel.Ratio.mulDiv(net, 100, yieldPercent.toLong()))
    }
}

/** Immutable recipe version. A new version starts on [effectiveFrom]; history never changes. */
data class RecipeVersion(
    val id: GlobalId,
    val menuItemId: GlobalId,
    val version: Int,
    val effectiveFrom: BusinessDate,
    val lines: List<RecipeLine>,
)

data class MenuItem(val id: GlobalId, val name: String, val isActive: Boolean = true)

/**
 * Immutable version of how a prepared item is made: [lines] produce [outputQuantity] of the item.
 * A new version applies from [effectiveFrom]; past production never changes.
 */
data class PrepRecipe(
    val id: GlobalId,
    val itemId: GlobalId,
    val version: Int,
    val effectiveFrom: BusinessDate,
    val outputQuantity: Quantity,
    val lines: List<RecipeLine>,
)

/** Quantity and value moved per item before a date (the opening balance of a period). */
data class MovementTotal(val itemId: GlobalId, val quantity: Long, val value: Long)

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
    /** Movements at [locationId] dated [from]..[to] (inclusive), in recording order. */
    fun movementsAt(locationId: GlobalId, from: BusinessDate, to: BusinessDate): List<StockMovement>
    /** Net quantity and value per item at [locationId] from movements dated before [date]. */
    fun totalsBefore(locationId: GlobalId, date: BusinessDate): List<MovementTotal>
}

interface RecipeStore {
    fun menuItem(id: GlobalId): MenuItem?
    fun menuItems(): List<MenuItem>
    fun saveMenuItem(item: MenuItem)
    fun versions(menuItemId: GlobalId): List<RecipeVersion>
    fun saveVersion(version: RecipeVersion)
    fun prepVersions(itemId: GlobalId): List<PrepRecipe>
    fun savePrepVersion(version: PrepRecipe)
}
