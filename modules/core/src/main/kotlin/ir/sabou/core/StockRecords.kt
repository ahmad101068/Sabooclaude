package ir.sabou.core

import ir.sabou.inventory.InventoryDocument
import ir.sabou.inventory.InventoryOperations
import ir.sabou.inventory.Item
import ir.sabou.inventory.MovementKind
import ir.sabou.inventory.StockMovement
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.platform.Actor
import ir.sabou.platform.Permission

/** One line of an inventory document: an item in or out at a location. */
data class DocLine(val item: Item?, val location: String, val quantity: Long, val value: Long)

/** A waste, transfer, production or opening document with its lines (ADR-0044). */
data class InventoryDocView(
    val document: InventoryDocument,
    val number: String?,
    val lines: List<DocLine>,
    val reversed: Boolean,
) {
    val value: Long get() = lines.filter { it.quantity < 0 }.sumOf { -it.value }.takeIf { it > 0 } ?: lines.sumOf { it.value }
}

/** One movement on an item's stock card, with the balance after it. */
data class CardRow(
    val date: BusinessDate,
    val kind: MovementKind,
    val title: String,
    val number: String?,
    val quantity: Long,
    val value: Long,
    val balanceQuantity: Long,
    val balanceValue: Long,
    val reversal: Boolean,
)

/** An item's stock card (کاردکس) at one location for a period. */
data class StockCard(val item: Item, val location: String, val openingQuantity: Long, val openingValue: Long, val rows: List<CardRow>) {
    val closingQuantity: Long get() = rows.lastOrNull()?.balanceQuantity ?: openingQuantity
    val closingValue: Long get() = rows.lastOrNull()?.balanceValue ?: openingValue
}

class StockRecords internal constructor(private val core: SabouCore) {
    private fun actor(vararg anyOf: Permission): Actor {
        val a = core.session.currentActor() ?: throw DomainException(DomainError.AuthenticationRequired)
        if (anyOf.none { a.role.allows(it) }) throw DomainException(DomainError.PermissionDenied(anyOf.first().name))
        return a
    }

    private fun Actor.require(scope: Scope) {
        if (!canAccess(scope)) throw DomainException(DomainError.ScopeDenied(ir.sabou.platform.CommandContext.scopeLabel(scope)))
    }

    private fun numberOf(id: GlobalId): String? = core.documentNumbers.ofDocument(id).firstOrNull()?.number?.text

    /** The branch's waste, transfer, production and opening documents of [from]..[to], newest first. */
    fun documents(branch: Scope.Branch, from: BusinessDate, to: BusinessDate): List<InventoryDocView> {
        actor(Permission.INVENTORY_VIEW, Permission.INVENTORY_WASTE, Permission.INVENTORY_TRANSFER, Permission.INVENTORY_PRODUCE).require(branch)
        val items = core.items.all().associateBy { it.id }
        val places = core.locations.all().associateBy { it.id }
        return core.inventoryDocuments.of(branch, from, to).map { d ->
            val moves = core.stock.movementsBySource(d.type, d.id)
            val originals = moves.filter { it.reversalOf == null }
            InventoryDocView(d, numberOf(d.id), originals.map { m -> DocLine(items[m.itemId], places[m.locationId]?.name.orEmpty(), m.quantityDelta, m.valueDelta) },
                moves.any { it.reversalOf != null })
        }
    }

    /** An item's movements at [locationId] in [from]..[to], from the balance before [from]. */
    fun card(itemId: GlobalId, locationId: GlobalId, from: BusinessDate, to: BusinessDate): StockCard {
        val location = core.locations.byId(locationId) ?: throw DomainException(DomainError.NotFound("LOCATION"))
        actor(Permission.INVENTORY_VIEW, Permission.INVENTORY_ADJUST).require(location.scope)
        val item = core.items.byId(itemId) ?: throw DomainException(DomainError.NotFound("ITEM"))
        val opening = core.stock.totalsBefore(locationId, from).firstOrNull { it.itemId == itemId }
        var q = opening?.quantity ?: 0L
        var v = opening?.value ?: 0L
        val docs = HashMap<GlobalId, String?>()
        val rows = core.stock.movementsAt(locationId, from, to).filter { it.itemId == itemId }.map { m ->
            q += m.quantityDelta; v += m.valueDelta
            CardRow(m.date, m.kind, title(m), docs.getOrPut(m.source.id) { numberOf(m.source.id) }, m.quantityDelta, m.valueDelta, q, v, m.reversalOf != null)
        }
        return StockCard(item, location.name, opening?.quantity ?: 0L, opening?.value ?: 0L, rows)
    }

    private fun title(m: StockMovement): String = (if (m.reversalOf != null) "برگشت " else "") + when (m.source.type) {
        InventoryOperations.WASTE -> "ضایعات"
        InventoryOperations.COMP -> "پذیرایی و اهدایی"
        InventoryOperations.TRANSFER -> if (m.kind == MovementKind.TRANSFER_IN) "انتقال از انبار دیگر" else "انتقال به انبار دیگر"
        InventoryOperations.PRODUCTION -> if (m.kind == MovementKind.PRODUCTION_IN) "تولید" else "مصرف در تولید"
        InventoryOperations.OPENING -> "موجودی اول دوره"
        InventoryOperations.COUNT -> if (m.kind == MovementKind.COUNT_GAIN) "اضافه انبارگردانی" else "کسری انبارگردانی"
        ir.sabou.inventory.InventoryGateway.SHORTAGE -> "تسویه کسری مصرف"
        else -> when (m.kind) {
            MovementKind.RECEIPT -> "خرید و دریافت کالا"
            MovementKind.ISSUE -> if (m.quantityDelta < 0) "مصرف فروش یا مرجوعی" else "برگشت مصرف"
            else -> m.kind.name
        }
    }

    companion object {
        fun typeName(type: String) = when (type) {
            InventoryOperations.WASTE -> "ضایعات"
            InventoryOperations.COMP -> "پذیرایی و اهدایی"
            InventoryOperations.TRANSFER -> "انتقال"
            InventoryOperations.PRODUCTION -> "تولید"
            InventoryOperations.OPENING -> "موجودی اول دوره"
            else -> type
        }
    }
}
