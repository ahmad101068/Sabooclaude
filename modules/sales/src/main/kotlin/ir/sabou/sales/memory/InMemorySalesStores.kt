package ir.sabou.sales.memory

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Scope
import ir.sabou.platform.memory.Table
import ir.sabou.platform.memory.Transactional
import ir.sabou.sales.Collection
import ir.sabou.sales.Customer
import ir.sabou.sales.CustomerStore
import ir.sabou.sales.DailySale
import ir.sabou.sales.Receivable
import ir.sabou.sales.SaleStatus
import ir.sabou.sales.SalesDay
import ir.sabou.sales.SalesStore

class InMemoryCustomerStore : Table<GlobalId, Customer>(), CustomerStore {
    override fun byId(id: GlobalId) = get(id)
    override fun all() = values()
    override fun save(customer: Customer) = put(customer.id, customer)
}

class InMemorySalesStore : SalesStore, Transactional {
    private val sales = LinkedHashMap<GlobalId, DailySale>()
    private val receivables = LinkedHashMap<GlobalId, Receivable>()
    private val collections = LinkedHashMap<GlobalId, Collection>()
    private val days = LinkedHashMap<Pair<Scope.Branch, Long>, SalesDay>()

    override fun sale(id: GlobalId) = sales[id]
    override fun activeSale(scope: Scope.Branch, date: BusinessDate) =
        sales.values.firstOrNull { it.scope == scope && it.date == date && it.status != SaleStatus.REVERSED }
    override fun saveSale(sale: DailySale) { sales[sale.id] = sale }
    override fun receivable(id: GlobalId) = receivables[id]
    override fun receivablesOfSale(saleId: GlobalId) = receivables.values.filter { it.saleId == saleId }
    override fun receivablesOfCustomer(customerId: GlobalId) = receivables.values.filter { it.customerId == customerId }
    override fun saveReceivable(receivable: Receivable) { receivables[receivable.id] = receivable }
    override fun collection(id: GlobalId) = collections[id]
    override fun collections(receivableId: GlobalId) = collections.values.filter { it.receivableId == receivableId }
    override fun saveCollection(collection: Collection) { collections[collection.id] = collection }
    override fun day(scope: Scope.Branch, date: BusinessDate) = days[scope to date.epochDay]
    override fun saveDay(day: SalesDay) { days[day.scope to day.date.epochDay] = day }

    override fun snapshot(): Any = listOf(LinkedHashMap(sales), LinkedHashMap(receivables), LinkedHashMap(collections), LinkedHashMap(days))
    @Suppress("UNCHECKED_CAST")
    override fun restore(snapshot: Any) {
        val l = snapshot as List<Map<Any, Any>>
        sales.clear(); sales.putAll(l[0] as Map<GlobalId, DailySale>)
        receivables.clear(); receivables.putAll(l[1] as Map<GlobalId, Receivable>)
        collections.clear(); collections.putAll(l[2] as Map<GlobalId, Collection>)
        days.clear(); days.putAll(l[3] as Map<Pair<Scope.Branch, Long>, SalesDay>)
    }
}

class InMemoryMenuPriceStore : ir.sabou.sales.MenuPriceStore, Transactional {
    private val prices = ArrayList<ir.sabou.sales.MenuPrice>()
    override fun versions(menuItemId: GlobalId) = prices.filter { it.menuItemId == menuItemId }
    override fun all() = prices.toList()
    override fun save(price: ir.sabou.sales.MenuPrice) { prices += price }
    override fun nextSequence(): Long = (prices.maxOfOrNull { it.sequence } ?: 0L) + 1
    override fun snapshot(): Any = ArrayList(prices)
    @Suppress("UNCHECKED_CAST")
    override fun restore(snapshot: Any) { prices.clear(); prices.addAll(snapshot as List<ir.sabou.sales.MenuPrice>) }
}
