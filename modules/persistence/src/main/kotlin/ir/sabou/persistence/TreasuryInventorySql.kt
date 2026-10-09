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
import ir.sabou.treasury.Cheque
import ir.sabou.treasury.ChequeDetails
import ir.sabou.treasury.ChequeDirection
import ir.sabou.treasury.ChequeEvent
import ir.sabou.treasury.ChequeStatus
import ir.sabou.treasury.ChequeStore
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
        d.long("recordedAt"), Codec.idOrNull(d.strOrNull("cheque")),
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
                "cheque" to movement.chequeId?.value,
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

class SqlChequeStore(db: SqlDatabase) : SqlTable(db), ChequeStore {
    private fun read(d: Doc): Cheque {
        val det = d.doc("details")
        return Cheque(
            Codec.id(d.str("id")), ChequeDirection.valueOf(d.str("direction")), Codec.id(d.str("account")), Codec.scopeOf(d.str("scope")),
            Codec.money(d.long("amount")),
            ChequeDetails(det.str("number"), det.str("bank"), det.strOr("sayad", ""), Codec.date(det.long("due")), det.str("counterparty"),
                det.strOr("note", ""), Codec.idOrNull(det.strOrNull("bankAccount"))),
            ChequeStatus.valueOf(d.str("status")),
            d.docs("events").map {
                ChequeEvent(ChequeStatus.valueOf(it.str("status")), Codec.date(it.long("date")), it.str("sourceType"), Codec.id(it.str("sourceId")),
                    Codec.idOrNull(it.strOrNull("account")), it.strOr("note", ""), it.boolOr("reversed", false))
            },
            Codec.idOrNull(d.strOrNull("bankAccount")),
        )
    }
    override fun byId(id: GlobalId) = doc("SELECT doc FROM cheques WHERE id = ?", id.value)?.let(::read)
    override fun all() = docs("SELECT doc FROM cheques ORDER BY due, rowid").map(::read)
    override fun save(cheque: Cheque) = upsert(
        "cheques", "id",
        mapOf(
            "id" to cheque.id.value, "scope" to Codec.scope(cheque.scope), "direction" to cheque.direction.name, "status" to cheque.status.name,
            "due" to cheque.dueDate.epochDay,
            "doc" to Json.encode(mapOf(
                "id" to cheque.id.value, "direction" to cheque.direction.name, "account" to cheque.accountId.value, "scope" to Codec.scope(cheque.scope),
                "amount" to cheque.amount.rial, "status" to cheque.status.name, "bankAccount" to cheque.bankAccountId?.value,
                "details" to cheque.details.let {
                    mapOf("number" to it.number, "bank" to it.bank, "sayad" to it.sayadId, "due" to it.dueDate.epochDay, "counterparty" to it.counterparty,
                        "note" to it.note, "bankAccount" to it.bankAccountId?.value)
                },
                "events" to cheque.events.map {
                    mapOf("status" to it.status.name, "date" to it.date.epochDay, "sourceType" to it.sourceType, "sourceId" to it.sourceId.value,
                        "account" to it.accountId?.value, "note" to it.note, "reversed" to it.reversed)
                },
            )),
        ),
    )
}

// ---------------------------------------------------------------- Inventory

class SqlItemStore(db: SqlDatabase) : SqlTable(db), ItemStore {
    private fun read(d: Doc) = Item(
        Codec.id(d.str("id")), d.str("name"), StockUnit.valueOf(d.str("unit")), Codec.qty(d.long("minimum")), d.bool("active"),
        parLevel = Codec.qty(d.longOr("par", 0)), shelf = d.strOr("shelf", ""), allergens = d.strOr("allergens", ""),
        prepared = d.boolOr("prepared", false), preferredSupplierId = Codec.idOrNull(d.strOrNull("preferredSupplier")),
        approvedSupplierIds = d.strsOr("approvedSuppliers").map(Codec::id).toSet(),
    )
    override fun byId(id: GlobalId) = doc("SELECT doc FROM items WHERE id = ?", id.value)?.let(::read)
    override fun all() = docs("SELECT doc FROM items ORDER BY rowid").map(::read)
    override fun save(item: Item) = upsert(
        "items", "id",
        mapOf(
            "id" to item.id.value,
            "doc" to Json.encode(
                mapOf(
                    "id" to item.id.value, "name" to item.name, "unit" to item.unit.name, "minimum" to item.minimumStock.micros, "active" to item.isActive,
                    "par" to item.parLevel.micros, "shelf" to item.shelf, "allergens" to item.allergens, "prepared" to item.prepared,
                    "preferredSupplier" to item.preferredSupplierId?.value, "approvedSuppliers" to item.approvedSupplierIds.map { it.value }.sorted(),
                ),
            ),
        ),
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

    override fun insertMovement(movement: StockMovement) {
        insertDoc(movement)
        db.execute(
            "INSERT INTO stock_movement_index (movement_id, item_id, location_id, date, kind, qty, value) VALUES (?, ?, ?, ?, ?, ?, ?)",
            movement.id.value, movement.itemId.value, movement.locationId.value, movement.date.epochDay, movement.kind.name,
            movement.quantityDelta, movement.valueDelta,
        )
    }

    override fun movementsAt(locationId: GlobalId, from: ir.sabou.kernel.BusinessDate, to: ir.sabou.kernel.BusinessDate) = docs(
        "SELECT m.doc AS doc FROM stock_movement_index i JOIN stock_movements m ON m.id = i.movement_id " +
            "WHERE i.location_id = ? AND i.date BETWEEN ? AND ? ORDER BY m.rowid",
        locationId.value, from.epochDay, to.epochDay,
    ).map(::read)

    override fun totalsBefore(locationId: GlobalId, date: ir.sabou.kernel.BusinessDate) = db.query(
        "SELECT item_id, SUM(qty) AS q, SUM(value) AS v FROM stock_movement_index WHERE location_id = ? AND date < ? GROUP BY item_id",
        locationId.value, date.epochDay,
    ).map { ir.sabou.inventory.MovementTotal(Codec.id(it.str("item_id")), it.long("q"), it.long("v")) }

    private fun insertDoc(movement: StockMovement) = db.execute(
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
    override fun menuItems(): List<MenuItem> = docs("SELECT doc FROM menu_items ORDER BY rowid").map { MenuItem(Codec.id(it.str("id")), it.str("name"), it.bool("active")) }
    override fun saveMenuItem(item: MenuItem) = upsert(
        "menu_items", "id",
        mapOf("id" to item.id.value, "doc" to Json.encode(mapOf("id" to item.id.value, "name" to item.name, "active" to item.isActive))),
    )

    override fun versions(menuItemId: GlobalId) = docs("SELECT doc FROM recipe_versions WHERE menu_item_id = ? ORDER BY rowid", menuItemId.value).map { d ->
        RecipeVersion(
            Codec.id(d.str("id")), Codec.id(d.str("menuItem")), d.int("version"), Codec.date(d.long("from")),
            d.docs("lines").map(::line),
        )
    }

    private fun line(d: Doc) = RecipeLine(Codec.id(d.str("item")), Codec.qty(d.long("qty")), d.intOr("yield", 100))
    private fun lineDoc(l: RecipeLine) = mapOf("item" to l.itemId.value, "qty" to l.quantityPerPortion.micros, "yield" to l.yieldPercent)

    override fun prepVersions(itemId: GlobalId) = docs("SELECT doc FROM prep_recipes WHERE item_id = ? ORDER BY rowid", itemId.value).map { d ->
        ir.sabou.inventory.PrepRecipe(
            Codec.id(d.str("id")), Codec.id(d.str("item")), d.int("version"), Codec.date(d.long("from")), Codec.qty(d.long("output")), d.docs("lines").map(::line),
        )
    }

    override fun savePrepVersion(version: ir.sabou.inventory.PrepRecipe) = db.execute(
        "INSERT INTO prep_recipes (id, item_id, doc) VALUES (?, ?, ?)",
        version.id.value, version.itemId.value,
        Json.encode(
            mapOf(
                "id" to version.id.value, "item" to version.itemId.value, "version" to version.version, "from" to version.effectiveFrom.epochDay,
                "output" to version.outputQuantity.micros, "lines" to version.lines.map(::lineDoc),
            ),
        ),
    )

    /** Versions are immutable: a plain INSERT, so re-saving an id fails loudly. */
    override fun saveVersion(version: RecipeVersion) = db.execute(
        "INSERT INTO recipe_versions (id, menu_item_id, doc) VALUES (?, ?, ?)",
        version.id.value, version.menuItemId.value,
        Json.encode(
            mapOf(
                "id" to version.id.value, "menuItem" to version.menuItemId.value, "version" to version.version, "from" to version.effectiveFrom.epochDay,
                "lines" to version.lines.map(::lineDoc),
            ),
        ),
    )
}
