package ir.sabou.inventory

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

data class CreateItem(
    override val commandId: GlobalId,
    val name: String,
    val unit: StockUnit,
    val minimumStock: Quantity,
    val prepared: Boolean = false,
) : Command {
    override val requiredPermission = Permission.INVENTORY_ITEM_MANAGE
    // The item catalogue is shared by all branches.
    override val scope: Scope = Scope.Organization
    override val sharedCatalog = true
    override fun fingerprint() = "$name|$unit|${minimumStock.micros}|$prepared"
}

/** Edits an item's ordering and storage details. The unit never changes (it would revalue history). */
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
) : Command {
    override val requiredPermission = Permission.INVENTORY_ITEM_MANAGE
    override val scope: Scope = Scope.Organization
    override val sharedCatalog = true
    override fun fingerprint() = "$itemId|$name|${minimumStock.micros}|${parLevel.micros}|$shelf|$allergens|$preferredSupplierId|" +
        approvedSupplierIds.map { it.value }.sorted().joinToString(",") + "|$isActive"
}

/** A new version of how a prepared item is made; [lines] yield [outputQuantity] of it. */
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
data class RecordProduction(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val locationId: GlobalId,
    val itemId: GlobalId,
    val quantity: Quantity,
    val date: BusinessDate,
) : Command {
    override val requiredPermission = Permission.INVENTORY_PRODUCE
    override fun fingerprint() = "$scope|$locationId|$itemId|${quantity.micros}|${date.epochDay}"
}

data class CreateLocation(override val commandId: GlobalId, override val scope: Scope.Branch, val name: String) : Command {
    override val requiredPermission = Permission.INVENTORY_LOCATION_MANAGE
    override fun fingerprint() = "$scope|$name"
}

/** First-time stock of a new installation, booked against capital. */
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

enum class WasteReason { SPOILAGE, EXPIRED, PREPARATION, DAMAGE, OTHER }

data class RecordWaste(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val locationId: GlobalId,
    val itemId: GlobalId,
    val quantity: Quantity,
    val reason: WasteReason,
    val note: String,
    val date: BusinessDate,
) : Command {
    override val requiredPermission = Permission.INVENTORY_WASTE
    override fun fingerprint() = "$scope|$locationId|$itemId|${quantity.micros}|$reason|$note|${date.epochDay}"
}

data class CountLine(val itemId: GlobalId, val counted: Quantity)

/** Physical count: each counted quantity replaces the book quantity; the difference goes to 6106. */
data class PostStockCount(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val locationId: GlobalId,
    val lines: List<CountLine>,
    val date: BusinessDate,
) : Command {
    override val requiredPermission = Permission.INVENTORY_COUNT
    override fun fingerprint() = "$scope|$locationId|${date.epochDay}|" + lines.joinToString(";") { "${it.itemId}:${it.counted.micros}" }
}

data class TransferStock(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val fromLocationId: GlobalId,
    val toLocationId: GlobalId,
    val lines: List<IssueLine>,
    val date: BusinessDate,
    val note: String,
) : Command {
    override val requiredPermission = Permission.INVENTORY_TRANSFER
    override fun fingerprint() = "$scope|$fromLocationId|$toLocationId|${date.epochDay}|$note|" + lines.joinToString(";") { "${it.itemId}:${it.quantity.micros}" }
}

data class DefineMenuItem(override val commandId: GlobalId, val name: String) : Command {
    override val requiredPermission = Permission.RECIPE_MANAGE
    override val scope: Scope = Scope.Organization
    override val sharedCatalog = true
    override fun fingerprint() = name
}

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
) {
    private val cap get() = gateway.ownCapability

    fun createItem(c: CreateItem): CommandOutcome = bus.execute(ModuleId.INVENTORY, c) { cmd, ctx ->
        val name = cmd.name.trim()
        ensure(name.length in 2..80) { DomainError.InvalidInput("name", "نام کالا الزامی است.") }
        ensure(items.all().none { it.name == name }) { DomainError.InvalidState("ITEM", "DUPLICATE_NAME") }
        val item = Item(GlobalId.new(), name, cmd.unit, cmd.minimumStock, prepared = cmd.prepared)
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
        ensure(cmd.preferredSupplierId == null || cmd.approvedSupplierIds.isEmpty() || cmd.preferredSupplierId in cmd.approvedSupplierIds) {
            DomainError.InvalidInput("supplier", "تأمین‌کننده‌ی اصلی باید در فهرست مجاز باشد.")
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
        )
        items.save(next)
        ctx.audit(AuditDraft("ITEM_UPDATE", "ITEM", item.id.value, name))
        item.id
    }

    fun publishPrepRecipe(c: PublishPrepRecipe): CommandOutcome = bus.execute(ModuleId.INVENTORY, c) { cmd, ctx ->
        val item = gateway.item(cmd.itemId)
        ensure(item.prepared) { DomainError.InvalidState("ITEM", "NOT_PREPARED") }
        ensure(!cmd.outputQuantity.isZero) { DomainError.InvalidInput("output", "مقدار تولید باید بیشتر از صفر باشد.") }
        validateLines(cmd.lines)
        ensure(cmd.lines.none { it.itemId == item.id }) { DomainError.InvalidInput("lines", "کالای آماده نمی‌تواند ماده‌ی اولیه‌ی خودش باشد.") }
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
        val requirements = RecipeBook(recipes).prepRequirements(item.id, cmd.date, cmd.quantity)
        // A quantity so small that every ingredient rounds to nothing would create stock for free.
        val recipeLines = RecipeBook(recipes).prepOn(item.id, cmd.date).lines.size
        ensure(requirements.size == recipeLines) { DomainError.InvalidInput("quantity", "مقدار تولید آن‌قدر کم است که مواد آن قابل اندازه‌گیری نیست.") }
        val priced = requirements.map { gateway.item(it.itemId); IssuedCost(it.itemId, it.quantity, gateway.valueOf(gateway.balance(it.itemId, location.id), it.quantity)) }
        val total = Money.sum(priced.map { it.cost })
        val docId = GlobalId.new()
        val source = SourceDocument(ModuleId.INVENTORY, PRODUCTION, docId)
        // One account, one branch: no journal is needed, inventory 1301 is unchanged.
        priced.forEach { gateway.stockOut(ctx, it.itemId, location, it.quantity, it.cost, MovementKind.PRODUCTION_OUT, cmd.date, null, source, null) }
        gateway.stockIn(ctx, item.id, location, cmd.quantity, total, MovementKind.PRODUCTION_IN, cmd.date, null, source, null)
        ctx.audit(AuditDraft("PRODUCTION", "ITEM", item.id.value, "loc=${location.id};qty=${cmd.quantity.micros};cost=${total.rial}"))
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
        val total = Money.sum(cmd.lines.map { it.value })
        gateway.receive(ctx, cap, cmd.locationId, cmd.lines, cmd.date, OPENING, docId, "موجودی اول دوره",
            listOf(LineDraft(StandardAccounts.CAPITAL, credit = total, memo = "موجودی اول دوره", by = cap)))
        docId
    }

    fun waste(c: RecordWaste): CommandOutcome = bus.execute(ModuleId.INVENTORY, c) { cmd, ctx ->
        val location = requireLocationScope(cmd.locationId, cmd.scope)
        gateway.item(cmd.itemId)
        ensure(!cmd.quantity.isZero) { DomainError.InvalidInput("quantity", "مقدار ضایعات باید بیشتر از صفر باشد.") }
        ensure(cmd.reason != WasteReason.OTHER || cmd.note.trim().length >= 3) { DomainError.InvalidInput("note", "برای «سایر» توضیح لازم است.") }
        val docId = GlobalId.new()
        val value = gateway.valueOf(gateway.balance(cmd.itemId, location.id), cmd.quantity)
        val journal = if (value.isZero) null else gateway.postOwn(ctx, JournalDraft(cmd.date, location.scope, WASTE, docId, "ضایعات: ${cmd.reason}", listOf(
            LineDraft(StandardAccounts.WASTE, debit = value, memo = cmd.note, by = cap),
            LineDraft(StandardAccounts.INVENTORY, credit = value, memo = location.name, by = cap),
        )))
        gateway.stockOut(ctx, cmd.itemId, location, cmd.quantity, value, MovementKind.WASTE, cmd.date, journal?.id, SourceDocument(ModuleId.INVENTORY, WASTE, docId), null)
        docId
    }

    fun count(c: PostStockCount): CommandOutcome = bus.execute(ModuleId.INVENTORY, c) { cmd, ctx ->
        val location = requireLocationScope(cmd.locationId, cmd.scope)
        ensure(cmd.lines.isNotEmpty() && cmd.lines.map { it.itemId }.distinct().size == cmd.lines.size) {
            DomainError.InvalidInput("lines", "هر کالا فقط یک‌بار شمارش شود.")
        }
        val docId = GlobalId.new()
        cmd.lines.forEach { line ->
            gateway.item(line.itemId)
            val book = gateway.balance(line.itemId, location.id)
            if (line.counted == book.quantity) return@forEach
            val source = SourceDocument(ModuleId.INVENTORY, COUNT, docId)
            if (line.counted < book.quantity) {
                val loss = book.quantity - line.counted
                val value = gateway.valueOf(book, loss)
                val journal = if (value.isZero) null else gateway.postOwn(ctx, JournalDraft(cmd.date, location.scope, COUNT, docId, "کسری انبارگردانی", listOf(
                    LineDraft(StandardAccounts.INVENTORY_VARIANCE, debit = value, by = cap),
                    LineDraft(StandardAccounts.INVENTORY, credit = value, memo = location.name, by = cap),
                )))
                gateway.stockOut(ctx, line.itemId, location, loss, value, MovementKind.COUNT_LOSS, cmd.date, journal?.id, source, null)
            } else {
                // A gain is valued at the current average cost; with no stock left there is no
                // reliable cost, so the gain is recorded at zero value and flagged for review.
                val gain = line.counted - book.quantity
                val value = if (book.quantity.isZero) Money.ZERO
                else Money.of(ir.sabou.kernel.Ratio.mulDiv(book.value.rial, gain.micros, book.quantity.micros))
                val journal = if (value.isZero) null else gateway.postOwn(ctx, JournalDraft(cmd.date, location.scope, COUNT, docId, "اضافه انبارگردانی", listOf(
                    LineDraft(StandardAccounts.INVENTORY, debit = value, memo = location.name, by = cap),
                    LineDraft(StandardAccounts.INVENTORY_VARIANCE, credit = value, by = cap),
                )))
                gateway.stockIn(ctx, line.itemId, location, gain, value, MovementKind.COUNT_GAIN, cmd.date, journal?.id, source, null)
            }
        }
        ctx.audit(AuditDraft("STOCK_COUNT", "LOCATION", location.id.value, "lines=${cmd.lines.size}"))
        docId
    }

    /**
     * Moves stock between locations at the source's average cost. Between two branches, each
     * branch's books are balanced through the inter-branch account (AUD-011).
     */
    fun transfer(c: TransferStock): CommandOutcome = bus.execute(ModuleId.INVENTORY, c) { cmd, ctx ->
        ensure(cmd.fromLocationId != cmd.toLocationId) { DomainError.InvalidInput("location", "مبدأ و مقصد یکسان است.") }
        val from = requireLocationScope(cmd.fromLocationId, cmd.scope)
        val to = gateway.location(cmd.toLocationId)
        ctx.requireScope(to.scope)
        val merged = cmd.lines.groupBy { it.itemId }.map { (id, rows) -> IssueLine(id, rows.fold(Quantity.ZERO) { a, r -> a + r.quantity }) }
        ensure(merged.isNotEmpty() && merged.none { it.quantity.isZero }) { DomainError.InvalidInput("lines", "مقدار انتقال باید بیشتر از صفر باشد.") }
        val priced = merged.map { gateway.item(it.itemId); IssuedCost(it.itemId, it.quantity, gateway.valueOf(gateway.balance(it.itemId, from.id), it.quantity)) }
        val total = Money.sum(priced.map { it.cost })
        val docId = GlobalId.new()
        val source = SourceDocument(ModuleId.INVENTORY, TRANSFER, docId)
        var outJournal: GlobalId? = null
        var inJournal: GlobalId? = null
        if (from.scope != to.scope && !total.isZero) {
            outJournal = gateway.postOwn(ctx, JournalDraft(cmd.date, from.scope, TRANSFER, docId, "انتقال کالا به ${to.name}", listOf(
                LineDraft(StandardAccounts.INTER_BRANCH, debit = total, memo = to.name, by = cap),
                LineDraft(StandardAccounts.INVENTORY, credit = total, memo = from.name, by = cap),
            ))).id
            inJournal = gateway.postOwn(ctx, JournalDraft(cmd.date, to.scope, TRANSFER, docId, "دریافت کالا از ${from.name}", listOf(
                LineDraft(StandardAccounts.INVENTORY, debit = total, memo = to.name, by = cap),
                LineDraft(StandardAccounts.INTER_BRANCH, credit = total, memo = from.name, by = cap),
            ))).id
        }
        priced.forEach {
            gateway.stockOut(ctx, it.itemId, from, it.quantity, it.cost, MovementKind.TRANSFER_OUT, cmd.date, outJournal, source, null)
            gateway.stockIn(ctx, it.itemId, to, it.quantity, it.cost, MovementKind.TRANSFER_IN, cmd.date, inJournal, source, null)
        }
        docId
    }

    fun defineMenuItem(c: DefineMenuItem): CommandOutcome = bus.execute(ModuleId.INVENTORY, c) { cmd, ctx ->
        val name = cmd.name.trim()
        ensure(name.length in 2..80) { DomainError.InvalidInput("name", "نام آیتم منو الزامی است.") }
        val menuItem = MenuItem(GlobalId.new(), name)
        recipes.saveMenuItem(menuItem)
        ctx.audit(AuditDraft("MENU_ITEM_CREATE", "MENU_ITEM", menuItem.id.value, name))
        menuItem.id
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
        const val COUNT = "INVENTORY_COUNT"
        const val TRANSFER = "INVENTORY_TRANSFER"
        const val PRODUCTION = "INVENTORY_PRODUCTION"
    }
}

/** Turns menu sales into ingredient requirements using the recipe version in force on the sale date. */
class RecipeBook(private val recipes: RecipeStore) {
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
