package ir.sabou.inventory

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity

/**
 * What a document consumed beyond the book stock of its location (ADR-0022). A sale is never refused for a
 * book shortage — it happened; stock never goes negative either. The shortfall waits here and is settled at
 * cost when the item next comes in to the location (purchase, transfer, production), written off at an
 * estimated cost by a stock count, or cancelled when its document is reversed.
 */
data class StockShortage(
    val id: GlobalId,
    val itemId: GlobalId,
    val locationId: GlobalId,
    val date: BusinessDate,
    /** The consuming document (e.g. a sale's consumption). */
    val sourceType: String,
    val sourceId: GlobalId,
    val quantity: Quantity,
    val settled: Quantity = Quantity.ZERO,
    /** Cost booked for [settled] (settlements and write-offs). */
    val settledCost: Money = Money.ZERO,
    /** Of [settledCost], what was taken from stock (and comes back if the document is reversed). */
    val takenFromStock: Quantity = Quantity.ZERO,
    val takenCost: Money = Money.ZERO,
    val status: Status = Status.OPEN,
    /** When it was recorded (clock); 0 for shortages recorded before this was kept (SABOU-AUDIT-009). */
    val recordedAt: Long = 0L,
) {
    enum class Status { OPEN, SETTLED, WRITTEN_OFF, CANCELLED }

    val open: Quantity get() = if (status == Status.OPEN) quantity - settled else Quantity.ZERO
}

interface ShortageStore {
    fun byId(id: GlobalId): StockShortage?
    fun ofSource(sourceType: String, sourceId: GlobalId): List<StockShortage>
    /** Open shortages of [itemId] at [locationId], oldest first. */
    fun open(itemId: GlobalId, locationId: GlobalId): List<StockShortage>
    /** Open shortages at any of [locationIds], oldest first. */
    fun openAt(locationIds: Set<GlobalId>): List<StockShortage>
    fun save(shortage: StockShortage)
}
