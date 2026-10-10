package ir.sabou.sales

import ir.sabou.platform.NoDocument

import ir.sabou.platform.DocumentSeries

import ir.sabou.platform.IssuesDocument

import ir.sabou.inventory.InventoryGateway
import ir.sabou.inventory.RecipeBook
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Scope
import ir.sabou.kernel.ensure
import ir.sabou.ledger.JournalDraft
import ir.sabou.ledger.Ledger
import ir.sabou.ledger.LineDraft
import ir.sabou.ledger.PostingCapability
import ir.sabou.ledger.StandardAccounts
import ir.sabou.platform.AuditDraft
import ir.sabou.platform.Command
import ir.sabou.platform.CommandBus
import ir.sabou.platform.CommandOutcome
import ir.sabou.platform.ModuleId
import ir.sabou.platform.Permission
import ir.sabou.treasury.Direction
import ir.sabou.treasury.TreasuryGateway

@NoDocument
data class RegisterCustomer(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val name: String,
    val type: CustomerType,
    val phone: String,
    val creditLimit: Money,
) : Command {
    override val requiredPermission = Permission.CUSTOMER_MANAGE
    override fun fingerprint() = "$scope|$name|$type|$phone|${creditLimit.rial}"
}

/** Creates or replaces the draft of a branch's day. Drafts have no financial effect. */
@NoDocument
data class SaveSaleDraft(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val date: BusinessDate,
    val kitchenLocationId: GlobalId,
    val lines: List<SaleLine>,
    val discount: Money,
    val serviceCharge: Money,
    val tax: Money,
    val settlements: List<Settlement>,
    val guests: Int = 0,
    val transactions: Int = 0,
) : Command {
    override val requiredPermission = Permission.SALES_RECORD
    override fun fingerprint() = "$scope|${date.epochDay}|$kitchenLocationId|${discount.rial}|${serviceCharge.rial}|${tax.rial}|$guests|$transactions|" +
        lines.joinToString(";") { "${it.menuItemId}:${it.portions.micros}:${it.gross.rial}" } + "|" +
        settlements.joinToString(";") { s -> when (s) {
            is Settlement.Liquid -> "L:${s.treasuryAccountId}:${s.amount.rial}:${s.cheque?.fingerprint()}"
            is Settlement.Credit -> "C:${s.customerId}:${s.amount.rial}:${s.dueDate.epochDay}"
        } }
}

@IssuesDocument(DocumentSeries.DAILY_SALE)
data class PostDailySale(override val commandId: GlobalId, override val scope: Scope.Branch, val saleId: GlobalId) : Command {
    override val requiredPermission = Permission.SALES_POST
    override fun fingerprint() = "$scope|$saleId"
}

@IssuesDocument(DocumentSeries.REVERSAL)
data class ReverseDailySale(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val saleId: GlobalId,
    val date: BusinessDate,
    val reason: String,
) : Command {
    override val requiredPermission = Permission.SALES_REVERSE
    override fun fingerprint() = "$scope|$saleId|${date.epochDay}|$reason"
}

@IssuesDocument(DocumentSeries.RECEIPT)
data class CollectReceivable(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val receivableId: GlobalId,
    val treasuryAccountId: GlobalId,
    val amount: Money,
    val date: BusinessDate,
    /** The customer's cheque, when collected into a cheque box. */
    val cheque: ir.sabou.treasury.ChequeDetails? = null,
) : Command {
    override val requiredPermission = Permission.RECEIVABLE_COLLECT
    override fun fingerprint() = "$scope|$receivableId|$treasuryAccountId|${amount.rial}|${date.epochDay}|${cheque?.fingerprint()}"
}

@IssuesDocument(DocumentSeries.REVERSAL)
data class ReverseCollection(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val collectionId: GlobalId,
    val date: BusinessDate,
    val reason: String,
) : Command {
    override val requiredPermission = Permission.RECEIVABLE_REVERSE
    override fun fingerprint() = "$scope|$collectionId|${date.epochDay}|$reason"
}

/** Ends the day: the sale must be posted and the cash box counted. Closed days are frozen. */
@NoDocument
data class CloseSalesDay(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val date: BusinessDate,
    val countedCash: Money,
) : Command {
    override val requiredPermission = Permission.SALES_DAY_CLOSE
    override fun fingerprint() = "$scope|${date.epochDay}|${countedCash.rial}"
}

@NoDocument
data class ReopenSalesDay(override val commandId: GlobalId, override val scope: Scope.Branch, val date: BusinessDate, val reason: String) : Command {
    override val requiredPermission = Permission.SALES_DAY_REOPEN
    override fun fingerprint() = "$scope|${date.epochDay}|$reason"
}

class SalesOperations(
    private val bus: CommandBus,
    private val ledger: Ledger,
    private val capability: PostingCapability,
    private val inventory: InventoryGateway,
    private val recipes: RecipeBook,
    private val treasury: TreasuryGateway,
    private val customers: CustomerStore,
    private val sales: SalesStore,
) {
    init {
        require(capability.module == ModuleId.SALES)
    }

    fun outstanding(receivableId: GlobalId): Money {
        val r = sales.receivable(receivableId) ?: throw DomainException(DomainError.NotFound("RECEIVABLE"))
        if (r.voided) return Money.ZERO
        return r.amount - Money.sum(sales.collections(r.id).filter { !it.reversed }.map { it.amount })
    }

    fun customerBalance(customerId: GlobalId): Money = Money.sum(sales.receivablesOfCustomer(customerId).map { outstanding(it.id) })

    fun registerCustomer(c: RegisterCustomer): CommandOutcome = bus.execute(ModuleId.SALES, c) { cmd, ctx ->
        val name = cmd.name.trim()
        ensure(name.length in 2..120) { DomainError.InvalidInput("name", "نام مشتری الزامی است.") }
        ensure(customers.all().none { it.name == name && it.phone == cmd.phone.trim() }) { DomainError.InvalidState("CUSTOMER", "DUPLICATE") }
        val customer = Customer(GlobalId.new(), name, cmd.type, cmd.phone.trim(), cmd.creditLimit, cmd.scope)
        customers.save(customer)
        ctx.audit(AuditDraft("CUSTOMER_REGISTER", "CUSTOMER", customer.id.value, name))
        customer.id
    }

    fun saveDraft(c: SaveSaleDraft): CommandOutcome = bus.execute(ModuleId.SALES, c) { cmd, ctx ->
        requireDayOpen(cmd.scope, cmd.date)
        ensure(inventory.location(cmd.kitchenLocationId).scope == cmd.scope) { DomainError.InvalidInput("location", "آشپزخانه متعلق به این شعبه نیست.") }
        ensure(cmd.lines.isNotEmpty() && cmd.lines.none { it.portions.isZero }) { DomainError.InvalidInput("lines", "حداقل یک آیتم فروش لازم است.") }
        ensure(cmd.lines.map { it.menuItemId }.distinct().size == cmd.lines.size) { DomainError.InvalidInput("lines", "هر آیتم منو فقط یک‌بار بیاید.") }
        val existing = sales.activeSale(cmd.scope, cmd.date)
        ensure(existing == null || existing.status == SaleStatus.DRAFT) { DomainError.InvalidState("DAILY_SALE", "ALREADY_POSTED") }
        val sale = DailySale(
            id = existing?.id ?: GlobalId.new(), scope = cmd.scope, date = cmd.date, kitchenLocationId = cmd.kitchenLocationId,
            lines = cmd.lines, discount = cmd.discount, serviceCharge = cmd.serviceCharge, tax = cmd.tax,
            settlements = cmd.settlements, status = SaleStatus.DRAFT, revenueJournalId = null, cost = Money.ZERO,
            guests = cmd.guests, transactions = cmd.transactions,
        )
        ensure(cmd.guests in 0..100_000 && cmd.transactions in 0..100_000) { DomainError.InvalidInput("guests", "تعداد مهمان یا تراکنش معتبر نیست.") }
        ensure(sale.discount <= sale.gross) { DomainError.InvalidInput("discount", "تخفیف از فروش بیشتر است.") }
        cmd.settlements.filterIsInstance<Settlement.Liquid>().forEach { s ->
            val account = treasury.account(s.treasuryAccountId)
            ensure(account.scope == cmd.scope) { DomainError.InvalidInput("account", "حساب تسویه متعلق به این شعبه نیست.") }
            ensure(account.kind != ir.sabou.treasury.TreasuryKind.ISSUED_CHEQUES) { DomainError.InvalidInput("account", "دسته‌چک برای دریافت نیست.") }
            // A cheque box takes one cheque per settlement, with its details; other accounts take none.
            ensure((account.kind == ir.sabou.treasury.TreasuryKind.RECEIVED_CHEQUES) == (s.cheque != null)) { DomainError.InvalidInput("cheque", "مشخصات چک فقط و حتماً برای صندوق چک لازم است.") }
            s.cheque?.validate()
        }
        sales.saveSale(sale)
        ctx.audit(AuditDraft("SALE_DRAFT_SAVE", "DAILY_SALE", sale.id.value, "payable=${sale.payable.rial};settled=${sale.settled.rial}"))
        sale.id
    }

    /**
     * Posts the day atomically: ingredient consumption at cost, revenue, every treasury receipt
     * and every receivable — or nothing at all.
     */
    fun post(c: PostDailySale): CommandOutcome = bus.execute(ModuleId.SALES, c) { cmd, ctx ->
        val sale = requireSale(cmd.saleId, cmd.scope)
        ensure(sale.status == SaleStatus.DRAFT) { DomainError.InvalidState("DAILY_SALE", sale.status.name) }
        requireDayOpen(sale.scope, sale.date)
        ensure(sale.remaining == 0L) { DomainError.InvalidState("DAILY_SALE", "SETTLEMENT_MISMATCH:${sale.remaining}") }
        ensure(sale.settlements.none { it.amount.isZero }) { DomainError.InvalidInput("settlement", "مبلغ تسویه صفر مجاز نیست.") }

        // Credit limits are checked against what the customer already owes.
        sale.settlements.filterIsInstance<Settlement.Credit>().groupBy { it.customerId }.forEach { (customerId, rows) ->
            val customer = customers.byId(customerId) ?: throw DomainException(DomainError.NotFound("CUSTOMER"))
            ensure(customer.isActive) { DomainError.InvalidState("CUSTOMER", "INACTIVE") }
            ensure(customer.registeredIn == sale.scope) { DomainError.InvalidInput("customer", "این مشتری متعلق به این شعبه نیست.") }
            val after = customerBalance(customerId) + Money.sum(rows.map { it.amount })
            ensure(after <= customer.creditLimit || ctx.actor.role.allows(Permission.CREDIT_OVERRIDE)) {
                DomainError.InvalidState("CUSTOMER", "CREDIT_LIMIT_EXCEEDED")
            }
            rows.forEach { ensure(it.dueDate >= sale.date) { DomainError.InvalidInput("dueDate", "سررسید قبل از تاریخ فروش است.") } }
        }

        ctx.number(DocumentSeries.DAILY_SALE, sale.date, sale.id)
        val requirements = sale.lines.flatMap { recipes.requirements(it.menuItemId, sale.date, it.portions) }
        val consumption = inventory.consume(ctx, capability, sale.kitchenLocationId, requirements, sale.date, CONSUMPTION, sale.id, "مصرف فروش روز")

        val liquid = Money.sum(sale.settlements.filterIsInstance<Settlement.Liquid>().map { it.amount })
        val credit = Money.sum(sale.settlements.filterIsInstance<Settlement.Credit>().map { it.amount })
        val lines = buildList {
            if (!liquid.isZero) add(LineDraft(StandardAccounts.SALES_CLEARING, debit = liquid, memo = "تسویه نقد و کارت", by = capability))
            if (!credit.isZero) add(LineDraft(StandardAccounts.RECEIVABLE, debit = credit, memo = "فروش نسیه", by = capability))
            if (!sale.netFood.isZero) add(LineDraft(StandardAccounts.FOOD_SALES, credit = sale.netFood, memo = "فروش پس از تخفیف", by = capability))
            if (!sale.serviceCharge.isZero) add(LineDraft(StandardAccounts.SERVICE_INCOME, credit = sale.serviceCharge, by = capability))
            if (!sale.tax.isZero) add(LineDraft(StandardAccounts.SALES_TAX_PAYABLE, credit = sale.tax, by = capability))
        }
        val revenue = ledger.post(ctx, capability, JournalDraft(sale.date, sale.scope, REVENUE, sale.id, "فروش روز", lines))

        sale.settlements.forEach { s ->
            when (s) {
                is Settlement.Liquid -> {
                    ensure(treasury.account(s.treasuryAccountId).scope == sale.scope) { DomainError.InvalidInput("account", "حساب تسویه متعلق به این شعبه نیست.") }
                    treasury.settle(ctx, capability, s.treasuryAccountId, Direction.RECEIPT, s.amount, sale.date, SETTLEMENT, sale.id, "تسویه فروش روز",
                        listOf(LineDraft(StandardAccounts.SALES_CLEARING, credit = s.amount, by = capability)), s.cheque?.let { ir.sabou.treasury.ChequeInstruction.New(it) })
                }
                is Settlement.Credit -> sales.saveReceivable(Receivable(GlobalId.new(), s.customerId, sale.scope, sale.id, s.amount, s.dueDate, voided = false))
            }
        }
        sales.saveSale(sale.copy(status = SaleStatus.POSTED, revenueJournalId = revenue.id, cost = consumption.totalCost, consumed = consumption.lines.isNotEmpty()))
        ctx.audit(AuditDraft("SALE_POST", "DAILY_SALE", sale.id.value, "payable=${sale.payable.rial};cost=${consumption.totalCost.rial}"))
        ctx.emit("DailySalePosted", mapOf("saleId" to sale.id.value, "payable" to sale.payable.rial.toString()))
        sale.id
    }

    fun reverse(c: ReverseDailySale): CommandOutcome = bus.execute(ModuleId.SALES, c) { cmd, ctx ->
        val sale = requireSale(cmd.saleId, cmd.scope)
        ensure(sale.status == SaleStatus.POSTED) { DomainError.InvalidState("DAILY_SALE", sale.status.name) }
        requireDayOpen(sale.scope, sale.date)
        val receivables = sales.receivablesOfSale(sale.id)
        ensure(receivables.all { r -> sales.collections(r.id).none { !it.reversed } }) { DomainError.InvalidState("DAILY_SALE", "HAS_COLLECTIONS") }
        if (sale.settlements.any { it is Settlement.Liquid }) treasury.reverseDocument(ctx, capability, SETTLEMENT, sale.id, cmd.date, cmd.reason)
        ledger.reverse(ctx, capability, emptySet(), requireNotNull(sale.revenueJournalId), cmd.date, cmd.reason)
        if (sale.consumed) inventory.reverseDocument(ctx, capability, CONSUMPTION, sale.id, cmd.date, cmd.reason)
        receivables.forEach { sales.saveReceivable(it.copy(voided = true)) }
        sales.saveSale(sale.copy(status = SaleStatus.REVERSED))
        ctx.number(DocumentSeries.REVERSAL, cmd.date, cmd.commandId, reverses = DocumentSeries.DAILY_SALE to sale.id)
        ctx.audit(AuditDraft("SALE_REVERSE", "DAILY_SALE", sale.id.value, cmd.reason.trim()))
        sale.id
    }

    fun collect(c: CollectReceivable): CommandOutcome = bus.execute(ModuleId.SALES, c) { cmd, ctx ->
        val r = sales.receivable(cmd.receivableId) ?: throw DomainException(DomainError.NotFound("RECEIVABLE"))
        ensure(r.scope == cmd.scope) { DomainError.InvalidInput("scope", "این مطالبه متعلق به این شعبه نیست.") }
        ensure(!cmd.amount.isZero && cmd.amount <= outstanding(r.id)) { DomainError.InvalidState("RECEIVABLE", "AMOUNT_EXCEEDS_OUTSTANDING") }
        ensure(treasury.account(cmd.treasuryAccountId).scope == r.scope) { DomainError.InvalidInput("account", "حساب دریافت متعلق به این شعبه نیست.") }
        val saleDate = sales.sale(r.saleId)?.date
        ensure(saleDate == null || cmd.date >= saleDate) { DomainError.InvalidInput("date", "تاریخ دریافت قبل از تاریخ فروش است.") }
        val collectionId = GlobalId.new()
        ctx.number(DocumentSeries.RECEIPT, cmd.date, collectionId)
        treasury.settle(ctx, capability, cmd.treasuryAccountId, Direction.RECEIPT, cmd.amount, cmd.date, COLLECTION, collectionId, "وصول مطالبات",
            listOf(LineDraft(StandardAccounts.RECEIVABLE, credit = cmd.amount, by = capability)), cmd.cheque?.let { ir.sabou.treasury.ChequeInstruction.New(it) })
        sales.saveCollection(Collection(collectionId, r.id, cmd.treasuryAccountId, cmd.amount, cmd.date, reversed = false))
        ctx.audit(AuditDraft("RECEIVABLE_COLLECT", "RECEIVABLE", r.id.value, "amount=${cmd.amount.rial}"))
        collectionId
    }

    fun reverseCollection(c: ReverseCollection): CommandOutcome = bus.execute(ModuleId.SALES, c) { cmd, ctx ->
        val collection = sales.collection(cmd.collectionId) ?: throw DomainException(DomainError.NotFound("COLLECTION"))
        val r = sales.receivable(collection.receivableId)!!
        ensure(r.scope == cmd.scope) { DomainError.InvalidInput("scope", "این وصول متعلق به این شعبه نیست.") }
        ensure(!collection.reversed) { DomainError.InvalidState("COLLECTION", "ALREADY_REVERSED") }
        treasury.reverseDocument(ctx, capability, COLLECTION, collection.id, cmd.date, cmd.reason)
        ctx.number(DocumentSeries.REVERSAL, cmd.date, cmd.commandId, reverses = DocumentSeries.RECEIPT to collection.id)
        sales.saveCollection(collection.copy(reversed = true))
        ctx.audit(AuditDraft("COLLECTION_REVERSE", "RECEIVABLE", r.id.value, cmd.reason.trim()))
        collection.id
    }

    fun closeDay(c: CloseSalesDay): CommandOutcome = bus.execute(ModuleId.SALES, c) { cmd, ctx ->
        requireDayOpen(cmd.scope, cmd.date)
        val sale = sales.activeSale(cmd.scope, cmd.date)
        ensure(sale == null || sale.status == SaleStatus.POSTED) { DomainError.InvalidState("DAILY_SALE", "NOT_POSTED") }
        sales.saveDay(SalesDay(cmd.scope, cmd.date, closed = true, countedCash = cmd.countedCash))
        ctx.audit(AuditDraft("SALES_DAY_CLOSE", "SALES_DAY", "${cmd.scope.branchId.value}:${cmd.date.epochDay}", "counted=${cmd.countedCash.rial}"))
        sale?.id ?: GlobalId.new()
    }

    fun reopenDay(c: ReopenSalesDay): CommandOutcome = bus.execute(ModuleId.SALES, c) { cmd, ctx ->
        val day = sales.day(cmd.scope, cmd.date)
        ensure(day?.closed == true) { DomainError.InvalidState("SALES_DAY", "NOT_CLOSED") }
        ensure(cmd.reason.trim().length in 3..300) { DomainError.InvalidInput("reason", "دلیل بازگشایی الزامی است.") }
        sales.saveDay(day!!.copy(closed = false))
        ctx.audit(AuditDraft("SALES_DAY_REOPEN", "SALES_DAY", "${cmd.scope.branchId.value}:${cmd.date.epochDay}", cmd.reason.trim()))
        sales.activeSale(cmd.scope, cmd.date)?.id ?: GlobalId.new()
    }

    private fun requireSale(id: GlobalId, scope: Scope.Branch): DailySale {
        val sale = sales.sale(id) ?: throw DomainException(DomainError.NotFound("DAILY_SALE"))
        ensure(sale.scope == scope) { DomainError.InvalidInput("scope", "این فروش متعلق به این شعبه نیست.") }
        return sale
    }

    private fun requireDayOpen(scope: Scope.Branch, date: BusinessDate) {
        ensure(sales.day(scope, date)?.closed != true) { DomainError.InvalidState("SALES_DAY", "CLOSED") }
    }

    companion object {
        const val CONSUMPTION = "DAILY_SALE_CONSUMPTION"
        const val REVENUE = "DAILY_SALE_REVENUE"
        const val SETTLEMENT = "DAILY_SALE_SETTLEMENT"
        const val COLLECTION = "RECEIVABLE_COLLECTION"
    }
}
