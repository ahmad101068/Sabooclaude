package ir.sabou.inventory

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Ratio
import ir.sabou.kernel.Rounding
import ir.sabou.kernel.ensure
import ir.sabou.ledger.JournalDraft
import ir.sabou.ledger.JournalEntry
import ir.sabou.ledger.Ledger
import ir.sabou.ledger.LineDraft
import ir.sabou.ledger.PostingCapability
import ir.sabou.ledger.SourceDocument
import ir.sabou.ledger.StandardAccounts
import ir.sabou.platform.AuditDraft
import ir.sabou.platform.CommandContext
import ir.sabou.platform.ModuleId

/** [expiresOn]: the date printed on the goods, if any; without it the item's shelf life applies (ADR-0030). */
data class ReceiptLine(val itemId: GlobalId, val quantity: Quantity, val value: Money, val expiresOn: BusinessDate? = null)
data class IssueLine(val itemId: GlobalId, val quantity: Quantity)
data class IssuedCost(val itemId: GlobalId, val quantity: Quantity, val cost: Money)
/** What a consumption took from stock at cost, and what it could not (left as open shortages, ADR-0022). */
data class Consumption(val lines: List<IssuedCost>, val totalCost: Money, val journal: JournalEntry?, val shortages: List<StockShortage> = emptyList())

/**
 * The only way stock changes. Valuation is moving weighted average **per location**; the value of
 * an issue is always computed here from the location's balance, never supplied by the caller.
 * Every value change posts its journal in the same transaction, so inventory 1301 in each branch
 * always equals the value of that branch's stock.
 */
class InventoryGateway(
    private val ledger: Ledger,
    private val capability: PostingCapability,
    private val items: ItemStore,
    private val locations: LocationStore,
    private val stock: StockStore,
    private val shortages: ShortageStore = ir.sabou.inventory.memory.InMemoryShortageStore(),
    private val lots: LotStore = ir.sabou.inventory.memory.InMemoryLotStore(),
) {
    init {
        require(capability.module == ModuleId.INVENTORY)
    }

    fun location(id: GlobalId): Location {
        val location = locations.byId(id) ?: throw DomainException(DomainError.NotFound("LOCATION"))
        ensure(location.isActive) { DomainError.InvalidState("LOCATION", "INACTIVE") }
        return location
    }

    private fun nameOf(id: GlobalId): String = items.byId(id)?.name.orEmpty()

    fun item(id: GlobalId): Item {
        val item = items.byId(id) ?: throw DomainException(DomainError.NotFound("ITEM"))
        ensure(item.isActive) { DomainError.InvalidState("ITEM", "INACTIVE") }
        return item
    }

    fun balance(itemId: GlobalId, locationId: GlobalId): StockBalance = stock.balance(itemId, locationId)

    fun activeItemIds(): List<GlobalId> = items.all().filter { it.isActive }.map { it.id }

    /** Weighted-average value of taking [quantity] out of the balance; the last unit takes the remainder. */
    fun valueOf(balance: StockBalance, quantity: Quantity): Money {
        ensure(quantity <= balance.quantity) {
            DomainError.InsufficientStock(balance.itemId.value, balance.quantity.micros, quantity.micros, nameOf(balance.itemId))
        }
        if (quantity == balance.quantity) return balance.value
        return Money.of(Ratio.mulDiv(balance.value.rial, quantity.micros, balance.quantity.micros, Rounding.HALF_UP))
    }

    /** Goods in, valued by the caller (purchase price). [counterLines] credit the owner's account (e.g. AP). */
    fun receive(
        context: CommandContext,
        owner: PostingCapability,
        locationId: GlobalId,
        lines: List<ReceiptLine>,
        date: BusinessDate,
        sourceType: String,
        sourceId: GlobalId,
        description: String,
        counterLines: List<LineDraft>,
    ): JournalEntry {
        requireOwner(context, owner)
        ensure(lines.isNotEmpty() && lines.all { !it.quantity.isZero }) { DomainError.InvalidInput("lines", "حداقل یک ردیف با مقدار مثبت لازم است.") }
        ensure(lines.map { it.itemId }.distinct().size == lines.size) { DomainError.InvalidInput("lines", "هر کالا فقط یک‌بار در سند بیاید.") }
        val location = location(locationId)
        context.requireScope(location.scope)
        val total = Money.sum(lines.map { it.value })
        ensure(!total.isZero) { DomainError.InvalidInput("value", "ارزش دریافت باید بیشتر از صفر باشد.") }
        ensure(Money.sum(counterLines.map { it.credit }) == total && counterLines.all { it.debit.isZero }) {
            DomainError.UnbalancedJournal(total.rial, Money.sum(counterLines.map { it.credit }).rial)
        }
        val journal = ledger.post(
            context, owner,
            JournalDraft(date, location.scope, sourceType, sourceId, description,
                listOf(LineDraft(StandardAccounts.INVENTORY, debit = total, memo = location.name, by = capability)) + counterLines),
        )
        lines.forEach { line ->
            val item = item(line.itemId)
            ensure(line.expiresOn == null || line.expiresOn >= date) { DomainError.InvalidInput("expiresOn", "تاریخ انقضای «${item.name}» پیش از تاریخ دریافت است.") }
            val expiry = line.expiresOn ?: shelfLifeEnd(item, date)
            stockIn(context, line.itemId, location, line.quantity, line.value, MovementKind.RECEIPT, date, journal.id, SourceDocument(owner.module, sourceType, sourceId), null,
                listOfNotNull(expiry?.let { LotPart(it, line.quantity) }))
        }
        // What was consumed before these goods were booked is taken from them now, at their cost.
        lines.forEach { settleShortages(context, owner, location, it.itemId, date) }
        return journal
    }

    /**
     * Goods out at weighted-average cost into COGS (sales consumption). Returns the exact cost. With
     * [allowShortage], what the book stock cannot cover is not refused: the available quantity is issued and the
     * rest is recorded as an open [StockShortage] (stock never goes negative).
     */
    fun consume(
        context: CommandContext,
        owner: PostingCapability,
        locationId: GlobalId,
        lines: List<IssueLine>,
        date: BusinessDate,
        sourceType: String,
        sourceId: GlobalId,
        description: String,
        allowShortage: Boolean = false,
    ): Consumption {
        requireOwner(context, owner)
        val location = location(locationId)
        context.requireScope(location.scope)
        val merged = lines.groupBy { it.itemId }.map { (id, rows) -> IssueLine(id, rows.fold(Quantity.ZERO) { a, r -> a + r.quantity }) }
            .filter { !it.quantity.isZero }
        // Price everything first so a shortage in any line fails (when not allowed) before anything is written.
        val missing = ArrayList<IssueLine>()
        val priced = merged.mapNotNull { line ->
            item(line.itemId)
            val balance = stock.balance(line.itemId, location.id)
            val take = if (allowShortage && line.quantity > balance.quantity) balance.quantity else line.quantity
            if (take != line.quantity) missing += IssueLine(line.itemId, line.quantity - take)
            if (take.isZero) null else IssuedCost(line.itemId, take, valueOf(balance, take))
        }
        val total = Money.sum(priced.map { it.cost })
        val journal = if (total.isZero) null else ledger.post(
            context, owner,
            JournalDraft(date, location.scope, sourceType, sourceId, description, listOf(
                LineDraft(StandardAccounts.COGS, debit = total, memo = "بهای تمام‌شده", by = capability),
                LineDraft(StandardAccounts.INVENTORY, credit = total, memo = location.name, by = capability),
            )),
        )
        priced.forEach {
            stockOut(context, it.itemId, location, it.quantity, it.cost, MovementKind.ISSUE, date, journal?.id, SourceDocument(owner.module, sourceType, sourceId), null)
        }
        val recorded = missing.map { m ->
            StockShortage(GlobalId.new(), m.itemId, location.id, date, sourceType, sourceId, m.quantity).also {
                shortages.save(it)
                context.audit(AuditDraft("STOCK_SHORTAGE_OPEN", "STOCK_SHORTAGE", it.id.value, "item=${m.itemId};loc=${location.id};qty=${m.quantity.micros};source=$sourceType:$sourceId"))
            }
        }
        return Consumption(priced, total, journal, recorded)
    }

    /**
     * Settles open shortages of [itemId] at [location] from what is in stock now, oldest first, at the current
     * average cost (COGS ← inventory). Called right after goods come in; the journal belongs to [owner], the
     * module whose document brought the goods.
     */
    fun settleShortages(context: CommandContext, owner: PostingCapability, location: Location, itemId: GlobalId, date: BusinessDate) {
        for (shortage in shortages.open(itemId, location.id)) {
            val balance = stock.balance(itemId, location.id)
            if (balance.quantity.isZero) return
            val take = if (shortage.open < balance.quantity) shortage.open else balance.quantity
            val cost = valueOf(balance, take)
            val journal = if (cost.isZero) null else ledger.post(context, owner, JournalDraft(date, location.scope, SHORTAGE, shortage.id,
                "تسویه کسری مصرف", listOf(
                    LineDraft(StandardAccounts.COGS, debit = cost, memo = "مصرف پیشین بدون موجودی", by = capability),
                    LineDraft(StandardAccounts.INVENTORY, credit = cost, memo = location.name, by = capability),
                )))
            stockOut(context, itemId, location, take, cost, MovementKind.ISSUE, date, journal?.id, SourceDocument(owner.module, SHORTAGE, shortage.id), null)
            val settled = shortage.settled + take
            shortages.save(shortage.copy(settled = settled, settledCost = shortage.settledCost + cost, takenFromStock = shortage.takenFromStock + take,
                takenCost = shortage.takenCost + cost, status = if (settled == shortage.quantity) StockShortage.Status.SETTLED else StockShortage.Status.OPEN))
            context.audit(AuditDraft("STOCK_SHORTAGE_SETTLE", "STOCK_SHORTAGE", shortage.id.value, "qty=${take.micros};cost=${cost.rial}"))
        }
    }

    /**
     * A stock count has measured what is really there, so a shortage still open for a counted item can no longer
     * be taken from stock: it is written off at an estimated cost (COGS ← inventory variance), keeping the cost of
     * what was sold in the cost of sales. Estimate: the average cost at the location, else the last cost it came in at.
     */
    fun writeOffShortages(context: CommandContext, location: Location, itemId: GlobalId, date: BusinessDate, sourceId: GlobalId) {
        for (shortage in shortages.open(itemId, location.id)) {
            val unit = unitCost(itemId, location.id)
            val cost = if (unit == null) Money.ZERO else Money.of(Ratio.mulDiv(unit, shortage.open.micros, Quantity.SCALE))
            if (!cost.isZero) ledger.post(context, capability, JournalDraft(date, location.scope, SHORTAGE, shortage.id, "کسری مصرف در انبارگردانی", listOf(
                LineDraft(StandardAccounts.COGS, debit = cost, memo = "مصرف بدون موجودی دفتری", by = capability),
                LineDraft(StandardAccounts.INVENTORY_VARIANCE, credit = cost, memo = "شمارش ${sourceId.value.take(8)}", by = capability),
            )))
            shortages.save(shortage.copy(settled = shortage.quantity, settledCost = shortage.settledCost + cost, status = StockShortage.Status.WRITTEN_OFF))
            context.audit(AuditDraft("STOCK_SHORTAGE_WRITE_OFF", "STOCK_SHORTAGE", shortage.id.value, "qty=${shortage.open.micros};cost=${cost.rial}"))
        }
    }

    /**
     * The consuming document is reversed: its open shortages are cancelled, and what settlements took from stock
     * comes back at the cost it left with (inventory ← COGS), in a journal of [owner].
     */
    fun cancelShortages(context: CommandContext, owner: PostingCapability, sourceType: String, sourceId: GlobalId, date: BusinessDate) {
        requireOwner(context, owner)
        for (shortage in shortages.ofSource(sourceType, sourceId).filter { it.status != StockShortage.Status.CANCELLED }) {
            if (!shortage.takenFromStock.isZero) {
                val location = location(shortage.locationId)
                val journal = if (shortage.takenCost.isZero) null else ledger.post(context, owner, JournalDraft(date, location.scope, SHORTAGE, shortage.id,
                    "برگشت تسویه کسری مصرف", listOf(
                        LineDraft(StandardAccounts.INVENTORY, debit = shortage.takenCost, memo = location.name, by = capability),
                        LineDraft(StandardAccounts.COGS, credit = shortage.takenCost, memo = "برگشت سند", by = capability),
                    )))
                stockIn(context, shortage.itemId, location, shortage.takenFromStock, shortage.takenCost, MovementKind.ISSUE, date, journal?.id,
                    SourceDocument(owner.module, SHORTAGE, shortage.id), null)
            }
            val writtenOff = shortage.settledCost - shortage.takenCost
            if (!writtenOff.isZero) {
                val location = location(shortage.locationId)
                ledger.post(context, owner, JournalDraft(date, location.scope, SHORTAGE, shortage.id, "برگشت کسری مصرف", listOf(
                    LineDraft(StandardAccounts.INVENTORY_VARIANCE, debit = writtenOff, memo = "برگشت سند", by = capability),
                    LineDraft(StandardAccounts.COGS, credit = writtenOff, memo = "برگشت سند", by = capability),
                )))
            }
            shortages.save(shortage.copy(status = StockShortage.Status.CANCELLED))
            context.audit(AuditDraft("STOCK_SHORTAGE_CANCEL", "STOCK_SHORTAGE", shortage.id.value, "source=$sourceType:$sourceId"))
        }
    }

    /** Rial per stock unit (SCALE micro-units): the location's average, else the last cost the item came in at. */
    private fun unitCost(itemId: GlobalId, locationId: GlobalId): Long? {
        val balance = stock.balance(itemId, locationId)
        if (balance.quantity.micros > 0 && balance.value.rial > 0) return Ratio.mulDiv(balance.value.rial, Quantity.SCALE, balance.quantity.micros)
        return stock.lastInboundUnitCost(itemId)
    }

    fun shortagesOf(sourceType: String, sourceId: GlobalId): List<StockShortage> = shortages.ofSource(sourceType, sourceId)
    fun openShortages(locationIds: Set<GlobalId>): List<StockShortage> = shortages.openAt(locationIds)

    /**
     * Reverses every stock movement of one source document at its original value. Taking back a
     * receipt requires the goods (and their value) to still be at the location; otherwise the
     * correct correction is a purchase return, not a reversal.
     */
    fun reverseDocument(
        context: CommandContext,
        owner: PostingCapability,
        sourceType: String,
        sourceId: GlobalId,
        date: BusinessDate,
        reason: String,
    ) {
        requireOwner(context, owner)
        val originals = stock.movementsBySource(sourceType, sourceId).filter { it.reversalOf == null }
        ensure(originals.isNotEmpty()) { DomainError.NotFound("STOCK_MOVEMENT") }
        originals.forEach { m ->
            ensure(m.source.module == owner.module) { DomainError.OwnedByAnotherModule(m.source.module.name) }
            ensure(stock.reversalOf(m.id) == null) { DomainError.InvalidState("STOCK_MOVEMENT", "ALREADY_REVERSED") }
        }
        val reversedJournals = HashMap<GlobalId, GlobalId>()
        originals.mapNotNull { it.journalId }.distinct().forEach { journalId ->
            reversedJournals[journalId] = ledger.reverse(context, owner, setOf(capability), journalId, date, reason).id
        }
        // Undo the ins first: the lots they take out go back with the outs (a reversed transfer returns its expiry dates).
        val back = HashMap<GlobalId, ArrayList<LotPart>>()
        val (ins, outs) = originals.partition { it.quantityDelta > 0 || (it.quantityDelta == 0L && it.valueDelta > 0) }
        (ins + outs).forEach { m ->
            val location = location(m.locationId)
            context.requireScope(location.scope)
            val qty = Quantity.of(kotlin.math.abs(m.quantityDelta))
            val value = Money.of(kotlin.math.abs(m.valueDelta))
            val journalId = m.journalId?.let { reversedJournals.getValue(it) }
            if (m in ins) {
                back.getOrPut(m.itemId) { ArrayList() } += stockOut(context, m.itemId, location, qty, value, m.kind, date, journalId, m.source, m.id)
            } else {
                stockIn(context, m.itemId, location, qty, value, m.kind, date, journalId, m.source, m.id, takeParts(back[m.itemId], qty))
            }
        }
    }

    /** Goods in; [inLots] are the parts of [quantity] with a known expiry (the rest has none). */
    internal fun stockIn(
        context: CommandContext, itemId: GlobalId, location: Location, quantity: Quantity, value: Money,
        kind: MovementKind, date: BusinessDate, journalId: GlobalId?, source: SourceDocument, reversalOf: GlobalId?,
        inLots: List<LotPart> = emptyList(),
    ) {
        ledger.requireOpenPeriod(date)   // also for moves without a journal (same-branch transfer, zero value)
        val before = stock.balance(itemId, location.id)
        stock.replace(before, before.copy(quantity = before.quantity + quantity, value = before.value + value))
        record(context, itemId, location, kind, quantity.micros, value.rial, date, journalId, source, reversalOf)
        check(inLots.fold(Quantity.ZERO) { a, p -> a + p.quantity } <= quantity) { "lots_exceed_quantity" }
        inLots.filter { !it.quantity.isZero }.groupBy { it.expiresOn }.forEach { (expiry, parts) ->
            val q = parts.fold(Quantity.ZERO) { a, p -> a + p.quantity }
            lots.save(StockLot(GlobalId.new(), itemId, location.id, expiry, date, q, q, source.type, source.id))
        }
    }

    /**
     * Goods out. Returns the lots they were taken from: earliest expiry first; a reversed receipt takes back its own
     * lot first. Goods with no known expiry are used only when the lots run out.
     */
    internal fun stockOut(
        context: CommandContext, itemId: GlobalId, location: Location, quantity: Quantity, value: Money,
        kind: MovementKind, date: BusinessDate, journalId: GlobalId?, source: SourceDocument, reversalOf: GlobalId?,
    ): List<LotPart> {
        ledger.requireOpenPeriod(date)
        val before = stock.balance(itemId, location.id)
        ensure(quantity <= before.quantity) { DomainError.InsufficientStock(itemId.value, before.quantity.micros, quantity.micros, nameOf(itemId)) }
        ensure(value <= before.value) { DomainError.InsufficientStock(itemId.value, before.value.rial, value.rial) }
        val nextQuantity = before.quantity - quantity
        // A balance that reaches zero quantity must also reach zero value (no orphan value).
        ensure(!nextQuantity.isZero || value == before.value) { DomainError.IntegrityViolation("STOCK_VALUE_RESIDUE") }
        stock.replace(before, before.copy(quantity = nextQuantity, value = before.value - value))
        record(context, itemId, location, kind, -quantity.micros, -value.rial, date, journalId, source, reversalOf)
        return takeFromLots(itemId, location.id, quantity, if (reversalOf != null) source else null)
    }

    private fun takeFromLots(itemId: GlobalId, locationId: GlobalId, quantity: Quantity, ownSource: SourceDocument?): List<LotPart> {
        val open = lots.open(itemId, locationId)
        val ordered = if (ownSource == null) open else open.filter { it.sourceType == ownSource.type && it.sourceId == ownSource.id } +
            open.filterNot { it.sourceType == ownSource.type && it.sourceId == ownSource.id }
        var left = quantity
        val taken = ArrayList<LotPart>()
        for (lot in ordered) {
            if (left.isZero) break
            val take = if (lot.remaining < left) lot.remaining else left
            lots.save(lot.copy(remaining = lot.remaining - take))
            taken += LotPart(lot.expiresOn, take)
            left -= take
        }
        return taken
    }

    /** The day goods received on [date] expire by the item's shelf life, or null when it has none. */
    fun shelfLifeEnd(item: Item, date: BusinessDate): BusinessDate? = item.shelfLifeDays?.let { date.plusDays(it.toLong()) }

    /** Lots with something left at [locationIds], earliest expiry first. */
    fun openLots(locationIds: Set<GlobalId>): List<StockLot> = lots.openAt(locationIds)

    private fun record(
        context: CommandContext, itemId: GlobalId, location: Location, kind: MovementKind, qty: Long, value: Long,
        date: BusinessDate, journalId: GlobalId?, source: SourceDocument, reversalOf: GlobalId?,
    ) {
        val movement = StockMovement(GlobalId.new(), itemId, location.id, kind, qty, value, date, journalId, source, reversalOf, context.nowEpochMillis)
        stock.insertMovement(movement)
        context.audit(AuditDraft("STOCK_${kind.name}", "STOCK_MOVEMENT", movement.id.value, "item=$itemId;loc=${location.id};qty=$qty;value=$value"))
    }

    /** Removes up to [quantity] from [parts] (earliest first) and returns what was removed. */
    private fun takeParts(parts: ArrayList<LotPart>?, quantity: Quantity): List<LotPart> {
        if (parts == null) return emptyList()
        var left = quantity
        val out = ArrayList<LotPart>()
        val it = parts.listIterator()
        while (it.hasNext() && !left.isZero) {
            val p = it.next()
            val take = if (p.quantity < left) p.quantity else left
            out += LotPart(p.expiresOn, take)
            left -= take
            if (take == p.quantity) it.remove() else it.set(p.copy(quantity = p.quantity - take))
        }
        return out
    }

    internal fun postOwn(context: CommandContext, draft: JournalDraft): JournalEntry = ledger.post(context, capability, draft)
    internal fun postAs(context: CommandContext, owner: PostingCapability, draft: JournalDraft): JournalEntry = ledger.post(context, owner, draft)
    internal val ownCapability: PostingCapability get() = capability

    companion object {
        /** Source type of shortage settlements, write-offs and cancellations. */
        const val SHORTAGE = "STOCK_SHORTAGE"
    }

    private fun requireOwner(context: CommandContext, owner: PostingCapability) {
        ensure(owner.module == context.module) { DomainError.OwnedByAnotherModule(owner.module.name) }
    }
}
