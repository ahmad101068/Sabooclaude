package ir.sabou.purchasing

import ir.sabou.platform.NoDocument

import ir.sabou.platform.DocumentSeries

import ir.sabou.platform.IssuesDocument

import ir.sabou.inventory.InventoryGateway
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Scope
import ir.sabou.kernel.ensure
import ir.sabou.platform.AuditDraft
import ir.sabou.platform.Command
import ir.sabou.platform.CommandBus
import ir.sabou.platform.CommandOutcome
import ir.sabou.platform.ModuleId
import ir.sabou.platform.Permission

/**
 * An order placed with a supplier before delivery. No stock or money moves; when the goods arrive
 * the invoice is recorded from it ([PostPurchaseInvoice.orderId]) and the order is closed.
 */
@IssuesDocument(DocumentSeries.PURCHASE_ORDER)
data class CreatePurchaseOrder(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val supplierId: GlobalId,
    val locationId: GlobalId,
    val date: BusinessDate,
    val expectedDate: BusinessDate,
    val lines: List<OrderLine>,
    val note: String = "",
) : Command {
    override val requiredPermission = Permission.PURCHASE_ORDER
    override fun fingerprint() = "$scope|$supplierId|$locationId|${date.epochDay}|${expectedDate.epochDay}|$note|" +
        lines.joinToString(";") { "${it.itemId}:${it.quantity.micros}:${it.unitPrice.rial}" }
}

@NoDocument
data class CancelPurchaseOrder(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val orderId: GlobalId,
    val reason: String,
) : Command {
    override val requiredPermission = Permission.PURCHASE_ORDER
    override fun fingerprint() = "$scope|$orderId|$reason"
}

class OrderOperations(
    private val bus: CommandBus,
    private val inventory: InventoryGateway,
    private val suppliers: SupplierStore,
    private val purchases: PurchaseStore,
) {
    fun create(c: CreatePurchaseOrder): CommandOutcome = bus.execute(ModuleId.PURCHASING, c) { cmd, ctx ->
        val supplier = suppliers.byId(cmd.supplierId) ?: throw DomainException(DomainError.NotFound("SUPPLIER"))
        ensure(supplier.isActive) { DomainError.InvalidState("SUPPLIER", "INACTIVE") }
        ensure(inventory.location(cmd.locationId).scope == cmd.scope) { DomainError.InvalidInput("scope", "انبار متعلق به این شعبه نیست.") }
        ensure(cmd.expectedDate >= cmd.date) { DomainError.InvalidInput("expectedDate", "تاریخ تحویل نمی‌تواند قبل از تاریخ سفارش باشد.") }
        ensure(cmd.lines.isNotEmpty() && cmd.lines.all { !it.quantity.isZero }) { DomainError.InvalidInput("lines", "حداقل یک کالا با مقدار مثبت لازم است.") }
        ensure(cmd.lines.map { it.itemId }.distinct().size == cmd.lines.size) { DomainError.InvalidInput("lines", "هر کالا فقط یک‌بار در سفارش بیاید.") }
        ensure(cmd.note.length <= 500) { DomainError.InvalidInput("note", "توضیح طولانی است.") }
        cmd.lines.forEach { line ->
            val item = inventory.item(line.itemId)
            // The approved-supplier list is enforced where buying is decided: the order.
            ensure(item.approvedSupplierIds.isEmpty() || supplier.id in item.approvedSupplierIds) {
                DomainError.InvalidState("ITEM", "SUPPLIER_NOT_APPROVED:${item.name}")
            }
        }
        val order = PurchaseOrder(
            GlobalId.new(), purchases.nextOrderNumber(), supplier.id, cmd.scope, cmd.locationId, cmd.date, cmd.expectedDate,
            cmd.lines, cmd.note.trim(), OrderStatus.OPEN,
        )
        purchases.saveOrder(order)
        ctx.number(DocumentSeries.PURCHASE_ORDER, cmd.date, order.id)
        ctx.audit(AuditDraft("PURCHASE_ORDER_CREATE", "PURCHASE_ORDER", order.id.value, "no=${order.number};supplier=${supplier.id};total=${order.total.rial}"))
        order.id
    }

    fun cancel(c: CancelPurchaseOrder): CommandOutcome = bus.execute(ModuleId.PURCHASING, c) { cmd, ctx ->
        val order = purchases.order(cmd.orderId) ?: throw DomainException(DomainError.NotFound("PURCHASE_ORDER"))
        ensure(order.scope == cmd.scope) { DomainError.InvalidInput("scope", "سفارش متعلق به این شعبه نیست.") }
        ensure(order.status == OrderStatus.OPEN) { DomainError.InvalidState("PURCHASE_ORDER", order.status.name) }
        ensure(cmd.reason.trim().length in 3..300) { DomainError.InvalidInput("reason", "دلیل لغو الزامی است.") }
        purchases.saveOrder(order.copy(status = OrderStatus.CANCELLED, cancelReason = cmd.reason.trim()))
        ctx.audit(AuditDraft("PURCHASE_ORDER_CANCEL", "PURCHASE_ORDER", order.id.value, cmd.reason.trim()))
        order.id
    }
}
