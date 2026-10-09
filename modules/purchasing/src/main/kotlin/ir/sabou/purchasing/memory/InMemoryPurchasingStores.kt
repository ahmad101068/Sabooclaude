package ir.sabou.purchasing.memory

import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Scope
import ir.sabou.platform.memory.Transactional
import ir.sabou.purchasing.CreditAllocation
import ir.sabou.purchasing.InvoiceStatus
import ir.sabou.purchasing.PurchaseInvoice
import ir.sabou.purchasing.PurchaseOrder
import ir.sabou.purchasing.PurchaseReturn
import ir.sabou.purchasing.PurchaseStore
import ir.sabou.purchasing.Supplier
import ir.sabou.purchasing.SupplierPayment
import ir.sabou.purchasing.SupplierStore

class InMemorySupplierStore : SupplierStore, Transactional {
    private val suppliers = LinkedHashMap<GlobalId, Supplier>()
    private val aliases = LinkedHashMap<Pair<GlobalId, String>, GlobalId>()
    override fun byId(id: GlobalId) = suppliers[id]
    override fun all() = suppliers.values.toList()
    override fun save(supplier: Supplier) { suppliers[supplier.id] = supplier }
    override fun aliases(supplierId: GlobalId) = aliases.filterKeys { it.first == supplierId }.mapKeys { it.key.second }
    override fun saveAlias(supplierId: GlobalId, name: String, itemId: GlobalId) { aliases[supplierId to name] = itemId }
    override fun snapshot(): Any = LinkedHashMap(suppliers) to LinkedHashMap(aliases)
    @Suppress("UNCHECKED_CAST")
    override fun restore(snapshot: Any) {
        val (s, a) = snapshot as Pair<Map<GlobalId, Supplier>, Map<Pair<GlobalId, String>, GlobalId>>
        suppliers.clear(); suppliers.putAll(s); aliases.clear(); aliases.putAll(a)
    }
}

class InMemoryPurchaseStore : PurchaseStore, Transactional {
    private val invoices = LinkedHashMap<GlobalId, PurchaseInvoice>()
    private val payments = LinkedHashMap<GlobalId, SupplierPayment>()
    private val returns = mutableListOf<PurchaseReturn>()
    private val allocations = LinkedHashMap<GlobalId, CreditAllocation>()
    private val orders = LinkedHashMap<GlobalId, PurchaseOrder>()
    override fun invoice(id: GlobalId) = invoices[id]
    override fun invoiceByNumber(supplierId: GlobalId, normalizedNo: String) =
        invoices.values.firstOrNull { it.supplierId == supplierId && it.supplierInvoiceNo == normalizedNo && it.status == InvoiceStatus.POSTED }
    override fun invoices() = invoices.values.toList()
    override fun saveInvoice(invoice: PurchaseInvoice) { invoices[invoice.id] = invoice }
    override fun payment(id: GlobalId) = payments[id]
    override fun payments(invoiceId: GlobalId) = payments.values.filter { it.invoiceId == invoiceId }
    override fun savePayment(payment: SupplierPayment) { payments[payment.id] = payment }
    override fun returns(invoiceId: GlobalId) = returns.filter { it.invoiceId == invoiceId }
    override fun saveReturn(purchaseReturn: PurchaseReturn) { returns += purchaseReturn }
    override fun allocation(id: GlobalId) = allocations[id]
    override fun allocationsTo(invoiceId: GlobalId) = allocations.values.filter { it.invoiceId == invoiceId }
    override fun allocationsOf(supplierId: GlobalId, scope: Scope.Branch) = allocations.values.filter { it.supplierId == supplierId && it.scope == scope }
    override fun saveAllocation(allocation: CreditAllocation) { allocations[allocation.id] = allocation }
    override fun order(id: GlobalId) = orders[id]
    override fun orders() = orders.values.toList()
    override fun saveOrder(order: PurchaseOrder) { orders[order.id] = order }
    override fun nextOrderNumber() = (orders.values.maxOfOrNull { it.number } ?: 0L) + 1
    override fun snapshot(): Any = listOf(LinkedHashMap(invoices), LinkedHashMap(payments), returns.toList(), LinkedHashMap(allocations), LinkedHashMap(orders))
    @Suppress("UNCHECKED_CAST")
    override fun restore(snapshot: Any) {
        val l = snapshot as List<Any>
        invoices.clear(); invoices.putAll(l[0] as Map<GlobalId, PurchaseInvoice>)
        payments.clear(); payments.putAll(l[1] as Map<GlobalId, SupplierPayment>)
        returns.clear(); returns.addAll(l[2] as List<PurchaseReturn>)
        allocations.clear(); allocations.putAll(l[3] as Map<GlobalId, CreditAllocation>)
        orders.clear(); orders.putAll(l[4] as Map<GlobalId, PurchaseOrder>)
    }
}

class InMemoryApprovalRuleStore : ir.sabou.platform.memory.Table<GlobalId, ir.sabou.purchasing.ApprovalRule>(), ir.sabou.purchasing.ApprovalRuleStore {
    override fun all() = values()
    override fun byId(id: GlobalId) = get(id)
    override fun save(rule: ir.sabou.purchasing.ApprovalRule) = put(rule.id, rule)
}
