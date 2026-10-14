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
    /** The branch that registered the customer; any branch may sell to them on credit. */
    val registeredIn: Scope.Branch,
    val isActive: Boolean = true,
    /** National ID, legal-entity ID or economic code, for invoices with the buyer's details (ADR-0037). */
    val taxId: String = "",
    val postalCode: String = "",
)

/**
 * One menu item of the day, as recorded. [gross] = [unitPrice] × [portions] (half-up to the Rial), computed by the
 * sales domain and kept as recorded. [listPrice] is the menu price in force when the line was recorded (null when the
 * item had none and the price was typed at the sale); a [unitPrice] that differs from it is an override and carries
 * its [overrideReason].
 */
data class SaleLine(
    val menuItemId: GlobalId,
    val portions: Quantity,
    val unitPrice: Money,
    val gross: Money,
    val listPrice: Money? = null,
    val overrideReason: String? = null,
) {
    val overridden: Boolean get() = listPrice != null && unitPrice != listPrice

    companion object {
        fun priced(menuItemId: GlobalId, portions: Quantity, unitPrice: Money, listPrice: Money?, overrideReason: String?): SaleLine =
            SaleLine(menuItemId, portions, unitPrice, unitPrice.times(portions), listPrice, overrideReason)
    }
}

/**
 * What is entered for a line: the item, how many and — only when the menu has no price for it, or the price is
 * overridden — the unit price. The total is never entered; the domain computes it.
 */
data class SaleLineInput(
    val menuItemId: GlobalId,
    val portions: Quantity,
    val unitPrice: Money? = null,
    val overrideReason: String? = null,
) : java.io.Serializable

sealed interface Settlement : java.io.Serializable {
    val amount: Money
    /**
     * Cash, card or bank transfer into a treasury account of the same branch, or a customer's cheque
     * into a cheque box ([cheque] then holds its details; one settlement per cheque).
     */
    data class Liquid(val treasuryAccountId: GlobalId, override val amount: Money, val cheque: ir.sabou.treasury.ChequeDetails? = null) : Settlement
    /** On account: becomes a receivable. */
    data class Credit(val customerId: GlobalId, override val amount: Money, val dueDate: BusinessDate) : Settlement
}

/** DRAFT → POSTED (→ REVERSED); a draft never posted can be DISCARDED (kept for the audit trail). */
enum class SaleStatus { DRAFT, POSTED, REVERSED, DISCARDED }

/**
 * One sale (a bill, or a batch such as a shift's or an imported POS day) of a branch on a business day; a day has
 * as many as needed, each numbered when posted. Amounts are entered once; the totals the user sees are computed
 * here, so screen and books cannot disagree.
 */
/** How a sale reached us (ADR-0052). Corporate: contract and company orders; online: ordering apps and the website. */
enum class SalesChannel { DINE_IN, TAKEAWAY, DELIVERY, ONLINE, CORPORATE, OTHER }

/**
 * The part of a sale's net food sales (before service and tax) that came through [channel], with its bills. In a
 * recorded sale the shares add up exactly to [DailySale.netFood]; in [SaveSaleDraft] they are proportions.
 */
data class ChannelShare(val channel: SalesChannel, val amount: Money, val transactions: Int = 0) : java.io.Serializable

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
    /** Why the [discount] was given (required with a discount). */
    val discountReason: String = "",
    /** Net food sales by channel (ADR-0052); empty: not recorded. */
    val channels: List<ChannelShare> = emptyList(),
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
    /** Parts written off as uncollectible (bad debt), each with its date and reason. */
    val writeOffs: List<WriteOff> = emptyList(),
) {
    val writtenOff: Money get() = Money.sum(writeOffs.map { it.amount })
}

data class WriteOff(val date: BusinessDate, val amount: Money, val reason: String)

data class Collection(
    val id: GlobalId,
    val receivableId: GlobalId,
    val treasuryAccountId: GlobalId,
    val amount: Money,
    val date: BusinessDate,
    val reversed: Boolean,
    /** Set from a customer's deposit (with the journal 2109 → receivable), not received into [treasuryAccountId]. */
    val depositId: GlobalId? = null,
    val journalId: GlobalId? = null,
)

/**
 * A branch's business day. Closing it counts the cash box ([cashAccountId]): [countedCash] is what was in it,
 * [difference] = counted − book (− short, + over), booked under the cash count [countId].
 */
data class SalesDay(
    val scope: Scope.Branch,
    val date: BusinessDate,
    val closed: Boolean,
    val countedCash: Money?,
    val cashAccountId: GlobalId? = null,
    val difference: Long = 0,
    val countId: GlobalId? = null,
    /** Card terminal settlement report minus the card sales recorded, per terminal account (+ = terminal shows more). */
    val terminalDifferences: Map<GlobalId, Long> = emptyMap(),
)

interface CustomerStore {
    fun byId(id: GlobalId): Customer?
    fun all(): List<Customer>
    fun save(customer: Customer)
}

interface SalesStore {
    fun sale(id: GlobalId): DailySale?
    /** Every sale of the branch on [date] (any status), in recording order. */
    fun salesOn(scope: Scope.Branch, date: BusinessDate): List<DailySale>
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

/** One imported point-of-sale day (ADR-0026). [active] until its sale is reversed. */
data class PosImport(
    val id: GlobalId,
    val pos: String,
    val scope: Scope.Branch,
    val date: BusinessDate,
    val fileNames: List<String>,
    val fileHashes: List<String>,
    val invoiceKeys: List<String>,
    val saleId: GlobalId,
    val active: Boolean,
)

interface PosImportStore {
    fun all(): List<PosImport>
    fun ofSale(saleId: GlobalId): PosImport?
    fun save(import: PosImport)
    /** POS item names already matched to the menu: normalized name → menu item. */
    fun aliases(pos: String): Map<String, GlobalId>
    fun saveAlias(pos: String, key: String, menuItemId: GlobalId)
}

