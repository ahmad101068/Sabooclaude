package ir.sabou.inventory

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Quantity

/**
 * Goods of one item at one location that expire on one day (ADR-0030). Lots are a quantity layer on top of the
 * stock balance: valuation stays the location's weighted average, so the books never depend on lots.
 *
 * A lot is made when goods come in with an expiry date (typed on the invoice line, or the item's shelf life from
 * the receipt or production date). Everything that takes stock out takes it from lots first, earliest expiry first
 * (the kitchen's «first expired, first out»); goods with no known expiry are used last. A transfer carries its lots
 * to the destination. So the lots of a location never hold more than its stock.
 */
data class StockLot(
    val id: GlobalId,
    val itemId: GlobalId,
    val locationId: GlobalId,
    val expiresOn: BusinessDate,
    val receivedOn: BusinessDate,
    val quantity: Quantity,
    val remaining: Quantity,
    /** The document that brought the goods in (invoice, production, transfer). */
    val sourceType: String,
    val sourceId: GlobalId,
) {
    fun daysLeft(today: BusinessDate): Long = expiresOn.epochDay - today.epochDay
}

/** A quantity with its expiry: what a movement takes out of lots, or brings into new ones. */
data class LotPart(val expiresOn: BusinessDate, val quantity: Quantity)

interface LotStore {
    /** Lots of [itemId] at [locationId] with something left, earliest expiry first. */
    fun open(itemId: GlobalId, locationId: GlobalId): List<StockLot>
    /** Lots with something left at any of [locationIds], earliest expiry first. */
    fun openAt(locationIds: Set<GlobalId>): List<StockLot>
    fun save(lot: StockLot)
}

/** First expired, first out; then first received. */
val FEFO: Comparator<StockLot> = compareBy<StockLot> { it.expiresOn }.thenBy { it.receivedOn }.thenBy { it.id.value }
