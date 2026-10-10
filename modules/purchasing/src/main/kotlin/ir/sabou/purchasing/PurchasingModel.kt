package ir.sabou.purchasing

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Ratio
import ir.sabou.kernel.Scope
import ir.sabou.ledger.AccountCode

/**
 * A supplier and when it delivers. [deliveryDays] are week days (0 = Saturday … 6 = Friday);
 * an order for a delivery must be placed [leadDays] before it, by [cutoffMinutes] past midnight.
 */
data class Supplier(
    val id: GlobalId,
    val name: String,
    val phone: String,
    val isActive: Boolean = true,
    val deliveryDays: Set<Int> = emptySet(),
    val cutoffMinutes: Int? = null,
    val leadDays: Int = 1,
    val note: String = "",
) {
    /** The first delivery whose order deadline has not passed (null when no delivery days are set). */
    fun nextDelivery(today: BusinessDate, minutesNow: Int): Delivery? {
        if (deliveryDays.isEmpty()) return null
        for (offset in 0L..21L) {
            val date = today.plusDays(offset)
            if (Weekdays.index(date) !in deliveryDays) continue
            val orderBy = date.plusDays(-leadDays.toLong())
            val missed = orderBy < today || (orderBy == today && cutoffMinutes != null && minutesNow > cutoffMinutes)
            if (!missed) return Delivery(date, orderBy, cutoffMinutes)
        }
        return null
    }
}

data class Delivery(val date: BusinessDate, val orderBy: BusinessDate, val cutoffMinutes: Int?)

object Weekdays {
    /** 0 = Saturday (the Iranian week), …, 6 = Friday. */
    fun index(date: BusinessDate): Int = Math.floorMod(date.epochDay + 5, 7L).toInt()
}

/** Goods line. [supplierItemName] is what the supplier calls it; it is remembered for next time. */
data class InvoiceLine(val itemId: GlobalId, val quantity: Quantity, val value: Money, val supplierItemName: String = "") {
    /** Rial per whole unit of the item. */
    val unitPrice: Long get() = if (quantity.isZero) 0 else Ratio.mulDiv(value.rial, Quantity.SCALE, quantity.micros)
}

/** A line booked straight to an expense account (gas bill, repairs), optionally for another branch. */
data class AccountLine(val account: AccountCode, val amount: Money, val branch: Scope.Branch? = null, val memo: String = "")

/** A line whose item is not known yet; held in 1302 until someone assigns it to an item or an account. */
data class ReviewLine(val supplierItemName: String, val quantityNote: String, val amount: Money, val resolution: ReviewResolution? = null)

data class ReviewResolution(
    val itemId: GlobalId?,
    val quantity: Quantity?,
    val locationId: GlobalId?,
    val account: AccountCode?,
    val journalId: GlobalId,
    val documentId: GlobalId,
    val date: BusinessDate,
)

enum class InvoiceStatus { POSTED, REVERSED }

/** Goods only (including held lines), expense lines only, or both. */
enum class InvoiceCategory { GOODS, EXPENSES, MIXED }

/** One approval step. [self] = the person approved an invoice they recorded themselves (owner only), with [reason]. */
data class Approval(val userId: GlobalId, val name: String, val atEpochMillis: Long, val self: Boolean = false, val reason: String? = null)

/**
 * Whether the owner may approve an invoice they recorded themselves, and up to which total. A self-approval
 * always needs a reason. Default: allowed without a cap (a one-person business), which the owner can tighten.
 */
data class SelfApprovalPolicy(val allowed: Boolean = true, val maxAmount: Money? = null)

/**
 * An invoice matching every non-null condition needs [steps] approvals (by different people, none of
 * them the person who recorded it) before it can be paid. The strictest matching rule wins.
 */
data class ApprovalRule(
    val id: GlobalId,
    val name: String,
    val branch: Scope.Branch?,
    val supplierId: GlobalId?,
    val category: InvoiceCategory?,
    val minAmount: Money,
    val steps: Int,
    val isActive: Boolean = true,
) {
    fun matches(scope: Scope.Branch, supplier: GlobalId, category: InvoiceCategory, total: Money): Boolean =
        isActive && (branch == null || branch == scope) && (supplierId == null || supplierId == supplier) &&
            (this.category == null || this.category == category) && total >= minAmount
}

interface ApprovalRuleStore {
    fun all(): List<ApprovalRule>
    fun byId(id: GlobalId): ApprovalRule?
    fun save(rule: ApprovalRule)
    fun selfApproval(): SelfApprovalPolicy
    fun saveSelfApproval(policy: SelfApprovalPolicy)
}

data class PurchaseInvoice(
    val id: GlobalId,
    val supplierId: GlobalId,
    val supplierInvoiceNo: String,
    val scope: Scope.Branch,
    /** Where goods were received; null for an invoice with no goods lines. */
    val locationId: GlobalId?,
    val date: BusinessDate,
    val dueDate: BusinessDate,
    val lines: List<InvoiceLine>,
    val total: Money,
    val status: InvoiceStatus,
    val accountLines: List<AccountLine> = emptyList(),
    val reviewLines: List<ReviewLine> = emptyList(),
    val note: String = "",
    val orderId: GlobalId? = null,
    /** Journals of the non-goods lines (the goods journal belongs to the stock receipt). */
    val journalIds: List<GlobalId> = emptyList(),
    val recordedBy: GlobalId? = null,
    /** Approvals needed before it may be paid, fixed when it is recorded (the rules then in force). */
    val requiredApprovals: Int = 0,
    val approvals: List<Approval> = emptyList(),
) {
    val category: InvoiceCategory get() = when {
        accountLines.isEmpty() -> InvoiceCategory.GOODS
        lines.isEmpty() && reviewLines.isEmpty() -> InvoiceCategory.EXPENSES
        else -> InvoiceCategory.MIXED
    }
    val approved: Boolean get() = approvals.size >= requiredApprovals
    val goodsTotal: Money get() = Money.sum(lines.map { it.value })
    val openReviewLines: List<Pair<Int, ReviewLine>> get() = reviewLines.withIndex().filter { it.value.resolution == null }.map { it.index to it.value }
}

data class SupplierPayment(
    val id: GlobalId,
    val invoiceId: GlobalId,
    val treasuryAccountId: GlobalId,
    val amount: Money,
    val date: BusinessDate,
    val bridgeJournalId: GlobalId?,
    val reversed: Boolean,
)

data class PurchaseReturn(
    val id: GlobalId,
    val invoiceId: GlobalId,
    val lines: List<InvoiceLine>,
    val credit: Money,
    val date: BusinessDate,
)

/**
 * Supplier credit (from a return) set against one invoice. A return's credit goes to its own invoice
 * first, then to the same supplier's other open invoices in that branch (oldest due first); what is
 * left stays as unapplied credit until it is applied to a later invoice. [released] = taken back.
 */
data class CreditAllocation(
    val id: GlobalId,
    val supplierId: GlobalId,
    val scope: Scope.Branch,
    val invoiceId: GlobalId,
    val amount: Money,
    val date: BusinessDate,
    val returnId: GlobalId?,
    val released: Boolean = false,
)

/**
 * The supplier paying back unapplied return credit (cash, transfer or a cheque into a cheque box).
 * [bridgeJournalId]: the branch side when the money arrives in another scope's account.
 */
data class SupplierRefund(
    val id: GlobalId,
    val supplierId: GlobalId,
    val scope: Scope.Branch,
    val treasuryAccountId: GlobalId,
    val amount: Money,
    val date: BusinessDate,
    val bridgeJournalId: GlobalId?,
    val reversed: Boolean = false,
)

enum class OrderStatus { OPEN, RECEIVED, CANCELLED }

/** Expected quantity and price per whole unit. */
data class OrderLine(val itemId: GlobalId, val quantity: Quantity, val unitPrice: Money) {
    val value: Money get() = Money.of(Ratio.mulDiv(unitPrice.rial, quantity.micros, Quantity.SCALE))
}

data class PurchaseOrder(
    val id: GlobalId,
    val number: Long,
    val supplierId: GlobalId,
    val scope: Scope.Branch,
    val locationId: GlobalId,
    val date: BusinessDate,
    val expectedDate: BusinessDate,
    val lines: List<OrderLine>,
    val note: String,
    val status: OrderStatus,
    val invoiceId: GlobalId? = null,
    val cancelReason: String? = null,
) {
    val total: Money get() = Money.sum(lines.map { it.value })
}

interface SupplierStore {
    fun byId(id: GlobalId): Supplier?
    fun all(): List<Supplier>
    fun save(supplier: Supplier)
    /** What the supplier calls our items: normalized name → item. */
    fun aliases(supplierId: GlobalId): Map<String, GlobalId>
    fun saveAlias(supplierId: GlobalId, name: String, itemId: GlobalId)
}

interface PurchaseStore {
    fun invoice(id: GlobalId): PurchaseInvoice?
    /** The POSTED invoice with this number; a reversed invoice frees its number for the corrected one. */
    fun invoiceByNumber(supplierId: GlobalId, normalizedNo: String): PurchaseInvoice?
    fun invoices(): List<PurchaseInvoice>
    fun saveInvoice(invoice: PurchaseInvoice)
    fun payment(id: GlobalId): SupplierPayment?
    fun payments(invoiceId: GlobalId): List<SupplierPayment>
    fun savePayment(payment: SupplierPayment)
    fun returns(invoiceId: GlobalId): List<PurchaseReturn>
    fun saveReturn(purchaseReturn: PurchaseReturn)
    fun allocation(id: GlobalId): CreditAllocation?
    fun allocationsTo(invoiceId: GlobalId): List<CreditAllocation>
    fun allocationsOf(supplierId: GlobalId, scope: Scope.Branch): List<CreditAllocation>
    fun saveAllocation(allocation: CreditAllocation)
    fun refund(id: GlobalId): SupplierRefund?
    fun refundsOf(supplierId: GlobalId, scope: Scope.Branch): List<SupplierRefund>
    fun saveRefund(refund: SupplierRefund)
    fun order(id: GlobalId): PurchaseOrder?
    fun orders(): List<PurchaseOrder>
    fun saveOrder(order: PurchaseOrder)
    fun nextOrderNumber(): Long
}

object SupplierNames {
    /** Same text however it was typed: Arabic/Persian letters, digits, spacing and case. */
    fun normalize(raw: String): String {
        val persian = "۰۱۲۳۴۵۶۷۸۹"; val arabic = "٠١٢٣٤٥٦٧٨٩"
        return raw.trim().lowercase().map { ch ->
            when (ch) {
                'ي', 'ى' -> 'ی'
                'ك' -> 'ک'
                'ة' -> 'ه'
                '‌' -> ' '
                in persian -> '0' + persian.indexOf(ch)
                in arabic -> '0' + arabic.indexOf(ch)
                else -> ch
            }
        }.joinToString("").split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }
}
