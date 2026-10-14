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

/**
 * Creates a new sale draft ([saleId] null) or replaces draft [saleId]. A day has as many sales as needed; drafts
 * have no financial effect.
 */
@NoDocument
data class SaveSaleDraft(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val date: BusinessDate,
    val kitchenLocationId: GlobalId,
    val lines: List<SaleLineInput>,
    val discount: Money,
    val serviceCharge: Money,
    val tax: Money,
    val settlements: List<Settlement>,
    val guests: Int = 0,
    val transactions: Int = 0,
    val saleId: GlobalId? = null,
    /** Why the discount was given; required with a discount, which needs [Permission.SALES_DISCOUNT]. */
    val discountReason: String = "",
) : Command {
    override val requiredPermission = Permission.SALES_RECORD
    override fun fingerprint() = "$saleId|$scope|${date.epochDay}|$kitchenLocationId|${discount.rial}|$discountReason|${serviceCharge.rial}|${tax.rial}|$guests|$transactions|" +
        lines.joinToString(";") { "${it.menuItemId}:${it.portions.micros}:${it.unitPrice?.rial}:${it.overrideReason}" } + "|" +
        settlements.joinToString(";") { s -> when (s) {
            is Settlement.Liquid -> "L:${s.treasuryAccountId}:${s.amount.rial}:${s.cheque?.fingerprint()}"
            is Settlement.Credit -> "C:${s.customerId}:${s.amount.rial}:${s.dueDate.epochDay}"
        } }
}

/**
 * Records a new version of a menu item's price from [effectiveFrom]: for every branch ([Scope.Organization]) or for
 * one branch. A null [unitPrice] is allowed only for a branch and ends its own price (it follows the organization's).
 */
@NoDocument
data class SetMenuPrice(
    override val commandId: GlobalId,
    override val scope: Scope,
    val menuItemId: GlobalId,
    val effectiveFrom: BusinessDate,
    val unitPrice: Money?,
) : Command {
    override val requiredPermission = Permission.MENU_PRICE_MANAGE
    override val sharedCatalog = true
    override fun fingerprint() = "$scope|$menuItemId|${effectiveFrom.epochDay}|${unitPrice?.rial}"
}

/** Drops a draft that will not be posted; it stays on record as DISCARDED. */
@NoDocument
data class DiscardSaleDraft(override val commandId: GlobalId, override val scope: Scope.Branch, val saleId: GlobalId) : Command {
    override val requiredPermission = Permission.SALES_RECORD
    override fun fingerprint() = "$scope|$saleId"
}

/** The target food cost of the menu (basis points of the price), or null to clear it (ADR-0028). */
@NoDocument
data class SetFoodCostTarget(override val commandId: GlobalId, val targetBp: Long?) : Command {
    override val requiredPermission = Permission.MENU_PRICE_MANAGE
    override val scope: Scope = Scope.Organization
    override val sharedCatalog = true
    override fun fingerprint() = "$targetBp"
}

/** A day imported from a point-of-sale export, posted at once (ADR-0026). */
@IssuesDocument(DocumentSeries.DAILY_SALE)
data class ImportPosSale(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    /** Which point-of-sale system («SEPAND»). */
    val pos: String,
    val fileNames: List<String>,
    /** SHA-256 of each file's bytes. */
    val fileHashes: List<String>,
    /** «number|date» of each bill counted. */
    val invoiceKeys: List<String>,
    val sale: SaveSaleDraft,
    /** POS item name (normalized key) → menu item, remembered for the next import. */
    val aliases: Map<String, GlobalId> = emptyMap(),
) : Command {
    override val requiredPermission = Permission.SALES_POST
    override fun fingerprint() = "$scope|$pos|${fileHashes.joinToString(",")}|${invoiceKeys.size}|${sale.fingerprint()}|" +
        aliases.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }
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

/**
 * Ends the day: the sale must be posted and the cash box [cashAccountId] counted. The count is a document of its
 * own and any shortage or overage is booked (cash over/short). Closed days are frozen.
 */
@IssuesDocument(DocumentSeries.CASH_COUNT)
data class CloseSalesDay(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val date: BusinessDate,
    val cashAccountId: GlobalId,
    val countedCash: Money,
) : Command {
    override val requiredPermission = Permission.SALES_DAY_CLOSE
    override fun fingerprint() = "$scope|${date.epochDay}|$cashAccountId|${countedCash.rial}"
}

/** Edits a customer: name, phone, kind, credit limit; an inactive customer buys no more on credit. */
@NoDocument
data class UpdateCustomer(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val customerId: GlobalId,
    val name: String,
    val type: CustomerType,
    val phone: String,
    val creditLimit: Money,
    val isActive: Boolean,
) : Command {
    override val requiredPermission = Permission.CUSTOMER_MANAGE
    override fun fingerprint() = "$scope|$customerId|$name|$type|$phone|${creditLimit.rial}|$isActive"
}

/** The service charge added to sales, basis points of the food after discount, or null for none. */
@NoDocument
data class SetServiceCharge(override val commandId: GlobalId, val bp: Long?) : Command {
    override val requiredPermission = Permission.MENU_PRICE_MANAGE
    override val scope: Scope = Scope.Organization
    override val sharedCatalog = true
    override fun fingerprint() = "$bp"
}

/**
 * Pays the branch's VAT and levies to the tax office: [amount] off what sales owe (2103), from a treasury account.
 * [inputCredit] is VAT paid on purchases (1108) set against it, so only the difference leaves the account.
 */
@IssuesDocument(DocumentSeries.PAYMENT)
data class PaySalesTax(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val treasuryAccountId: GlobalId,
    val amount: Money,
    val date: BusinessDate,
    val note: String,
    val inputCredit: Money = Money.ZERO,
) : Command {
    override val requiredPermission = Permission.TREASURY_PAYMENT
    override fun fingerprint() = "$scope|$treasuryAccountId|${amount.rial}|${inputCredit.rial}|${date.epochDay}|$note"
}

/** Gives up (part of) a customer's debt as uncollectible: bad-debt expense against the receivable. */
@NoDocument
data class WriteOffReceivable(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val receivableId: GlobalId,
    val amount: Money,
    val date: BusinessDate,
    val reason: String,
) : Command {
    override val requiredPermission = Permission.CREDIT_OVERRIDE
    override fun fingerprint() = "$scope|$receivableId|${amount.rial}|${date.epochDay}|$reason"
}

/** What a customer owed when the books started: a receivable against capital, collected like any other. */
@NoDocument
data class RecordCustomerOpeningBalance(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val customerId: GlobalId,
    val amount: Money,
    val date: BusinessDate,
    val dueDate: BusinessDate,
) : Command {
    override val requiredPermission = Permission.JOURNAL_MANUAL_POST
    override fun fingerprint() = "$scope|$customerId|${amount.rial}|${date.epochDay}|${dueDate.epochDay}"
}

/** Takes back an opening balance entered by mistake (nothing collected or written off yet). */
@NoDocument
data class ReverseCustomerOpeningBalance(override val commandId: GlobalId, override val scope: Scope.Branch, val receivableId: GlobalId, val date: BusinessDate, val reason: String) : Command {
    override val requiredPermission = Permission.JOURNAL_MANUAL_REVERSE
    override fun fingerprint() = "$scope|$receivableId|${date.epochDay}|$reason"
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
    private val prices: MenuPriceStore,
    private val imports: PosImportStore = ir.sabou.sales.memory.InMemoryPosImportStore(),
) {
    val priceList = PriceList(prices)

    init {
        require(capability.module == ModuleId.SALES)
    }

    fun outstanding(receivableId: GlobalId): Money {
        val r = sales.receivable(receivableId) ?: throw DomainException(DomainError.NotFound("RECEIVABLE"))
        if (r.voided) return Money.ZERO
        return r.amount - r.writtenOff - Money.sum(sales.collections(r.id).filter { !it.reversed }.map { it.amount })
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

    fun saveDraft(c: SaveSaleDraft): CommandOutcome = bus.execute(ModuleId.SALES, c) { cmd, ctx -> draftOf(cmd, ctx).id }

    /** Validates, prices and stores a draft (new, or replacing [SaveSaleDraft.saleId]). */
    private fun draftOf(cmd: SaveSaleDraft, ctx: ir.sabou.platform.CommandContext): DailySale {
        requireDayOpen(cmd.scope, cmd.date)
        ensure(inventory.location(cmd.kitchenLocationId).scope == cmd.scope) { DomainError.InvalidInput("location", "آشپزخانه متعلق به این شعبه نیست.") }
        ensure(cmd.lines.isNotEmpty() && cmd.lines.none { it.portions.isZero }) { DomainError.InvalidInput("lines", "حداقل یک آیتم فروش لازم است.") }
        ensure(cmd.lines.map { it.menuItemId }.distinct().size == cmd.lines.size) { DomainError.InvalidInput("lines", "هر آیتم منو فقط یک‌بار بیاید.") }
        val lines = cmd.lines.map { price(it, cmd.scope, cmd.date, ctx.actor.role) }
        val existing = cmd.saleId?.let { requireSale(it, cmd.scope) }
        ensure(existing == null || existing.status == SaleStatus.DRAFT) { DomainError.InvalidState("DAILY_SALE", "ALREADY_POSTED") }
        ensure(existing == null || existing.date == cmd.date) { DomainError.InvalidInput("date", "تاریخ پیش‌نویس را نمی‌شود عوض کرد؛ آن را کنار بگذارید و فروش تازه ثبت کنید.") }
        val sale = DailySale(
            id = existing?.id ?: GlobalId.new(), scope = cmd.scope, date = cmd.date, kitchenLocationId = cmd.kitchenLocationId,
            lines = lines, discount = cmd.discount, serviceCharge = cmd.serviceCharge, tax = cmd.tax,
            settlements = cmd.settlements, status = SaleStatus.DRAFT, revenueJournalId = null, cost = Money.ZERO,
            guests = cmd.guests, transactions = cmd.transactions, discountReason = cmd.discountReason.trim(),
        )
        // A discount takes money off the books like a price change: it carries its reason, and whoever posts the
        // sale needs the right to give it (a cashier may record one; the manager who posts approves it).
        ensure(cmd.discount.isZero || cmd.discountReason.trim().length in 3..200) { DomainError.InvalidInput("discountReason", "دلیل تخفیف را بنویسید.") }
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
        return sale
    }

    fun discardDraft(c: DiscardSaleDraft): CommandOutcome = bus.execute(ModuleId.SALES, c) { cmd, ctx ->
        val sale = requireSale(cmd.saleId, cmd.scope)
        ensure(sale.status == SaleStatus.DRAFT) { DomainError.InvalidState("DAILY_SALE", sale.status.name) }
        requireDayOpen(sale.scope, sale.date)
        sales.saveSale(sale.copy(status = SaleStatus.DISCARDED))
        ctx.audit(AuditDraft("SALE_DRAFT_DISCARD", "DAILY_SALE", sale.id.value, "payable=${sale.payable.rial}"))
        sale.id
    }

    /**
     * Prices one line: the menu price in force, or the typed unit price when the menu has none. Typing a price other
     * than the menu's is an override: it needs [Permission.SALES_PRICE_OVERRIDE] and a reason, both kept on the line
     * (and in the command's audit record).
     */
    private fun price(input: SaleLineInput, branch: Scope.Branch, date: BusinessDate, role: ir.sabou.platform.Role): SaleLine {
        val item = recipes.menuItem(input.menuItemId) ?: throw DomainException(DomainError.NotFound("MENU_ITEM"))
        ensure(item.isActive) { DomainError.InvalidInput("lines", "«${item.name}» از منو خارج شده است.") }
        val list = priceList.unitPriceOn(item.id, branch, date)
        val unit = input.unitPrice ?: list
            ?: throw DomainException(DomainError.InvalidInput("price", "«${item.name}» در منو قیمت ندارد؛ قیمت واحد را وارد کنید."))
        val overridden = list != null && unit != list
        if (overridden) {
            ensure(role.allows(Permission.SALES_PRICE_OVERRIDE)) { DomainError.PermissionDenied(Permission.SALES_PRICE_OVERRIDE.name) }
            ensure((input.overrideReason?.trim()?.length ?: 0) in 3..200) { DomainError.InvalidInput("reason", "دلیل تغییر قیمت «${item.name}» الزامی است.") }
        } else {
            ensure(!unit.isZero) { DomainError.InvalidInput("price", "قیمت واحد «${item.name}» صفر است.") }
        }
        return SaleLine.priced(item.id, input.portions, unit, list, input.overrideReason?.trim()?.takeIf { overridden })
    }

    fun foodCostTarget(): Long? = prices.foodCostTarget()

    fun setFoodCostTarget(c: SetFoodCostTarget): CommandOutcome = bus.execute(ModuleId.SALES, c) { cmd, ctx ->
        ensure(cmd.targetBp == null || cmd.targetBp in 500..9_000) { DomainError.InvalidInput("target", "هدف درصد بهای غذا بین ۵ تا ۹۰ درصد است.") }
        prices.saveFoodCostTarget(cmd.targetBp)
        ctx.audit(AuditDraft("FOOD_COST_TARGET", "MENU", "target", "bp=${cmd.targetBp}"))
        GlobalId.new()
    }

    fun setMenuPrice(c: SetMenuPrice): CommandOutcome = bus.execute(ModuleId.SALES, c) { cmd, ctx ->
        val item = recipes.menuItem(cmd.menuItemId) ?: throw DomainException(DomainError.NotFound("MENU_ITEM"))
        ensure(cmd.unitPrice != null || cmd.scope is Scope.Branch) { DomainError.InvalidInput("price", "قیمت منو الزامی است.") }
        ensure(cmd.unitPrice?.isZero != true) { DomainError.InvalidInput("price", "قیمت منو باید بیشتر از صفر باشد.") }
        val price = MenuPrice(GlobalId.new(), item.id, cmd.scope, cmd.effectiveFrom, cmd.unitPrice, prices.nextSequence())
        prices.save(price)
        ctx.audit(AuditDraft("MENU_PRICE_SET", "MENU_ITEM", item.id.value,
            "scope=${ir.sabou.platform.CommandContext.scopeLabel(cmd.scope)};from=${cmd.effectiveFrom.epochDay};price=${cmd.unitPrice?.rial ?: "ORG"}"))
        price.id
    }

    /**
     * Posts the day atomically: ingredient consumption at cost, revenue, every treasury receipt
     * and every receivable — or nothing at all.
     */
    fun post(c: PostDailySale): CommandOutcome = bus.execute(ModuleId.SALES, c) { cmd, ctx -> postSale(requireSale(cmd.saleId, cmd.scope), ctx) }

    private fun postSale(sale: DailySale, ctx: ir.sabou.platform.CommandContext): GlobalId {
        ensure(sale.status == SaleStatus.DRAFT) { DomainError.InvalidState("DAILY_SALE", sale.status.name) }
        requireDayOpen(sale.scope, sale.date)
        ensure(sale.remaining == 0L) { DomainError.InvalidState("DAILY_SALE", "SETTLEMENT_MISMATCH:${sale.remaining}") }
        ensure(sale.settlements.none { it.amount.isZero }) { DomainError.InvalidInput("settlement", "مبلغ تسویه صفر مجاز نیست.") }
        ensure(sale.discount.isZero || ctx.actor.role.allows(Permission.SALES_DISCOUNT)) { DomainError.PermissionDenied(Permission.SALES_DISCOUNT.name) }

        // Credit limits are checked against what the customer already owes.
        sale.settlements.filterIsInstance<Settlement.Credit>().groupBy { it.customerId }.forEach { (customerId, rows) ->
            val customer = customers.byId(customerId) ?: throw DomainException(DomainError.NotFound("CUSTOMER"))
            ensure(customer.isActive) { DomainError.InvalidState("CUSTOMER", "INACTIVE") }
            val after = customerBalance(customerId) + Money.sum(rows.map { it.amount })
            ensure(after <= customer.creditLimit || ctx.actor.role.allows(Permission.CREDIT_OVERRIDE)) {
                DomainError.InvalidState("CUSTOMER", "CREDIT_LIMIT_EXCEEDED")
            }
            rows.forEach { ensure(it.dueDate >= sale.date) { DomainError.InvalidInput("dueDate", "سررسید قبل از تاریخ فروش است.") } }
        }

        ctx.number(DocumentSeries.DAILY_SALE, sale.date, sale.id)
        // A dish with no recipe yet is sold all the same; it takes no stock (shown as "without recipe" in the day).
        val requirements = sale.lines.filter { recipes.hasRecipeOn(it.menuItemId, sale.date) }.flatMap { recipes.requirements(it.menuItemId, sale.date, it.portions) }
        // Sold is sold: a book-stock shortage does not stop the sale; it waits as an open shortage (ADR-0022).
        val consumption = inventory.consume(ctx, capability, sale.kitchenLocationId, requirements, sale.date, CONSUMPTION, sale.id, "مصرف فروش", allowShortage = true)

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
        return sale.id
    }

    /**
     * A day of a point-of-sale system in one step (ADR-0026): the sale is drafted and posted as usual, together
     * with the import record that makes the same file, the same bills or the same POS day impossible to import
     * twice while it stands. Reversing the sale releases them.
     */
    fun importPosDay(c: ImportPosSale): CommandOutcome = bus.execute(ModuleId.SALES, c) { cmd, ctx ->
        val pos = cmd.pos.trim()
        ensure(pos.isNotEmpty() && cmd.fileHashes.isNotEmpty()) { DomainError.InvalidInput("file", "فایل صندوق لازم است.") }
        val active = imports.all().filter { it.active && it.pos == pos }
        ensure(active.none { it.scope == cmd.scope && it.date == cmd.sale.date }) { DomainError.InvalidState("POS_IMPORT", "DAY_ALREADY_IMPORTED") }
        ensure(active.none { i -> i.fileHashes.any { it in cmd.fileHashes } }) { DomainError.InvalidState("POS_IMPORT", "FILE_ALREADY_IMPORTED") }
        ensure(active.none { i -> i.invoiceKeys.any { it in cmd.invoiceKeys } }) { DomainError.InvalidState("POS_IMPORT", "INVOICE_ALREADY_IMPORTED") }
        ensure(cmd.sale.scope == cmd.scope && cmd.sale.saleId == null) { DomainError.InvalidInput("scope", "فروش ورودی متعلق به این شعبه نیست.") }
        val sale = draftOf(cmd.sale, ctx)
        postSale(sale, ctx)
        imports.save(PosImport(GlobalId.new(), pos, cmd.scope, cmd.sale.date, cmd.fileNames, cmd.fileHashes, cmd.invoiceKeys, sale.id, active = true))
        cmd.aliases.forEach { (key, menuItem) -> imports.saveAlias(pos, key, menuItem) }
        ctx.audit(AuditDraft("POS_IMPORT", "DAILY_SALE", sale.id.value, "pos=$pos;files=${cmd.fileNames.joinToString(",")};bills=${cmd.invoiceKeys.size}"))
        sale.id
    }

    fun reverse(c: ReverseDailySale): CommandOutcome = bus.execute(ModuleId.SALES, c) { cmd, ctx ->
        val sale = requireSale(cmd.saleId, cmd.scope)
        ensure(sale.status == SaleStatus.POSTED) { DomainError.InvalidState("DAILY_SALE", sale.status.name) }
        requireDayOpen(sale.scope, sale.date)
        requireDayOpen(sale.scope, cmd.date)
        val receivables = sales.receivablesOfSale(sale.id)
        ensure(receivables.all { r -> sales.collections(r.id).none { !it.reversed } }) { DomainError.InvalidState("DAILY_SALE", "HAS_COLLECTIONS") }
        ensure(receivables.all { it.writtenOff.isZero }) { DomainError.InvalidState("DAILY_SALE", "HAS_WRITE_OFFS") }
        if (sale.settlements.any { it is Settlement.Liquid }) treasury.reverseDocument(ctx, capability, SETTLEMENT, sale.id, cmd.date, cmd.reason)
        ledger.reverse(ctx, capability, emptySet(), requireNotNull(sale.revenueJournalId), cmd.date, cmd.reason)
        if (sale.consumed) inventory.reverseDocument(ctx, capability, CONSUMPTION, sale.id, cmd.date, cmd.reason)
        inventory.cancelShortages(ctx, capability, CONSUMPTION, sale.id, cmd.date)
        receivables.forEach { sales.saveReceivable(it.copy(voided = true)) }
        sales.saveSale(sale.copy(status = SaleStatus.REVERSED))
        // An imported POS day can be imported again (corrected) once its sale is reversed.
        imports.ofSale(sale.id)?.let { imports.save(it.copy(active = false)) }
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

    fun updateCustomer(c: UpdateCustomer): CommandOutcome = bus.execute(ModuleId.SALES, c) { cmd, ctx ->
        val customer = customers.byId(cmd.customerId) ?: throw DomainException(DomainError.NotFound("CUSTOMER"))
        val name = cmd.name.trim()
        ensure(name.length in 2..120) { DomainError.InvalidInput("name", "نام مشتری الزامی است.") }
        ensure(cmd.phone.trim().length <= 20) { DomainError.InvalidInput("phone", "شماره تلفن معتبر نیست.") }
        ensure(customers.all().none { it.id != customer.id && it.name == name && it.phone == cmd.phone.trim() }) { DomainError.InvalidState("CUSTOMER", "DUPLICATE") }
        customers.save(customer.copy(name = name, type = cmd.type, phone = cmd.phone.trim(), creditLimit = cmd.creditLimit, isActive = cmd.isActive))
        ctx.audit(AuditDraft("CUSTOMER_UPDATE", "CUSTOMER", customer.id.value, "name=$name;limit=${cmd.creditLimit.rial};active=${cmd.isActive}"))
        customer.id
    }

    fun serviceCharge(): Long? = prices.serviceCharge()

    fun setServiceCharge(c: SetServiceCharge): CommandOutcome = bus.execute(ModuleId.SALES, c) { cmd, ctx ->
        ensure(cmd.bp == null || cmd.bp in 1..3_000) { DomainError.InvalidInput("service", "حق سرویس بین ۰ و ۳۰ درصد است.") }
        prices.saveServiceCharge(cmd.bp)
        ctx.audit(AuditDraft("SERVICE_CHARGE_SET", "MENU", "service", "bp=${cmd.bp}"))
        GlobalId.new()
    }

    /** What the branch's sales owe in VAT and levies now (2103), net of what was paid. */
    fun salesTaxOwed(scope: Scope.Branch): Money = Money.of(maxOf(0L, -ledger.balance(StandardAccounts.SALES_TAX_PAYABLE, scope).rial))

    fun paySalesTax(c: PaySalesTax): CommandOutcome = bus.execute(ModuleId.SALES, c) { cmd, ctx ->
        ensure(!cmd.amount.isZero) { DomainError.InvalidInput("amount", "مبلغ باید بیشتر از صفر باشد.") }
        ensure(cmd.amount <= salesTaxOwed(cmd.scope)) { DomainError.InvalidState("SALES_TAX", "MORE_THAN_OWED") }
        ensure(cmd.inputCredit <= cmd.amount) { DomainError.InvalidInput("inputCredit", "اعتبار مالیاتی خرید از مبلغ تسویه بیشتر است.") }
        ensure(cmd.note.length <= 200) { DomainError.InvalidInput("note", "توضیح طولانی است.") }
        ensure(treasury.account(cmd.treasuryAccountId).scope == cmd.scope) { DomainError.InvalidInput("account", "حساب پرداخت متعلق به این شعبه نیست.") }
        val docId = GlobalId.new()
        ctx.number(DocumentSeries.PAYMENT, cmd.date, docId)
        val title = "پرداخت مالیات و عوارض فروش" + if (cmd.note.isBlank()) "" else ": ${cmd.note.trim()}"
        if (!cmd.inputCredit.isZero) {
            ensure(cmd.inputCredit.rial <= ledger.balance(StandardAccounts.VAT_RECEIVABLE, cmd.scope).rial) { DomainError.InvalidState("SALES_TAX", "CREDIT_MORE_THAN_HELD") }
            ledger.post(ctx, capability, JournalDraft(cmd.date, cmd.scope, TAX_PAYMENT, docId, "تهاتر اعتبار مالیاتی خرید", listOf(
                LineDraft(StandardAccounts.SALES_TAX_PAYABLE, debit = cmd.inputCredit, by = capability),
                LineDraft(StandardAccounts.VAT_RECEIVABLE, credit = cmd.inputCredit, by = capability),
            )))
        }
        val cash = cmd.amount - cmd.inputCredit
        if (!cash.isZero) treasury.settle(ctx, capability, cmd.treasuryAccountId, Direction.PAYMENT, cash, cmd.date, TAX_PAYMENT, docId, title,
            listOf(LineDraft(StandardAccounts.SALES_TAX_PAYABLE, debit = cash, memo = cmd.note.trim(), by = capability)))
        ctx.audit(AuditDraft("SALES_TAX_PAY", "SALES_TAX", docId.value, "amount=${cmd.amount.rial};credit=${cmd.inputCredit.rial}"))
        docId
    }

    fun writeOff(c: WriteOffReceivable): CommandOutcome = bus.execute(ModuleId.SALES, c) { cmd, ctx ->
        val r = sales.receivable(cmd.receivableId) ?: throw DomainException(DomainError.NotFound("RECEIVABLE"))
        ensure(r.scope == cmd.scope) { DomainError.InvalidInput("scope", "این مطالبه متعلق به این شعبه نیست.") }
        ensure(!cmd.amount.isZero && cmd.amount <= outstanding(r.id)) { DomainError.InvalidState("RECEIVABLE", "AMOUNT_EXCEEDS_OUTSTANDING") }
        ensure(cmd.reason.trim().length in 3..200) { DomainError.InvalidInput("reason", "دلیل سوخت شدن را بنویسید.") }
        val docId = GlobalId.new()
        ledger.post(ctx, capability, JournalDraft(cmd.date, r.scope, WRITE_OFF, docId, "سوخت مطالبات: ${cmd.reason.trim()}", listOf(
            LineDraft(StandardAccounts.BAD_DEBTS, debit = cmd.amount, by = capability),
            LineDraft(StandardAccounts.RECEIVABLE, credit = cmd.amount, memo = customers.byId(r.customerId)?.name.orEmpty(), by = capability),
        )))
        sales.saveReceivable(r.copy(writeOffs = r.writeOffs + WriteOff(cmd.date, cmd.amount, cmd.reason.trim())))
        ctx.audit(AuditDraft("RECEIVABLE_WRITE_OFF", "RECEIVABLE", r.id.value, "amount=${cmd.amount.rial};reason=${cmd.reason.trim()}"))
        docId
    }

    fun customerOpeningBalance(c: RecordCustomerOpeningBalance): CommandOutcome = bus.execute(ModuleId.SALES, c) { cmd, ctx ->
        val customer = customers.byId(cmd.customerId) ?: throw DomainException(DomainError.NotFound("CUSTOMER"))
        ensure(!cmd.amount.isZero) { DomainError.InvalidInput("amount", "مبلغ باید بیشتر از صفر باشد.") }
        ensure(cmd.dueDate >= cmd.date) { DomainError.InvalidInput("dueDate", "سررسید نمی‌تواند قبل از تاریخ باشد.") }
        val docId = GlobalId.new()
        ledger.post(ctx, capability, JournalDraft(cmd.date, cmd.scope, OPENING, docId, "مانده‌ی اول دوره‌ی ${customer.name}", listOf(
            LineDraft(StandardAccounts.RECEIVABLE, debit = cmd.amount, memo = customer.name, by = capability),
            LineDraft(StandardAccounts.CAPITAL, credit = cmd.amount, memo = "مانده‌ی اول دوره", by = capability),
        )))
        val r = Receivable(GlobalId.new(), customer.id, cmd.scope, docId, cmd.amount, cmd.dueDate, voided = false)
        sales.saveReceivable(r)
        ctx.audit(AuditDraft("CUSTOMER_OPENING_BALANCE", "RECEIVABLE", r.id.value, "customer=${customer.id};amount=${cmd.amount.rial}"))
        r.id
    }

    fun reverseCustomerOpening(c: ReverseCustomerOpeningBalance): CommandOutcome = bus.execute(ModuleId.SALES, c) { cmd, ctx ->
        val r = sales.receivable(cmd.receivableId) ?: throw DomainException(DomainError.NotFound("RECEIVABLE"))
        ensure(r.scope == cmd.scope) { DomainError.InvalidInput("scope", "این مطالبه متعلق به این شعبه نیست.") }
        val journal = ledger.entriesOf(OPENING, r.saleId).firstOrNull { it.reversalOf == null } ?: throw DomainException(DomainError.InvalidState("RECEIVABLE", "NOT_OPENING"))
        ensure(!r.voided) { DomainError.InvalidState("RECEIVABLE", "VOIDED") }
        ensure(sales.collections(r.id).none { !it.reversed } && r.writtenOff.isZero) { DomainError.InvalidState("RECEIVABLE", "HAS_COLLECTIONS") }
        ledger.reverse(ctx, capability, emptySet(), journal.id, cmd.date, cmd.reason)
        sales.saveReceivable(r.copy(voided = true))
        ctx.audit(AuditDraft("CUSTOMER_OPENING_REVERSE", "RECEIVABLE", r.id.value, cmd.reason.trim()))
        r.id
    }

    fun closeDay(c: CloseSalesDay): CommandOutcome = bus.execute(ModuleId.SALES, c) { cmd, ctx ->
        requireDayOpen(cmd.scope, cmd.date)
        // Every sale of the day is posted (or reversed, or discarded): no draft is left behind a closed day.
        ensure(sales.salesOn(cmd.scope, cmd.date).none { it.status == SaleStatus.DRAFT }) { DomainError.InvalidState("SALES_DAY", "HAS_DRAFTS") }
        val box = treasury.account(cmd.cashAccountId)
        ensure(box.scope == cmd.scope) { DomainError.InvalidInput("account", "صندوق متعلق به این شعبه نیست.") }
        ensure(box.kind == ir.sabou.treasury.TreasuryKind.CASH || box.kind == ir.sabou.treasury.TreasuryKind.PETTY_CASH) {
            DomainError.InvalidInput("account", "در پایان روز صندوق نقد شمرده می‌شود.")
        }
        val countId = GlobalId.new()
        val difference = treasury.count(ctx, capability, box.id, cmd.countedCash, cmd.date, DAY_COUNT, countId, "شمارش صندوق پایان روز")
        sales.saveDay(SalesDay(cmd.scope, cmd.date, closed = true, countedCash = cmd.countedCash, cashAccountId = box.id, difference = difference, countId = countId))
        ctx.audit(AuditDraft("SALES_DAY_CLOSE", "SALES_DAY", "${cmd.scope.branchId.value}:${cmd.date.epochDay}", "counted=${cmd.countedCash.rial};difference=$difference"))
        countId
    }

    fun reopenDay(c: ReopenSalesDay): CommandOutcome = bus.execute(ModuleId.SALES, c) { cmd, ctx ->
        val day = sales.day(cmd.scope, cmd.date)
        ensure(day?.closed == true) { DomainError.InvalidState("SALES_DAY", "NOT_CLOSED") }
        ensure(cmd.reason.trim().length in 3..300) { DomainError.InvalidInput("reason", "دلیل بازگشایی الزامی است.") }
        sales.saveDay(day!!.copy(closed = false))
        ctx.audit(AuditDraft("SALES_DAY_REOPEN", "SALES_DAY", "${cmd.scope.branchId.value}:${cmd.date.epochDay}", cmd.reason.trim()))
        day.countId ?: GlobalId.new()
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
        const val DAY_COUNT = "SALES_DAY_CASH_COUNT"
        const val TAX_PAYMENT = "SALES_TAX_PAYMENT"
        const val WRITE_OFF = "RECEIVABLE_WRITE_OFF"
        const val OPENING = "RECEIVABLE_OPENING_BALANCE"
    }
}
