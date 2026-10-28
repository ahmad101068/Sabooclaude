package ir.sabou.purchasing

import ir.sabou.kernel.sumOfExact
import ir.sabou.platform.NoDocument

import ir.sabou.platform.DocumentSeries

import ir.sabou.platform.IssuesDocument

import ir.sabou.inventory.InventoryGateway
import ir.sabou.inventory.IssueLine
import ir.sabou.inventory.ReceiptLine
import ir.sabou.inventory.issueWithCounter
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Ratio
import ir.sabou.kernel.Scope
import ir.sabou.kernel.SignedAmount
import ir.sabou.kernel.ensure
import ir.sabou.ledger.AccountCode
import ir.sabou.ledger.AccountType
import ir.sabou.ledger.JournalDraft
import ir.sabou.ledger.Ledger
import ir.sabou.ledger.LineDraft
import ir.sabou.ledger.PostingCapability
import ir.sabou.ledger.StandardAccounts
import ir.sabou.platform.AttachmentInput
import ir.sabou.platform.AttachmentStore
import ir.sabou.platform.Attachments
import ir.sabou.platform.AuditDraft
import ir.sabou.platform.Command
import ir.sabou.platform.CommandBus
import ir.sabou.platform.CommandContext
import ir.sabou.platform.CommandOutcome
import ir.sabou.platform.ModuleId
import ir.sabou.platform.Permission
import ir.sabou.treasury.ChequeDetails
import ir.sabou.treasury.ChequeInstruction
import ir.sabou.treasury.Direction
import ir.sabou.treasury.paymentCheque
import ir.sabou.treasury.TreasuryGateway

@NoDocument
data class RegisterSupplier(override val commandId: GlobalId, val name: String, val phone: String) : Command {
    override val requiredPermission get() = Permission.SUPPLIER_MANAGE
    override val scope: Scope get() = Scope.Organization
    override val sharedCatalog get() = true
    override fun fingerprint() = "$name|$phone"
}

/** Name, phone, delivery days, order cutoff and lead time; deactivating keeps the history. */
@NoDocument
data class UpdateSupplier(
    override val commandId: GlobalId,
    val supplierId: GlobalId,
    val name: String,
    val phone: String,
    val isActive: Boolean,
    val deliveryDays: Set<Int>,
    val cutoffMinutes: Int?,
    val leadDays: Int,
    val note: String,
    /** National ID or economic code; null keeps them as they are (also [address] and [sheba]). */
    val taxId: String? = null,
    val address: String? = null,
    val sheba: String? = null,
    /** Send orders by SMS to the supplier's mobile; null keeps it as it is. */
    val orderSms: Boolean? = null,
) : Command {
    override val requiredPermission get() = Permission.SUPPLIER_MANAGE
    override val scope: Scope get() = Scope.Organization
    override val sharedCatalog get() = true
    override fun fingerprint() = "$supplierId|$name|$phone|$isActive|${deliveryDays.sorted()}|$cutoffMinutes|$leadDays|$note|$taxId|$address|$sheba|$orderSms"
}

/** [cheque]: our new cheque from a cheque book; [chequeId]: a customer's cheque passed on from a cheque box. */
data class ImmediatePayment(val treasuryAccountId: GlobalId, val amount: Money, val cheque: ChequeDetails? = null, val chequeId: GlobalId? = null) : java.io.Serializable {
    fun fingerprint() = "$treasuryAccountId:${amount.rial}:${cheque?.fingerprint()}:$chequeId"
}

/**
 * A supplier invoice: goods into a location, lines booked to expense accounts (any granted branch),
 * and lines whose item is not known yet (held for review). Optionally paid on the spot, optionally
 * closing a purchase order, with photos or PDFs of the paper invoice.
 */
@IssuesDocument(DocumentSeries.PURCHASE_INVOICE)
data class PostPurchaseInvoice(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val supplierId: GlobalId,
    val supplierInvoiceNo: String,
    val locationId: GlobalId?,
    val date: BusinessDate,
    val dueDate: BusinessDate,
    val lines: List<InvoiceLine>,
    val payNow: ImmediatePayment? = null,
    val accountLines: List<AccountLine> = emptyList(),
    val reviewLines: List<ReviewLine> = emptyList(),
    val note: String = "",
    val orderId: GlobalId? = null,
    val attachments: List<AttachmentInput> = emptyList(),
    /** VAT on the invoice (claimed against sales VAT), its discount and its freight (the last two spread into the goods' cost). */
    val vat: Money = Money.ZERO,
    val discount: Money = Money.ZERO,
    val freight: Money = Money.ZERO,
    /** Part of [orderId] arrived: the order stays open for the rest. */
    val orderPartial: Boolean = false,
) : Command {
    override val requiredPermission get() = Permission.PURCHASE_RECORD
    override val additionalPermissions get() = if (payNow != null) setOf(Permission.PURCHASE_PAY) else emptySet()
    override fun fingerprint() = "$scope|$supplierId|$supplierInvoiceNo|$locationId|${date.epochDay}|${dueDate.epochDay}|" +
        lines.joinToString(";") { "${it.itemId}:${it.quantity.micros}:${it.value.rial}:${it.supplierItemName}" } + "|${payNow?.fingerprint()}|" +
        accountLines.joinToString(";") { "${it.account}:${it.amount.rial}:${it.branch}:${it.memo}" } + "|" +
        reviewLines.joinToString(";") { "${it.supplierItemName}:${it.quantityNote}:${it.amount.rial}" } + "|$note|$orderId|" +
        attachments.joinToString(";") { it.fingerprint() } + "|${vat.rial}|${discount.rial}|${freight.rial}|$orderPartial"
}

/** Accepts an invoice's differences from its order (or a possible duplicate) after looking at them (ADR-0029). */
@NoDocument
data class ReviewInvoiceMatch(override val commandId: GlobalId, override val scope: Scope.Branch, val invoiceId: GlobalId, val note: String) : Command {
    override val requiredPermission get() = Permission.PURCHASE_APPROVE
    override fun fingerprint() = "$scope|$invoiceId|${note.trim()}"
}

/** Adds photos or PDFs to an invoice after it was recorded. */
@NoDocument
data class AttachToInvoice(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val invoiceId: GlobalId,
    val attachments: List<AttachmentInput>,
) : Command {
    override val requiredPermission get() = Permission.PURCHASE_RECORD
    override fun fingerprint() = "$scope|$invoiceId|" + attachments.joinToString(";") { it.fingerprint() }
}

/**
 * Assigns a held line: to an item (received into [locationId] at the line's amount) or to an
 * expense account. Exactly one of the two.
 */
@NoDocument
data class ResolveReviewLine(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val invoiceId: GlobalId,
    val index: Int,
    val itemId: GlobalId?,
    val quantity: Quantity?,
    val locationId: GlobalId?,
    val account: AccountCode?,
    val date: BusinessDate,
) : Command {
    override val requiredPermission get() = Permission.PURCHASE_RECORD
    override fun fingerprint() = "$scope|$invoiceId|$index|$itemId|${quantity?.micros}|$locationId|$account|${date.epochDay}"
}

@IssuesDocument(DocumentSeries.PAYMENT)
data class PaySupplierInvoice(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val invoiceId: GlobalId,
    val treasuryAccountId: GlobalId,
    val amount: Money,
    val date: BusinessDate,
    val cheque: ChequeDetails? = null,
    val chequeId: GlobalId? = null,
) : Command {
    override val requiredPermission get() = Permission.PURCHASE_PAY
    override fun fingerprint() = "$scope|$invoiceId|$treasuryAccountId|${amount.rial}|${date.epochDay}|${cheque?.fingerprint()}|$chequeId"
}

@IssuesDocument(DocumentSeries.REVERSAL)
data class ReverseSupplierPayment(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val paymentId: GlobalId,
    val date: BusinessDate,
    val reason: String,
) : Command {
    override val requiredPermission get() = Permission.PURCHASE_REVERSE
    override fun fingerprint() = "$scope|$paymentId|${date.epochDay}|$reason"
}

/** Full reversal of a wrongly entered invoice. Only possible before any payment, return, credit or review assignment. */
@IssuesDocument(DocumentSeries.REVERSAL)
data class ReversePurchaseInvoice(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val invoiceId: GlobalId,
    val date: BusinessDate,
    val reason: String,
) : Command {
    override val requiredPermission get() = Permission.PURCHASE_REVERSE
    override fun fingerprint() = "$scope|$invoiceId|${date.epochDay}|$reason"
}

/**
 * Corrects the amounts of a recorded invoice (SABOU-AUDIT-002): the corrected value of goods items ([items]: the whole
 * value of that item on the invoice) and of account lines ([accounts]: by index). Works whether or not the goods are
 * still in stock — the share in stock changes the stock's value, the share already used changes cost of sales — and
 * after payments, as long as what was already paid or credited does not exceed the corrected amount.
 */
@IssuesDocument(DocumentSeries.PURCHASE_CORRECTION)
data class CorrectPurchaseInvoice(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val invoiceId: GlobalId,
    val items: List<InvoiceLineValue>,
    val date: BusinessDate,
    val reason: String,
    val accounts: List<AccountLineValue> = emptyList(),
) : Command {
    override val requiredPermission get() = Permission.PURCHASE_REVERSE
    override fun fingerprint() = "$scope|$invoiceId|${items.joinToString(",") { "${it.itemId}=${it.value.rial}" }}|" +
        "${accounts.joinToString(",") { "${it.index}=${it.amount.rial}" }}|${date.epochDay}|$reason"
}

/** The corrected value of an item on an invoice (all its lines together). */
data class InvoiceLineValue(val itemId: GlobalId, val value: Money) : java.io.Serializable

/** The corrected amount of an invoice's account line [index]. */
data class AccountLineValue(val index: Int, val amount: Money) : java.io.Serializable

/**
 * Goods sent back to the supplier, credited at the invoice price. The credit settles this invoice
 * first, then the supplier's other open invoices in the branch (oldest due first); any rest is kept
 * as supplier credit.
 */
@IssuesDocument(DocumentSeries.PURCHASE_RETURN)
data class ReturnToSupplier(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val invoiceId: GlobalId,
    val lines: List<IssueLine>,
    val date: BusinessDate,
    val reason: String,
) : Command {
    override val requiredPermission get() = Permission.PURCHASE_REVERSE
    override fun fingerprint() = "$scope|$invoiceId|${date.epochDay}|$reason|" + lines.joinToString(";") { "${it.itemId}:${it.quantity.micros}" }
}

/** Uses the supplier's unapplied credit (from returns) against one of its invoices. */
@IssuesDocument(DocumentSeries.CREDIT_SETTLEMENT)
data class ApplySupplierCredit(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val invoiceId: GlobalId,
    val amount: Money,
    val date: BusinessDate,
) : Command {
    override val requiredPermission get() = Permission.PURCHASE_PAY
    override fun fingerprint() = "$scope|$invoiceId|${amount.rial}|${date.epochDay}"
}

/** Takes back a credit allocation (the credit becomes unapplied again), e.g. before reversing that invoice. */
@NoDocument
data class ReleaseCreditAllocation(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val allocationId: GlobalId,
    val reason: String,
    /** When an advance's allocation is taken back, the date of that correction (default: the allocation's). */
    val date: BusinessDate? = null,
) : Command {
    override val requiredPermission get() = Permission.PURCHASE_PAY
    override fun fingerprint() = "$scope|$allocationId|$reason|${date?.epochDay}"
}

/** The supplier pays back unapplied return credit into [treasuryAccountId] (a cheque box needs [cheque]). */
@IssuesDocument(DocumentSeries.RECEIPT)
data class ReceiveSupplierRefund(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val supplierId: GlobalId,
    val treasuryAccountId: GlobalId,
    val amount: Money,
    val date: BusinessDate,
    val cheque: ChequeDetails? = null,
) : Command {
    override val requiredPermission get() = Permission.PURCHASE_PAY
    override val additionalPermissions get() = setOf(Permission.TREASURY_RECEIPT)
    override fun fingerprint() = "$scope|$supplierId|$treasuryAccountId|${amount.rial}|${date.epochDay}|${cheque?.fingerprint()}"
}

@IssuesDocument(DocumentSeries.REVERSAL)
data class ReverseSupplierRefund(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val refundId: GlobalId,
    val date: BusinessDate,
    val reason: String,
) : Command {
    override val requiredPermission get() = Permission.PURCHASE_REVERSE
    override fun fingerprint() = "$scope|$refundId|${date.epochDay}|$reason"
}

/**
 * What the restaurant owed a supplier when it started with these books: a payable against capital, paid later like
 * any invoice. [reference] stands for the invoice number (e.g. «مانده ۱۴۰۵/۰۱/۰۱»).
 */
@IssuesDocument(DocumentSeries.PURCHASE_INVOICE)
data class RecordSupplierOpeningBalance(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val supplierId: GlobalId,
    val reference: String,
    val amount: Money,
    val date: BusinessDate,
    val dueDate: BusinessDate,
) : Command {
    override val requiredPermission get() = Permission.JOURNAL_MANUAL_POST
    override fun fingerprint() = "$scope|$supplierId|$reference|${amount.rial}|${date.epochDay}|${dueDate.epochDay}"
}

class PurchasingOperations(
    private val bus: CommandBus,
    private val ledger: Ledger,
    private val capability: PostingCapability,
    private val inventory: InventoryGateway,
    private val treasury: TreasuryGateway,
    private val suppliers: SupplierStore,
    private val purchases: PurchaseStore,
    private val attachments: AttachmentStore,
    private val approvalRules: ApprovalRuleStore = ir.sabou.purchasing.memory.InMemoryApprovalRuleStore(),
    private val advances: SupplierAdvanceStore = ir.sabou.purchasing.memory.InMemorySupplierAdvanceStore(),
) {
    init {
        require(capability.module == ModuleId.PURCHASING)
    }

    /** What we still owe on an invoice: total − active payments − credits set against it. Derived, never stored. */
    fun outstanding(invoiceId: GlobalId): Money {
        val invoice = purchases.invoice(invoiceId) ?: throw DomainException(DomainError.NotFound("PURCHASE_INVOICE"))
        if (invoice.status == InvoiceStatus.REVERSED) return Money.ZERO
        val paid = Money.sum(purchases.payments(invoiceId).filter { !it.reversed }.map { it.amount })
        val credited = Money.sum(purchases.allocationsTo(invoiceId).filter { !it.released }.map { it.amount })
        return invoice.total - paid - credited
    }

    /** Return credit of a supplier in a branch not yet set against any invoice. */
    fun unappliedCredit(supplierId: GlobalId, scope: Scope.Branch): Money {
        val returned = purchases.invoicesOf(supplierId).filter { it.scope == scope }
            .sumOfExact { inv -> purchases.returns(inv.id).sumOfExact { it.credit.rial } }
        val allocated = purchases.allocationsOf(supplierId, scope).filter { !it.released && it.advanceId == null }.sumOfExact { it.amount.rial }
        val refunded = purchases.refundsOf(supplierId, scope).filter { !it.reversed }.sumOfExact { it.amount.rial }
        return Money.of(returned - allocated - refunded)
    }

    /** Supplier sub-ledger balance (owed minus unapplied credit); equals GL 2101 for that supplier. */
    fun supplierBalance(supplierId: GlobalId, scope: Scope.Branch? = null): SignedAmount {
        val scopes = purchases.invoicesOf(supplierId).filter { scope == null || it.scope == scope }.map { it.scope }.distinct()
        val owed = purchases.invoicesOf(supplierId).filter { scope == null || it.scope == scope }.sumOfExact { outstanding(it.id).rial }
        val ahead = advances.of(supplierId).filter { !it.reversed && (scope == null || it.scope == scope) }.sumOfExact { advanceLeft(it.id).rial }
        return SignedAmount(owed - scopes.sumOfExact { unappliedCredit(supplierId, it).rial } - ahead)
    }

    /** What is left of an advance to set against invoices. */
    fun advanceLeft(advanceId: GlobalId): Money {
        val a = advances.byId(advanceId) ?: throw DomainException(DomainError.NotFound("SUPPLIER_ADVANCE"))
        if (a.reversed) return Money.ZERO
        return a.amount - Money.sum(purchases.allocationsOf(a.supplierId, a.scope).filter { it.advanceId == a.id && !it.released }.map { it.amount })
    }

    fun advancesOf(supplierId: GlobalId): List<SupplierAdvance> = advances.of(supplierId)

    fun payAdvance(c: PaySupplierAdvance): CommandOutcome = bus.execute(ModuleId.PURCHASING, c) { cmd, ctx ->
        val supplier = suppliers.byId(cmd.supplierId) ?: throw DomainException(DomainError.NotFound("SUPPLIER"))
        ensure(supplier.isActive) { DomainError.InvalidState("SUPPLIER", "INACTIVE") }
        ensure(!cmd.amount.isZero) { DomainError.InvalidInput("amount", "مبلغ باید بیشتر از صفر باشد.") }
        ensure(cmd.note.length <= 200) { DomainError.InvalidInput("note", "توضیح طولانی است.") }
        val account = treasury.account(cmd.treasuryAccountId)
        val id = GlobalId.new()
        ctx.number(DocumentSeries.PAYMENT, cmd.date, id, scope = cmd.scope)
        val title = "پیش‌پرداخت به ${supplier.name}" + if (cmd.note.isBlank()) "" else ": ${cmd.note.trim()}"
        val cheque = paymentCheque(cmd.cheque, null)
        var bridge: GlobalId? = null
        if (account.scope == cmd.scope) {
            treasury.settle(ctx, capability, account.id, Direction.PAYMENT, cmd.amount, cmd.date, ADVANCE, id, title,
                listOf(LineDraft(StandardAccounts.SUPPLIER_ADVANCES, debit = cmd.amount, memo = supplier.name, by = capability)), cheque)
        } else {
            treasury.settle(ctx, capability, account.id, Direction.PAYMENT, cmd.amount, cmd.date, ADVANCE, id, title,
                listOf(LineDraft(StandardAccounts.INTER_BRANCH, debit = cmd.amount, memo = "پرداخت برای شعبه", by = capability)), cheque)
            bridge = ledger.post(ctx, capability, JournalDraft(cmd.date, cmd.scope, ADVANCE, id, "$title (از حساب مرکزی)", listOf(
                LineDraft(StandardAccounts.SUPPLIER_ADVANCES, debit = cmd.amount, memo = supplier.name, by = capability),
                LineDraft(StandardAccounts.INTER_BRANCH, credit = cmd.amount, memo = account.name, by = capability),
            ))).id
        }
        advances.save(SupplierAdvance(id, supplier.id, cmd.scope, account.id, cmd.amount, cmd.date, cmd.note.trim(), bridge))
        ctx.audit(AuditDraft("SUPPLIER_ADVANCE", "SUPPLIER", supplier.id.value, "advance=$id;amount=${cmd.amount.rial}"))
        id
    }

    fun applyAdvance(c: ApplySupplierAdvance): CommandOutcome = bus.execute(ModuleId.PURCHASING, c) { cmd, ctx ->
        val advance = advances.byId(cmd.advanceId) ?: throw DomainException(DomainError.NotFound("SUPPLIER_ADVANCE"))
        val invoice = requireInvoice(cmd.invoiceId, cmd.scope)
        ensure(advance.scope == cmd.scope && advance.supplierId == invoice.supplierId) { DomainError.InvalidInput("advance", "پیش‌پرداخت متعلق به این تأمین‌کننده و شعبه نیست.") }
        ensure(!cmd.amount.isZero && cmd.amount <= advanceLeft(advance.id)) { DomainError.InvalidState("SUPPLIER_ADVANCE", "EXCEEDS_AVAILABLE") }
        ensure(cmd.amount <= outstanding(invoice.id)) { DomainError.InvalidState("PURCHASE_INVOICE", "PAYMENT_EXCEEDS_OUTSTANDING") }
        ensure(cmd.date >= advance.date) { DomainError.InvalidInput("date", "تاریخ قبل از پیش‌پرداخت است.") }
        val id = GlobalId.new()
        ctx.number(DocumentSeries.CREDIT_SETTLEMENT, cmd.date, id)
        val supplier = suppliers.byId(invoice.supplierId)!!
        val journal = ledger.post(ctx, capability, JournalDraft(cmd.date, cmd.scope, ADVANCE_APPLY, id, "تسویه‌ی فاکتور ${invoice.supplierInvoiceNo} از پیش‌پرداخت", listOf(
            LineDraft(StandardAccounts.PAYABLE, debit = cmd.amount, memo = supplier.name, by = capability),
            LineDraft(StandardAccounts.SUPPLIER_ADVANCES, credit = cmd.amount, memo = supplier.name, by = capability),
        )))
        purchases.saveAllocation(CreditAllocation(id, invoice.supplierId, invoice.scope, invoice.id, cmd.amount, cmd.date, null, advanceId = advance.id, journalId = journal.id))
        ctx.audit(AuditDraft("SUPPLIER_ADVANCE_APPLY", "PURCHASE_INVOICE", invoice.id.value, "advance=${advance.id};amount=${cmd.amount.rial}"))
        id
    }

    fun reverseAdvance(c: ReverseSupplierAdvance): CommandOutcome = bus.execute(ModuleId.PURCHASING, c) { cmd, ctx ->
        val advance = advances.byId(cmd.advanceId) ?: throw DomainException(DomainError.NotFound("SUPPLIER_ADVANCE"))
        ensure(advance.scope == cmd.scope) { DomainError.InvalidInput("scope", "متعلق به این شعبه نیست.") }
        ensure(!advance.reversed) { DomainError.InvalidState("SUPPLIER_ADVANCE", "ALREADY_REVERSED") }
        ensure(advanceLeft(advance.id) == advance.amount) { DomainError.InvalidState("SUPPLIER_ADVANCE", "IN_USE") }
        treasury.reverseDocument(ctx, capability, ADVANCE, advance.id, cmd.date, cmd.reason)
        advance.bridgeJournalId?.let { ledger.reverse(ctx, capability, emptySet(), it, cmd.date, cmd.reason) }
        ctx.number(DocumentSeries.REVERSAL, cmd.date, cmd.commandId, reverses = DocumentSeries.PAYMENT to advance.id)
        advances.save(advance.copy(reversed = true))
        ctx.audit(AuditDraft("SUPPLIER_ADVANCE_REVERSE", "SUPPLIER", advance.supplierId.value, "advance=${advance.id};reason=${cmd.reason.trim()}"))
        advance.id
    }

    /** One payment document per invoice, oldest due first, until [PaySupplier.amount] is used. */
    fun paySupplier(c: PaySupplier): CommandOutcome = bus.execute(ModuleId.PURCHASING, c) { cmd, ctx ->
        ensure(!cmd.amount.isZero) { DomainError.InvalidInput("amount", "مبلغ باید بیشتر از صفر باشد.") }
        val payable = purchases.invoicesOf(cmd.supplierId).filter {
            it.scope == cmd.scope && it.status == InvoiceStatus.POSTED && it.approved && !it.needsMatchReview && it.date <= cmd.date
        }.sortedWith(compareBy({ it.dueDate }, { it.date })).map { it to outstanding(it.id) }.filter { !it.second.isZero }
        ensure(cmd.amount.rial <= payable.sumOfExact { it.second.rial }) { DomainError.InvalidState("SUPPLIER", "PAYMENT_EXCEEDS_PAYABLE") }
        var left = cmd.amount
        var first: GlobalId? = null
        for ((invoice, open) in payable) {
            if (left.isZero) break
            val take = minOf(left, open)
            val p = pay(ctx, invoice, cmd.treasuryAccountId, take, cmd.date, null)
            if (first == null) first = p.id
            left -= take
        }
        first!!
    }

    fun registerSupplier(c: RegisterSupplier): CommandOutcome = bus.execute(ModuleId.PURCHASING, c) { cmd, ctx ->
        val name = cmd.name.trim()
        ensure(name.length in 2..120) { DomainError.InvalidInput("name", "نام تأمین‌کننده الزامی است.") }
        ensure(suppliers.all().none { it.name == name }) { DomainError.InvalidState("SUPPLIER", "DUPLICATE_NAME") }
        val supplier = Supplier(GlobalId.new(), name, cmd.phone.trim())
        suppliers.save(supplier)
        ctx.audit(AuditDraft("SUPPLIER_REGISTER", "SUPPLIER", supplier.id.value, name))
        supplier.id
    }

    fun updateSupplier(c: UpdateSupplier): CommandOutcome = bus.execute(ModuleId.PURCHASING, c) { cmd, ctx ->
        val current = suppliers.byId(cmd.supplierId) ?: throw DomainException(DomainError.NotFound("SUPPLIER"))
        val name = cmd.name.trim()
        ensure(name.length in 2..120) { DomainError.InvalidInput("name", "نام تأمین‌کننده الزامی است.") }
        ensure(suppliers.all().none { it.id != current.id && it.name == name }) { DomainError.InvalidState("SUPPLIER", "DUPLICATE_NAME") }
        ensure(cmd.deliveryDays.all { it in 0..6 }) { DomainError.InvalidInput("deliveryDays", "روز تحویل نامعتبر است.") }
        ensure(cmd.cutoffMinutes == null || cmd.cutoffMinutes in 0 until 24 * 60) { DomainError.InvalidInput("cutoff", "ساعت مهلت سفارش نامعتبر است.") }
        ensure(cmd.leadDays in 0..14) { DomainError.InvalidInput("leadDays", "فاصله‌ی سفارش تا تحویل باید بین ۰ تا ۱۴ روز باشد.") }
        ensure(cmd.note.length <= 500) { DomainError.InvalidInput("note", "توضیح طولانی است.") }
        val taxId = cmd.taxId?.let { ir.sabou.kernel.NationalId.normalize(it).filter(Char::isDigit) } ?: current.taxId
        ensure(taxId.isEmpty() || taxId.length in 10..14) { DomainError.InvalidInput("taxId", "کد ملی، شناسه‌ی ملی یا کد اقتصادی معتبر نیست.") }
        val sheba = cmd.sheba?.let { if (it.isBlank()) "" else ir.sabou.kernel.Sheba.normalize(it) } ?: current.sheba
        ensure(sheba.isEmpty() || ir.sabou.kernel.Sheba.isValid(sheba)) { DomainError.InvalidInput("sheba", "شماره شبا معتبر نیست.") }
        val address = cmd.address?.trim() ?: current.address
        ensure(address.length <= 300) { DomainError.InvalidInput("address", "نشانی طولانی است.") }
        val orderSms = cmd.orderSms ?: current.orderSms
        // An order by SMS needs a mobile number to go to.
        ensure(!orderSms || ir.sabou.kernel.Mobile.isValid(cmd.phone)) { DomainError.InvalidInput("phone", "برای ارسال پیامکی سفارش، شماره همراه (۰۹…) لازم است.") }
        val updated = current.copy(
            name = name, phone = cmd.phone.trim(), isActive = cmd.isActive, deliveryDays = cmd.deliveryDays,
            cutoffMinutes = cmd.cutoffMinutes, leadDays = cmd.leadDays, note = cmd.note.trim(), taxId = taxId, address = address, sheba = sheba,
            orderSms = orderSms,
        )
        suppliers.save(updated)
        ctx.audit(AuditDraft("SUPPLIER_UPDATE", "SUPPLIER", current.id.value, "active=${updated.isActive};days=${updated.deliveryDays.sorted()};cutoff=${updated.cutoffMinutes};lead=${updated.leadDays};orderSms=${updated.orderSms}"))
        current.id
    }

    fun postInvoice(c: PostPurchaseInvoice): CommandOutcome = bus.execute(ModuleId.PURCHASING, c) { cmd, ctx ->
        val supplier = suppliers.byId(cmd.supplierId) ?: throw DomainException(DomainError.NotFound("SUPPLIER"))
        ensure(supplier.isActive) { DomainError.InvalidState("SUPPLIER", "INACTIVE") }
        val number = normalize(cmd.supplierInvoiceNo)
        ensure(number.isNotEmpty()) { DomainError.InvalidInput("invoiceNo", "شماره فاکتور تأمین‌کننده الزامی است.") }
        ensure(purchases.invoiceByNumber(supplier.id, number) == null) { DomainError.InvalidState("PURCHASE_INVOICE", "DUPLICATE_NUMBER") }
        ensure(cmd.dueDate >= cmd.date) { DomainError.InvalidInput("dueDate", "سررسید نمی‌تواند قبل از تاریخ فاکتور باشد.") }
        ensure(cmd.lines.isNotEmpty() || cmd.accountLines.isNotEmpty() || cmd.reviewLines.isNotEmpty()) {
            DomainError.InvalidInput("lines", "فاکتور حداقل یک ردیف لازم دارد.")
        }
        ensure(cmd.note.length <= 500) { DomainError.InvalidInput("note", "توضیح طولانی است.") }
        if (cmd.lines.isNotEmpty()) ensure(cmd.locationId != null) { DomainError.InvalidInput("location", "انبار دریافت کالا را انتخاب کنید.") }
        cmd.locationId?.let { ensure(inventory.location(it).scope == cmd.scope) { DomainError.InvalidInput("scope", "انبار متعلق به این شعبه نیست.") } }
        cmd.accountLines.forEach { line ->
            ensure(!line.amount.isZero) { DomainError.InvalidInput("amount", "مبلغ ردیف حساب باید بیشتر از صفر باشد.") }
            requireExpenseAccount(line.account)
            line.branch?.let { ctx.requireScope(it) }
        }
        cmd.reviewLines.forEach { line ->
            ensure(line.resolution == null) { DomainError.InvalidInput("review", "ردیف بررسی نباید از پیش تعیین‌تکلیف شده باشد.") }
            ensure(line.supplierItemName.isNotBlank() && line.supplierItemName.length <= 120) { DomainError.InvalidInput("review", "نام کالا در فاکتور تأمین‌کننده را بنویسید.") }
            ensure(!line.amount.isZero) { DomainError.InvalidInput("amount", "مبلغ ردیف باید بیشتر از صفر باشد.") }
        }
        val order = cmd.orderId?.let { id ->
            val o = purchases.order(id) ?: throw DomainException(DomainError.NotFound("PURCHASE_ORDER"))
            ensure(o.scope == cmd.scope && o.supplierId == supplier.id) { DomainError.InvalidInput("order", "سفارش متعلق به این تأمین‌کننده و شعبه نیست.") }
            ensure(o.status == OrderStatus.OPEN) { DomainError.InvalidState("PURCHASE_ORDER", o.status.name) }
            o
        }
        ensure(cmd.lines.isNotEmpty() || (cmd.discount.isZero && cmd.freight.isZero)) { DomainError.InvalidInput("discount", "تخفیف و کرایه روی کالا سرشکن می‌شود؛ فاکتور بدون کالا را با ردیف هزینه ثبت کنید.") }
        ensure(cmd.discount <= Money.sum(cmd.lines.map { it.value })) { DomainError.InvalidInput("discount", "تخفیف از جمع کالاها بیشتر است.") }
        // The invoice's discount and freight are part of what the goods cost: spread over the lines by value.
        val lines = spread(cmd.lines, cmd.freight.rial - cmd.discount.rial)
        val goods = Money.sum(lines.map { it.value })
        val accountsTotal = Money.sum(cmd.accountLines.map { it.amount })
        val reviewTotal = Money.sum(cmd.reviewLines.map { it.amount })
        val total = goods + accountsTotal + reviewTotal + cmd.vat
        ensure(!total.isZero) { DomainError.InvalidInput("value", "جمع فاکتور باید بیشتر از صفر باشد.") }
        val invoiceId = GlobalId.new()
        ctx.number(DocumentSeries.PURCHASE_INVOICE, cmd.date, invoiceId)
        val title = "خرید از ${supplier.name} · فاکتور $number"
        if (lines.isNotEmpty()) {
            inventory.receive(
                ctx, capability, cmd.locationId!!, lines.map { ReceiptLine(it.itemId, it.quantity, it.value, it.expiresOn) }, cmd.date, INVOICE, invoiceId, title,
                listOf(LineDraft(StandardAccounts.PAYABLE, credit = goods, memo = supplier.name, by = capability)),
            )
        }
        val journals = postOtherLines(ctx, cmd.scope, cmd.date, invoiceId, title, supplier.name, cmd.accountLines, reviewTotal) +
            listOfNotNull(cmd.vat.takeIf { !it.isZero }?.let { vat ->
                ledger.post(ctx, capability, JournalDraft(cmd.date, cmd.scope, INVOICE, invoiceId, "$title (مالیات بر ارزش افزوده)", listOf(
                    LineDraft(StandardAccounts.VAT_RECEIVABLE, debit = vat, memo = supplier.name, by = capability),
                    LineDraft(StandardAccounts.PAYABLE, credit = vat, memo = supplier.name, by = capability),
                ))).id
            })
        val invoice = PurchaseInvoice(
            invoiceId, supplier.id, number, cmd.scope, cmd.locationId, cmd.date, cmd.dueDate, lines.map { it.copy(supplierItemName = it.supplierItemName.trim()) },
            total, InvoiceStatus.POSTED, cmd.accountLines.map { it.copy(memo = it.memo.trim()) },
            cmd.reviewLines.map { it.copy(supplierItemName = it.supplierItemName.trim(), quantityNote = it.quantityNote.trim()) },
            cmd.note.trim(), order?.id, journals, recordedBy = ctx.actor.userId,
            discrepancies = order?.let { InvoiceMatching.compare(it, lines, receivedOf(it), cmd.orderPartial) }.orEmpty() + possibleDuplicates(supplier.id, cmd.supplierInvoiceNo, total, cmd.date),
            vat = cmd.vat, discount = cmd.discount, freight = cmd.freight,
        ).let { inv -> inv.copy(requiredApprovals = approvalRules.all().filter { it.matches(inv.scope, inv.supplierId, inv.category, inv.total) }.maxOfOrNull { it.steps } ?: 0) }
        purchases.saveInvoice(invoice)
        cmd.lines.filter { it.supplierItemName.isNotBlank() }.forEach { suppliers.saveAlias(supplier.id, SupplierNames.normalize(it.supplierItemName), it.itemId) }
        order?.let {
            val received = receivedOf(it).toMutableMap().apply { lines.forEach { l -> merge(l.itemId, l.quantity.micros, Long::plus) } }
            val complete = !cmd.orderPartial || it.lines.groupBy { l -> l.itemId }.all { (item, l) -> (received[item] ?: 0L) >= l.sumOfExact { x -> x.quantity.micros } }
            purchases.saveOrder(it.copy(status = if (complete) OrderStatus.RECEIVED else OrderStatus.OPEN, invoiceId = invoice.id, invoiceIds = it.invoiceIds + invoice.id))
            ctx.audit(AuditDraft("PURCHASE_ORDER_RECEIVE", "PURCHASE_ORDER", it.id.value, "invoice=${invoice.id};complete=$complete"))
        }
        Attachments.attach(ctx, attachments, INVOICE, invoice.id, cmd.attachments)
        ctx.audit(AuditDraft("PURCHASE_INVOICE_POST", "PURCHASE_INVOICE", invoice.id.value, "supplier=${supplier.id};no=$number;total=${total.rial};review=${reviewTotal.rial}"))
        ctx.emit("PurchaseInvoicePosted", mapOf("invoiceId" to invoice.id.value, "total" to total.rial.toString()))
        cmd.payNow?.let { pay(ctx, invoice, it.treasuryAccountId, it.amount, cmd.date, paymentCheque(it.cheque, it.chequeId)) }
        invoice.id
    }

    fun openingBalance(c: RecordSupplierOpeningBalance): CommandOutcome = bus.execute(ModuleId.PURCHASING, c) { cmd, ctx ->
        val supplier = suppliers.byId(cmd.supplierId) ?: throw DomainException(DomainError.NotFound("SUPPLIER"))
        val number = normalize(cmd.reference)
        ensure(number.isNotEmpty() && number.length <= 40) { DomainError.InvalidInput("reference", "شرح یا شماره‌ی مانده را بنویسید.") }
        ensure(purchases.invoiceByNumber(supplier.id, number) == null) { DomainError.InvalidState("PURCHASE_INVOICE", "DUPLICATE_NUMBER") }
        ensure(!cmd.amount.isZero) { DomainError.InvalidInput("amount", "مبلغ باید بیشتر از صفر باشد.") }
        ensure(cmd.dueDate >= cmd.date) { DomainError.InvalidInput("dueDate", "سررسید نمی‌تواند قبل از تاریخ باشد.") }
        val invoiceId = GlobalId.new()
        ctx.number(DocumentSeries.PURCHASE_INVOICE, cmd.date, invoiceId)
        val journal = ledger.post(ctx, capability, JournalDraft(cmd.date, cmd.scope, OPENING, invoiceId, "مانده‌ی اول دوره‌ی ${supplier.name}", listOf(
            LineDraft(StandardAccounts.CAPITAL, debit = cmd.amount, memo = "مانده‌ی اول دوره", by = capability),
            LineDraft(StandardAccounts.PAYABLE, credit = cmd.amount, memo = supplier.name, by = capability),
        )))
        val invoice = PurchaseInvoice(invoiceId, supplier.id, number, cmd.scope, null, cmd.date, cmd.dueDate, emptyList(), cmd.amount, InvoiceStatus.POSTED,
            note = "مانده‌ی اول دوره", journalIds = listOf(journal.id), recordedBy = ctx.actor.userId, opening = true)
            .let { inv -> inv.copy(requiredApprovals = approvalRules.all().filter { it.matches(inv.scope, inv.supplierId, inv.category, inv.total) }.maxOfOrNull { it.steps } ?: 0) }
        purchases.saveInvoice(invoice)
        ctx.audit(AuditDraft("SUPPLIER_OPENING_BALANCE", "PURCHASE_INVOICE", invoiceId.value, "supplier=${supplier.id};amount=${cmd.amount.rial}"))
        invoiceId
    }

    fun attach(c: AttachToInvoice): CommandOutcome = bus.execute(ModuleId.PURCHASING, c) { cmd, ctx ->
        val invoice = purchases.invoice(cmd.invoiceId) ?: throw DomainException(DomainError.NotFound("PURCHASE_INVOICE"))
        ensure(invoice.scope == cmd.scope) { DomainError.InvalidInput("scope", "فاکتور متعلق به این شعبه نیست.") }
        ensure(cmd.attachments.isNotEmpty()) { DomainError.InvalidInput("attachment", "فایلی انتخاب نشده است.") }
        Attachments.attach(ctx, attachments, INVOICE, invoice.id, cmd.attachments)
        invoice.id
    }

    fun resolveReviewLine(c: ResolveReviewLine): CommandOutcome = bus.execute(ModuleId.PURCHASING, c) { cmd, ctx ->
        val invoice = requireInvoice(cmd.invoiceId, cmd.scope)
        val line = invoice.reviewLines.getOrNull(cmd.index) ?: throw DomainException(DomainError.NotFound("REVIEW_LINE"))
        ensure(line.resolution == null) { DomainError.InvalidState("REVIEW_LINE", "ALREADY_RESOLVED") }
        ensure(cmd.date >= invoice.date) { DomainError.InvalidInput("date", "تاریخ قبل از تاریخ فاکتور است.") }
        ensure((cmd.itemId != null) != (cmd.account != null)) { DomainError.InvalidInput("target", "یا کالا را انتخاب کنید یا حساب هزینه را.") }
        val docId = GlobalId.new()
        val supplier = suppliers.byId(invoice.supplierId)!!
        val title = "تعیین تکلیف «${line.supplierItemName}» از فاکتور ${invoice.supplierInvoiceNo}"
        val resolution = if (cmd.itemId != null) {
            val qty = cmd.quantity ?: throw DomainException(DomainError.InvalidInput("quantity", "مقدار کالا را وارد کنید."))
            val locationId = cmd.locationId ?: invoice.locationId ?: throw DomainException(DomainError.InvalidInput("location", "انبار را انتخاب کنید."))
            ensure(inventory.location(locationId).scope == invoice.scope) { DomainError.InvalidInput("scope", "انبار متعلق به این شعبه نیست.") }
            val journal = inventory.receive(
                ctx, capability, locationId, listOf(ReceiptLine(cmd.itemId, qty, line.amount)), cmd.date, REVIEW, docId, title,
                listOf(LineDraft(StandardAccounts.PURCHASES_PENDING_REVIEW, credit = line.amount, memo = supplier.name, by = capability)),
            )
            suppliers.saveAlias(supplier.id, SupplierNames.normalize(line.supplierItemName), cmd.itemId)
            ReviewResolution(cmd.itemId, qty, locationId, null, journal.id, docId, cmd.date)
        } else {
            requireExpenseAccount(cmd.account!!)
            val journal = ledger.post(ctx, capability, JournalDraft(cmd.date, invoice.scope, REVIEW, docId, title, listOf(
                LineDraft(cmd.account, debit = line.amount, memo = line.supplierItemName, by = capability),
                LineDraft(StandardAccounts.PURCHASES_PENDING_REVIEW, credit = line.amount, memo = supplier.name, by = capability),
            )))
            ReviewResolution(null, null, null, cmd.account, journal.id, docId, cmd.date)
        }
        purchases.saveInvoice(invoice.copy(reviewLines = invoice.reviewLines.mapIndexed { i, l -> if (i == cmd.index) l.copy(resolution = resolution) else l }))
        ctx.audit(AuditDraft("PURCHASE_REVIEW_RESOLVE", "PURCHASE_INVOICE", invoice.id.value, "line=${cmd.index};item=${cmd.itemId};account=${cmd.account}"))
        docId
    }

    fun payInvoice(c: PaySupplierInvoice): CommandOutcome = bus.execute(ModuleId.PURCHASING, c) { cmd, ctx ->
        val invoice = requireInvoice(cmd.invoiceId, cmd.scope)
        pay(ctx, invoice, cmd.treasuryAccountId, cmd.amount, cmd.date, paymentCheque(cmd.cheque, cmd.chequeId)).id
    }

    fun reversePayment(c: ReverseSupplierPayment): CommandOutcome = bus.execute(ModuleId.PURCHASING, c) { cmd, ctx ->
        val payment = purchases.payment(cmd.paymentId) ?: throw DomainException(DomainError.NotFound("SUPPLIER_PAYMENT"))
        requireInvoice(payment.invoiceId, cmd.scope)
        ensure(!payment.reversed) { DomainError.InvalidState("SUPPLIER_PAYMENT", "ALREADY_REVERSED") }
        treasury.reverseDocument(ctx, capability, PAYMENT, payment.id, cmd.date, cmd.reason)
        ctx.number(DocumentSeries.REVERSAL, cmd.date, cmd.commandId, reverses = DocumentSeries.PAYMENT to payment.id)
        payment.bridgeJournalId?.let { ledger.reverse(ctx, capability, emptySet(), it, cmd.date, cmd.reason) }
        purchases.savePayment(payment.copy(reversed = true))
        ctx.audit(AuditDraft("SUPPLIER_PAYMENT_REVERSE", "SUPPLIER_PAYMENT", payment.id.value, cmd.reason.trim()))
        payment.id
    }

    fun reverseInvoice(c: ReversePurchaseInvoice): CommandOutcome = bus.execute(ModuleId.PURCHASING, c) { cmd, ctx ->
        val invoice = requireInvoice(cmd.invoiceId, cmd.scope)
        ensure(purchases.payments(invoice.id).none { !it.reversed }) { DomainError.InvalidState("PURCHASE_INVOICE", "HAS_ACTIVE_PAYMENTS") }
        ensure(purchases.returns(invoice.id).isEmpty()) { DomainError.InvalidState("PURCHASE_INVOICE", "HAS_RETURNS") }
        ensure(purchases.allocationsTo(invoice.id).none { !it.released }) { DomainError.InvalidState("PURCHASE_INVOICE", "HAS_CREDITS") }
        // Its corrections are undone first (newest first), so the goods leave stock at the value they came in with.
        invoice.corrections.reversed().forEach { c ->
            c.journalId?.let { inventory.reverseRevaluation(ctx, capability, CORRECTION, c.id, it, cmd.date, cmd.reason) }
        }
        if (invoice.lines.isNotEmpty()) inventory.reverseDocument(ctx, capability, INVOICE, invoice.id, cmd.date, cmd.reason)
        // Lines assigned after recording go back too: the goods leave stock (they must still be there), or the expense is reversed.
        invoice.reviewLines.mapNotNull { it.resolution }.forEach { r ->
            if (r.itemId != null) inventory.reverseDocument(ctx, capability, REVIEW, r.documentId, cmd.date, cmd.reason)
            else ledger.reverse(ctx, capability, emptySet(), r.journalId, cmd.date, cmd.reason)
        }
        invoice.journalIds.forEach { ledger.reverse(ctx, capability, emptySet(), it, cmd.date, cmd.reason) }
        ctx.number(DocumentSeries.REVERSAL, cmd.date, cmd.commandId, reverses = DocumentSeries.PURCHASE_INVOICE to invoice.id)
        purchases.saveInvoice(invoice.copy(status = InvoiceStatus.REVERSED))
        // The order it closed is open again for the corrected invoice.
        invoice.orderId?.let { id -> purchases.order(id)?.takeIf { invoice.id in it.invoiceIds }?.let { o ->
            val rest = o.invoiceIds - invoice.id
            purchases.saveOrder(o.copy(status = if (o.status == OrderStatus.CANCELLED) o.status else OrderStatus.OPEN, invoiceId = rest.lastOrNull(), invoiceIds = rest))
        } }
        ctx.audit(AuditDraft("PURCHASE_INVOICE_REVERSE", "PURCHASE_INVOICE", invoice.id.value, cmd.reason.trim()))
        invoice.id
    }

    fun correctInvoice(c: CorrectPurchaseInvoice): CommandOutcome = bus.execute(ModuleId.PURCHASING, c) { cmd, ctx ->
        val invoice = requireInvoice(cmd.invoiceId, cmd.scope)
        ensure(!invoice.opening) { DomainError.InvalidState("PURCHASE_INVOICE", "OPENING_BALANCE") }
        ensure(cmd.reason.trim().length in 3..300) { DomainError.InvalidInput("reason", "دلیل اصلاح الزامی است.") }
        ensure(cmd.date >= invoice.date) { DomainError.InvalidInput("date", "تاریخ اصلاح قبل از تاریخ فاکتور است.") }
        ensure(cmd.items.map { it.itemId }.distinct().size == cmd.items.size && cmd.accounts.map { it.index }.distinct().size == cmd.accounts.size) {
            DomainError.InvalidInput("lines", "هر ردیف فقط یک‌بار بیاید.")
        }
        val itemChanges = cmd.items.map { v ->
            ensure(invoice.lines.any { it.itemId == v.itemId }) { DomainError.InvalidInput("item", "این کالا در فاکتور نیست.") }
            ItemChange(v.itemId, Math.subtractExact(v.value.rial, invoice.itemValue(v.itemId)))
        }.filter { it.delta != 0L }
        val accountChanges = cmd.accounts.map { v ->
            ensure(v.index in invoice.accountLines.indices) { DomainError.InvalidInput("account", "این ردیف در فاکتور نیست.") }
            val line = invoice.accountLines[v.index]
            // A line booked to another branch belongs to that branch's journal: correct it there (reverse and record again).
            ensure(line.branch == null || line.branch == invoice.scope) { DomainError.InvalidState("PURCHASE_INVOICE", "ACCOUNT_LINE_OTHER_BRANCH") }
            AccountChange(v.index, Math.subtractExact(v.amount.rial, invoice.accountValue(v.index)))
        }.filter { it.delta != 0L }
        ensure(itemChanges.isNotEmpty() || accountChanges.isNotEmpty()) { DomainError.InvalidInput("lines", "مبلغی تغییر نکرده است.") }
        val change = itemChanges.sumOfExact { it.delta } + accountChanges.sumOfExact { it.delta }
        // What was paid or credited must still fit: a corrected amount below it would leave a negative debt.
        ensure(outstanding(invoice.id).rial + change >= 0) { DomainError.InvalidState("PURCHASE_INVOICE", "CORRECTION_BELOW_PAID") }

        // Goods: the share of the item still in stock (of what this invoice brought and was not returned) changes the
        // stock's value; the rest was used and changes cost of sales. Stock value never goes below zero.
        val returnedQty = purchases.returns(invoice.id).flatMap { it.lines }.groupBy { it.itemId }.mapValues { (_, l) -> l.sumOfExact { it.quantity.micros } }
        val parts = itemChanges.map { ch ->
            val bought = invoice.lines.filter { it.itemId == ch.itemId }.sumOfExact { it.quantity.micros } - (returnedQty[ch.itemId] ?: 0L)
            val balance = invoice.locationId?.let { inventory.balance(ch.itemId, it) }
            val onHand = minOf(balance?.quantity?.micros ?: 0L, bought.coerceAtLeast(0L))
            var inStock = if (bought <= 0L || onHand <= 0L) 0L else Ratio.mulDivSigned(ch.delta, onHand, bought)
            if (inStock < 0 && -inStock > (balance?.value?.rial ?: 0L)) inStock = -(balance?.value?.rial ?: 0L)
            ir.sabou.inventory.Revaluation(ch.itemId, inStock, ch.delta - inStock)
        }
        val counter = ArrayList<LineDraft>()
        if (change > 0) counter += LineDraft(StandardAccounts.PAYABLE, credit = Money.of(change), memo = "اصلاح فاکتور ${invoice.supplierInvoiceNo}", by = capability)
        if (change < 0) counter += LineDraft(StandardAccounts.PAYABLE, debit = Money.of(-change), memo = "اصلاح فاکتور ${invoice.supplierInvoiceNo}", by = capability)
        accountChanges.forEach { a ->
            val account = invoice.accountLines[a.index].account
            if (a.delta > 0) counter += LineDraft(account, debit = Money.of(a.delta), memo = cmd.reason.trim(), by = capability)
            if (a.delta < 0) counter += LineDraft(account, credit = Money.of(-a.delta), memo = cmd.reason.trim(), by = capability)
        }
        val correctionId = GlobalId.new()
        ctx.number(DocumentSeries.PURCHASE_CORRECTION, cmd.date, correctionId)
        val journal = inventory.revalue(ctx, capability, invoice.locationId, parts, cmd.date, CORRECTION, correctionId,
            "اصلاح فاکتور ${invoice.supplierInvoiceNo}: ${cmd.reason.trim()}", invoice.scope, counter)
        purchases.saveInvoice(invoice.copy(total = Money.of(Math.addExact(invoice.total.rial, change)), corrections = invoice.corrections +
            InvoiceCorrection(correctionId, cmd.date, itemChanges, accountChanges, cmd.reason.trim(), journal.id)))
        ctx.audit(AuditDraft("PURCHASE_INVOICE_CORRECT", "PURCHASE_INVOICE", invoice.id.value,
            "correction=$correctionId;change=$change;items=${itemChanges.size};accounts=${accountChanges.size}"))
        correctionId
    }

    fun returnGoods(c: ReturnToSupplier): CommandOutcome = bus.execute(ModuleId.PURCHASING, c) { cmd, ctx ->
        val invoice = requireInvoice(cmd.invoiceId, cmd.scope)
        ensure(cmd.reason.trim().length in 3..300) { DomainError.InvalidInput("reason", "دلیل مرجوعی الزامی است.") }
        ensure(cmd.date >= invoice.date) { DomainError.InvalidInput("date", "تاریخ مرجوعی قبل از تاریخ فاکتور است.") }
        ensure(cmd.lines.isNotEmpty() && cmd.lines.map { it.itemId }.distinct().size == cmd.lines.size) {
            DomainError.InvalidInput("lines", "هر کالا فقط یک‌بار در مرجوعی بیاید.")
        }
        val locationId = invoice.locationId ?: throw DomainException(DomainError.InvalidInput("item", "این فاکتور کالایی ندارد."))
        // An item may appear on several invoice lines: price it per item over the whole invoice.
        val boughtQty = invoice.lines.groupBy { it.itemId }.mapValues { (_, l) -> l.sumOfExact { it.quantity.micros } }
        // After a correction, goods are returned at their corrected value (SABOU-AUDIT-002).
        val boughtValue = invoice.lines.map { it.itemId }.distinct().associateWith { invoice.itemValue(it) }
        val previous = purchases.returns(invoice.id).flatMap { it.lines }.groupBy { it.itemId }
        val priced = cmd.lines.map { line ->
            val qty = boughtQty[line.itemId] ?: throw DomainException(DomainError.InvalidInput("item", "این کالا در فاکتور نیست."))
            val value = boughtValue.getValue(line.itemId)
            val returnedQty = previous[line.itemId].orEmpty().sumOfExact { it.quantity.micros }
            val returnedValue = previous[line.itemId].orEmpty().sumOfExact { it.value.rial }
            val remaining = qty - returnedQty
            ensure(!line.quantity.isZero && line.quantity.micros <= remaining) { DomainError.InvalidInput("quantity", "مقدار مرجوعی از مقدار خریداری‌شده بیشتر است.") }
            // Cumulative pricing: after this return, the total credited is value × returned/bought (rounded).
            // Each step is never negative, never exceeds what is left, and returning everything credits
            // exactly the invoice value — however small the price per unit.
            val credit = maxOf(0L, Ratio.mulDiv(value, returnedQty + line.quantity.micros, qty) - returnedValue)
            InvoiceLine(line.itemId, line.quantity, Money.of(credit))
        }
        val credit = Money.sum(priced.map { it.value })
        val returnId = GlobalId.new()
        ctx.number(DocumentSeries.PURCHASE_RETURN, cmd.date, returnId)
        inventory.issueWithCounter(
            ctx, capability, locationId, cmd.lines, cmd.date, RETURN, returnId, "مرجوعی به تأمین‌کننده: ${cmd.reason.trim()}",
            if (credit.isZero) emptyList() else listOf(LineDraft(StandardAccounts.PAYABLE, debit = credit, memo = "مرجوعی", by = capability)),
        )
        purchases.saveReturn(PurchaseReturn(returnId, invoice.id, priced, credit, cmd.date))
        // Settle this invoice first, then the supplier's other open invoices here, oldest due first.
        var left = credit
        val targets = listOf(invoice) + purchases.invoicesOf(invoice.supplierId)
            .filter { it.id != invoice.id && it.scope == invoice.scope && it.status == InvoiceStatus.POSTED }
            .sortedWith(compareBy({ it.dueDate }, { it.date }))
        for (target in targets) {
            if (left.isZero) break
            val take = minOf(left, outstanding(target.id))
            if (take.isZero) continue
            purchases.saveAllocation(CreditAllocation(GlobalId.new(), invoice.supplierId, invoice.scope, target.id, take, cmd.date, returnId))
            left -= take
        }
        ctx.audit(AuditDraft("PURCHASE_RETURN", "PURCHASE_INVOICE", invoice.id.value, "return=$returnId;credit=${credit.rial};unapplied=${left.rial}"))
        returnId
    }

    fun applyCredit(c: ApplySupplierCredit): CommandOutcome = bus.execute(ModuleId.PURCHASING, c) { cmd, ctx ->
        val invoice = requireInvoice(cmd.invoiceId, cmd.scope)
        ensure(!cmd.amount.isZero) { DomainError.InvalidInput("amount", "مبلغ باید بیشتر از صفر باشد.") }
        ensure(cmd.amount <= unappliedCredit(invoice.supplierId, invoice.scope)) { DomainError.InvalidState("SUPPLIER_CREDIT", "EXCEEDS_AVAILABLE") }
        ensure(cmd.amount <= outstanding(invoice.id)) { DomainError.InvalidState("PURCHASE_INVOICE", "PAYMENT_EXCEEDS_OUTSTANDING") }
        val allocation = CreditAllocation(GlobalId.new(), invoice.supplierId, invoice.scope, invoice.id, cmd.amount, cmd.date, null)
        ctx.number(DocumentSeries.CREDIT_SETTLEMENT, cmd.date, allocation.id)
        purchases.saveAllocation(allocation)
        ctx.audit(AuditDraft("SUPPLIER_CREDIT_APPLY", "PURCHASE_INVOICE", invoice.id.value, "allocation=${allocation.id};amount=${cmd.amount.rial}"))
        allocation.id
    }

    fun releaseAllocation(c: ReleaseCreditAllocation): CommandOutcome = bus.execute(ModuleId.PURCHASING, c) { cmd, ctx ->
        val allocation = purchases.allocation(cmd.allocationId) ?: throw DomainException(DomainError.NotFound("CREDIT_ALLOCATION"))
        ensure(allocation.scope == cmd.scope) { DomainError.InvalidInput("scope", "متعلق به این شعبه نیست.") }
        ensure(!allocation.released) { DomainError.InvalidState("CREDIT_ALLOCATION", "ALREADY_RELEASED") }
        ensure(cmd.reason.trim().length in 3..300) { DomainError.InvalidInput("reason", "دلیل الزامی است.") }
        // An advance set against the invoice goes back to 1403.
        allocation.journalId?.let { ledger.reverse(ctx, capability, emptySet(), it, cmd.date ?: allocation.date, cmd.reason) }
        purchases.saveAllocation(allocation.copy(released = true))
        ctx.audit(AuditDraft("SUPPLIER_CREDIT_RELEASE", "PURCHASE_INVOICE", allocation.invoiceId.value, "allocation=${allocation.id};reason=${cmd.reason.trim()}"))
        allocation.id
    }

    fun receiveRefund(c: ReceiveSupplierRefund): CommandOutcome = bus.execute(ModuleId.PURCHASING, c) { cmd, ctx ->
        val supplier = suppliers.byId(cmd.supplierId) ?: throw DomainException(DomainError.NotFound("SUPPLIER"))
        ensure(!cmd.amount.isZero) { DomainError.InvalidInput("amount", "مبلغ باید بیشتر از صفر باشد.") }
        ensure(cmd.amount <= unappliedCredit(supplier.id, cmd.scope)) { DomainError.InvalidState("SUPPLIER_CREDIT", "EXCEEDS_AVAILABLE") }
        val account = treasury.account(cmd.treasuryAccountId)
        val refundId = GlobalId.new()
        ctx.number(DocumentSeries.RECEIPT, cmd.date, refundId)
        val title = "استرداد اعتبار مرجوعی از ${supplier.name}"
        val cheque = cmd.cheque?.let { ChequeInstruction.New(it) }
        var bridge: GlobalId? = null
        // The credit sits as a debit in the branch's payables: the money received clears it.
        if (account.scope == cmd.scope) {
            treasury.settle(ctx, capability, account.id, Direction.RECEIPT, cmd.amount, cmd.date, REFUND, refundId, title,
                listOf(LineDraft(StandardAccounts.PAYABLE, credit = cmd.amount, memo = supplier.name, by = capability)), cheque)
        } else {
            treasury.settle(ctx, capability, account.id, Direction.RECEIPT, cmd.amount, cmd.date, REFUND, refundId, title,
                listOf(LineDraft(StandardAccounts.INTER_BRANCH, credit = cmd.amount, memo = "طلب شعبه", by = capability)), cheque)
            bridge = ledger.post(ctx, capability, JournalDraft(cmd.date, cmd.scope, REFUND, refundId, "$title (به حساب مرکزی)", listOf(
                LineDraft(StandardAccounts.INTER_BRANCH, debit = cmd.amount, memo = account.name, by = capability),
                LineDraft(StandardAccounts.PAYABLE, credit = cmd.amount, memo = supplier.name, by = capability),
            ))).id
        }
        purchases.saveRefund(SupplierRefund(refundId, supplier.id, cmd.scope, account.id, cmd.amount, cmd.date, bridge))
        ctx.audit(AuditDraft("SUPPLIER_REFUND", "SUPPLIER", supplier.id.value, "refund=$refundId;amount=${cmd.amount.rial}"))
        refundId
    }

    fun reverseRefund(c: ReverseSupplierRefund): CommandOutcome = bus.execute(ModuleId.PURCHASING, c) { cmd, ctx ->
        val refund = purchases.refund(cmd.refundId) ?: throw DomainException(DomainError.NotFound("SUPPLIER_REFUND"))
        ensure(refund.scope == cmd.scope) { DomainError.InvalidInput("scope", "متعلق به این شعبه نیست.") }
        ensure(!refund.reversed) { DomainError.InvalidState("SUPPLIER_REFUND", "ALREADY_REVERSED") }
        treasury.reverseDocument(ctx, capability, REFUND, refund.id, cmd.date, cmd.reason)
        ctx.number(DocumentSeries.REVERSAL, cmd.date, cmd.commandId, reverses = DocumentSeries.RECEIPT to refund.id)
        refund.bridgeJournalId?.let { ledger.reverse(ctx, capability, emptySet(), it, cmd.date, cmd.reason) }
        purchases.saveRefund(refund.copy(reversed = true))
        ctx.audit(AuditDraft("SUPPLIER_REFUND_REVERSE", "SUPPLIER", refund.supplierId.value, "refund=${refund.id};reason=${cmd.reason.trim()}"))
        refund.id
    }

    /**
     * Another posted invoice of the supplier with the same total, on the same day or under the same number written
     * differently («00125» and «۱۲۵»). Same total on different days is normal (daily bread) and is not flagged.
     */
    private fun possibleDuplicates(supplierId: GlobalId, number: String, total: Money, date: BusinessDate): List<Discrepancy> {
        val key = InvoiceMatching.numberKey(number)
        return purchases.invoicesOf(supplierId).filter {
            it.status == InvoiceStatus.POSTED && it.total == total &&
                (it.date == date || (key.isNotEmpty() && InvoiceMatching.numberKey(it.supplierInvoiceNo) == key))
        }.map { Discrepancy(Discrepancy.Kind.POSSIBLE_DUPLICATE, null, null, it.date.epochDay) }.take(1)
    }

    /**
     * Someone other than the recorder (the owner within the self-approval policy) looks at the differences and
     * accepts the invoice as it is; what is wrong in it is corrected with a return or a reversal instead.
     */
    fun reviewMatch(c: ReviewInvoiceMatch): CommandOutcome = bus.execute(ModuleId.PURCHASING, c) { cmd, ctx ->
        val invoice = requireInvoice(cmd.invoiceId, cmd.scope)
        ensure(invoice.needsMatchReview) { DomainError.InvalidState("PURCHASE_INVOICE", "NO_MATCH_REVIEW_NEEDED") }
        ensure(invoice.recordedBy != ctx.actor.userId || ctx.actor.role == ir.sabou.platform.Role.OWNER) { DomainError.InvalidState("PURCHASE_INVOICE", "RECORDER_CANNOT_APPROVE") }
        val note = cmd.note.trim()
        ensure(note.length in 3..300) { DomainError.InvalidInput("note", "توضیح بررسی مغایرت الزامی است.") }
        purchases.saveInvoice(invoice.copy(matchReview = MatchReview(ctx.actor.userId, ctx.actor.displayName, ctx.nowEpochMillis, note)))
        ctx.audit(AuditDraft("PURCHASE_MATCH_REVIEW", "PURCHASE_INVOICE", invoice.id.value, "discrepancies=${invoice.discrepancies.size};note=$note"))
        invoice.id
    }

    private fun pay(ctx: CommandContext, invoice: PurchaseInvoice, accountId: GlobalId, amount: Money, date: BusinessDate, cheque: ChequeInstruction?): SupplierPayment {
        ensure(!invoice.needsMatchReview) { DomainError.InvalidState("PURCHASE_INVOICE", "NEEDS_MATCH_REVIEW") }
        ensure(invoice.approved) { DomainError.InvalidState("PURCHASE_INVOICE", "NOT_APPROVED:${invoice.approvals.size}/${invoice.requiredApprovals}") }
        ensure(!amount.isZero) { DomainError.InvalidInput("amount", "مبلغ پرداخت باید بیشتر از صفر باشد.") }
        ensure(amount <= outstanding(invoice.id)) { DomainError.InvalidState("PURCHASE_INVOICE", "PAYMENT_EXCEEDS_OUTSTANDING") }
        ensure(date >= invoice.date) { DomainError.InvalidInput("date", "تاریخ پرداخت قبل از تاریخ فاکتور است.") }
        val account = treasury.account(accountId)
        val paymentId = GlobalId.new()
        ctx.number(DocumentSeries.PAYMENT, date, paymentId, scope = invoice.scope)
        val supplier = suppliers.byId(invoice.supplierId)!!
        var bridge: GlobalId? = null
        if (account.scope == invoice.scope) {
            treasury.settle(ctx, capability, account.id, Direction.PAYMENT, amount, date, PAYMENT, paymentId, "پرداخت به ${supplier.name}",
                listOf(LineDraft(StandardAccounts.PAYABLE, debit = amount, memo = supplier.name, by = capability)), cheque)
        } else {
            // Paying a branch's invoice from another scope's account (e.g. the organization bank):
            // each scope stays balanced through the inter-branch account.
            treasury.settle(ctx, capability, account.id, Direction.PAYMENT, amount, date, PAYMENT, paymentId, "پرداخت به ${supplier.name}",
                listOf(LineDraft(StandardAccounts.INTER_BRANCH, debit = amount, memo = "بدهی شعبه", by = capability)), cheque)
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

    /**
     * Journals for the lines that are not goods: one in the invoice's branch (expenses, other
     * branches' shares through the inter-branch account, held lines into 1302, against AP) and
     * one per other branch for its share of the expenses.
     */
    /** Quantities of [order] already invoiced (by posted invoices). */
    private fun receivedOf(order: PurchaseOrder): Map<GlobalId, Long> = order.invoiceIds.mapNotNull { purchases.invoice(it) }
        .filter { it.status == InvoiceStatus.POSTED }.flatMap { it.lines }.groupBy { it.itemId }.mapValues { (_, l) -> l.sumOfExact { it.quantity.micros } }

    /** Adds [delta] Rial (freight − discount) to [lines] in proportion to their value; the rounding rest goes to the largest. */
    private fun spread(lines: List<InvoiceLine>, delta: Long): List<InvoiceLine> {
        if (delta == 0L || lines.isEmpty()) return lines
        val total = lines.sumOfExact { it.value.rial }
        val sign = if (delta < 0) -1L else 1L
        val shares = lines.map { sign * (if (total == 0L) kotlin.math.abs(delta) / lines.size else Ratio.mulDiv(kotlin.math.abs(delta), it.value.rial, total, ir.sabou.kernel.Rounding.DOWN)) }.toMutableList()
        val largest = lines.indices.maxByOrNull { lines[it].value.rial }!!
        shares[largest] += delta - shares.sum()
        return lines.mapIndexed { i, l -> l.copy(value = Money.of(l.value.rial + shares[i])) }
    }

    private fun postOtherLines(
        ctx: CommandContext, scope: Scope.Branch, date: BusinessDate, invoiceId: GlobalId, title: String, supplier: String,
        accountLines: List<AccountLine>, reviewTotal: Money,
    ): List<GlobalId> {
        val accountsTotal = Money.sum(accountLines.map { it.amount })
        if (accountsTotal.isZero && reviewTotal.isZero) return emptyList()
        val byBranch = accountLines.groupBy { it.branch?.takeIf { b -> b != scope } }
        val here = byBranch[null].orEmpty().map { LineDraft(it.account, debit = it.amount, memo = it.memo, by = capability) } +
            byBranch.filterKeys { it != null }.map { (branch, l) -> LineDraft(StandardAccounts.INTER_BRANCH, debit = Money.sum(l.map { it.amount }), memo = "سهم شعبه دیگر", by = capability) } +
            (if (reviewTotal.isZero) emptyList() else listOf(LineDraft(StandardAccounts.PURCHASES_PENDING_REVIEW, debit = reviewTotal, memo = "در انتظار بررسی", by = capability))) +
            LineDraft(StandardAccounts.PAYABLE, credit = accountsTotal + reviewTotal, memo = supplier, by = capability)
        val ids = mutableListOf(ledger.post(ctx, capability, JournalDraft(date, scope, INVOICE, invoiceId, title, here)).id)
        byBranch.forEach { (branch, l) ->
            if (branch == null) return@forEach
            ids += ledger.post(ctx, capability, JournalDraft(date, branch, INVOICE, invoiceId, "$title (سهم این شعبه)",
                l.map { LineDraft(it.account, debit = it.amount, memo = it.memo, by = capability) } +
                    LineDraft(StandardAccounts.INTER_BRANCH, credit = Money.sum(l.map { it.amount }), memo = "بدهی به شعبه خریدار", by = capability),
            )).id
        }
        return ids
    }

    /** Open (non-control) expense accounts only: control accounts belong to their modules. */
    private fun requireExpenseAccount(code: AccountCode) {
        val account = ledger.account(code) ?: throw DomainException(DomainError.NotFound("ACCOUNT:$code"))
        ensure(account.isActive) { DomainError.InvalidState("ACCOUNT:$code", "INACTIVE") }
        ensure(account.type == AccountType.EXPENSE && !account.isControl) { DomainError.InvalidInput("account", "فقط حساب‌های هزینه‌ی آزاد (مثل اجاره، آب و برق، سایر هزینه‌ها) مجاز است.") }
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
        const val CORRECTION = "PURCHASE_CORRECTION"
        const val REVIEW = "PURCHASE_REVIEW"
        const val OPENING = "PURCHASE_OPENING_BALANCE"
        const val ADVANCE = "SUPPLIER_ADVANCE"
        const val ADVANCE_APPLY = "SUPPLIER_ADVANCE_APPLY"
        const val REFUND = "SUPPLIER_REFUND"
    }
}
