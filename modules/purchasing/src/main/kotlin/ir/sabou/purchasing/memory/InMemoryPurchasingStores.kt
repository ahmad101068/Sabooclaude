package ir.sabou.purchasing.memory

import ir.sabou.kernel.GlobalId
import ir.sabou.platform.memory.Table
import ir.sabou.platform.memory.Transactional
import ir.sabou.purchasing.PurchaseInvoice
import ir.sabou.purchasing.PurchaseReturn
import ir.sabou.purchasing.PurchaseStore
import ir.sabou.purchasing.Supplier
import ir.sabou.purchasing.SupplierPayment
import ir.sabou.purchasing.SupplierStore

class InMemorySupplierStore : Table<GlobalId, Supplier>(), SupplierStore {
    override fun byId(id: GlobalId) = get(id)
    override fun all() = values()
    override fun save(supplier: Supplier) = put(supplier.id, supplier)
}

class InMemoryPurchaseStore : PurchaseStore, Transactional {
    private val invoices = LinkedHashMap<GlobalId, PurchaseInvoice>()
    private val payments = LinkedHashMap<GlobalId, SupplierPayment>()
    private val returns = mutableListOf<PurchaseReturn>()
    override fun invoice(id: GlobalId) = invoices[id]
    override fun invoiceByNumber(supplierId: GlobalId, normalizedNo: String) =
        invoices.values.firstOrNull { it.supplierId == supplierId && it.supplierInvoiceNo == normalizedNo }
    override fun invoices() = invoices.values.toList()
    override fun saveInvoice(invoice: PurchaseInvoice) { invoices[invoice.id] = invoice }
    override fun payment(id: GlobalId) = payments[id]
    override fun payments(invoiceId: GlobalId) = payments.values.filter { it.invoiceId == invoiceId }
    override fun savePayment(payment: SupplierPayment) { payments[payment.id] = payment }
    override fun returns(invoiceId: GlobalId) = returns.filter { it.invoiceId == invoiceId }
    override fun saveReturn(purchaseReturn: PurchaseReturn) { returns += purchaseReturn }
    override fun snapshot(): Any = Triple(LinkedHashMap(invoices), LinkedHashMap(payments), returns.toList())
    @Suppress("UNCHECKED_CAST")
    override fun restore(snapshot: Any) {
        val (i, p, r) = snapshot as Triple<Map<GlobalId, PurchaseInvoice>, Map<GlobalId, SupplierPayment>, List<PurchaseReturn>>
        invoices.clear(); invoices.putAll(i); payments.clear(); payments.putAll(p); returns.clear(); returns.addAll(r)
    }
}
