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
    /** Who the previous price was paid to (may be another supplier). */
    val previousSupplier: String = "",
) {
    /** null when there was no earlier price to compare with. */
    val changeBp: Long? get() = Ratio.changeBp(previousPrice, price)
}

data class PlanLine(val menuItemId: GlobalId, val portions: Quantity)

/**
 * What to buy for one item at one location: enough to be back at the par level after covering the
 * planned dishes. [suggested] = par + planned − on hand − already on order (never below zero). [par] is the one for
 * the weekday of the supplier's next delivery when the item has one ([deliveryDay], ADR-0032).
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
    /** The delivery the par was chosen for, when it is a weekday's own par. */
    val deliveryDay: BusinessDate? = null,
    /**
     * From the sales forecast (ADR-0054): what the expected dishes use from the delivery until the one after it.
     * The order then restores the larger of [par] and this cover; [planned] is the expected use before the delivery.
     */
    val forecastCover: Quantity? = null,
) {
    val value: Money? get() = unitPrice?.let { Money.of(Ratio.mulDiv(it, suggested.micros, Quantity.SCALE)) }
}

/**
 * An ingredient the expected dishes use up before its next delivery (ADR-0054): [need] until [delivery] (or the
 * next 7 days without one) is more than [onHand] + [onOrder] in the branch; [runsOutOn] is the first day short.
 */
data class RunOut(val item: Item, val onHand: Quantity, val onOrder: Quantity, val need: Quantity, val runsOutOn: BusinessDate, val delivery: BusinessDate?, val supplier: String?)

/** The last price paid for an item: per unit, to whom, when. */
data class LastPaid(val price: Long, val supplier: String, val date: BusinessDate)

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
    companion object {
        /** Days ahead the run-out check looks without a delivery to wait for. */
        const val RUN_OUT_DAYS = 7
    }

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

    /** The order as an SMS for its supplier (ADR-0046): branch, number, delivery, items and quantities. */
    fun orderSms(id: GlobalId): OrderSms {
        val row = order(id)
        val o = row.order
        val supplier = core.suppliers.byId(o.supplierId)
        val branch = core.branches.all().firstOrNull { it.id == o.scope.branchId }?.name.orEmpty()
        val items = core.items.all().associateBy { it.id }
        val head = listOfNotNull(
            "سفارش خرید $branch" + " · شماره ${row.number?.let(Fa::digits) ?: Fa.number(o.number)}",
            "تحویل: ${Fa.dayTitle(o.expectedDate)} (${Fa.date(o.expectedDate)})" + if (row.location.isNotBlank()) " · ${row.location}" else "",
        ).joinToString("\n")
        val lines = o.lines.map { l -> val item = items[l.itemId]; "- ${item?.name.orEmpty()}: ${Fa.quantity(l.quantity)} ${item?.let { SmsText.unit(it.unit) }.orEmpty()}".trimEnd() }
        val tail = listOfNotNull(o.note.takeIf { it.isNotBlank() }, "لطفاً دریافت سفارش را اعلام کنید.")
        val body = SmsText.fitLines(head, lines + tail, SmsText.ORDER_MAX_CHARS) { n -> "… و ${Fa.number(n.toLong())} ردیف دیگر (فهرست کامل در PDF سفارش)" }
        return OrderSms(supplier?.phone?.let(ir.sabou.kernel.Mobile::normalize), body, supplier?.orderSms == true)
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
    /**
     * Unit prices that moved by at least [thresholdBp] against the last price paid for the same item — from any
     * supplier, into any warehouse or branch the person can see — so a dearer supplier or another warehouse's
     * purchase is not missed.
     */
    fun priceChanges(from: BusinessDate, to: BusinessDate, thresholdBp: Long = 500): List<PriceChange> {
        val a = actor(Permission.PURCHASE_VIEW)
        val items = core.items.all().associateBy { it.id }
        val names = supplierNames()
        val last = HashMap<GlobalId, LastPaid>()
        val changes = ArrayList<PriceChange>()
        postedInOrder(a).forEach { inv ->
            inv.lines.filter { !it.quantity.isZero }.groupBy { it.itemId }.forEach { (itemId, lines) ->
                val price = Ratio.mulDiv(lines.sumOf { it.value.rial }, Quantity.SCALE, lines.sumOf { it.quantity.micros })
                val previous = last[itemId]
                if (previous != null && inv.date >= from && inv.date <= to) {
                    val change = PriceChange(items[itemId] ?: return@forEach, names[inv.supplierId].orEmpty(), inv.id, inv.date, previous.date, previous.price, price, previous.supplier)
                    val bp = change.changeBp
                    if (bp == null || kotlin.math.abs(bp) >= thresholdBp) changes += change
                }
                last[itemId] = LastPaid(price, names[inv.supplierId].orEmpty(), inv.date)
            }
        }
        return changes.sortedByDescending { it.date }
    }

    /** The last unit price paid for each item, from any supplier, in what the person can see (form hints). */
    fun lastPaid(branch: Scope.Branch): Map<GlobalId, LastPaid> {
        val a = actor(Permission.PURCHASE_VIEW, Permission.PURCHASE_RECORD, Permission.PURCHASE_ORDER)
        a.require(branch)
        val names = supplierNames()
        val result = HashMap<GlobalId, LastPaid>()
        postedInOrder(a).forEach { inv -> inv.lines.filter { !it.quantity.isZero }.forEach { result[it.itemId] = LastPaid(it.unitPrice, names[inv.supplierId].orEmpty(), inv.date) } }
        return result
    }

    private fun postedInOrder(a: Actor) = core.purchases.invoices().filter { it.status == InvoiceStatus.POSTED && a.canAccess(it.scope) }
        .withIndex().sortedWith(compareBy({ it.value.date }, { it.index })).map { it.value }

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
        val location = suggestionLocation(locationId)
        val problems = ArrayList<RecipeProblem>()
        val dishes = HashMap<GlobalId, Long>()
        plan.filter { !it.portions.isZero }.forEach { dishes[it.menuItemId] = (dishes[it.menuItemId] ?: 0L) + it.portions.micros }
        val need = rawNeed(dishes, date, { core.stock.balance(it, locationId).quantity.micros }, problems)
        return build(location, date, minutesNow, problems) { item, _ -> (need[item.id] ?: 0L) to null }
    }

    /**
     * Suggested purchases from the sales forecast (ADR-0054): for each item, what the expected dishes use until its
     * supplier's next delivery, plus the larger of its par and what they use from that delivery until the one after
     * (7 days without delivery days; tomorrow and a week for an item without a supplier).
     */
    fun forecastSuggestions(locationId: GlobalId, date: BusinessDate, minutesNow: Int): SuggestedOrders {
        val location = suggestionLocation(locationId)
        val problems = ArrayList<RecipeProblem>()
        val windows = HashMap<Pair<Long, Long>, Pair<Map<GlobalId, Long>, Map<GlobalId, Long>>>()
        fun onHand(id: GlobalId) = core.stock.balance(id, locationId).quantity.micros
        val suppliers = core.suppliers.all().associateBy { it.id }
        return build(location, date, minutesNow, problems) { item, _ ->
            val supplier = item.preferredSupplierId?.let { suppliers[it] }?.takeIf { it.isActive }
            val (d1, d2) = deliveryWindow(supplier, date, minutesNow)
            val (before, cover) = windows.getOrPut(d1.epochDay to d2.epochDay) {
                // Prepared stock is used up first, once: before the delivery; the cover after it takes none of it.
                rawNeed(forecastDishes(location.scope as Scope.Branch, date, d1), date, ::onHand, problems) to
                    rawNeed(forecastDishes(location.scope as Scope.Branch, d1, d2), d1, { 0L }, ArrayList())
            }
            (before[item.id] ?: 0L) to (cover[item.id] ?: 0L)
        }
    }

    /**
     * Ingredients the expected dishes use up before their next delivery, branch-wide (ADR-0054), soonest first:
     * day by day from [date], the first day the cumulative need passes what is in the branch and on order.
     */
    fun runOuts(branch: Scope.Branch, date: BusinessDate, minutesNow: Int): List<RunOut> {
        val a = actor(Permission.PURCHASE_ORDER, Permission.PURCHASE_RECORD, Permission.INVENTORY_VIEW)
        a.require(branch)
        val places = core.locations.all().filter { it.scope == branch && it.isActive }
        if (places.isEmpty()) return emptyList()
        val items = core.items.all().filter { it.isActive && !it.prepared }
        val suppliers = core.suppliers.all().associateBy { it.id }
        val onHand = items.associate { i -> i.id to places.sumOf { core.stock.balance(i.id, it.id).quantity.micros } }
        val onOrder = HashMap<GlobalId, Long>().also { m -> places.forEach { p -> openOrders(p.id).forEach { (k, v) -> m[k] = (m[k] ?: 0L) + v } } }
        fun prepared(id: GlobalId) = places.sumOf { core.stock.balance(id, it.id).quantity.micros }
        // Cumulative need per day over the longest window any item needs.
        val horizon = items.map { i -> deliveryWindow(i.preferredSupplierId?.let { suppliers[it] }?.takeIf { it.isActive }, date, minutesNow).first }
            .maxOfOrNull { it.epochDay - date.epochDay }?.coerceIn(1, RUN_OUT_DAYS.toLong()) ?: 1L
        val cumulative = ArrayList<Map<GlobalId, Long>>()
        val dishes = HashMap<GlobalId, Long>()
        for (d in 0 until horizon) {
            core.prep.forecastOf(branch, date.plusDays(d)).forEach { f -> dishes[f.item.id] = (dishes[f.item.id] ?: 0L) + f.portions.micros }
            cumulative += rawNeed(HashMap(dishes), date, ::prepared, ArrayList())
        }
        return items.mapNotNull { item ->
            val supplier = item.preferredSupplierId?.let { suppliers[it] }?.takeIf { it.isActive }
            val delivery = supplier?.nextDelivery(date, minutesNow)?.date
            val days = ((delivery ?: date.plusDays(RUN_OUT_DAYS.toLong())).epochDay - date.epochDay).coerceIn(1, horizon).toInt()
            val have = (onHand[item.id] ?: 0L) + (onOrder[item.id] ?: 0L)
            val short = (0 until days).firstOrNull { (cumulative[it][item.id] ?: 0L) > have } ?: return@mapNotNull null
            RunOut(item, Quantity.of(onHand[item.id] ?: 0L), Quantity.of(onOrder[item.id] ?: 0L), Quantity.of(cumulative[days - 1][item.id] ?: 0L),
                date.plusDays(short.toLong()), delivery, supplier?.name)
        }.sortedWith(compareBy({ it.runsOutOn.epochDay }, { it.item.name }))
    }

    private fun suggestionLocation(locationId: GlobalId): ir.sabou.inventory.Location {
        val a = actor(Permission.PURCHASE_ORDER, Permission.PURCHASE_RECORD)
        val location = core.locations.byId(locationId) ?: throw DomainException(DomainError.NotFound("LOCATION"))
        a.require(location.scope)
        return location
    }

    /** The order's delivery and the one after it; without delivery days tomorrow and a week later. */
    private fun deliveryWindow(supplier: ir.sabou.purchasing.Supplier?, date: BusinessDate, minutesNow: Int): Pair<BusinessDate, BusinessDate> {
        val d1 = supplier?.nextDelivery(date, minutesNow)?.date ?: date.plusDays(1)
        val d2 = supplier?.takeIf { it.deliveryDays.isNotEmpty() }?.let { s ->
            (1L..14L).map { d1.plusDays(it) }.firstOrNull { ir.sabou.purchasing.Weekdays.index(it) in s.deliveryDays }
        } ?: d1.plusDays(7)
        return d1 to d2
    }

    /** Portions per dish the forecast expects on the days [from] … before [until] (ADR-0041, ADR-0033). */
    private fun forecastDishes(branch: Scope.Branch, from: BusinessDate, until: BusinessDate): Map<GlobalId, Long> {
        val out = HashMap<GlobalId, Long>()
        var d = from
        while (d < until) {
            core.prep.forecastOf(branch, d).forEach { f -> out[f.item.id] = (out[f.item.id] ?: 0L) + f.portions.micros }
            d = d.plusDays(1)
        }
        return out
    }

    /** Dishes → raw ingredients; prepared items → their ingredients for what [onHand] does not cover. */
    private fun rawNeed(dishes: Map<GlobalId, Long>, date: BusinessDate, onHand: (GlobalId) -> Long, problems: MutableList<RecipeProblem>): Map<GlobalId, Long> {
        val items = core.items.all().associateBy { it.id }
        val book = RecipeBook(core.recipes)
        val needs = HashMap<GlobalId, Long>()
        dishes.filter { it.value > 0 }.forEach { (menuItemId, portions) ->
            val lines = runCatching { book.requirements(menuItemId, date, Quantity.of(portions)) }.getOrNull()
            if (lines == null) {
                problems += RecipeProblem(core.recipes.menuItem(menuItemId)?.name ?: "آیتم منو", RecipeProblem.Kind.NO_RECIPE, "رسپی این آیتم منو در این تاریخ نیست؛ مواد آن در پیشنهاد نیامده است.")
            }
            lines?.forEach { needs[it.itemId] = (needs[it.itemId] ?: 0L) + it.quantity.micros }
        }
        val expansion = PrepGraph(core.recipes) { items[it]?.prepared == true }.expand(needs, date, onHand)
        expansion.problems.forEach { pr ->
            problems += when (pr) {
                is PrepGraph.Problem.NoRecipe -> RecipeProblem(items[pr.itemId]?.name ?: "کالای آماده", RecipeProblem.Kind.NO_RECIPE,
                    "رسپی تولید ندارد؛ مواد آن در پیشنهاد نیامده است.")
                is PrepGraph.Problem.Cycle -> RecipeProblem(items[pr.itemId]?.name ?: "کالای آماده", RecipeProblem.Kind.CYCLE,
                    "رسپی‌ها چرخه دارند: " + pr.path.joinToString(" ← ") { items[it]?.name ?: "؟" })
            }
        }
        return expansion.need
    }

    /** What open orders to [locationId] still bring, per item (a partial delivery's goods are in stock already). */
    private fun openOrders(locationId: GlobalId): Map<GlobalId, Long> {
        val onOrder = HashMap<GlobalId, Long>()
        core.purchases.orders().filter { it.status == OrderStatus.OPEN && it.locationId == locationId }
            .forEach { o ->
                val got = o.invoiceIds.mapNotNull { core.purchases.invoice(it) }.filter { it.status == InvoiceStatus.POSTED }.flatMap { it.lines }
                    .groupBy { it.itemId }.mapValues { (_, l) -> l.sumOf { it.quantity.micros } }
                o.lines.groupBy { it.itemId }.forEach { (item, l) -> onOrder[item] = (onOrder[item] ?: 0) + maxOf(0L, l.sumOf { it.quantity.micros } - (got[item] ?: 0L)) }
            }
        return onOrder
    }

    /**
     * The suggestion rows: [planned] gives, per item, what is used before the delivery and (forecast only) the cover
     * after it. suggested = planned + max(par, cover) − on hand − on order.
     */
    private fun build(
        location: ir.sabou.inventory.Location, date: BusinessDate, minutesNow: Int, problems: List<RecipeProblem>,
        planned: (Item, BusinessDate?) -> Pair<Long, Long?>,
    ): SuggestedOrders {
        val locationId = location.id
        val items = core.items.all().associateBy { it.id }
        fun onHand(id: GlobalId) = core.stock.balance(id, locationId).quantity.micros
        val onOrder = openOrders(locationId)
        val branchInvoices = core.purchases.invoices().filter { it.status == InvoiceStatus.POSTED && it.scope == location.scope }
            .withIndex().sortedWith(compareBy({ it.value.date }, { it.index })).map { it.value }
        val lastBySupplier = HashMap<Pair<GlobalId, GlobalId>, Long>()
        val lastAny = HashMap<GlobalId, Long>()
        branchInvoices.forEach { inv ->
            inv.lines.filter { !it.quantity.isZero }.forEach { l -> lastBySupplier[inv.supplierId to l.itemId] = l.unitPrice; lastAny[l.itemId] = l.unitPrice }
        }

        val suppliers = core.suppliers.all().associateBy { it.id }
        val rows = items.values.filter { it.isActive && !it.prepared }.mapNotNull { item ->
            // The par of the weekday the goods will arrive (the supplier's next delivery), else the item's usual par.
            val delivery = item.preferredSupplierId?.let { suppliers[it] }?.takeIf { it.isActive }?.nextDelivery(date, minutesNow)?.date
            val (before, cover) = planned(item, delivery)
            val par = item.parAt(locationId, delivery)
            val target = maxOf(par.micros, cover ?: 0L)
            if (target == 0L && before == 0L) return@mapNotNull null
            val raw = target + before - onHand(item.id) - (onOrder[item.id] ?: 0L)
            val suggested = roundUp(item, maxOf(0L, raw))
            val price = item.preferredSupplierId?.let { lastBySupplier[it to item.id] } ?: lastAny[item.id]
            item to Suggestion(item, Quantity.of(onHand(item.id)), Quantity.of(onOrder[item.id] ?: 0L), par, Quantity.of(before), Quantity.of(suggested), price,
                delivery?.takeIf { par != item.parLevel }, cover?.let { Quantity.of(it) })
        }.filter { !it.second.suggested.isZero }
        return rows.groupBy { (item, _) -> item.preferredSupplierId?.let { suppliers[it] }?.takeIf { it.isActive } }
            .map { (supplier, l) ->
                SuggestionGroup(supplier, supplier?.nextDelivery(date, minutesNow), l.map { it.second }.sortedBy { it.item.shelf + "\u0000" + it.item.name })
            }
            .sortedWith(compareBy({ it.supplier == null }, { it.delivery?.orderBy?.epochDay ?: Long.MAX_VALUE }, { it.supplier?.name }))
            .let { SuggestedOrders(it, problems.distinct()) }
    }

    /** Countable units are bought whole. */
    private fun roundUp(item: Item, micros: Long): Long = when (item.unit) {
        StockUnit.PIECE, StockUnit.PACK -> ((micros + Quantity.SCALE - 1) / Quantity.SCALE) * Quantity.SCALE
        else -> micros
    }
}
