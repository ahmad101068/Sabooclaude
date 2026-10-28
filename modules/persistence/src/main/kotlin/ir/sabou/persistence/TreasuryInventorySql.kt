package ir.sabou.persistence

import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.inventory.Item
import ir.sabou.inventory.ItemStore
import ir.sabou.inventory.Location
import ir.sabou.inventory.PackUnit
import ir.sabou.inventory.StockLevels
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.Scope
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

    override fun insert(movement: TreasuryMovement) {
        insertRow(movement)
        val net = if (movement.direction == Direction.RECEIPT) movement.amount.rial else -movement.amount.rial
        db.execute("INSERT INTO treasury_days (account_id, date, net) VALUES (?, ?, ?) ON CONFLICT(account_id, date) DO UPDATE SET net = treasury_days.net + excluded.net",
            movement.accountId.value, movement.date.epochDay, net)
    }

    private fun insertRow(movement: TreasuryMovement) = db.execute(
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
    override fun byAccount(accountId: GlobalId) = docs("SELECT doc FROM treasury_movements WHERE account_id = ? ORDER BY rowid", accountId.value).map(::read)

    fun byAccount(accountId: GlobalId, limit: Int): List<TreasuryMovement> =
        docs("SELECT doc FROM treasury_movements WHERE account_id = ? ORDER BY rowid DESC LIMIT ?", accountId.value, limit).map(::read)

    override fun balanceAsOf(accountId: GlobalId, date: BusinessDate): Long = db.query(
        "SELECT COALESCE(SUM(net), 0) AS n FROM treasury_days WHERE account_id = ? AND date <= ?", accountId.value, date.epochDay,
    ).single().long("n")

    override fun lowestBalanceFrom(accountId: GlobalId, date: BusinessDate): Long {
        var running = balanceAsOf(accountId, date.plusDays(-1))
        var lowest = running
        db.query("SELECT net FROM treasury_days WHERE account_id = ? AND date >= ? ORDER BY date", accountId.value, date.epochDay).forEach {
            running = Math.addExact(running, it.long("net")); lowest = minOf(lowest, running)
        }
        return lowest
    }

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
            Codec.chequeOf(det),
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
                "details" to Codec.cheque(cheque.details),
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
        packs = d.docsOr("packs").map { PackUnit(it.str("name"), Codec.qty(it.long("contains"))) },
        shelfLifeDays = d.longOrNull("shelfLife")?.toInt(),
        parByWeekday = d.docsOr("parByDay").associate { it.long("day").toInt() to Codec.qty(it.long("par")) },
        category = d.strOr("category", ""), code = d.strOr("code", ""),
        locationLevels = d.docsOr("levels").associate { Codec.id(it.str("location")) to StockLevels(Codec.qty(it.long("min")), Codec.qty(it.long("par"))) },
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
                    "packs" to item.packs.map { mapOf("name" to it.name, "contains" to it.contains.micros) },
                    "shelfLife" to item.shelfLifeDays?.toLong(),
                    "parByDay" to item.parByWeekday.entries.sortedBy { it.key }.map { mapOf("day" to it.key.toLong(), "par" to it.value.micros) },
                    "category" to item.category, "code" to item.code,
                    "levels" to item.locationLevels.entries.sortedBy { it.key.value.toString() }.map { mapOf("location" to it.key.value, "min" to it.value.minimum.micros, "par" to it.value.par.micros) },
                ),
            ),
        ),
    )

    override fun delete(id: GlobalId) { db.execute("DELETE FROM items WHERE id = ?", id.value) }

    /** Whether any record other than the item itself names it (documents, recipes, prices, plans, balances). */
    fun isReferenced(id: GlobalId): Boolean {
        val tables = db.tableNames().filter { it != "items" && it != "sync_changes" && it != "audit_log" && it != "schema_version" }
        return tables.any { t ->
            val cols = db.columnsOf(t).map { it.first }.filter { it == "doc" || it == "item_id" }
            cols.any { c -> db.query("SELECT 1 AS x FROM $t WHERE $c LIKE ? LIMIT 1", "%${id.value}%").isNotEmpty() }
        }
    }
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
    override fun hasMovements(itemId: GlobalId) = db.query("SELECT 1 AS x FROM stock_movement_index WHERE item_id = ? LIMIT 1", itemId.value).isNotEmpty()
    override fun lastInboundUnitCost(itemId: GlobalId): Long? =
        db.query("SELECT qty, value FROM stock_movement_index WHERE item_id = ? AND kind = 'RECEIPT' AND qty > 0 AND value > 0 ORDER BY date DESC, rowid DESC LIMIT 1", itemId.value)
            .firstOrNull()?.let { ir.sabou.kernel.Ratio.mulDiv(it.long("value"), Quantity.SCALE, it.long("qty")) }
    override fun balances(locationId: GlobalId) =
        db.query("SELECT * FROM stock_balances WHERE location_id = ? ORDER BY rowid", locationId.value).map {
            StockBalance(Codec.id(it.str("item_id")), locationId, Codec.qty(it.long("quantity")), Codec.money(it.long("value")))
        }
}

class SqlRecipeStore(db: SqlDatabase) : SqlTable(db), RecipeStore {
    override fun menuItem(id: GlobalId) = doc("SELECT doc FROM menu_items WHERE id = ?", id.value)?.let { MenuItem(Codec.id(it.str("id")), it.str("name"), it.bool("active"), it.strOr("category", "")) }
    override fun menuItems(): List<MenuItem> = docs("SELECT doc FROM menu_items ORDER BY rowid").map { MenuItem(Codec.id(it.str("id")), it.str("name"), it.bool("active"), it.strOr("category", "")) }
    override fun deleteMenuItem(id: GlobalId) { db.execute("DELETE FROM menu_items WHERE id = ?", id.value) }
    override fun saveMenuItem(item: MenuItem) = upsert(
        "menu_items", "id",
        mapOf("id" to item.id.value, "doc" to Json.encode(mapOf("id" to item.id.value, "name" to item.name, "active" to item.isActive, "category" to item.category))),
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

class SqlStockCountStore(db: SqlDatabase) : SqlTable(db), ir.sabou.inventory.StockCountStore {
    private fun read(d: Doc) = ir.sabou.inventory.StockCount(
        Codec.id(d.str("id")), Codec.branchOf(d.str("scope")), Codec.id(d.str("location")), Codec.date(d.long("date")),
        d.docs("lines").map {
            ir.sabou.inventory.CountedLine(
                Codec.id(it.str("item")), Codec.qty(it.long("counted")), Codec.qty(it.long("book")), it.strOr("note", ""),
                it.strOrNull("reason")?.let(ir.sabou.inventory.VarianceReason::valueOf), it.strOr("reasonNote", ""), it.longOrNull("posted"),
            )
        },
        d.strOr("note", ""), ir.sabou.inventory.CountStatus.valueOf(d.str("status")), Codec.id(d.str("countedBy")), d.str("countedByName"), d.long("countedAt"),
        Codec.idOrNull(d.strOrNull("reviewedBy")), d.strOrNull("reviewedByName"), d.longOrNull("reviewedAt"), d.strOrNull("rejectReason"),
        Codec.idOrNull(d.strOrNull("plan")),
        if (d.has("shortagesAtCount")) d.strs("shortagesAtCount").map(Codec::id) else null,
    )
    override fun byId(id: GlobalId) = doc("SELECT doc FROM stock_counts WHERE id = ?", id.value)?.let(::read)
    override fun all() = docs("SELECT doc FROM stock_counts ORDER BY rowid DESC").map(::read)
    override fun save(count: ir.sabou.inventory.StockCount) = upsert(
        "stock_counts", "id",
        mapOf(
            "id" to count.id.value, "scope" to Codec.scope(count.scope), "location_id" to count.locationId.value, "status" to count.status.name,
            "doc" to Json.encode(mapOf(
                "id" to count.id.value, "scope" to Codec.scope(count.scope), "location" to count.locationId.value, "date" to count.date.epochDay,
                "lines" to count.lines.map {
                    mapOf("item" to it.itemId.value, "counted" to it.counted.micros, "book" to it.bookAtCount.micros, "note" to it.note,
                        "reason" to it.reason?.name, "reasonNote" to it.reasonNote, "posted" to it.postedValue)
                },
                "note" to count.note, "status" to count.status.name, "countedBy" to count.countedBy.value, "countedByName" to count.countedByName,
                "countedAt" to count.countedAt, "reviewedBy" to count.reviewedBy?.value, "reviewedByName" to count.reviewedByName,
                "reviewedAt" to count.reviewedAt, "rejectReason" to count.rejectReason, "plan" to count.planId?.value,
                "shortagesAtCount" to count.shortagesAtCount?.map { it.value },
            )),
        ),
    )
}

/** Shortages of consumption (ADR-0022). */
class SqlShortageStore(db: SqlDatabase) : SqlTable(db), ir.sabou.inventory.ShortageStore {
    private fun read(d: Doc) = ir.sabou.inventory.StockShortage(
        Codec.id(d.str("id")), Codec.id(d.str("item")), Codec.id(d.str("location")), Codec.date(d.long("date")),
        d.str("sourceType"), Codec.id(d.str("sourceId")), Codec.qty(d.long("qty")), Codec.qty(d.long("settled")), Codec.money(d.long("settledCost")),
        Codec.qty(d.long("taken")), Codec.money(d.long("takenCost")), ir.sabou.inventory.StockShortage.Status.valueOf(d.str("status")),
        d.longOr("recordedAt", 0L),
    )
    override fun byId(id: GlobalId) = doc("SELECT doc FROM stock_shortages WHERE id = ?", id.value)?.let(::read)
    override fun ofSource(sourceType: String, sourceId: GlobalId) =
        docs("SELECT doc FROM stock_shortages WHERE source_type = ? AND source_id = ? ORDER BY rowid", sourceType, sourceId.value).map(::read)
    override fun open(itemId: GlobalId, locationId: GlobalId) =
        docs("SELECT doc FROM stock_shortages WHERE item_id = ? AND location_id = ? AND status = 'OPEN' ORDER BY date, rowid", itemId.value, locationId.value).map(::read)
    override fun openAt(locationIds: Set<GlobalId>) =
        docs("SELECT doc FROM stock_shortages WHERE status = 'OPEN' ORDER BY date, rowid").map(::read).filter { it.locationId in locationIds }
    override fun save(shortage: ir.sabou.inventory.StockShortage) = upsert(
        "stock_shortages", "id",
        mapOf(
            "id" to shortage.id.value, "item_id" to shortage.itemId.value, "location_id" to shortage.locationId.value, "date" to shortage.date.epochDay,
            "source_type" to shortage.sourceType, "source_id" to shortage.sourceId.value, "status" to shortage.status.name,
            "doc" to Json.encode(mapOf(
                "id" to shortage.id.value, "item" to shortage.itemId.value, "location" to shortage.locationId.value, "date" to shortage.date.epochDay,
                "sourceType" to shortage.sourceType, "sourceId" to shortage.sourceId.value, "qty" to shortage.quantity.micros,
                "settled" to shortage.settled.micros, "settledCost" to shortage.settledCost.rial, "taken" to shortage.takenFromStock.micros,
                "takenCost" to shortage.takenCost.rial, "status" to shortage.status.name, "recordedAt" to shortage.recordedAt,
            )),
        ),
    )
}

/** Expiry lots (ADR-0030). */
class SqlLotStore(db: SqlDatabase) : SqlTable(db), ir.sabou.inventory.LotStore {
    private fun read(d: Doc) = ir.sabou.inventory.StockLot(
        Codec.id(d.str("id")), Codec.id(d.str("item")), Codec.id(d.str("location")), Codec.date(d.long("expires")), Codec.date(d.long("received")),
        Codec.qty(d.long("qty")), Codec.qty(d.long("remaining")), d.str("sourceType"), Codec.id(d.str("sourceId")),
    )
    override fun open(itemId: GlobalId, locationId: GlobalId) =
        docs("SELECT doc FROM stock_lots WHERE item_id = ? AND location_id = ? AND open = 1 ORDER BY expires_on, rowid", itemId.value, locationId.value)
            .map(::read).sortedWith(ir.sabou.inventory.FEFO)
    override fun openAt(locationIds: Set<GlobalId>) =
        docs("SELECT doc FROM stock_lots WHERE open = 1 ORDER BY expires_on, rowid").map(::read).filter { it.locationId in locationIds }.sortedWith(ir.sabou.inventory.FEFO)
    override fun save(lot: ir.sabou.inventory.StockLot) = upsert(
        "stock_lots", "id",
        mapOf(
            "id" to lot.id.value, "item_id" to lot.itemId.value, "location_id" to lot.locationId.value, "expires_on" to lot.expiresOn.epochDay,
            "open" to if (lot.remaining.isZero) 0L else 1L,
            "doc" to Json.encode(mapOf(
                "id" to lot.id.value, "item" to lot.itemId.value, "location" to lot.locationId.value, "expires" to lot.expiresOn.epochDay,
                "received" to lot.receivedOn.epochDay, "qty" to lot.quantity.micros, "remaining" to lot.remaining.micros,
                "sourceType" to lot.sourceType, "sourceId" to lot.sourceId.value,
            )),
        ),
    )
}

/** Count plans (ADR-0031). */
class SqlCountPlanStore(db: SqlDatabase) : SqlTable(db), ir.sabou.inventory.CountPlanStore {
    private fun read(d: Doc) = ir.sabou.inventory.CountPlan(
        Codec.id(d.str("id")), d.str("name"), Codec.branchOf(d.str("scope")), Codec.id(d.str("location")), d.strsOr("items").map(Codec::id),
        ir.sabou.inventory.CountFrequency(ir.sabou.inventory.CountFrequency.Kind.valueOf(d.str("frequency")), d.long("day").toInt()), d.bool("active"),
    )
    override fun byId(id: GlobalId) = doc("SELECT doc FROM count_plans WHERE id = ?", id.value)?.let(::read)
    override fun all() = docs("SELECT doc FROM count_plans ORDER BY rowid").map(::read)
    override fun save(plan: ir.sabou.inventory.CountPlan) = upsert(
        "count_plans", "id",
        mapOf(
            "id" to plan.id.value, "scope" to Codec.scope(plan.scope),
            "doc" to Json.encode(mapOf(
                "id" to plan.id.value, "name" to plan.name, "scope" to Codec.scope(plan.scope), "location" to plan.locationId.value,
                "items" to plan.itemIds.map { it.value }, "frequency" to plan.frequency.kind.name, "day" to plan.frequency.day.toLong(), "active" to plan.isActive,
            )),
        ),
    )
}

/** Imported bank statements and their matches (ADR-0035). */
class SqlBankStatementStore(db: SqlDatabase) : SqlTable(db), ir.sabou.treasury.BankStatementStore {
    private fun read(d: Doc) = ir.sabou.treasury.BankStatement(
        Codec.id(d.str("id")), Codec.id(d.str("account")), Codec.scopeOf(d.str("scope")), d.str("file"), Codec.date(d.long("from")), Codec.date(d.long("to")),
        d.longOrNull("closing")?.let(Codec::money),
        d.docs("lines").map {
            ir.sabou.treasury.StatementLine(it.long("i").toInt(), Codec.date(it.long("date")), it.str("text"), ir.sabou.treasury.Direction.valueOf(it.str("dir")),
                Codec.money(it.long("amount")), it.longOrNull("balance")?.let(Codec::money), it.str("ref"), Codec.idOrNull(it.strOrNull("movement")))
        },
        d.str("by"), d.long("at"),
    )
    override fun byId(id: GlobalId) = doc("SELECT doc FROM bank_statements WHERE id = ?", id.value)?.let(::read)
    override fun of(accountId: GlobalId) = docs("SELECT doc FROM bank_statements WHERE account_id = ? ORDER BY rowid", accountId.value).map(::read)
    override fun save(statement: ir.sabou.treasury.BankStatement) = upsert(
        "bank_statements", "id",
        mapOf(
            "id" to statement.id.value, "account_id" to statement.accountId.value,
            "doc" to Json.encode(mapOf(
                "id" to statement.id.value, "account" to statement.accountId.value, "scope" to Codec.scope(statement.scope), "file" to statement.fileName,
                "from" to statement.from.epochDay, "to" to statement.to.epochDay, "closing" to statement.closingBalance?.rial,
                "lines" to statement.lines.map {
                    mapOf("i" to it.index.toLong(), "date" to it.date.epochDay, "text" to it.description, "dir" to it.direction.name, "amount" to it.amount.rial,
                        "balance" to it.balance?.rial, "ref" to it.reference, "movement" to it.movementId?.value)
                },
                "by" to statement.importedBy, "at" to statement.importedAt,
            )),
        ),
    )
}

/** Reason, note and planned batch of inventory documents (ADR-0044). */
class SqlInventoryDocumentStore(db: SqlDatabase) : SqlTable(db), ir.sabou.inventory.InventoryDocumentStore {
    private fun read(d: Doc) = ir.sabou.inventory.InventoryDocument(
        Codec.id(d.str("id")), d.str("type"), Codec.branchOf(d.str("scope")), Codec.date(d.long("date")), d.strOr("note", ""), d.strOr("reason", ""),
        d.longOrNull("planned")?.let(Codec::qty), d.strOr("dish", ""), d.strOr("reversed", ""),
    )
    override fun byId(id: GlobalId) = doc("SELECT doc FROM inventory_documents WHERE id = ?", id.value)?.let(::read)
    override fun of(scope: Scope.Branch, from: BusinessDate, to: BusinessDate) =
        docs("SELECT doc FROM inventory_documents WHERE scope = ? AND date BETWEEN ? AND ? ORDER BY date DESC, rowid DESC", Codec.scope(scope), from.epochDay, to.epochDay).map(::read)
    override fun save(document: ir.sabou.inventory.InventoryDocument) = upsert("inventory_documents", "id", mapOf(
        "id" to document.id.value, "scope" to Codec.scope(document.scope), "date" to document.date.epochDay,
        "doc" to Json.encode(mapOf(
            "id" to document.id.value, "type" to document.type, "scope" to Codec.scope(document.scope), "date" to document.date.epochDay,
            "note" to document.note, "reason" to document.reason, "planned" to document.planned?.micros, "dish" to document.dish, "reversed" to document.reversedReason,
        )),
    ))
}

/** Transfers between branches waiting for (or past) the receiving branch's count (ADR-0044). */
class SqlTransitStore(db: SqlDatabase) : SqlTable(db), ir.sabou.inventory.TransitStore {
    private fun read(d: Doc) = ir.sabou.inventory.StockTransit(
        Codec.id(d.str("id")), Codec.branchOf(d.str("fromScope")), Codec.id(d.str("fromLoc")), Codec.branchOf(d.str("toScope")), Codec.id(d.str("toLoc")),
        Codec.date(d.long("date")),
        d.docs("lines").map { l ->
            ir.sabou.inventory.TransitLine(
                Codec.id(l.str("item")), Codec.qty(l.long("qty")), Codec.money(l.long("value")),
                l.docsOr("lots").map { p -> ir.sabou.inventory.LotPart(Codec.date(p.long("exp")), Codec.qty(p.long("qty"))) },
                l.longOrNull("received")?.let(Codec::qty),
            )
        },
        ir.sabou.inventory.StockTransit.Status.valueOf(d.str("status")), d.longOrNull("receivedOn")?.let(Codec::date), d.strOr("receivedBy", ""),
        Codec.idOrNull(d.strOrNull("receipt")),
    )
    override fun byId(id: GlobalId) = doc("SELECT doc FROM stock_transits WHERE id = ?", id.value)?.let(::read)
    override fun of(scope: Scope.Branch) =
        docs("SELECT doc FROM stock_transits WHERE from_scope = ? OR to_scope = ? ORDER BY date DESC, rowid DESC", Codec.scope(scope), Codec.scope(scope)).map(::read)
    override fun save(transit: ir.sabou.inventory.StockTransit) = upsert("stock_transits", "id", mapOf(
        "id" to transit.id.value, "from_scope" to Codec.scope(transit.fromScope), "to_scope" to Codec.scope(transit.toScope),
        "date" to transit.date.epochDay, "status" to transit.status.name,
        "doc" to Json.encode(mapOf(
            "id" to transit.id.value, "fromScope" to Codec.scope(transit.fromScope), "fromLoc" to transit.fromLocationId.value,
            "toScope" to Codec.scope(transit.toScope), "toLoc" to transit.toLocationId.value, "date" to transit.date.epochDay,
            "lines" to transit.lines.map { l ->
                mapOf("item" to l.itemId.value, "qty" to l.quantity.micros, "value" to l.value.rial, "received" to l.received?.micros,
                    "lots" to l.lots.map { mapOf("exp" to it.expiresOn.epochDay, "qty" to it.quantity.micros) })
            },
            "status" to transit.status.name, "receivedOn" to transit.receivedOn?.epochDay, "receivedBy" to transit.receivedBy, "receipt" to transit.receiptId?.value,
        )),
    ))
}

/** Requests for goods from a storeroom (ADR-0044). */
class SqlStockRequestStore(db: SqlDatabase) : SqlTable(db), ir.sabou.inventory.StockRequestStore {
    private fun read(d: Doc) = ir.sabou.inventory.StockRequest(
        Codec.id(d.str("id")), Codec.branchOf(d.str("scope")), Codec.branchOf(d.str("fromScope")), Codec.id(d.str("fromLoc")), Codec.id(d.str("toLoc")),
        d.docs("lines").map { ir.sabou.inventory.IssueLine(Codec.id(it.str("item")), Codec.qty(it.long("qty"))) },
        d.strOr("note", ""), Codec.date(d.long("date")), d.strOr("by", ""), Codec.id(d.str("byId")),
        ir.sabou.inventory.StockRequest.Status.valueOf(d.str("status")), d.strOr("handledBy", ""), Codec.idOrNull(d.strOrNull("transfer")), d.strOr("reason", ""),
    )
    override fun byId(id: GlobalId) = doc("SELECT doc FROM stock_requests WHERE id = ?", id.value)?.let(::read)
    override fun of(scope: Scope.Branch) =
        docs("SELECT doc FROM stock_requests WHERE scope = ? OR from_scope = ? ORDER BY date DESC, rowid DESC", Codec.scope(scope), Codec.scope(scope)).map(::read)
    override fun save(request: ir.sabou.inventory.StockRequest) = upsert("stock_requests", "id", mapOf(
        "id" to request.id.value, "scope" to Codec.scope(request.scope), "from_scope" to Codec.scope(request.fromScope),
        "date" to request.date.epochDay, "status" to request.status.name,
        "doc" to Json.encode(mapOf(
            "id" to request.id.value, "scope" to Codec.scope(request.scope), "fromScope" to Codec.scope(request.fromScope),
            "fromLoc" to request.fromLocationId.value, "toLoc" to request.toLocationId.value,
            "lines" to request.lines.map { mapOf("item" to it.itemId.value, "qty" to it.quantity.micros) },
            "note" to request.note, "date" to request.date.epochDay, "by" to request.requestedBy, "byId" to request.requesterId.value,
            "status" to request.status.name, "handledBy" to request.handledBy, "transfer" to request.transferId?.value, "reason" to request.rejectReason,
        )),
    ))
}
