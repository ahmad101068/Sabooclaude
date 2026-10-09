package ir.sabou.inventory.memory

import ir.sabou.inventory.Item
import ir.sabou.inventory.ItemStore
import ir.sabou.inventory.Location
import ir.sabou.inventory.LocationStore
import ir.sabou.inventory.MenuItem
import ir.sabou.inventory.RecipeStore
import ir.sabou.inventory.RecipeVersion
import ir.sabou.inventory.StockBalance
import ir.sabou.inventory.StockMovement
import ir.sabou.inventory.StockStore
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.platform.memory.Table
import ir.sabou.platform.memory.Transactional

class InMemoryItemStore : Table<GlobalId, Item>(), ItemStore {
    override fun byId(id: GlobalId) = get(id)
    override fun all() = values()
    override fun save(item: Item) = put(item.id, item)
}

class InMemoryLocationStore : Table<GlobalId, Location>(), LocationStore {
    override fun byId(id: GlobalId) = get(id)
    override fun all() = values()
    override fun save(location: Location) = put(location.id, location)
}

class InMemoryStockStore : StockStore, Transactional {
    private val balances = LinkedHashMap<Pair<GlobalId, GlobalId>, StockBalance>()
    private val movements = mutableListOf<StockMovement>()

    override fun balance(itemId: GlobalId, locationId: GlobalId) =
        balances[itemId to locationId] ?: StockBalance(itemId, locationId, Quantity.ZERO, Money.ZERO)

    override fun replace(expected: StockBalance, next: StockBalance) {
        if (balance(expected.itemId, expected.locationId) != expected) throw DomainException(DomainError.ConcurrentModification("STOCK_BALANCE"))
        balances[next.itemId to next.locationId] = next
    }

    override fun insertMovement(movement: StockMovement) {
        check(movement.reversalOf == null || movements.none { it.reversalOf == movement.reversalOf }) { "movement_reversal_unique" }
        movements += movement
    }

    override fun movementsBySource(type: String, id: GlobalId) = movements.filter { it.source.type == type && it.source.id == id }
    override fun reversalOf(id: GlobalId) = movements.firstOrNull { it.reversalOf == id }
    override fun balances(locationId: GlobalId) = balances.values.filter { it.locationId == locationId }
    fun movements(): List<StockMovement> = movements.toList()
    override fun movementsAt(locationId: GlobalId, from: ir.sabou.kernel.BusinessDate, to: ir.sabou.kernel.BusinessDate) =
        movements.filter { it.locationId == locationId && it.date >= from && it.date <= to }
    override fun totalsBefore(locationId: GlobalId, date: ir.sabou.kernel.BusinessDate) =
        movements.filter { it.locationId == locationId && it.date < date }.groupBy { it.itemId }
            .map { (item, l) -> ir.sabou.inventory.MovementTotal(item, l.sumOf { it.quantityDelta }, l.sumOf { it.valueDelta }) }

    override fun snapshot(): Any = LinkedHashMap(balances) to movements.toList()
    @Suppress("UNCHECKED_CAST")
    override fun restore(snapshot: Any) {
        val (b, m) = snapshot as Pair<Map<Pair<GlobalId, GlobalId>, StockBalance>, List<StockMovement>>
        balances.clear(); balances.putAll(b); movements.clear(); movements.addAll(m)
    }
}

class InMemoryRecipeStore : RecipeStore, Transactional {
    private val menu = LinkedHashMap<GlobalId, MenuItem>()
    private val versions = mutableListOf<RecipeVersion>()
    private val preps = mutableListOf<ir.sabou.inventory.PrepRecipe>()
    override fun prepVersions(itemId: GlobalId) = preps.filter { it.itemId == itemId }
    override fun savePrepVersion(version: ir.sabou.inventory.PrepRecipe) { preps += version }
    override fun menuItem(id: GlobalId) = menu[id]
    override fun menuItems() = menu.values.toList()
    override fun saveMenuItem(item: MenuItem) { menu[item.id] = item }
    override fun versions(menuItemId: GlobalId) = versions.filter { it.menuItemId == menuItemId }
    override fun saveVersion(version: RecipeVersion) { versions += version }
    override fun snapshot(): Any = Triple(LinkedHashMap(menu), versions.toList(), preps.toList())
    @Suppress("UNCHECKED_CAST")
    override fun restore(snapshot: Any) {
        val (m, v, p) = snapshot as Triple<Map<GlobalId, MenuItem>, List<RecipeVersion>, List<ir.sabou.inventory.PrepRecipe>>
        menu.clear(); menu.putAll(m); versions.clear(); versions.addAll(v); preps.clear(); preps.addAll(p)
    }
}
