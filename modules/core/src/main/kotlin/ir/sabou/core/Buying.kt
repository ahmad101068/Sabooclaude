package ir.sabou.core

import ir.sabou.inventory.Item
import ir.sabou.inventory.PrepGraph
import ir.sabou.inventory.RecipeBook
import ir.sabou.inventory.StockUnit
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Ratio
import ir.sabou.kernel.Scope
import ir.sabou.ledger.Account
import ir.sabou.ledger.AccountType
import ir.sabou.platform.Actor
import ir.sabou.platform.Attachment
import ir.sabou.platform.Permission
import ir.sabou.purchasing.Delivery
import ir.sabou.purchasing.InvoiceStatus
import ir.sabou.purchasing.OrderStatus
import ir.sabou.purchasing.PurchaseInvoice
import ir.sabou.purchasing.PurchaseOrder
import ir.sabou.purchasing.PurchasingOperations
import ir.sabou.purchasing.ReviewLine
import ir.sabou.purchasing.Supplier
import ir.sabou.purchasing.SupplierNames

/** One supplier: what the visible branches owe, return credit per branch, and refunds received. */
data class SupplierAccount(
    val supplier: Supplier,
    val owed: Money,
    val credits: List<Pair<Scope.Branch, Money>>,
    val refunds: List<ir.sabou.purchasing.SupplierRefund>,
)

data class OrderRow(val order: PurchaseOrder, val supplier: String, val location: String, val number: String? = null)

/** A held invoice line waiting for someone to say what it is. */
data class ReviewItem(val invoice: PurchaseInvoice, val supplier: String, val index: Int, val line: ReviewLine)

/** A supplier charging a different unit price than last time ([changeBp] in basis points, + = dearer). */
data class PriceChange(
    val item: Item,
    val supplier: String,
    val invoiceId: GlobalId,
    val date: BusinessDate,
    val previousDate: BusinessDate,
    val previousPrice: Long,
    val price: Long,
) {
    /** null when there was no earlier price to compare with. */
    val changeBp: Long? get() = Ratio.changeBp(previousPrice, price)
}

data class PlanLine(val menuItemId: GlobalId, val portions: Quantity)

/**
 * What to buy for one item at one location: enough to be back at the par level after covering the
 * planned dishes. [suggested] = par + planned − on hand − already on order (never below zero).
 */
data class Suggestion(
    val item: Item,
    val onHand: Quantity,
    val onOrder: Quantity,
    val par: Quantity,
    val planned: Quantity,
    val suggested: Quantity,
    /** Rial per whole unit (last price from this supplier, else from any supplier in the branch). */
    val unitPrice: Long?,
) {
    val value: Money? get() = unitPrice?.let { Money.of(Ratio.mulDiv(it, suggested.micros, Quantity.SCALE)) }
}

data class SuggestionGroup(val supplier: Supplier?, val delivery: Delivery?, val lines: List<Suggestion>) {
    val total: Money get() = Money.sum(lines.mapNotNull { it.value })
}

/** Something the plan needs that could not be broken down into what to buy; shown with the suggestion. */
data class RecipeProblem(val name: String, val kind: Kind, val detail: String) {
    enum class Kind { NO_RECIPE, CYCLE }
}

data class SuggestedOrders(val groups: List<SuggestionGroup>, val problems: List<RecipeProblem>)

/**
 * Purchasing read models beyond [Overview]: orders, the review queue, price changes, suggested
 * orders, supplier item names and attachments. Same rules: signed-in actor, permission, branch scope.
 */
class Buying internal constructor(private val core: SabouCore) {
    private fun actor(vararg anyOf: Permission): Actor {
        val a = core.session.currentActor() ?: throw DomainException(DomainError.AuthenticationRequired)
        if (anyOf.none { a.role.allows(it) }) throw DomainException(DomainError.PermissionDenied(anyOf.first().name))
        return a
    }

    private fun Actor.require(scope: Scope) {
        if (!canAccess(scope)) throw DomainException(DomainError.ScopeDenied(ir.sabou.platform.CommandContext.scopeLabel(scope)))
    }

    private fun supplierNames() = core.suppliers.all().associate { it.id to it.name }

    // ------------------------------------------------------------ Orders

    fun orders(): List<OrderRow> {
        val a = actor(Permission.PURCHASE_VIEW, Permission.PURCHASE_ORDER)
        val names = supplierNames()
        val locations = core.locations.all().associate { it.id to it.name }
        return core.purchases.orders().filter { a.canAccess(it.scope) }
            .let { list -> val nos = core.numbers.of(ir.sabou.platform.DocumentSeries.PURCHASE_ORDER, list.map { it.id }); list.map { OrderRow(it, names[it.supplierId].orEmpty(), locations[it.locationId].orEmpty(), nos[it.id]?.text) } }
    }

    fun order(id: GlobalId): OrderRow {
        val a = actor(Permission.PURCHASE_VIEW, Permission.PURCHASE_ORDER)
        val order = core.purchases.order(id) ?: throw DomainException(DomainError.NotFound("PURCHASE_ORDER"))
        a.require(order.scope)
        return OrderRow(order, core.suppliers.byId(order.supplierId)?.name.orEmpty(), core.locations.byId(order.locationId)?.name.orEmpty(),
            core.numbers.of(ir.sabou.platform.DocumentSeries.PURCHASE_ORDER, order.id)?.text)
    }

    // ------------------------------------------------------------ Supplier account

    fun supplierAccount(supplierId: GlobalId): SupplierAccount {
        val a = actor(Permission.PURCHASE_VIEW, Permission.SUPPLIER_MANAGE)
        val supplier = core.suppliers.byId(supplierId) ?: throw DomainException(DomainError.NotFound("SUPPLIER"))
        val invoices = core.purchases.invoices().filter { it.supplierId == supplierId && a.canAccess(it.scope) }
        val scopes = invoices.map { it.scope }.distinct()
        return SupplierAccount(
            supplier,
            Money.sum(invoices.filter { it.status == InvoiceStatus.POSTED }.map { core.purchasing.outstanding(it.id) }),
            scopes.map { it to core.purchasing.unappliedCredit(supplierId, it) }.filter { !it.second.isZero },
            scopes.flatMap { core.purchases.refundsOf(supplierId, it) }.sortedByDescending { it.date },
        )
    }

    // ------------------------------------------------------------ Review queue

    fun reviewQueue(): List<ReviewItem> {
        val a = actor(Permission.PURCHASE_VIEW)
        val names = supplierNames()
        return core.purchases.invoices().filter { it.status == InvoiceStatus.POSTED && a.canAccess(it.scope) }.flatMap { inv ->
            inv.openReviewLines.map { (i, line) -> ReviewItem(inv, names[inv.supplierId].orEmpty(), i, line) }
        }.sortedBy { it.invoice.date }
    }

    // ------------------------------------------------------------ Prices

    /** Unit price changes on invoices dated in [from]..[to] against the same supplier's previous invoice for the item. */
    fun priceChanges(from: BusinessDate, to: BusinessDate, thresholdBp: Long = 500): List<PriceChange> {
        val a = actor(Permission.PURCHASE_VIEW)
        val items = core.items.all().associateBy { it.id }
        val names = supplierNames()
        val invoices = core.purchases.invoices().filter { it.status == InvoiceStatus.POSTED && a.canAccess(it.scope) }
            .withIndex().sortedWith(compareBy({ it.value.date }, { it.index })).map { it.value }
        val last = HashMap<Pair<GlobalId, GlobalId>, Pair<BusinessDate, Long>>()
        val changes = ArrayList<PriceChange>()
        invoices.forEach { inv ->
            inv.lines.filter { !it.quantity.isZero }.groupBy { it.itemId }.forEach { (itemId, lines) ->
                val price = Ratio.mulDiv(lines.sumOf { it.value.rial }, Quantity.SCALE, lines.sumOf { it.quantity.micros })
                val key = inv.supplierId to itemId
                val previous = last[key]
                if (previous != null && inv.date >= from && inv.date <= to) {
                    val change = PriceChange(items[itemId] ?: return@forEach, names[inv.supplierId].orEmpty(), inv.id, inv.date, previous.first, previous.second, price)
                    val bp = change.changeBp
                    if (bp == null || kotlin.math.abs(bp) >= thresholdBp) changes += change
                }
                last[key] = inv.date to price
            }
        }
        return changes.sortedByDescending { it.date }
    }

    /** The last unit price paid to [supplierId] for each item (form hints and price warnings). */
    fun lastPrices(supplierId: GlobalId, branch: Scope.Branch): Map<GlobalId, Long> {
        val a = actor(Permission.PURCHASE_VIEW, Permission.PURCHASE_ORDER)
        a.require(branch)
        val result = HashMap<GlobalId, Long>()
        core.purchases.invoices().filter { it.status == InvoiceStatus.POSTED && it.supplierId == supplierId && a.canAccess(it.scope) }
            .withIndex().sortedWith(compareBy({ it.value.date }, { it.index })).map { it.value }
            .forEach { inv -> inv.lines.filter { !it.quantity.isZero }.forEach { result[it.itemId] = it.unitPrice } }
        return result
    }

    /** What [supplierId] calls our items (normalized name → item). */
    fun supplierItemNames(supplierId: GlobalId): Map<String, GlobalId> {
        actor(Permission.PURCHASE_RECORD, Permission.PURCHASE_VIEW)
        return core.suppliers.aliases(supplierId)
    }

    fun matchSupplierName(supplierId: GlobalId, name: String): GlobalId? = supplierItemNames(supplierId)[SupplierNames.normalize(name)]

    /** Accounts an invoice line may be booked to directly (open expense accounts). */
    fun expenseAccounts(): List<Account> {
        actor(Permission.PURCHASE_RECORD, Permission.PURCHASE_VIEW)
        return core.accounts.all().filter { it.isActive && it.type == AccountType.EXPENSE && !it.isControl }
    }

    // ------------------------------------------------------------ Attachments

    /** Bytes of an attachment; the reader must be allowed to see the document it belongs to. */
    fun attachment(id: GlobalId): Pair<Attachment, ByteArray> {
        val meta = core.attachments.meta(id) ?: throw DomainException(DomainError.NotFound("ATTACHMENT"))
        when (meta.ownerType) {
            PurchasingOperations.INVOICE -> {
                val a = actor(Permission.PURCHASE_VIEW)
                val invoice = core.purchases.invoice(meta.ownerId) ?: throw DomainException(DomainError.NotFound("PURCHASE_INVOICE"))
                a.require(invoice.scope)
            }
            else -> attachmentOwnerCheck(meta)
        }
        return meta to (core.attachments.content(id) ?: throw DomainException(DomainError.NotFound("ATTACHMENT")))
    }

    /** Hook for attachments of other modules (cheques); refuses unknown owners. */
    internal var attachmentOwnerCheck: (Attachment) -> Unit = { throw DomainException(DomainError.PermissionDenied("ATTACHMENT")) }

    // ------------------------------------------------------------ Suggested orders

    /**
     * Suggested purchases for one location, grouped by preferred supplier with the next delivery the
     * order can still make. [plan] = dishes expected before the next delivery (prepared items in their
     * recipes are broken down into what they are made of, after using what is already prepared).
     */
    fun suggestions(locationId: GlobalId, plan: List<PlanLine>, date: BusinessDate, minutesNow: Int): SuggestedOrders {
        val a = actor(Permission.PURCHASE_ORDER, Permission.PURCHASE_RECORD)
        val location = core.locations.byId(locationId) ?: throw DomainException(DomainError.NotFound("LOCATION"))
        a.require(location.scope)
        val items = core.items.all().associateBy { it.id }
        val book = RecipeBook(core.recipes)
        fun onHand(id: GlobalId) = core.stock.balance(id, locationId).quantity.micros

        // Planned need: dishes → ingredients; prepared items → their ingredients for the shortfall only.
        val problems = ArrayList<RecipeProblem>()
        val dishes = HashMap<GlobalId, Long>()
        plan.filter { !it.portions.isZero }.forEach { p ->
            val lines = runCatching { book.requirements(p.menuItemId, date, p.portions) }.getOrNull()
            if (lines == null) {
                problems += RecipeProblem(core.recipes.menuItem(p.menuItemId)?.name ?: "آیتم منو", RecipeProblem.Kind.NO_RECIPE, "رسپی این آیتم منو در این تاریخ نیست؛ مواد آن در پیشنهاد نیامده است.")
            }
            lines?.forEach { dishes[it.itemId] = (dishes[it.itemId] ?: 0L) + it.quantity.micros }
        }
        val expansion = PrepGraph(core.recipes) { items[it]?.prepared == true }.expand(dishes, date, ::onHand)
        expansion.problems.forEach { pr ->
            problems += when (pr) {
                is PrepGraph.Problem.NoRecipe -> RecipeProblem(items[pr.itemId]?.name ?: "کالای آماده", RecipeProblem.Kind.NO_RECIPE,
                    "رسپی تولید ندارد؛ مواد آن در پیشنهاد نیامده است.")
                is PrepGraph.Problem.Cycle -> RecipeProblem(items[pr.itemId]?.name ?: "کالای آماده", RecipeProblem.Kind.CYCLE,
                    "رسپی‌ها چرخه دارند: " + pr.path.joinToString(" ← ") { items[it]?.name ?: "؟" })
            }
        }
        val need = expansion.need
        val onOrder = HashMap<GlobalId, Long>()
        core.purchases.orders().filter { it.status == OrderStatus.OPEN && it.locationId == locationId }
            .forEach { o -> o.lines.forEach { onOrder[it.itemId] = (onOrder[it.itemId] ?: 0) + it.quantity.micros } }

        val branchInvoices = core.purchases.invoices().filter { it.status == InvoiceStatus.POSTED && it.scope == location.scope }
            .withIndex().sortedWith(compareBy({ it.value.date }, { it.index })).map { it.value }
        val lastBySupplier = HashMap<Pair<GlobalId, GlobalId>, Long>()
        val lastAny = HashMap<GlobalId, Long>()
        branchInvoices.forEach { inv ->
            inv.lines.filter { !it.quantity.isZero }.forEach { l -> lastBySupplier[inv.supplierId to l.itemId] = l.unitPrice; lastAny[l.itemId] = l.unitPrice }
        }

        val rows = items.values.filter { it.isActive && !it.prepared }.mapNotNull { item ->
            val planned = need[item.id] ?: 0L
            if (item.parLevel.isZero && planned == 0L) return@mapNotNull null
            val raw = item.parLevel.micros + planned - onHand(item.id) - (onOrder[item.id] ?: 0L)
            val suggested = roundUp(item, maxOf(0L, raw))
            val price = item.preferredSupplierId?.let { lastBySupplier[it to item.id] } ?: lastAny[item.id]
            item to Suggestion(item, Quantity.of(onHand(item.id)), Quantity.of(onOrder[item.id] ?: 0L), item.parLevel, Quantity.of(planned), Quantity.of(suggested), price)
        }.filter { !it.second.suggested.isZero }
        val suppliers = core.suppliers.all().associateBy { it.id }
        return rows.groupBy { (item, _) -> item.preferredSupplierId?.let { suppliers[it] }?.takeIf { it.isActive } }
            .map { (supplier, l) ->
                SuggestionGroup(supplier, supplier?.nextDelivery(date, minutesNow), l.map { it.second }.sortedBy { it.item.shelf + "\u0000" + it.item.name })
            }
            .sortedWith(compareBy({ it.supplier == null }, { it.delivery?.orderBy?.epochDay ?: Long.MAX_VALUE }, { it.supplier?.name }))
            .let { SuggestedOrders(it, problems) }
    }

    /** Countable units are bought whole. */
    private fun roundUp(item: Item, micros: Long): Long = when (item.unit) {
        StockUnit.PIECE, StockUnit.PACK -> ((micros + Quantity.SCALE - 1) / Quantity.SCALE) * Quantity.SCALE
        else -> micros
    }
}
