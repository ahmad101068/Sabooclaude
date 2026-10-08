package ir.sabou.persistence

import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.inventory.Item
import ir.sabou.inventory.ItemStore
import ir.sabou.inventory.Location
import ir.sabou.inventory.LocationStore
import ir.sabou.inventory.MenuItem
import ir.sabou.inventory.MovementKind
import ir.sabou.inventory.RecipeLine
import ir.sabou.inventory.RecipeStore
import ir.sabou.inventory.RecipeVersion
import ir.sabou.inventory.StockBalance
import ir.sabou.inventory.StockMovement
import ir.sabou.inventory.StockStore
import ir.sabou.inventory.StockUnit
import ir.sabou.ledger.AccountCode
import ir.sabou.treasury.Direction
import ir.sabou.treasury.MovementStore
import ir.sabou.treasury.TreasuryAccount
import ir.sabou.treasury.TreasuryAccountStore
import ir.sabou.treasury.TreasuryKind
import ir.sabou.treasury.TreasuryMovement

// ---------------------------------------------------------------- Treasury

class SqlTreasuryAccountStore(db: SqlDatabase) : SqlTable(db), TreasuryAccountStore {
    private fun read(d: Doc) = TreasuryAccount(
        Codec.id(d.str("id")), d.str("name"), TreasuryKind.valueOf(d.str("kind")), Codec.scopeOf(d.str("scope")),
        AccountCode.of(d.str("gl")), d.bool("overdraft"), d.bool("active"),
    )
    override fun byId(id: GlobalId) = doc("SELECT doc FROM treasury_accounts WHERE id = ?", id.value)?.let(::read)
    override fun all() = docs("SELECT doc FROM treasury_accounts ORDER BY rowid").map(::read)
    override fun save(account: TreasuryAccount) = upsert(
        "treasury_accounts", "id",
        mapOf(
            "id" to account.id.value,
            "doc" to Json.encode(
                mapOf(
                    "id" to account.id.value, "name" to account.name, "kind" to account.kind.name, "scope" to Codec.scope(account.scope),
                    "gl" to account.glAccount.value, "overdraft" to account.allowOverdraft, "active" to account.isActive,
                ),
            ),
        ),
    )
}

class SqlMovementStore(db: SqlDatabase) : SqlTable(db), MovementStore {
    private fun read(d: Doc) = TreasuryMovement(
        Codec.id(d.str("id")), Codec.id(d.str("account")), Direction.valueOf(d.str("direction")), Codec.money(d.long("amount")),
        Codec.date(d.long("date")), Codec.id(d.str("journal")), Codec.sourceOf(d.doc("source")), Codec.idOrNull(d.strOrNull("reversalOf")),
        d.long("recordedAt"),
    )

    override fun insert(movement: TreasuryMovement) = db.execute(
        "INSERT INTO treasury_movements (id, account_id, direction, amount, source_type, source_id, reversal_of, doc) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        movement.id.value, movement.accountId.value, movement.direction.name, movement.amount.rial, movement.source.type,
        movement.source.id.value, movement.reversalOf?.value,
        Json.encode(
            mapOf(
                "id" to movement.id.value, "account" to movement.accountId.value, "direction" to movement.direction.name,
                "amount" to movement.amount.rial, "date" to movement.date.epochDay, "journal" to movement.journalId.value,
                "source" to Codec.source(movement.source), "reversalOf" to movement.reversalOf?.value, "recordedAt" to movement.recordedAtEpochMillis,
            ),
        ),
    )

    override fun byId(id: GlobalId) = doc("SELECT doc FROM treasury_movements WHERE id = ?", id.value)?.let(::read)
    override fun reversalOf(id: GlobalId) = doc("SELECT doc FROM treasury_movements WHERE reversal_of = ?", id.value)?.let(::read)
    override fun bySource(type: String, id: GlobalId) =
        docs("SELECT doc FROM treasury_movements WHERE source_type = ? AND source_id = ? ORDER BY rowid", type, id.value).map(::read)

    fun byAccount(accountId: GlobalId, limit: Int): List<TreasuryMovement> =
        docs("SELECT doc FROM treasury_movements WHERE account_id = ? ORDER BY rowid DESC LIMIT ?", accountId.value, limit).map(::read)

    override fun balance(accountId: GlobalId): Long = db.query(
        "SELECT COALESCE(SUM(CASE direction WHEN 'RECEIPT' THEN amount ELSE -amount END), 0) AS n FROM treasury_movements WHERE account_id = ?",
        accountId.value,
    ).single().long("n")
}

// ---------------------------------------------------------------- Inventory

class SqlItemStore(db: SqlDatabase) : SqlTable(db), ItemStore {
    private fun read(d: Doc) = Item(Codec.id(d.str("id")), d.str("name"), StockUnit.valueOf(d.str("unit")), Codec.qty(d.long("minimum")), d.bool("active"))
    override fun byId(id: GlobalId) = doc("SELECT doc FROM items WHERE id = ?", id.value)?.let(::read)
    override fun all() = docs("SELECT doc FROM items ORDER BY rowid").map(::read)
    override fun save(item: Item) = upsert(
        "items", "id",
        mapOf("id" to item.id.value, "doc" to Json.encode(mapOf("id" to item.id.value, "name" to item.name, "unit" to item.unit.name, "minimum" to item.minimumStock.micros, "active" to item.isActive))),
    )
}

class SqlLocationStore(db: SqlDatabase) : SqlTable(db), LocationStore {
    private fun read(d: Doc) = Location(Codec.id(d.str("id")), d.str("name"), Codec.branchOf(d.str("scope")), d.bool("active"))
    override fun byId(id: GlobalId) = doc("SELECT doc FROM locations WHERE id = ?", id.value)?.let(::read)
    override fun all() = docs("SELECT doc FROM locations ORDER BY rowid").map(::read)
    override fun save(location: Location) = upsert(
        "locations", "id",
        mapOf("id" to location.id.value, "doc" to Json.encode(mapOf("id" to location.id.value, "name" to location.name, "scope" to Codec.scope(location.scope), "active" to location.isActive))),
    )
}

class SqlStockStore(db: SqlDatabase) : SqlTable(db), StockStore {
    private fun zero(item: GlobalId, location: GlobalId) = StockBalance(item, location, Quantity.ZERO, Money.ZERO)

    override fun balance(itemId: GlobalId, locationId: GlobalId): StockBalance =
        db.query("SELECT quantity, value FROM stock_balances WHERE item_id = ? AND location_id = ?", itemId.value, locationId.value).firstOrNull()
            ?.let { StockBalance(itemId, locationId, Codec.qty(it.long("quantity")), Codec.money(it.long("value"))) }
            ?: zero(itemId, locationId)

    /** Compare-and-set in one statement: the row changes only if it still holds [expected]. */
    override fun replace(expected: StockBalance, next: StockBalance) {
        require(expected.itemId == next.itemId && expected.locationId == next.locationId)
        db.execute(
            "UPDATE stock_balances SET quantity = ?, value = ? WHERE item_id = ? AND location_id = ? AND quantity = ? AND value = ?",
            next.quantity.micros, next.value.rial, next.itemId.value, next.locationId.value, expected.quantity.micros, expected.value.rial,
        )
        if (db.changes() == 1L) return
        val exists = db.query("SELECT 1 AS x FROM stock_balances WHERE item_id = ? AND location_id = ?", expected.itemId.value, expected.locationId.value).isNotEmpty()
        if (exists || expected != zero(expected.itemId, expected.locationId)) throw DomainException(DomainError.ConcurrentModification("STOCK_BALANCE"))
        db.execute(
            "INSERT INTO stock_balances (item_id, location_id, quantity, value) VALUES (?, ?, ?, ?)",
            next.itemId.value, next.locationId.value, next.quantity.micros, next.value.rial,
        )
    }

    private fun read(d: Doc) = StockMovement(
        Codec.id(d.str("id")), Codec.id(d.str("item")), Codec.id(d.str("location")), MovementKind.valueOf(d.str("kind")),
        d.long("qty"), d.long("value"), Codec.date(d.long("date")), Codec.idOrNull(d.strOrNull("journal")),
        Codec.sourceOf(d.doc("source")), Codec.idOrNull(d.strOrNull("reversalOf")), d.long("recordedAt"),
    )

    override fun insertMovement(movement: StockMovement) = db.execute(
        "INSERT INTO stock_movements (id, source_type, source_id, reversal_of, doc) VALUES (?, ?, ?, ?, ?)",
        movement.id.value, movement.source.type, movement.source.id.value, movement.reversalOf?.value,
        Json.encode(
            mapOf(
                "id" to movement.id.value, "item" to movement.itemId.value, "location" to movement.locationId.value, "kind" to movement.kind.name,
                "qty" to movement.quantityDelta, "value" to movement.valueDelta, "date" to movement.date.epochDay, "journal" to movement.journalId?.value,
                "source" to Codec.source(movement.source), "reversalOf" to movement.reversalOf?.value, "recordedAt" to movement.recordedAtEpochMillis,
            ),
        ),
    )

    override fun movementsBySource(type: String, id: GlobalId) =
        docs("SELECT doc FROM stock_movements WHERE source_type = ? AND source_id = ? ORDER BY rowid", type, id.value).map(::read)
    override fun reversalOf(id: GlobalId) = doc("SELECT doc FROM stock_movements WHERE reversal_of = ?", id.value)?.let(::read)
    override fun balances(locationId: GlobalId) =
        db.query("SELECT * FROM stock_balances WHERE location_id = ? ORDER BY rowid", locationId.value).map {
            StockBalance(Codec.id(it.str("item_id")), locationId, Codec.qty(it.long("quantity")), Codec.money(it.long("value")))
        }
}

class SqlRecipeStore(db: SqlDatabase) : SqlTable(db), RecipeStore {
    override fun menuItem(id: GlobalId) = doc("SELECT doc FROM menu_items WHERE id = ?", id.value)?.let { MenuItem(Codec.id(it.str("id")), it.str("name"), it.bool("active")) }
    fun menuItems(): List<MenuItem> = docs("SELECT doc FROM menu_items ORDER BY rowid").map { MenuItem(Codec.id(it.str("id")), it.str("name"), it.bool("active")) }
    override fun saveMenuItem(item: MenuItem) = upsert(
        "menu_items", "id",
        mapOf("id" to item.id.value, "doc" to Json.encode(mapOf("id" to item.id.value, "name" to item.name, "active" to item.isActive))),
    )

    override fun versions(menuItemId: GlobalId) = docs("SELECT doc FROM recipe_versions WHERE menu_item_id = ? ORDER BY rowid", menuItemId.value).map { d ->
        RecipeVersion(
            Codec.id(d.str("id")), Codec.id(d.str("menuItem")), d.int("version"), Codec.date(d.long("from")),
            d.docs("lines").map { RecipeLine(Codec.id(it.str("item")), Codec.qty(it.long("qty"))) },
        )
    }

    /** Versions are immutable: a plain INSERT, so re-saving an id fails loudly. */
    override fun saveVersion(version: RecipeVersion) = db.execute(
        "INSERT INTO recipe_versions (id, menu_item_id, doc) VALUES (?, ?, ?)",
        version.id.value, version.menuItemId.value,
        Json.encode(
            mapOf(
                "id" to version.id.value, "menuItem" to version.menuItemId.value, "version" to version.version, "from" to version.effectiveFrom.epochDay,
                "lines" to version.lines.map { mapOf("item" to it.itemId.value, "qty" to it.quantityPerPortion.micros) },
            ),
        ),
    )
}
