package ir.sabou.purchasing

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope

data class Supplier(val id: GlobalId, val name: String, val phone: String, val isActive: Boolean = true)

data class InvoiceLine(val itemId: GlobalId, val quantity: Quantity, val value: Money)

enum class InvoiceStatus { POSTED, REVERSED }

data class PurchaseInvoice(
    val id: GlobalId,
    val supplierId: GlobalId,
    val supplierInvoiceNo: String,
    val scope: Scope.Branch,
    val locationId: GlobalId,
    val date: BusinessDate,
    val dueDate: BusinessDate,
    val lines: List<InvoiceLine>,
    val total: Money,
    val status: InvoiceStatus,
)

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

interface SupplierStore {
    fun byId(id: GlobalId): Supplier?
    fun all(): List<Supplier>
    fun save(supplier: Supplier)
}

interface PurchaseStore {
    fun invoice(id: GlobalId): PurchaseInvoice?
    fun invoiceByNumber(supplierId: GlobalId, normalizedNo: String): PurchaseInvoice?
    fun invoices(): List<PurchaseInvoice>
    fun saveInvoice(invoice: PurchaseInvoice)
    fun payment(id: GlobalId): SupplierPayment?
    fun payments(invoiceId: GlobalId): List<SupplierPayment>
    fun savePayment(payment: SupplierPayment)
    fun returns(invoiceId: GlobalId): List<PurchaseReturn>
    fun saveReturn(purchaseReturn: PurchaseReturn)
}
