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

data class ReceiptLine(val itemId: GlobalId, val quantity: Quantity, val value: Money)
data class IssueLine(val itemId: GlobalId, val quantity: Quantity)
data class IssuedCost(val itemId: GlobalId, val quantity: Quantity, val cost: Money)
data class Consumption(val lines: List<IssuedCost>, val totalCost: Money, val journal: JournalEntry?)

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
) {
    init {
        require(capability.module == ModuleId.INVENTORY)
    }

    fun location(id: GlobalId): Location {
        val location = locations.byId(id) ?: throw DomainException(DomainError.NotFound("LOCATION"))
        ensure(location.isActive) { DomainError.InvalidState("LOCATION", "INACTIVE") }
        return location
    }

    fun item(id: GlobalId): Item {
        val item = items.byId(id) ?: throw DomainException(DomainError.NotFound("ITEM"))
        ensure(item.isActive) { DomainError.InvalidState("ITEM", "INACTIVE") }
        return item
    }

    fun balance(itemId: GlobalId, locationId: GlobalId): StockBalance = stock.balance(itemId, locationId)

    /** Weighted-average value of taking [quantity] out of the balance; the last unit takes the remainder. */
    fun valueOf(balance: StockBalance, quantity: Quantity): Money {
        ensure(quantity <= balance.quantity) {
            DomainError.InsufficientStock(balance.itemId.value, balance.quantity.micros, quantity.micros)
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
            item(line.itemId)
            stockIn(context, line.itemId, location, line.quantity, line.value, MovementKind.RECEIPT, date, journal.id, SourceDocument(owner.module, sourceType, sourceId), null)
        }
        return journal
    }

    /** Goods out at weighted-average cost into COGS (sales consumption). Returns the exact cost. */
    fun consume(
        context: CommandContext,
        owner: PostingCapability,
        locationId: GlobalId,
        lines: List<IssueLine>,
        date: BusinessDate,
        sourceType: String,
        sourceId: GlobalId,
        description: String,
    ): Consumption {
        requireOwner(context, owner)
        val location = location(locationId)
        context.requireScope(location.scope)
        val merged = lines.groupBy { it.itemId }.map { (id, rows) -> IssueLine(id, rows.fold(Quantity.ZERO) { a, r -> a + r.quantity }) }
            .filter { !it.quantity.isZero }
        // Price everything first so a shortage in any line fails before anything is written.
        val priced = merged.map { line ->
            item(line.itemId)
            IssuedCost(line.itemId, line.quantity, valueOf(stock.balance(line.itemId, location.id), line.quantity))
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
        return Consumption(priced, total, journal)
    }

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
        originals.forEach { m ->
            val location = location(m.locationId)
            context.requireScope(location.scope)
            val qty = Quantity.of(kotlin.math.abs(m.quantityDelta))
            val value = Money.of(kotlin.math.abs(m.valueDelta))
            val journalId = m.journalId?.let { reversedJournals.getValue(it) }
            if (m.quantityDelta > 0 || (m.quantityDelta == 0L && m.valueDelta > 0)) {
                stockOut(context, m.itemId, location, qty, value, m.kind, date, journalId, m.source, m.id)
            } else {
                stockIn(context, m.itemId, location, qty, value, m.kind, date, journalId, m.source, m.id)
            }
        }
    }

    internal fun stockIn(
        context: CommandContext, itemId: GlobalId, location: Location, quantity: Quantity, value: Money,
        kind: MovementKind, date: BusinessDate, journalId: GlobalId?, source: SourceDocument, reversalOf: GlobalId?,
    ) {
        val before = stock.balance(itemId, location.id)
        stock.replace(before, before.copy(quantity = before.quantity + quantity, value = before.value + value))
        record(context, itemId, location, kind, quantity.micros, value.rial, date, journalId, source, reversalOf)
    }

    internal fun stockOut(
        context: CommandContext, itemId: GlobalId, location: Location, quantity: Quantity, value: Money,
        kind: MovementKind, date: BusinessDate, journalId: GlobalId?, source: SourceDocument, reversalOf: GlobalId?,
    ) {
        val before = stock.balance(itemId, location.id)
        ensure(quantity <= before.quantity) { DomainError.InsufficientStock(itemId.value, before.quantity.micros, quantity.micros) }
        ensure(value <= before.value) { DomainError.InsufficientStock(itemId.value, before.value.rial, value.rial) }
        val nextQuantity = before.quantity - quantity
        // A balance that reaches zero quantity must also reach zero value (no orphan value).
        ensure(!nextQuantity.isZero || value == before.value) { DomainError.IntegrityViolation("STOCK_VALUE_RESIDUE") }
        stock.replace(before, before.copy(quantity = nextQuantity, value = before.value - value))
        record(context, itemId, location, kind, -quantity.micros, -value.rial, date, journalId, source, reversalOf)
    }

    private fun record(
        context: CommandContext, itemId: GlobalId, location: Location, kind: MovementKind, qty: Long, value: Long,
        date: BusinessDate, journalId: GlobalId?, source: SourceDocument, reversalOf: GlobalId?,
    ) {
        val movement = StockMovement(GlobalId.new(), itemId, location.id, kind, qty, value, date, journalId, source, reversalOf, context.nowEpochMillis)
        stock.insertMovement(movement)
        context.audit(AuditDraft("STOCK_${kind.name}", "STOCK_MOVEMENT", movement.id.value, "item=$itemId;loc=${location.id};qty=$qty;value=$value"))
    }

    internal fun postOwn(context: CommandContext, draft: JournalDraft): JournalEntry = ledger.post(context, capability, draft)
    internal val ownCapability: PostingCapability get() = capability

    private fun requireOwner(context: CommandContext, owner: PostingCapability) {
        ensure(owner.module == context.module) { DomainError.OwnedByAnotherModule(owner.module.name) }
    }
}
