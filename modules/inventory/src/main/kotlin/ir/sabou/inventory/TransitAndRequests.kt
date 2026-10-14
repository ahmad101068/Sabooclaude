package ir.sabou.inventory

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope
import ir.sabou.platform.Command
import ir.sabou.platform.DocumentSeries
import ir.sabou.platform.IssuesDocument
import ir.sabou.platform.NoDocument
import ir.sabou.platform.Permission

/**
 * Goods sent to another branch that its people have not yet confirmed (ADR-0044). They left the sending location
 * (its books: inter-branch ← inventory); the receiving branch books them when it counts them in, and what did not
 * arrive becomes its inventory variance.
 */
data class StockTransit(
    val id: GlobalId,
    val fromScope: Scope.Branch,
    val fromLocationId: GlobalId,
    val toScope: Scope.Branch,
    val toLocationId: GlobalId,
    val date: BusinessDate,
    val lines: List<TransitLine>,
    val status: Status = Status.IN_TRANSIT,
    val receivedOn: BusinessDate? = null,
    val receivedBy: String = "",
    val receiptId: GlobalId? = null,
) {
    enum class Status { IN_TRANSIT, RECEIVED, CANCELLED }
}

/** One item sent: [value] at the sender's cost, with the expiry dates it carried. */
data class TransitLine(val itemId: GlobalId, val quantity: Quantity, val value: Money, val lots: List<LotPart> = emptyList(), val received: Quantity? = null)

interface TransitStore {
    fun byId(id: GlobalId): StockTransit?
    /** Transfers on their way to [scope], or from it. */
    fun of(scope: Scope.Branch): List<StockTransit>
    fun save(transit: StockTransit)
}

/** The receiving branch counts in what arrived; anything missing is booked as its inventory variance. */
@IssuesDocument(DocumentSeries.STOCK_TRANSFER)
data class ReceiveTransfer(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val transferId: GlobalId,
    /** What arrived of each item; an item left out arrived in full. */
    val received: List<IssueLine>,
    val date: BusinessDate,
    val note: String = "",
) : Command {
    override val requiredPermission = Permission.INVENTORY_TRANSFER
    override fun fingerprint() = "$scope|$transferId|${date.epochDay}|$note|" + received.joinToString(";") { "${it.itemId}:${it.quantity.micros}" }
}

/**
 * A request for goods from a storeroom (the kitchen asks; the storekeeper sends). [scope] is the asking branch (that of
 * [toLocationId]); [fromScope] the one that sends, the same branch or another (a central storeroom).
 */
data class StockRequest(
    val id: GlobalId,
    val scope: Scope.Branch,
    val fromScope: Scope.Branch,
    val fromLocationId: GlobalId,
    val toLocationId: GlobalId,
    val lines: List<IssueLine>,
    val note: String,
    val date: BusinessDate,
    val requestedBy: String,
    val requesterId: GlobalId,
    val status: Status = Status.OPEN,
    val handledBy: String = "",
    val transferId: GlobalId? = null,
    val rejectReason: String = "",
) {
    enum class Status { OPEN, SENT, REJECTED, CANCELLED }
}

interface StockRequestStore {
    fun byId(id: GlobalId): StockRequest?
    /** Requests made by [scope] or sent to it. */
    fun of(scope: Scope.Branch): List<StockRequest>
    fun save(request: StockRequest)
}

@NoDocument
data class RequestStock(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val fromLocationId: GlobalId,
    val toLocationId: GlobalId,
    val lines: List<IssueLine>,
    val note: String,
    val date: BusinessDate,
) : Command {
    override val requiredPermission = Permission.INVENTORY_VIEW
    override fun fingerprint() = "$scope|$fromLocationId|$toLocationId|${date.epochDay}|$note|" + lines.joinToString(";") { "${it.itemId}:${it.quantity.micros}" }
}

/** Sends a request: a transfer of [lines] (what is actually given, which may differ from what was asked). [scope] is the sender's. */
@IssuesDocument(DocumentSeries.STOCK_TRANSFER)
data class FulfilStockRequest(override val commandId: GlobalId, override val scope: Scope.Branch, val requestId: GlobalId, val lines: List<IssueLine>, val date: BusinessDate) : Command {
    override val requiredPermission = Permission.INVENTORY_TRANSFER
    override fun fingerprint() = "$scope|$requestId|${date.epochDay}|" + lines.joinToString(";") { "${it.itemId}:${it.quantity.micros}" }
}

/** The sender refuses a request (with a reason; [scope] the sender's), or the requester withdraws it ([cancel]; [scope] the requester's). */
@NoDocument
data class CloseStockRequest(override val commandId: GlobalId, override val scope: Scope.Branch, val requestId: GlobalId, val reason: String, val cancel: Boolean = false) : Command {
    override val requiredPermission = Permission.INVENTORY_VIEW
    override fun fingerprint() = "$scope|$requestId|$reason|$cancel"
}
