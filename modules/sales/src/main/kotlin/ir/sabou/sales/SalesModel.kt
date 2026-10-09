package ir.sabou.sales

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Scope

enum class CustomerType { PERSON, COMPANY }

data class Customer(
    val id: GlobalId,
    val name: String,
    val type: CustomerType,
    val phone: String,
    val creditLimit: Money,
    val registeredIn: Scope.Branch,
    val isActive: Boolean = true,
)

data class SaleLine(val menuItemId: GlobalId, val portions: Quantity, val gross: Money)

sealed interface Settlement {
    val amount: Money
    /** Cash, card or bank transfer into a treasury account of the same branch. */
    data class Liquid(val treasuryAccountId: GlobalId, override val amount: Money) : Settlement
    /** On account: becomes a receivable. */
    data class Credit(val customerId: GlobalId, override val amount: Money, val dueDate: BusinessDate) : Settlement
}

enum class SaleStatus { DRAFT, POSTED, REVERSED }

/**
 * One branch's sales for one business day. Amounts are entered once; the totals the user sees
 * are computed here, so screen and books cannot disagree.
 */
data class DailySale(
    val id: GlobalId,
    val scope: Scope.Branch,
    val date: BusinessDate,
    val kitchenLocationId: GlobalId,
    val lines: List<SaleLine>,
    val discount: Money,
    val serviceCharge: Money,
    val tax: Money,
    val settlements: List<Settlement>,
    val status: SaleStatus,
    val revenueJournalId: GlobalId?,
    val cost: Money,
    val consumed: Boolean = false,
    /** Statistics for reports (average check, covers); no accounting effect. */
    val guests: Int = 0,
    val transactions: Int = 0,
) {
    val gross: Money get() = Money.sum(lines.map { it.gross })
    val netFood: Money get() = gross - discount
    val payable: Money get() = netFood + serviceCharge + tax
    val settled: Money get() = Money.sum(settlements.map { it.amount })
    val remaining: Long get() = payable.rial - settled.rial
}

data class Receivable(
    val id: GlobalId,
    val customerId: GlobalId,
    val scope: Scope.Branch,
    val saleId: GlobalId,
    val amount: Money,
    val dueDate: BusinessDate,
    val voided: Boolean,
)

data class Collection(
    val id: GlobalId,
    val receivableId: GlobalId,
    val treasuryAccountId: GlobalId,
    val amount: Money,
    val date: BusinessDate,
    val reversed: Boolean,
)

data class SalesDay(val scope: Scope.Branch, val date: BusinessDate, val closed: Boolean, val countedCash: Money?)

interface CustomerStore {
    fun byId(id: GlobalId): Customer?
    fun all(): List<Customer>
    fun save(customer: Customer)
}

interface SalesStore {
    fun sale(id: GlobalId): DailySale?
    fun activeSale(scope: Scope.Branch, date: BusinessDate): DailySale?
    fun saveSale(sale: DailySale)
    fun receivable(id: GlobalId): Receivable?
    fun receivablesOfSale(saleId: GlobalId): List<Receivable>
    fun receivablesOfCustomer(customerId: GlobalId): List<Receivable>
    fun saveReceivable(receivable: Receivable)
    fun collection(id: GlobalId): Collection?
    fun collections(receivableId: GlobalId): List<Collection>
    fun saveCollection(collection: Collection)
    fun day(scope: Scope.Branch, date: BusinessDate): SalesDay?
    fun saveDay(day: SalesDay)
}
