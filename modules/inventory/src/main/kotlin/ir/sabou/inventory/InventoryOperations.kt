package ir.sabou.inventory

import ir.sabou.platform.NoDocument

import ir.sabou.platform.DocumentSeries

import ir.sabou.platform.IssuesDocument

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.kernel.ensure
import ir.sabou.ledger.JournalDraft
import ir.sabou.ledger.LineDraft
import ir.sabou.ledger.SourceDocument
import ir.sabou.ledger.StandardAccounts
import ir.sabou.platform.AuditDraft
import ir.sabou.platform.Command
import ir.sabou.platform.CommandBus
import ir.sabou.platform.CommandOutcome
import ir.sabou.platform.ModuleId
import ir.sabou.platform.Permission

@NoDocument
data class CreateItem(
    override val commandId: GlobalId,
    val name: String,
    val unit: StockUnit,
    val minimumStock: Quantity,
    val prepared: Boolean = false,
    val packs: List<PackUnit> = emptyList(),
) : Command {
    override val requiredPermission = Permission.INVENTORY_ITEM_MANAGE
    // The item catalogue is shared by all branches.
    override val scope: Scope = Scope.Organization
    override val sharedCatalog = true
    override fun fingerprint() = "$name|$unit|${minimumStock.micros}|$prepared|${packs.joinToString(";") { "${it.name}=${it.contains.micros}" }}"
}

/** Edits an item's ordering and storage details. The unit never changes (it would revalue history). */
@NoDocument
data class UpdateItem(
    override val commandId: GlobalId,
    val itemId: GlobalId,
    val name: String,
    val minimumStock: Quantity,
    val parLevel: Quantity,
    val shelf: String,
    val allergens: String,
    val preferredSupplierId: GlobalId?,
    val approvedSupplierIds: Set<GlobalId>,
    val isActive: Boolean,
    /** The item's purchase packs; null keeps them as they are. */
    val packs: List<PackUnit>? = null,
    /** Days it keeps (1..3650), or null for no shelf life. */
    val shelfLifeDays: Int? = null,
    /** Par per delivery weekday (0..6); null keeps them as they are, an empty map clears them. */
    val parByWeekday: Map<Int, Quantity>? = null,
    /** null keeps them as they are. */
    val category: String? = null,
    val code: String? = null,
    /** Levels per location; null keeps them, an empty map clears them. */
    val locationLevels: Map<GlobalId, StockLevels>? = null,
    /** A new stock unit: only for an item that never moved and is in no recipe (a unit typed wrong). */
    val unit: StockUnit? = null,
) : Command {
    override val requiredPermission = Permission.INVENTORY_ITEM_MANAGE
    override val scope: Scope = Scope.Organization
    override val sharedCatalog = true
    override fun fingerprint() = "$itemId|$name|${minimumStock.micros}|${parLevel.micros}|$shelf|$allergens|$preferredSupplierId|" +
        approvedSupplierIds.map { it.value }.sorted().joinToString(",") + "|$isActive|" +
        (packs?.joinToString(";") { "${it.name}=${it.contains.micros}" } ?: "keep") + "|${shelfLifeDays ?: "-"}|" +
        (parByWeekday?.entries?.sortedBy { it.key }?.joinToString(",") { "${it.key}=${it.value.micros}" } ?: "keep") +
        "|$category|$code|" + (locationLevels?.entries?.sortedBy { it.key.value.toString() }?.joinToString(",") { "${it.key}=${it.value.minimum.micros}/${it.value.par.micros}" } ?: "keep") + "|$unit"
}

/** Deletes an item created by mistake that nothing refers to (no stock movement, recipe, document or price). */
@NoDocument
data class DeleteItem(override val commandId: GlobalId, val itemId: GlobalId) : Command {
    override val requiredPermission = Permission.INVENTORY_ITEM_MANAGE
    override val scope: Scope = Scope.Organization
    override val sharedCatalog = true
    override fun fingerprint() = itemId.toString()
}

/** Renames a storeroom or kitchen, or retires it (only when nothing is kept there and no count is waiting). */
@NoDocument
data class UpdateLocation(override val commandId: GlobalId, override val scope: Scope.Branch, val locationId: GlobalId, val name: String, val isActive: Boolean) : Command {
    override val requiredPermission = Permission.INVENTORY_LOCATION_MANAGE
    override fun fingerprint() = "$scope|$locationId|$name|$isActive"
}

/**
 * Takes back a waste, a transfer, a production or an opening stock entered by mistake, with a reason: every movement
 * of the document is reversed at its original value (the goods must still be where the document put them).
 */
@IssuesDocument(DocumentSeries.REVERSAL)
data class ReverseInventoryDocument(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val sourceType: String,
    val documentId: GlobalId,
    val date: BusinessDate,
    val reason: String,
) : Command {
    override val requiredPermission = Permission.INVENTORY_ADJUST
    override fun fingerprint() = "$scope|$sourceType|$documentId|${date.epochDay}|$reason"
}

/** A new version of how a prepared item is made; [lines] yield [outputQuantity] of it. */
@NoDocument
data class PublishPrepRecipe(
    override val commandId: GlobalId,
    val itemId: GlobalId,
    val effectiveFrom: BusinessDate,
    val outputQuantity: Quantity,
    val lines: List<RecipeLine>,
) : Command {
    override val requiredPermission = Permission.RECIPE_MANAGE
    override val scope: Scope = Scope.Organization
    override val sharedCatalog = true
    override fun fingerprint() = "$itemId|${effectiveFrom.epochDay}|${outputQuantity.micros}|" +
        lines.joinToString(";") { "${it.itemId}:${it.quantityPerPortion.micros}:${it.yieldPercent}" }
}

/** Makes [quantity] of a prepared item at a location: ingredients out at average cost, the item in at that cost. */
@IssuesDocument(DocumentSeries.PRODUCTION)
data class RecordProduction(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val locationId: GlobalId,
    val itemId: GlobalId,
    /** The planned batch: ingredients are taken for this much. */
    val quantity: Quantity,
    val date: BusinessDate,
    /** What actually came out, when it differs from [quantity] (the yield of the day); all the cost goes into it. */
    val actualOutput: Quantity? = null,
) : Command {
    override val requiredPermission = Permission.INVENTORY_PRODUCE
    override fun fingerprint() = "$scope|$locationId|$itemId|${quantity.micros}|${date.epochDay}|${actualOutput?.micros}"
}

@NoDocument
data class CreateLocation(override val commandId: GlobalId, override val scope: Scope.Branch, val name: String) : Command {
    override val requiredPermission = Permission.INVENTORY_LOCATION_MANAGE
    override fun fingerprint() = "$scope|$name"
}

/** First-time stock of a new installation, booked against capital. */
@IssuesDocument(DocumentSeries.OPENING_STOCK)
data class RecordOpeningStock(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val locationId: GlobalId,
    val lines: List<ReceiptLine>,
    val date: BusinessDate,
) : Command {
    override val requiredPermission = Permission.INVENTORY_OPENING
    override fun fingerprint() = "$scope|$locationId|${date.epochDay}|" + lines.joinToString(";") { "${it.itemId}:${it.quantity.micros}:${it.value.rial}" }
}

enum class WasteReason(val givenAway: Boolean = false) {
    SPOILAGE, EXPIRED, PREPARATION, DAMAGE, OTHER,
    /** Food given away rather than lost: booked to 6109 and shown apart from waste in usage reports. */
    COMPLIMENTARY(true), STAFF_MEAL(true), DONATION(true),
}

/**
 * Waste of one or more items ([itemId]/[quantity] and [lines]) or of dishes: [portions] of menu item [dishId] take
 * their recipe's ingredients (a returned pizza, a dropped plate). One reason and note for the document.
 */
@IssuesDocument(DocumentSeries.WASTE)
data class RecordWaste(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val locationId: GlobalId,
    val itemId: GlobalId?,
    val quantity: Quantity?,
    val reason: WasteReason,
    val note: String,
    val date: BusinessDate,
    val lines: List<IssueLine> = emptyList(),
    val dishId: GlobalId? = null,
    val portions: Quantity? = null,
) : Command {
    override val requiredPermission = Permission.INVENTORY_WASTE
    override fun fingerprint() = "$scope|$locationId|$itemId|${quantity?.micros}|$reason|$note|${date.epochDay}|" +
        lines.joinToString(";") { "${it.itemId}:${it.quantity.micros}" } + "|$dishId|${portions?.micros}"
}

@IssuesDocument(DocumentSeries.STOCK_TRANSFER)
data class TransferStock(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val fromLocationId: GlobalId,
    val toLocationId: GlobalId,
    val lines: List<IssueLine>,
    val date: BusinessDate,
    val note: String,
    /**
     * To another branch: the goods leave now and enter there only when its people confirm what arrived
     * ([ReceiveTransfer]); otherwise both ends move at once. Ignored inside one branch.
     */
    val inTransit: Boolean = false,
) : Command {
    override val requiredPermission = Permission.INVENTORY_TRANSFER
    override fun fingerprint() = "$scope|$fromLocationId|$toLocationId|${date.epochDay}|$note|$inTransit|" + lines.joinToString(";") { "${it.itemId}:${it.quantity.micros}" }
}

@NoDocument
data class DefineMenuItem(override val commandId: GlobalId, val name: String) : Command {
    override val requiredPermission = Permission.RECIPE_MANAGE
    override val scope: Scope = Scope.Organization
    override val sharedCatalog = true
    override fun fingerprint() = name
}

/** Renames a menu item or takes it off / back on the menu (inactive items are not sold or priced). */
@NoDocument
data class UpdateMenuItem(override val commandId: GlobalId, val menuItemId: GlobalId, val name: String, val isActive: Boolean) : Command {
    override val requiredPermission = Permission.RECIPE_MANAGE
    override val scope: Scope = Scope.Organization
    override val sharedCatalog = true
    override fun fingerprint() = "$menuItemId|$name|$isActive"
}

/** Puts a menu item in a category for the reports (empty: none). */
@NoDocument
data class SetMenuItemCategory(override val commandId: GlobalId, val menuItemId: GlobalId, val category: String) : Command {
    override val requiredPermission = Permission.RECIPE_MANAGE
    override val scope: Scope = Scope.Organization
    override val sharedCatalog = true
    override fun fingerprint() = "$menuItemId|$category"
}

/** Deletes a menu item that was never used (no recipe, price or sale); a used one can only be deactivated. */
@NoDocument
data class DeleteMenuItem(override val commandId: GlobalId, val menuItemId: GlobalId) : Command {
    override val requiredPermission = Permission.RECIPE_MANAGE
    override val scope: Scope = Scope.Organization
    override val sharedCatalog = true
    override fun fingerprint() = menuItemId.toString()
}

@NoDocument
data class PublishRecipe(
    override val commandId: GlobalId,
    val menuItemId: GlobalId,
    val effectiveFrom: BusinessDate,
    val lines: List<RecipeLine>,
) : Command {
    override val requiredPermission = Permission.RECIPE_MANAGE
    override val scope: Scope = Scope.Organization
    override val sharedCatalog = true
    override fun fingerprint() = "$menuItemId|${effectiveFrom.epochDay}|" + lines.joinToString(";") { "${it.itemId}:${it.quantityPerPortion.micros}:${it.yieldPercent}" }
}

class InventoryOperations(
    private val bus: CommandBus,
    private val gateway: InventoryGateway,
    private val items: ItemStore,
    private val locations: LocationStore,
    private val recipes: RecipeStore,
    /** Whether another module refers to a menu item (prices, sales); such an item is never deleted. */
    private val menuItemInUse: (GlobalId) -> Boolean = { false },
    /** Whether anything refers to an item (documents, prices, plans); such an item is never deleted. */
    private val itemReferenced: (GlobalId) -> Boolean = { true },
    private val stock: StockStore? = null,
    /** Pending stock counts at a location (block retiring it). */
    private val pendingCountAt: (GlobalId) -> Boolean = { false },
    private val documents: InventoryDocumentStore = ir.sabou.inventory.memory.InMemoryInventoryDocumentStore(),
    private val transits: TransitStore = ir.sabou.inventory.memory.InMemoryTransitStore(),
    private val requests: StockRequestStore = ir.sabou.inventory.memory.InMemoryStockRequestStore(),
) {
    private val cap get() = gateway.ownCapability

    fun createItem(c: CreateItem): CommandOutcome = bus.execute(ModuleId.INVENTORY, c) { cmd, ctx ->
        val name = cmd.name.trim()
        ensure(name.length in 2..80) { DomainError.InvalidInput("name", "نام کالا الزامی است.") }
        ensure(items.all().none { it.name == name }) { DomainError.InvalidState("ITEM", "DUPLICATE_NAME") }
        val item = Item(GlobalId.new(), name, cmd.unit, cmd.minimumStock, prepared = cmd.prepared, packs = Units.validatePacks(cmd.packs))
        items.save(item)
        ctx.audit(AuditDraft("ITEM_CREATE", "ITEM", item.id.value, name))
        item.id
    }

    fun updateItem(c: UpdateItem): CommandOutcome = bus.execute(ModuleId.INVENTORY, c) { cmd, ctx ->
        val item = items.byId(cmd.itemId) ?: throw DomainException(DomainError.NotFound("ITEM"))
        val name = cmd.name.trim()
        ensure(name.length in 2..80) { DomainError.InvalidInput("name", "نام کالا الزامی است.") }
        ensure(items.all().none { it.id != item.id && it.name == name }) { DomainError.InvalidState("ITEM", "DUPLICATE_NAME") }
        ensure(cmd.parLevel.isZero || cmd.parLevel >= cmd.minimumStock) { DomainError.InvalidInput("parLevel", "سقف سفارش باید از حد سفارش بیشتر باشد.") }
        ensure(cmd.shelf.length <= 80 && cmd.allergens.length <= 200) { DomainError.InvalidInput("text", "متن بیش از حد طولانی است.") }
        ensure(cmd.shelfLifeDays == null || cmd.shelfLifeDays in 1..3650) { DomainError.InvalidInput("shelfLife", "ماندگاری باید بین ۱ و ۳۶۵۰ روز باشد.") }
        cmd.parByWeekday?.let { byDay ->
            ensure(byDay.keys.all { it in 0..6 } && byDay.values.none { it.isZero || it.micros < 0 }) { DomainError.InvalidInput("parByWeekday", "سطح مطلوب هر روز باید بیشتر از صفر باشد.") }
            ensure(byDay.values.all { it >= cmd.minimumStock }) { DomainError.InvalidInput("parByWeekday", "سطح مطلوب هر روز نباید کمتر از حداقل موجودی باشد.") }
        }
        ensure(cmd.preferredSupplierId == null || cmd.approvedSupplierIds.isEmpty() || cmd.preferredSupplierId in cmd.approvedSupplierIds) {
            DomainError.InvalidInput("supplier", "تأمین‌کننده‌ی اصلی باید در فهرست مجاز باشد.")
        }
        val code = cmd.code?.trim() ?: item.code
        ensure(code.length <= 40 && (cmd.category?.trim()?.length ?: 0) <= 40) { DomainError.InvalidInput("code", "کد یا گروه طولانی است.") }
        ensure(code.isEmpty() || items.all().none { it.id != item.id && it.code == code }) { DomainError.InvalidState("ITEM", "DUPLICATE_CODE") }
        cmd.locationLevels?.forEach { (loc, l) ->
            ensure(locations.byId(loc) != null) { DomainError.NotFound("LOCATION") }
            ensure(l.minimum.micros >= 0 && l.par.micros >= 0 && (l.par.isZero || l.par >= l.minimum)) { DomainError.InvalidInput("levels", "سقف هر انبار باید از حداقل آن بیشتر باشد.") }
        }
        // The unit decides what every quantity means: it changes only for an item nothing was recorded with.
        val unit = cmd.unit ?: item.unit
        if (unit != item.unit) {
            val inRecipe = recipes.menuItems().any { m -> recipes.versions(m.id).any { v -> v.lines.any { it.itemId == item.id } } } ||
                items.all().any { p -> recipes.prepVersions(p.id).any { v -> p.id == item.id || v.lines.any { it.itemId == item.id } } }
            ensure(stock?.hasMovements(item.id) == false && !inRecipe && item.packs.isEmpty() && cmd.packs.isNullOrEmpty()) { DomainError.InvalidState("ITEM", "UNIT_IN_USE") }
        }
        if (item.isActive && !cmd.isActive) {
            // An inactive item can no longer leave stock (sales, waste, counts, transfers refuse it):
            // only items with nothing on hand and no current recipe may be deactivated.
            ensure(locations.all().all { gateway.balance(item.id, it.id).quantity.isZero }) { DomainError.InvalidState("ITEM", "HAS_STOCK") }
            val usedByMenu = recipes.menuItems().filter { it.isActive }.any { m -> recipes.versions(m.id).maxByOrNull { it.version }?.lines?.any { it.itemId == item.id } == true }
            val usedByPrep = items.all().filter { it.prepared && it.isActive && it.id != item.id }
                .any { p -> recipes.prepVersions(p.id).maxByOrNull { it.version }?.lines?.any { it.itemId == item.id } == true }
            ensure(!usedByMenu && !usedByPrep) { DomainError.InvalidState("ITEM", "USED_IN_RECIPE") }
        }
        val next = item.copy(
            name = name, minimumStock = cmd.minimumStock, parLevel = cmd.parLevel, shelf = cmd.shelf.trim(), allergens = cmd.allergens.trim(),
            preferredSupplierId = cmd.preferredSupplierId, approvedSupplierIds = cmd.approvedSupplierIds, isActive = cmd.isActive,
            packs = cmd.packs?.let(Units::validatePacks) ?: item.packs, shelfLifeDays = cmd.shelfLifeDays,
            parByWeekday = cmd.parByWeekday ?: item.parByWeekday,
            category = cmd.category?.trim() ?: item.category, code = code, locationLevels = cmd.locationLevels ?: item.locationLevels, unit = unit,
        )
        items.save(next)
        ctx.audit(AuditDraft("ITEM_UPDATE", "ITEM", item.id.value, name + if (unit != item.unit) ";unit=${item.unit}->$unit" else ""))
        item.id
    }

    fun deleteItem(c: DeleteItem): CommandOutcome = bus.execute(ModuleId.INVENTORY, c) { cmd, ctx ->
        val item = items.byId(cmd.itemId) ?: throw DomainException(DomainError.NotFound("ITEM"))
        val inRecipe = recipes.menuItems().any { m -> recipes.versions(m.id).any { v -> v.lines.any { it.itemId == item.id } } } ||
            items.all().any { p -> recipes.prepVersions(p.id).any { v -> p.id == item.id || v.lines.any { it.itemId == item.id } } }
        ensure(stock?.hasMovements(item.id) == false && !inRecipe && !itemReferenced(item.id)) { DomainError.InvalidState("ITEM", "IN_USE") }
        items.delete(item.id)
        ctx.audit(AuditDraft("ITEM_DELETE", "ITEM", item.id.value, "${item.name};${item.unit}"))
        item.id
    }

    fun updateLocation(c: UpdateLocation): CommandOutcome = bus.execute(ModuleId.INVENTORY, c) { cmd, ctx ->
        val location = locations.byId(cmd.locationId) ?: throw DomainException(DomainError.NotFound("LOCATION"))
        ensure(location.scope == cmd.scope) { DomainError.InvalidInput("scope", "انبار متعلق به این شعبه نیست.") }
        val name = cmd.name.trim()
        ensure(name.length in 2..80) { DomainError.InvalidInput("name", "نام انبار الزامی است.") }
        ensure(locations.all().none { it.id != location.id && it.scope == cmd.scope && it.name == name }) { DomainError.InvalidState("LOCATION", "DUPLICATE_NAME") }
        if (location.isActive && !cmd.isActive) {
            ensure(stock?.balances(location.id)?.all { it.quantity.isZero } != false) { DomainError.InvalidState("LOCATION", "HAS_STOCK") }
            ensure(!pendingCountAt(location.id)) { DomainError.InvalidState("STOCK_COUNT", "PENDING_EXISTS") }
            ensure(gateway.openShortages(setOf(location.id)).isEmpty()) { DomainError.InvalidState("LOCATION", "HAS_SHORTAGES") }
        }
        locations.save(location.copy(name = name, isActive = cmd.isActive))
        ctx.audit(AuditDraft("LOCATION_UPDATE", "LOCATION", location.id.value, "name=$name;active=${cmd.isActive}"))
        location.id
    }

    /** The document series of each inventory document type (for the reversal's number). */
    private val seriesOf = mapOf(WASTE to DocumentSeries.WASTE, COMP to DocumentSeries.WASTE, TRANSFER to DocumentSeries.STOCK_TRANSFER,
        PRODUCTION to DocumentSeries.PRODUCTION, OPENING to DocumentSeries.OPENING_STOCK)

    fun reverseDocument(c: ReverseInventoryDocument): CommandOutcome = bus.execute(ModuleId.INVENTORY, c) { cmd, ctx ->
        val series = seriesOf[cmd.sourceType] ?: throw DomainException(DomainError.InvalidInput("sourceType", "این نوع سند از اینجا برگشت نمی‌خورد."))
        val reason = cmd.reason.trim()
        ensure(reason.length in 3..200) { DomainError.InvalidInput("reason", "دلیل برگشت را بنویسید.") }
        // The document must belong to this branch (a transfer: its source branch).
        val first = stock?.movementsBySource(cmd.sourceType, cmd.documentId)?.firstOrNull { it.reversalOf == null } ?: throw DomainException(DomainError.NotFound("STOCK_MOVEMENT"))
        val from = locations.byId(first.locationId) ?: throw DomainException(DomainError.NotFound("LOCATION"))
        ensure(from.scope == cmd.scope) { DomainError.InvalidInput("scope", "این سند متعلق به این شعبه نیست.") }
        // Goods the other branch already counted in are no longer the sender's to take back.
        val transit = if (cmd.sourceType == TRANSFER) transits.byId(cmd.documentId) else null
        ensure(transit == null || transit.status == StockTransit.Status.IN_TRANSIT) { DomainError.InvalidState("TRANSFER", "ALREADY_RECEIVED") }
        gateway.reverseDocument(ctx, cap, cmd.sourceType, cmd.documentId, cmd.date, reason)
        transit?.let { transits.save(it.copy(status = StockTransit.Status.CANCELLED)) }
        documents.byId(cmd.documentId)?.let { documents.save(it.copy(reversedReason = reason)) }
        ctx.number(DocumentSeries.REVERSAL, cmd.date, cmd.commandId, reverses = series to cmd.documentId)
        ctx.audit(AuditDraft("INVENTORY_REVERSE", cmd.sourceType, cmd.documentId.value, reason))
        cmd.documentId
    }

    fun publishPrepRecipe(c: PublishPrepRecipe): CommandOutcome = bus.execute(ModuleId.INVENTORY, c) { cmd, ctx ->
        val item = gateway.item(cmd.itemId)
        ensure(item.prepared) { DomainError.InvalidState("ITEM", "NOT_PREPARED") }
        ensure(!cmd.outputQuantity.isZero) { DomainError.InvalidInput("output", "مقدار تولید باید بیشتر از صفر باشد.") }
        validateLines(cmd.lines)
        ensure(cmd.lines.none { it.itemId == item.id }) { DomainError.InvalidInput("lines", "کالای آماده نمی‌تواند ماده‌ی اولیه‌ی خودش باشد.") }
        // No recipe may (indirectly) use the item it makes: production and costing would never end.
        PrepGraph(recipes) { items.byId(it)?.prepared == true }.cycleWith(item.id, cmd.lines, cmd.effectiveFrom)?.let { cycle ->
            val names = cycle.joinToString(" ← ") { items.byId(it)?.name ?: "؟" }
            throw DomainException(DomainError.InvalidInput("lines", "این رسپی چرخه می‌سازد: $names"))
        }
        val existing = recipes.prepVersions(item.id)
        ensure(existing.none { it.effectiveFrom >= cmd.effectiveFrom }) { DomainError.InvalidState("RECIPE", "NOT_AFTER_LATEST_VERSION") }
        val version = PrepRecipe(GlobalId.new(), item.id, (existing.maxOfOrNull { it.version } ?: 0) + 1, cmd.effectiveFrom, cmd.outputQuantity, cmd.lines)
        recipes.savePrepVersion(version)
        ctx.audit(AuditDraft("PREP_RECIPE_PUBLISH", "RECIPE", version.id.value, "item=${item.id};v=${version.version}"))
        version.id
    }

    /** Ingredients leave at their average cost and the prepared item enters at exactly that total: value is conserved. */
    fun produce(c: RecordProduction): CommandOutcome = bus.execute(ModuleId.INVENTORY, c) { cmd, ctx ->
        val location = requireLocationScope(cmd.locationId, cmd.scope)
        val item = gateway.item(cmd.itemId)
        ensure(item.prepared) { DomainError.InvalidState("ITEM", "NOT_PREPARED") }
        ensure(!cmd.quantity.isZero) { DomainError.InvalidInput("quantity", "مقدار تولید باید بیشتر از صفر باشد.") }
        val output = cmd.actualOutput ?: cmd.quantity
        ensure(!output.isZero) { DomainError.InvalidInput("actualOutput", "مقدار به‌دست‌آمده باید بیشتر از صفر باشد.") }
        val requirements = RecipeBook(recipes).prepRequirements(item.id, cmd.date, cmd.quantity)
        // A quantity so small that every ingredient rounds to nothing would create stock for free.
        val recipeLines = RecipeBook(recipes).prepOn(item.id, cmd.date).lines.size
        ensure(requirements.size == recipeLines) { DomainError.InvalidInput("quantity", "مقدار تولید آن‌قدر کم است که مواد آن قابل اندازه‌گیری نیست.") }
        val priced = requirements.map { gateway.item(it.itemId); IssuedCost(it.itemId, it.quantity, gateway.valueOf(gateway.balance(it.itemId, location.id), it.quantity)) }
        val total = Money.sum(priced.map { it.cost })
        val docId = GlobalId.new()
        ctx.number(DocumentSeries.PRODUCTION, cmd.date, docId)
        val source = SourceDocument(ModuleId.INVENTORY, PRODUCTION, docId)
        // One account, one branch: no journal is needed, inventory 1301 is unchanged.
        priced.forEach { gateway.stockOut(ctx, it.itemId, location, it.quantity, it.cost, MovementKind.PRODUCTION_OUT, cmd.date, null, source, null) }
        // A batch that came out short or long carries all its cost: the unit cost of what came out shows the yield.
        gateway.stockIn(ctx, item.id, location, output, total, MovementKind.PRODUCTION_IN, cmd.date, null, source, null,
            listOfNotNull(gateway.shelfLifeEnd(item, cmd.date)?.let { LotPart(it, output) }))
        gateway.settleShortages(ctx, gateway.ownCapability, location, item.id, cmd.date)
        documents.save(InventoryDocument(docId, PRODUCTION, cmd.scope, cmd.date, planned = cmd.quantity.takeIf { output != cmd.quantity }))
        ctx.audit(AuditDraft("PRODUCTION", "ITEM", item.id.value, "loc=${location.id};planned=${cmd.quantity.micros};qty=${output.micros};cost=${total.rial}"))
        docId
    }

    private fun validateLines(lines: List<RecipeLine>) {
        ensure(lines.isNotEmpty() && lines.none { it.quantityPerPortion.isZero }) { DomainError.InvalidInput("lines", "رسپی حداقل یک ماده با مقدار مثبت لازم دارد.") }
        ensure(lines.map { it.itemId }.distinct().size == lines.size) { DomainError.InvalidInput("lines", "هر ماده فقط یک‌بار بیاید.") }
        ensure(lines.all { it.yieldPercent in 1..100 }) { DomainError.InvalidInput("yield", "درصد بازده باید بین ۱ و ۱۰۰ باشد.") }
        lines.forEach { gateway.item(it.itemId) }
    }

    fun createLocation(c: CreateLocation): CommandOutcome = bus.execute(ModuleId.INVENTORY, c) { cmd, ctx ->
        val name = cmd.name.trim()
        ensure(name.length in 2..80) { DomainError.InvalidInput("name", "نام انبار الزامی است.") }
        ensure(locations.all().none { it.scope == cmd.scope && it.name == name }) { DomainError.InvalidState("LOCATION", "DUPLICATE_NAME") }
        val location = Location(GlobalId.new(), name, cmd.scope)
        locations.save(location)
        ctx.audit(AuditDraft("LOCATION_CREATE", "LOCATION", location.id.value, name))
        location.id
    }

    fun openingStock(c: RecordOpeningStock): CommandOutcome = bus.execute(ModuleId.INVENTORY, c) { cmd, ctx ->
        requireLocationScope(cmd.locationId, cmd.scope)
        val docId = GlobalId.new()
        ctx.number(DocumentSeries.OPENING_STOCK, cmd.date, docId)
        documents.save(InventoryDocument(docId, OPENING, cmd.scope, cmd.date))
        val total = Money.sum(cmd.lines.map { it.value })
        gateway.receive(ctx, cap, cmd.locationId, cmd.lines, cmd.date, OPENING, docId, "موجودی اول دوره",
            listOf(LineDraft(StandardAccounts.CAPITAL, credit = total, memo = "موجودی اول دوره", by = cap)))
        docId
    }

    fun waste(c: RecordWaste): CommandOutcome = bus.execute(ModuleId.INVENTORY, c) { cmd, ctx ->
        val location = requireLocationScope(cmd.locationId, cmd.scope)
        ensure(cmd.reason != WasteReason.OTHER || cmd.note.trim().length >= 3) { DomainError.InvalidInput("note", "برای «سایر» توضیح لازم است.") }
        ensure(cmd.note.length <= 200) { DomainError.InvalidInput("note", "توضیح طولانی است.") }
        ensure((cmd.itemId == null) == (cmd.quantity == null) && (cmd.dishId == null) == (cmd.portions == null)) { DomainError.InvalidInput("lines", "کالا و مقدار با هم لازم‌اند.") }
        val dish = cmd.dishId?.let { d ->
            ensure(!cmd.portions!!.isZero) { DomainError.InvalidInput("portions", "تعداد پرس باید بیشتر از صفر باشد.") }
            RecipeBook(recipes).requirements(d, cmd.date, cmd.portions)
        }.orEmpty()
        val all = listOfNotNull(cmd.itemId?.let { IssueLine(it, cmd.quantity!!) }) + cmd.lines + dish
        val merged = all.groupBy { it.itemId }.map { (id, rows) -> IssueLine(id, rows.fold(Quantity.ZERO) { a, r -> a + r.quantity }) }.filter { !it.quantity.isZero }
        ensure(merged.isNotEmpty() && all.none { it.quantity.isZero }) { DomainError.InvalidInput("quantity", "مقدار ضایعات باید بیشتر از صفر باشد.") }
        val priced = merged.map { gateway.item(it.itemId); IssuedCost(it.itemId, it.quantity, gateway.valueOf(gateway.balance(it.itemId, location.id), it.quantity)) }
        val value = Money.sum(priced.map { it.cost })
        val docId = GlobalId.new()
        ctx.number(DocumentSeries.WASTE, cmd.date, docId)
        val (type, account, title) = if (cmd.reason.givenAway) Triple(COMP, StandardAccounts.COMPS, "پذیرایی و اهدایی") else Triple(WASTE, StandardAccounts.WASTE, "ضایعات")
        val dishName = cmd.dishId?.let { recipes.menuItem(it)?.name }?.let { " · $it × ${cmd.portions!!.micros / Quantity.SCALE}" }.orEmpty()
        val journal = if (value.isZero) null else gateway.postOwn(ctx, JournalDraft(cmd.date, location.scope, type, docId, "$title: ${cmd.reason}$dishName", listOf(
            LineDraft(account, debit = value, memo = cmd.note, by = cap),
            LineDraft(StandardAccounts.INVENTORY, credit = value, memo = location.name, by = cap),
        )))
        priced.forEach { gateway.stockOut(ctx, it.itemId, location, it.quantity, it.cost, MovementKind.WASTE, cmd.date, journal?.id, SourceDocument(ModuleId.INVENTORY, type, docId), null) }
        documents.save(InventoryDocument(docId, type, cmd.scope, cmd.date, note = cmd.note.trim(), reason = cmd.reason.name, dish = dishName.removePrefix(" · ")))
        ctx.audit(AuditDraft("WASTE", type, docId.value, "reason=${cmd.reason};lines=${priced.size};value=${value.rial};note=${cmd.note.trim()}$dishName"))
        docId
    }

    /**
     * Moves stock between locations at the source's average cost. Between two branches, each
     * branch's books are balanced through the inter-branch account (AUD-011).
     */
    fun transfer(c: TransferStock): CommandOutcome = bus.execute(ModuleId.INVENTORY, c) { cmd, ctx ->
        val from = requireLocationScope(cmd.fromLocationId, cmd.scope)
        moveStock(ctx, from, cmd.toLocationId, cmd.lines, cmd.date, cmd.note, cmd.inTransit)
    }

    private fun moveStock(ctx: ir.sabou.platform.CommandContext, from: Location, toLocationId: GlobalId, lines: List<IssueLine>, date: BusinessDate, note: String, inTransit: Boolean): GlobalId {
        ensure(from.id != toLocationId) { DomainError.InvalidInput("location", "مبدأ و مقصد یکسان است.") }
        ensure(note.length <= 200) { DomainError.InvalidInput("note", "توضیح طولانی است.") }
        val to = gateway.location(toLocationId)
        ensure(to.isActive) { DomainError.InvalidState("LOCATION", "INACTIVE") }
        val transit = inTransit && from.scope != to.scope
        // Sent in transit, the receiving branch's books are touched only by its own people on receipt.
        if (!transit) ctx.requireScope(to.scope)
        val merged = lines.groupBy { it.itemId }.map { (id, rows) -> IssueLine(id, rows.fold(Quantity.ZERO) { a, r -> a + r.quantity }) }
        ensure(merged.isNotEmpty() && merged.none { it.quantity.isZero }) { DomainError.InvalidInput("lines", "مقدار انتقال باید بیشتر از صفر باشد.") }
        val priced = merged.map { gateway.item(it.itemId); IssuedCost(it.itemId, it.quantity, gateway.valueOf(gateway.balance(it.itemId, from.id), it.quantity)) }
        val total = Money.sum(priced.map { it.cost })
        val docId = GlobalId.new()
        ctx.number(DocumentSeries.STOCK_TRANSFER, date, docId)
        val source = SourceDocument(ModuleId.INVENTORY, TRANSFER, docId)
        documents.save(InventoryDocument(docId, TRANSFER, from.scope, date, note = note.trim()))
        var outJournal: GlobalId? = null
        var inJournal: GlobalId? = null
        if (from.scope != to.scope && !total.isZero) {
            outJournal = gateway.postOwn(ctx, JournalDraft(date, from.scope, TRANSFER, docId, "انتقال کالا به ${to.name}", listOf(
                LineDraft(StandardAccounts.INTER_BRANCH, debit = total, memo = to.name, by = cap),
                LineDraft(StandardAccounts.INVENTORY, credit = total, memo = from.name, by = cap),
            ))).id
            if (!transit) inJournal = gateway.postOwn(ctx, JournalDraft(date, to.scope, TRANSFER, docId, "دریافت کالا از ${from.name}", listOf(
                LineDraft(StandardAccounts.INVENTORY, debit = total, memo = to.name, by = cap),
                LineDraft(StandardAccounts.INTER_BRANCH, credit = total, memo = from.name, by = cap),
            ))).id
        }
        val sent = priced.map {
            val carried = gateway.stockOut(ctx, it.itemId, from, it.quantity, it.cost, MovementKind.TRANSFER_OUT, date, outJournal, source, null)
            if (!transit) gateway.stockIn(ctx, it.itemId, to, it.quantity, it.cost, MovementKind.TRANSFER_IN, date, inJournal, source, null, carried)
            TransitLine(it.itemId, it.quantity, it.cost, carried)
        }
        if (transit) {
            transits.save(StockTransit(docId, from.scope, from.id, to.scope, to.id, date, sent))
            ctx.audit(AuditDraft("TRANSFER_SENT", TRANSFER, docId.value, "to=${to.id};lines=${sent.size};value=${total.rial}"))
        } else {
            priced.forEach { gateway.settleShortages(ctx, gateway.ownCapability, to, it.itemId, date) }
        }
        return docId
    }

    /**
     * The receiving branch counts in a transfer sent in transit: what arrived enters at the sender's cost (in proportion),
     * and the value of what did not arrive is this branch's inventory variance (both against the inter-branch account,
     * which the sender already debited with the whole value).
     */
    fun receiveTransfer(c: ReceiveTransfer): CommandOutcome = bus.execute(ModuleId.INVENTORY, c) { cmd, ctx ->
        val t = transits.byId(cmd.transferId) ?: throw DomainException(DomainError.NotFound("TRANSFER"))
        ensure(t.toScope == cmd.scope) { DomainError.InvalidInput("scope", "این انتقال برای این شعبه نیست.") }
        ensure(t.status == StockTransit.Status.IN_TRANSIT) { DomainError.InvalidState("TRANSFER", if (t.status == StockTransit.Status.RECEIVED) "ALREADY_RECEIVED" else "CANCELLED") }
        ensure(cmd.date >= t.date) { DomainError.InvalidInput("date", "تاریخ دریافت نمی‌تواند پیش از ارسال باشد.") }
        ensure(cmd.note.length <= 200) { DomainError.InvalidInput("note", "توضیح طولانی است.") }
        val sentItems = t.lines.map { it.itemId }.toSet()
        ensure(cmd.received.all { it.itemId in sentItems } && cmd.received.map { it.itemId }.distinct().size == cmd.received.size) { DomainError.InvalidInput("received", "کالای دریافتی در این انتقال نیست.") }
        val counted = cmd.received.associate { it.itemId to it.quantity }
        val to = gateway.location(t.toLocationId)
        val from = gateway.location(t.fromLocationId)
        val lines = t.lines.map { l ->
            val got = counted[l.itemId] ?: l.quantity
            ensure(got.micros >= 0 && got <= l.quantity) { DomainError.InvalidInput("received", "مقدار دریافتی بیشتر از ارسالی است.") }
            l.copy(received = got)
        }
        val value = lines.map { l -> if (l.received == l.quantity) l.value else Money.of(ir.sabou.kernel.Ratio.mulDiv(l.value.rial, l.received!!.micros, l.quantity.micros)) }
        val inValue = Money.sum(value)
        val missing = Money.sum(lines.map { it.value }) - inValue
        val receiptId = GlobalId.new()
        ctx.number(DocumentSeries.STOCK_TRANSFER, cmd.date, receiptId)
        val source = SourceDocument(ModuleId.INVENTORY, TRANSFER_RECEIPT, receiptId)
        val journal = if (inValue.isZero && missing.isZero) null else gateway.postOwn(ctx, JournalDraft(cmd.date, to.scope, TRANSFER_RECEIPT, receiptId, "دریافت کالا از ${from.name}", listOfNotNull(
            LineDraft(StandardAccounts.INVENTORY, debit = inValue, memo = to.name, by = cap).takeIf { !inValue.isZero },
            LineDraft(StandardAccounts.INVENTORY_VARIANCE, debit = missing, memo = "کسری در راه", by = cap).takeIf { !missing.isZero },
            LineDraft(StandardAccounts.INTER_BRANCH, credit = inValue + missing, memo = from.name, by = cap),
        ))).id
        lines.zip(value).filter { !it.first.received!!.isZero }.forEach { (l, v) ->
            gateway.stockIn(ctx, l.itemId, to, l.received!!, v, MovementKind.TRANSFER_IN, cmd.date, journal, source, null, firstParts(l.lots, l.received))
            gateway.settleShortages(ctx, gateway.ownCapability, to, l.itemId, cmd.date)
        }
        documents.save(InventoryDocument(receiptId, TRANSFER_RECEIPT, cmd.scope, cmd.date, note = cmd.note.trim()))
        transits.save(t.copy(lines = lines, status = StockTransit.Status.RECEIVED, receivedOn = cmd.date, receivedBy = ctx.actor.displayName, receiptId = receiptId))
        ctx.audit(AuditDraft("TRANSFER_RECEIVE", TRANSFER, t.id.value, "receipt=$receiptId;value=${inValue.rial};missing=${missing.rial}"))
        receiptId
    }

    /** The earliest-expiring parts of [lots] adding up to at most [quantity]. */
    private fun firstParts(lots: List<LotPart>, quantity: Quantity): List<LotPart> {
        var left = quantity
        return lots.sortedBy { it.expiresOn }.mapNotNull { p ->
            val take = if (p.quantity < left) p.quantity else left
            left -= take
            if (take.isZero) null else LotPart(p.expiresOn, take)
        }
    }

    fun requestStock(c: RequestStock): CommandOutcome = bus.execute(ModuleId.INVENTORY, c) { cmd, ctx ->
        val to = requireLocationScope(cmd.toLocationId, cmd.scope)
        val from = gateway.location(cmd.fromLocationId)
        ensure(from.id != to.id) { DomainError.InvalidInput("location", "مبدأ و مقصد یکسان است.") }
        ensure(from.isActive && to.isActive) { DomainError.InvalidState("LOCATION", "INACTIVE") }
        ensure(cmd.note.length <= 200) { DomainError.InvalidInput("note", "توضیح طولانی است.") }
        val merged = cmd.lines.groupBy { it.itemId }.map { (id, rows) -> IssueLine(id, rows.fold(Quantity.ZERO) { a, r -> a + r.quantity }) }
        ensure(merged.isNotEmpty() && merged.none { it.quantity.isZero }) { DomainError.InvalidInput("lines", "مقدار درخواست باید بیشتر از صفر باشد.") }
        merged.forEach { gateway.item(it.itemId) }
        val request = StockRequest(GlobalId.new(), cmd.scope, from.scope, from.id, to.id, merged, cmd.note.trim(), cmd.date, ctx.actor.displayName, ctx.actor.userId)
        requests.save(request)
        ctx.audit(AuditDraft("STOCK_REQUEST", "STOCK_REQUEST", request.id.value, "from=${from.id};to=${to.id};lines=${merged.size}"))
        request.id
    }

    fun fulfilRequest(c: FulfilStockRequest): CommandOutcome = bus.execute(ModuleId.INVENTORY, c) { cmd, ctx ->
        val r = requests.byId(cmd.requestId) ?: throw DomainException(DomainError.NotFound("STOCK_REQUEST"))
        ensure(r.fromScope == cmd.scope) { DomainError.InvalidInput("scope", "این درخواست از انبار این شعبه نیست.") }
        ensure(r.status == StockRequest.Status.OPEN) { DomainError.InvalidState("STOCK_REQUEST", "NOT_OPEN") }
        val from = requireLocationScope(r.fromLocationId, cmd.scope)
        val docId = moveStock(ctx, from, r.toLocationId, cmd.lines, cmd.date, "درخواست ${r.requestedBy}" + if (r.note.isEmpty()) "" else ": ${r.note}", inTransit = true)
        requests.save(r.copy(status = StockRequest.Status.SENT, handledBy = ctx.actor.displayName, transferId = docId))
        ctx.audit(AuditDraft("STOCK_REQUEST_SEND", "STOCK_REQUEST", r.id.value, "transfer=$docId"))
        docId
    }

    fun closeRequest(c: CloseStockRequest): CommandOutcome = bus.execute(ModuleId.INVENTORY, c) { cmd, ctx ->
        val r = requests.byId(cmd.requestId) ?: throw DomainException(DomainError.NotFound("STOCK_REQUEST"))
        ensure(r.status == StockRequest.Status.OPEN) { DomainError.InvalidState("STOCK_REQUEST", "NOT_OPEN") }
        val reason = cmd.reason.trim()
        ensure(reason.length <= 200) { DomainError.InvalidInput("reason", "توضیح طولانی است.") }
        if (cmd.cancel) {
            ensure(r.scope == cmd.scope) { DomainError.InvalidInput("scope", "این درخواست از این شعبه نیست.") }
            ensure(r.requesterId == ctx.actor.userId || ctx.actor.role.allows(Permission.INVENTORY_TRANSFER)) { DomainError.PermissionDenied(Permission.INVENTORY_TRANSFER.name) }
        } else {
            ensure(r.fromScope == cmd.scope) { DomainError.InvalidInput("scope", "این درخواست از انبار این شعبه نیست.") }
            ensure(ctx.actor.role.allows(Permission.INVENTORY_TRANSFER)) { DomainError.PermissionDenied(Permission.INVENTORY_TRANSFER.name) }
            ensure(reason.length >= 3) { DomainError.InvalidInput("reason", "دلیل رد را بنویسید.") }
        }
        requests.save(r.copy(status = if (cmd.cancel) StockRequest.Status.CANCELLED else StockRequest.Status.REJECTED, handledBy = ctx.actor.displayName, rejectReason = reason))
        ctx.audit(AuditDraft(if (cmd.cancel) "STOCK_REQUEST_CANCEL" else "STOCK_REQUEST_REJECT", "STOCK_REQUEST", r.id.value, reason))
        r.id
    }

    fun defineMenuItem(c: DefineMenuItem): CommandOutcome = bus.execute(ModuleId.INVENTORY, c) { cmd, ctx ->
        val name = cmd.name.trim()
        ensure(name.length in 2..80) { DomainError.InvalidInput("name", "نام آیتم منو الزامی است.") }
        ensure(recipes.menuItems().none { it.name == name }) { DomainError.InvalidInput("name", "آیتمی با این نام در منو هست.") }
        val menuItem = MenuItem(GlobalId.new(), name)
        recipes.saveMenuItem(menuItem)
        ctx.audit(AuditDraft("MENU_ITEM_CREATE", "MENU_ITEM", menuItem.id.value, name))
        menuItem.id
    }

    fun updateMenuItem(c: UpdateMenuItem): CommandOutcome = bus.execute(ModuleId.INVENTORY, c) { cmd, ctx ->
        val item = recipes.menuItem(cmd.menuItemId) ?: throw DomainException(DomainError.NotFound("MENU_ITEM"))
        val name = cmd.name.trim()
        ensure(name.length in 2..80) { DomainError.InvalidInput("name", "نام آیتم منو الزامی است.") }
        ensure(recipes.menuItems().none { it.id != item.id && it.name == name }) { DomainError.InvalidInput("name", "آیتمی با این نام در منو هست.") }
        recipes.saveMenuItem(item.copy(name = name, isActive = cmd.isActive))
        ctx.audit(AuditDraft("MENU_ITEM_UPDATE", "MENU_ITEM", item.id.value, "name=$name;active=${cmd.isActive}"))
        item.id
    }

    fun setMenuItemCategory(c: SetMenuItemCategory): CommandOutcome = bus.execute(ModuleId.INVENTORY, c) { cmd, ctx ->
        val item = recipes.menuItem(cmd.menuItemId) ?: throw DomainException(DomainError.NotFound("MENU_ITEM"))
        val category = cmd.category.trim()
        ensure(category.length <= 40) { DomainError.InvalidInput("category", "نام دسته حداکثر ۴۰ حرف است.") }
        recipes.saveMenuItem(item.copy(category = category))
        ctx.audit(AuditDraft("MENU_ITEM_CATEGORY", "MENU_ITEM", item.id.value, category))
        item.id
    }

    fun deleteMenuItem(c: DeleteMenuItem): CommandOutcome = bus.execute(ModuleId.INVENTORY, c) { cmd, ctx ->
        val item = recipes.menuItem(cmd.menuItemId) ?: throw DomainException(DomainError.NotFound("MENU_ITEM"))
        ensure(recipes.versions(item.id).isEmpty() && !menuItemInUse(item.id)) { DomainError.InvalidState("MENU_ITEM", "IN_USE") }
        recipes.deleteMenuItem(item.id)
        ctx.audit(AuditDraft("MENU_ITEM_DELETE", "MENU_ITEM", item.id.value, item.name))
        item.id
    }

    /** Recipes are versioned: a new version applies from its date and never alters past sales. */
    fun publishRecipe(c: PublishRecipe): CommandOutcome = bus.execute(ModuleId.INVENTORY, c) { cmd, ctx ->
        recipes.menuItem(cmd.menuItemId) ?: throw DomainException(DomainError.NotFound("MENU_ITEM"))
        validateLines(cmd.lines)
        val existing = recipes.versions(cmd.menuItemId)
        ensure(existing.none { it.effectiveFrom >= cmd.effectiveFrom }) { DomainError.InvalidState("RECIPE", "NOT_AFTER_LATEST_VERSION") }
        val version = RecipeVersion(GlobalId.new(), cmd.menuItemId, (existing.maxOfOrNull { it.version } ?: 0) + 1, cmd.effectiveFrom, cmd.lines)
        recipes.saveVersion(version)
        ctx.audit(AuditDraft("RECIPE_PUBLISH", "RECIPE", version.id.value, "menu=${cmd.menuItemId};v=${version.version}"))
        version.id
    }

    private fun requireLocationScope(locationId: GlobalId, scope: Scope): Location {
        val location = gateway.location(locationId)
        ensure(location.scope == scope) { DomainError.InvalidInput("scope", "محدوده فرمان با انبار یکسان نیست.") }
        return location
    }

    companion object {
        const val OPENING = "INVENTORY_OPENING"
        const val WASTE = "INVENTORY_WASTE"
        const val COMP = "INVENTORY_COMP"
        const val COUNT = "INVENTORY_COUNT"
        const val TRANSFER = "INVENTORY_TRANSFER"
        /** The receiving branch's side of a transfer sent in transit. */
        const val TRANSFER_RECEIPT = "INVENTORY_TRANSFER_RECEIPT"
        const val PRODUCTION = "INVENTORY_PRODUCTION"
    }
}

/** Turns menu sales into ingredient requirements using the recipe version in force on the sale date. */
class RecipeBook(private val recipes: RecipeStore) {
    fun menuItem(id: GlobalId): MenuItem? = recipes.menuItem(id)

    /** Whether the menu item has a recipe in force on [date] (one without is sold without taking stock). */
    fun hasRecipeOn(menuItemId: GlobalId, date: BusinessDate): Boolean = recipes.versions(menuItemId).any { it.effectiveFrom <= date }

    fun versionOn(menuItemId: GlobalId, date: BusinessDate): RecipeVersion =
        recipes.versions(menuItemId).filter { it.effectiveFrom <= date }.maxByOrNull { it.effectiveFrom }
            ?: throw DomainException(DomainError.InvalidState("RECIPE", "NO_VERSION_ON_DATE"))

    /** Stock taken for [portions] of a menu item, including each ingredient's preparation loss (yield). */
    fun requirements(menuItemId: GlobalId, date: BusinessDate, portions: Quantity): List<IssueLine> =
        versionOn(menuItemId, date).lines.map { IssueLine(it.itemId, it.grossFor(portions)) }

    fun prepOn(itemId: GlobalId, date: BusinessDate): PrepRecipe =
        recipes.prepVersions(itemId).filter { it.effectiveFrom <= date }.maxByOrNull { it.effectiveFrom }
            ?: throw DomainException(DomainError.InvalidState("RECIPE", "NO_VERSION_ON_DATE"))

    /** Ingredients for producing [quantity] of a prepared item (its recipe scaled to that output). */
    fun prepRequirements(itemId: GlobalId, date: BusinessDate, quantity: Quantity): List<IssueLine> {
        val prep = prepOn(itemId, date)
        val batches = Quantity.of(ir.sabou.kernel.Ratio.mulDiv(quantity.micros, Quantity.SCALE, prep.outputQuantity.micros))
        return prep.lines.map { IssueLine(it.itemId, it.grossFor(batches)) }.filter { !it.quantity.isZero }
    }
}
