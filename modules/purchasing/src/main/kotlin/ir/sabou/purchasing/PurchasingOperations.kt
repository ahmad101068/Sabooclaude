package ir.sabou.purchasing

import ir.sabou.inventory.InventoryGateway
import ir.sabou.inventory.IssueLine
import ir.sabou.inventory.ReceiptLine
import ir.sabou.inventory.issueWithCounter
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Ratio
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
import ir.sabou.platform.CommandContext
import ir.sabou.platform.CommandOutcome
import ir.sabou.platform.ModuleId
import ir.sabou.platform.Permission
import ir.sabou.treasury.Direction
import ir.sabou.treasury.TreasuryGateway

data class RegisterSupplier(override val commandId: GlobalId, val name: String, val phone: String) : Command {
    override val requiredPermission = Permission.SUPPLIER_MANAGE
    override val scope: Scope = Scope.Organization
    override fun fingerprint() = "$name|$phone"
}

data class ImmediatePayment(val treasuryAccountId: GlobalId, val amount: Money)

/** A supplier invoice for goods received into a location. Optionally paid on the spot. */
data class PostPurchaseInvoice(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val supplierId: GlobalId,
    val supplierInvoiceNo: String,
    val locationId: GlobalId,
    val date: BusinessDate,
    val dueDate: BusinessDate,
    val lines: List<InvoiceLine>,
    val payNow: ImmediatePayment? = null,
) : Command {
    override val requiredPermission = Permission.PURCHASE_RECORD
    override fun fingerprint() = "$scope|$supplierId|$supplierInvoiceNo|$locationId|${date.epochDay}|${dueDate.epochDay}|" +
        lines.joinToString(";") { "${it.itemId}:${it.quantity.micros}:${it.value.rial}" } + "|${payNow?.treasuryAccountId}:${payNow?.amount?.rial}"
}

data class PaySupplierInvoice(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val invoiceId: GlobalId,
    val treasuryAccountId: GlobalId,
    val amount: Money,
    val date: BusinessDate,
) : Command {
    override val requiredPermission = Permission.PURCHASE_PAY
    override fun fingerprint() = "$scope|$invoiceId|$treasuryAccountId|${amount.rial}|${date.epochDay}"
}

data class ReverseSupplierPayment(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val paymentId: GlobalId,
    val date: BusinessDate,
    val reason: String,
) : Command {
    override val requiredPermission = Permission.PURCHASE_REVERSE
    override fun fingerprint() = "$scope|$paymentId|${date.epochDay}|$reason"
}

/** Full reversal of a wrongly entered invoice. Only possible before any payment or return. */
data class ReversePurchaseInvoice(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val invoiceId: GlobalId,
    val date: BusinessDate,
    val reason: String,
) : Command {
    override val requiredPermission = Permission.PURCHASE_REVERSE
    override fun fingerprint() = "$scope|$invoiceId|${date.epochDay}|$reason"
}

/** Goods sent back to the supplier; reduces what we owe at the invoice price. */
data class ReturnToSupplier(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val invoiceId: GlobalId,
    val lines: List<IssueLine>,
    val date: BusinessDate,
    val reason: String,
) : Command {
    override val requiredPermission = Permission.PURCHASE_REVERSE
    override fun fingerprint() = "$scope|$invoiceId|${date.epochDay}|$reason|" + lines.joinToString(";") { "${it.itemId}:${it.quantity.micros}" }
}

class PurchasingOperations(
    private val bus: CommandBus,
    private val ledger: Ledger,
    private val capability: PostingCapability,
    private val inventory: InventoryGateway,
    private val treasury: TreasuryGateway,
    private val suppliers: SupplierStore,
    private val purchases: PurchaseStore,
) {
    init {
        require(capability.module == ModuleId.PURCHASING)
    }

    /** What we still owe on an invoice: total − active payments − returns. Derived, never stored. */
    fun outstanding(invoiceId: GlobalId): Money {
        val invoice = purchases.invoice(invoiceId) ?: throw DomainException(DomainError.NotFound("PURCHASE_INVOICE"))
        if (invoice.status == InvoiceStatus.REVERSED) return Money.ZERO
        val paid = Money.sum(purchases.payments(invoiceId).filter { !it.reversed }.map { it.amount })
        val returned = Money.sum(purchases.returns(invoiceId).map { it.credit })
        return invoice.total - paid - returned
    }

    /** Supplier sub-ledger balance; equals GL 2101 for that supplier's invoices. */
    fun supplierBalance(supplierId: GlobalId, scope: Scope? = null): Money =
        Money.sum(purchases.invoices().filter { it.supplierId == supplierId && (scope == null || it.scope == scope) }.map { outstanding(it.id) })

    fun registerSupplier(c: RegisterSupplier): CommandOutcome = bus.execute(ModuleId.PURCHASING, c) { cmd, ctx ->
        val name = cmd.name.trim()
        ensure(name.length in 2..120) { DomainError.InvalidInput("name", "نام تأمین‌کننده الزامی است.") }
        ensure(suppliers.all().none { it.name == name }) { DomainError.InvalidState("SUPPLIER", "DUPLICATE_NAME") }
        val supplier = Supplier(GlobalId.new(), name, cmd.phone.trim())
        suppliers.save(supplier)
        ctx.audit(AuditDraft("SUPPLIER_REGISTER", "SUPPLIER", supplier.id.value, name))
        supplier.id
    }

    fun postInvoice(c: PostPurchaseInvoice): CommandOutcome = bus.execute(ModuleId.PURCHASING, c) { cmd, ctx ->
        val supplier = suppliers.byId(cmd.supplierId) ?: throw DomainException(DomainError.NotFound("SUPPLIER"))
        ensure(supplier.isActive) { DomainError.InvalidState("SUPPLIER", "INACTIVE") }
        val number = normalize(cmd.supplierInvoiceNo)
        ensure(number.isNotEmpty()) { DomainError.InvalidInput("invoiceNo", "شماره فاکتور تأمین‌کننده الزامی است.") }
        ensure(purchases.invoiceByNumber(supplier.id, number) == null) { DomainError.InvalidState("PURCHASE_INVOICE", "DUPLICATE_NUMBER") }
        ensure(cmd.dueDate >= cmd.date) { DomainError.InvalidInput("dueDate", "سررسید نمی‌تواند قبل از تاریخ فاکتور باشد.") }
        ensure(inventory.location(cmd.locationId).scope == cmd.scope) { DomainError.InvalidInput("scope", "انبار متعلق به این شعبه نیست.") }
        val total = Money.sum(cmd.lines.map { it.value })
        val invoice = PurchaseInvoice(GlobalId.new(), supplier.id, number, cmd.scope, cmd.locationId, cmd.date, cmd.dueDate, cmd.lines, total, InvoiceStatus.POSTED)
        inventory.receive(
            ctx, capability, cmd.locationId, cmd.lines.map { ReceiptLine(it.itemId, it.quantity, it.value) }, cmd.date,
            INVOICE, invoice.id, "خرید از ${supplier.name} · فاکتور $number",
            listOf(LineDraft(StandardAccounts.PAYABLE, credit = total, memo = supplier.name, by = capability)),
        )
        purchases.saveInvoice(invoice)
        ctx.audit(AuditDraft("PURCHASE_INVOICE_POST", "PURCHASE_INVOICE", invoice.id.value, "supplier=${supplier.id};no=$number;total=${total.rial}"))
        ctx.emit("PurchaseInvoicePosted", mapOf("invoiceId" to invoice.id.value, "total" to total.rial.toString()))
        cmd.payNow?.let { pay(ctx, invoice, it.treasuryAccountId, it.amount, cmd.date) }
        invoice.id
    }

    fun payInvoice(c: PaySupplierInvoice): CommandOutcome = bus.execute(ModuleId.PURCHASING, c) { cmd, ctx ->
        val invoice = requireInvoice(cmd.invoiceId, cmd.scope)
        pay(ctx, invoice, cmd.treasuryAccountId, cmd.amount, cmd.date).id
    }

    fun reversePayment(c: ReverseSupplierPayment): CommandOutcome = bus.execute(ModuleId.PURCHASING, c) { cmd, ctx ->
        val payment = purchases.payment(cmd.paymentId) ?: throw DomainException(DomainError.NotFound("SUPPLIER_PAYMENT"))
        requireInvoice(payment.invoiceId, cmd.scope)
        ensure(!payment.reversed) { DomainError.InvalidState("SUPPLIER_PAYMENT", "ALREADY_REVERSED") }
        treasury.reverseDocument(ctx, capability, PAYMENT, payment.id, cmd.date, cmd.reason)
        payment.bridgeJournalId?.let { ledger.reverse(ctx, capability, emptySet(), it, cmd.date, cmd.reason) }
        purchases.savePayment(payment.copy(reversed = true))
        ctx.audit(AuditDraft("SUPPLIER_PAYMENT_REVERSE", "SUPPLIER_PAYMENT", payment.id.value, cmd.reason.trim()))
        payment.id
    }

    fun reverseInvoice(c: ReversePurchaseInvoice): CommandOutcome = bus.execute(ModuleId.PURCHASING, c) { cmd, ctx ->
        val invoice = requireInvoice(cmd.invoiceId, cmd.scope)
        ensure(purchases.payments(invoice.id).none { !it.reversed }) { DomainError.InvalidState("PURCHASE_INVOICE", "HAS_ACTIVE_PAYMENTS") }
        ensure(purchases.returns(invoice.id).isEmpty()) { DomainError.InvalidState("PURCHASE_INVOICE", "HAS_RETURNS") }
        inventory.reverseDocument(ctx, capability, INVOICE, invoice.id, cmd.date, cmd.reason)
        purchases.saveInvoice(invoice.copy(status = InvoiceStatus.REVERSED))
        ctx.audit(AuditDraft("PURCHASE_INVOICE_REVERSE", "PURCHASE_INVOICE", invoice.id.value, cmd.reason.trim()))
        invoice.id
    }

    fun returnGoods(c: ReturnToSupplier): CommandOutcome = bus.execute(ModuleId.PURCHASING, c) { cmd, ctx ->
        val invoice = requireInvoice(cmd.invoiceId, cmd.scope)
        ensure(cmd.reason.trim().length in 3..300) { DomainError.InvalidInput("reason", "دلیل مرجوعی الزامی است.") }
        val alreadyReturned = purchases.returns(invoice.id).flatMap { it.lines }.groupBy { it.itemId }
            .mapValues { (_, l) -> l.sumOf { it.quantity.micros } }
        val priced = cmd.lines.map { line ->
            val bought = invoice.lines.firstOrNull { it.itemId == line.itemId }
                ?: throw DomainException(DomainError.InvalidInput("item", "این کالا در فاکتور نیست."))
            val remaining = bought.quantity.micros - (alreadyReturned[line.itemId] ?: 0L)
            ensure(line.quantity.micros <= remaining) { DomainError.InvalidInput("quantity", "مقدار مرجوعی از مقدار خریداری‌شده بیشتر است.") }
            InvoiceLine(line.itemId, line.quantity, Money.of(Ratio.mulDiv(bought.value.rial, line.quantity.micros, bought.quantity.micros)))
        }
        val credit = Money.sum(priced.map { it.value })
        ensure(credit <= outstanding(invoice.id)) { DomainError.InvalidState("PURCHASE_INVOICE", "RETURN_EXCEEDS_OUTSTANDING") }
        val returnId = GlobalId.new()
        inventory.issueWithCounter(
            ctx, capability, invoice.locationId, cmd.lines, cmd.date, RETURN, returnId, "مرجوعی به تأمین‌کننده: ${cmd.reason.trim()}",
            listOf(LineDraft(StandardAccounts.PAYABLE, debit = credit, memo = "مرجوعی", by = capability)),
        )
        purchases.saveReturn(PurchaseReturn(returnId, invoice.id, priced, credit, cmd.date))
        ctx.audit(AuditDraft("PURCHASE_RETURN", "PURCHASE_INVOICE", invoice.id.value, "return=$returnId;credit=${credit.rial}"))
        returnId
    }

    private fun pay(ctx: CommandContext, invoice: PurchaseInvoice, accountId: GlobalId, amount: Money, date: BusinessDate): SupplierPayment {
        ensure(!amount.isZero) { DomainError.InvalidInput("amount", "مبلغ پرداخت باید بیشتر از صفر باشد.") }
        ensure(amount <= outstanding(invoice.id)) { DomainError.InvalidState("PURCHASE_INVOICE", "PAYMENT_EXCEEDS_OUTSTANDING") }
        ensure(date >= invoice.date) { DomainError.InvalidInput("date", "تاریخ پرداخت قبل از تاریخ فاکتور است.") }
        val account = treasury.account(accountId)
        val paymentId = GlobalId.new()
        val supplier = suppliers.byId(invoice.supplierId)!!
        var bridge: GlobalId? = null
        if (account.scope == invoice.scope) {
            treasury.settle(ctx, capability, account.id, Direction.PAYMENT, amount, date, PAYMENT, paymentId, "پرداخت به ${supplier.name}",
                listOf(LineDraft(StandardAccounts.PAYABLE, debit = amount, memo = supplier.name, by = capability)))
        } else {
            // Paying a branch's invoice from another scope's account (e.g. the organization bank):
            // each scope stays balanced through the inter-branch account.
            treasury.settle(ctx, capability, account.id, Direction.PAYMENT, amount, date, PAYMENT, paymentId, "پرداخت به ${supplier.name}",
                listOf(LineDraft(StandardAccounts.INTER_BRANCH, debit = amount, memo = "بدهی شعبه", by = capability)))
            bridge = ledger.post(ctx, capability, JournalDraft(date, invoice.scope, PAYMENT, paymentId, "تسویه بدهی ${supplier.name} از حساب مرکزی", listOf(
                LineDraft(StandardAccounts.PAYABLE, debit = amount, memo = supplier.name, by = capability),
                LineDraft(StandardAccounts.INTER_BRANCH, credit = amount, memo = account.name, by = capability),
            ))).id
        }
        val payment = SupplierPayment(paymentId, invoice.id, account.id, amount, date, bridge, reversed = false)
        purchases.savePayment(payment)
        ctx.audit(AuditDraft("SUPPLIER_PAYMENT", "PURCHASE_INVOICE", invoice.id.value, "payment=$paymentId;amount=${amount.rial}"))
        return payment
    }

    private fun requireInvoice(id: GlobalId, scope: Scope.Branch): PurchaseInvoice {
        val invoice = purchases.invoice(id) ?: throw DomainException(DomainError.NotFound("PURCHASE_INVOICE"))
        ensure(invoice.scope == scope) { DomainError.InvalidInput("scope", "فاکتور متعلق به این شعبه نیست.") }
        ensure(invoice.status == InvoiceStatus.POSTED) { DomainError.InvalidState("PURCHASE_INVOICE", invoice.status.name) }
        return invoice
    }

    private fun normalize(raw: String): String {
        val persian = "۰۱۲۳۴۵۶۷۸۹"; val arabic = "٠١٢٣٤٥٦٧٨٩"
        return raw.trim().uppercase().map { ch ->
            when (ch) {
                in persian -> '0' + persian.indexOf(ch)
                in arabic -> '0' + arabic.indexOf(ch)
                else -> ch
            }
        }.filter { !it.isWhitespace() && it != '-' && it != '/' }.joinToString("")
    }

    companion object {
        const val INVOICE = "PURCHASE_INVOICE"
        const val PAYMENT = "SUPPLIER_PAYMENT"
        const val RETURN = "PURCHASE_RETURN"
    }
}
