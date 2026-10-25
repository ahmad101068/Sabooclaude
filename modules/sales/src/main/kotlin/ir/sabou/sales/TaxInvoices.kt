package ir.sabou.sales

import ir.sabou.kernel.sumOfExact
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Quantity
import ir.sabou.kernel.Ratio
import ir.sabou.kernel.Scope
import ir.sabou.kernel.ensure
import ir.sabou.platform.AuditDraft
import ir.sabou.platform.Command
import ir.sabou.platform.CommandBus
import ir.sabou.platform.CommandOutcome
import ir.sabou.platform.ModuleId
import ir.sabou.platform.NoDocument
import ir.sabou.platform.Permission

/**
 * Electronic invoices for the tax system (سامانه مودیان, ADR-0047). Sabou issues the invoices **with the buyer's
 * details** (type 1): a posted sale settled — wholly or partly — on account to a customer with a tax ID. Consumer
 * bills stay with the point of sale (ADR-0037). An invoice is fixed when issued (its tax ID, serial, lines and
 * amounts); sending happens outside the books and its outcome is recorded here.
 */
enum class TaxInvoiceKind { ORIGINAL, CANCELLATION }

enum class TaxInvoiceStatus {
    /** Issued, not yet accepted for processing. */
    READY,
    /** The tax system received it (has a reference number); the result is not known yet. */
    SENT,
    ACCEPTED,
    /** Refused by the tax system (see messages); the sale may be issued again with a new serial. */
    REJECTED,
    /** An accepted original whose cancellation was accepted. */
    CANCELLED,
}

data class TaxInvoiceLine(
    /** Product/service ID (13 digits) and the tax system's unit code. */
    val productId: String,
    val unitCode: String,
    val description: String,
    val quantity: Quantity,
    val unitPrice: Money,
    val gross: Money,
    val discount: Money,
    val vatBp: Long,
    val vat: Money,
) {
    val net: Money get() = gross - discount
    val total: Money get() = net + vat
}

data class TaxInvoice(
    val id: GlobalId,
    val scope: Scope.Branch,
    val saleId: GlobalId,
    val customerId: GlobalId,
    val kind: TaxInvoiceKind,
    val serial: Long,
    val taxId: String,
    /** For a cancellation: the tax ID of the invoice it cancels. */
    val refTaxId: String?,
    val date: BusinessDate,
    /** Issue time (ms since 1970, UTC), as the tax system's `indatim`. */
    val issuedAt: Long,
    val sellerTaxId: String,
    val memoryId: String,
    /** 1 person, 2 legal entity (the tax system's buyer types). */
    val buyerType: Int,
    val buyerTaxId: String,
    val buyerPostalCode: String,
    val lines: List<TaxInvoiceLine>,
    /** Paid at the sale (cash, card, cheque) and on account. */
    val cash: Money,
    val credit: Money,
    val status: TaxInvoiceStatus = TaxInvoiceStatus.READY,
    val referenceNumber: String? = null,
    val uid: String? = null,
    /** The tax system's errors and warnings, as given. */
    val messages: List<String> = emptyList(),
    val updatedAt: Long? = null,
) {
    val gross: Money get() = Money.sum(lines.map { it.gross })
    val discount: Money get() = Money.sum(lines.map { it.discount })
    val net: Money get() = Money.sum(lines.map { it.net })
    val vat: Money get() = Money.sum(lines.map { it.vat })
    val total: Money get() = Money.sum(lines.map { it.total })
    /** 1 cash, 2 on account, 3 both (the tax system's `setm`). */
    val settlementMethod: Int get() = when { credit.isZero -> 1; cash.isZero -> 2; else -> 3 }
    /** Hex serial as the tax system's internal invoice number (`inno`). */
    val internalNumber: String get() = serial.toString(16).padStart(10, '0').uppercase()
}

interface TaxInvoiceStore {
    fun byId(id: GlobalId): TaxInvoice?
    fun all(): List<TaxInvoice>
    fun save(invoice: TaxInvoice)
}

/** Issues the invoice of a sale; nothing is sent. */
@NoDocument
data class IssueTaxInvoice(override val commandId: GlobalId, override val scope: Scope.Branch, val saleId: GlobalId, val issuedAt: Long) : Command {
    override val requiredPermission = Permission.TAX_SETTINGS
    override fun fingerprint() = "$scope|$saleId|$issuedAt"
}

/** Issues the cancellation (ابطال) of an accepted invoice. */
@NoDocument
data class CancelTaxInvoice(override val commandId: GlobalId, override val scope: Scope.Branch, val invoiceId: GlobalId, val issuedAt: Long) : Command {
    override val requiredPermission = Permission.TAX_SETTINGS
    override fun fingerprint() = "$scope|$invoiceId|$issuedAt"
}

/** What the tax system said about an invoice: received (with its reference), accepted or refused. */
@NoDocument
data class RecordTaxInvoiceResult(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val invoiceId: GlobalId,
    val status: TaxInvoiceStatus,
    val referenceNumber: String?,
    val uid: String?,
    val messages: List<String>,
    val at: Long,
) : Command {
    override val requiredPermission = Permission.TAX_SETTINGS
    override fun fingerprint() = "$scope|$invoiceId|$status|$referenceNumber|$uid|${messages.joinToString("\u0001")}|$at"
}

class TaxInvoiceOperations(
    private val bus: CommandBus,
    private val tax: TaxStore,
    private val invoices: TaxInvoiceStore,
    private val sales: SalesStore,
    private val customers: CustomerStore,
    private val menuItemName: (GlobalId) -> String?,
) {
    fun issue(c: IssueTaxInvoice): CommandOutcome = bus.execute(ModuleId.SALES, c) { cmd, ctx ->
        val profile = tax.profile()
        ensure(profile != null && profile.memoryId.isNotEmpty()) { DomainError.InvalidState("TAX_PROFILE", "MISSING_MEMORY_ID") }
        profile!!
        val sale = sales.sale(cmd.saleId) ?: throw DomainException(DomainError.NotFound("SALE"))
        ensure(sale.scope == cmd.scope) { DomainError.InvalidInput("scope", "فروش متعلق به این شعبه نیست.") }
        ensure(sale.status == SaleStatus.POSTED) { DomainError.InvalidState("SALE", sale.status.name) }
        ensure(invoices.all().none { it.saleId == sale.id && it.kind == TaxInvoiceKind.ORIGINAL && it.status != TaxInvoiceStatus.REJECTED }) {
            DomainError.InvalidState("TAX_INVOICE", "ALREADY_ISSUED")
        }
        val credit = sale.settlements.filterIsInstance<Settlement.Credit>()
        ensure(credit.map { it.customerId }.distinct().size == 1) { DomainError.InvalidState("SALE", "NOT_ONE_CREDIT_CUSTOMER") }
        val customer = customers.byId(credit.first().customerId) ?: throw DomainException(DomainError.NotFound("CUSTOMER"))
        ensure(customer.taxId.isNotEmpty()) { DomainError.InvalidState("CUSTOMER", "NO_TAX_ID") }
        ensure(sale.serviceCharge.isZero) { DomainError.InvalidState("SALE", "SERVICE_CHARGE") }
        val vatBp = profile.vatOn(sale.date) ?: throw DomainException(DomainError.InvalidState("TAX_PROFILE", "NO_VAT_RATE"))
        val codes = tax.codes().associateBy { it.menuItemId }
        sale.lines.forEach { l ->
            val code = codes[l.menuItemId]
            ensure(code != null && code.productId.isNotEmpty()) { DomainError.InvalidState("MENU_ITEM", "NO_PRODUCT_ID:${menuItemName(l.menuItemId).orEmpty()}") }
            ensure(code!!.unitCode.isNotEmpty()) { DomainError.InvalidState("MENU_ITEM", "NO_UNIT_CODE:${menuItemName(l.menuItemId).orEmpty()}") }
        }
        val discounts = TaxInvoiceMath.spread(sale.discount.rial, sale.lines.map { it.gross.rial })
        val lines = sale.lines.mapIndexed { i, l ->
            val code = codes.getValue(l.menuItemId)
            val discount = Money.of(discounts[i])
            val net = l.gross - discount
            TaxInvoiceLine(code.productId, code.unitCode, menuItemName(l.menuItemId).orEmpty(), l.portions, l.unitPrice, l.gross, discount, vatBp,
                Money.of(Ratio.mulDiv(net.rial, vatBp, 10_000L)))
        }
        // The VAT recorded on the sale must be the VAT of its lines at the legal rate (a rial per line for rounding).
        val vat = lines.sumOfExact { it.vat.rial }
        ensure(kotlin.math.abs(vat - sale.tax.rial) <= lines.size) { DomainError.InvalidState("SALE", "VAT_MISMATCH:$vat") }
        val creditTotal = Money.sum(credit.map { it.amount })
        val total = lines.sumOfExact { it.total.rial }
        val serial = nextSerial()
        val invoice = TaxInvoice(
            GlobalId.new(), sale.scope, sale.id, customer.id, TaxInvoiceKind.ORIGINAL, serial,
            TaxIds.taxId(profile.memoryId, sale.date, serial), null, sale.date, cmd.issuedAt, profile.sellerTaxId, profile.memoryId,
            buyerType(customer), customer.taxId, customer.postalCode, lines,
            Money.of(maxOf(0L, total - creditTotal.rial)), Money.of(minOf(total, creditTotal.rial)),
        )
        invoices.save(invoice)
        ctx.audit(AuditDraft("TAX_INVOICE_ISSUE", "TAX_INVOICE", invoice.id.value, "sale=${sale.id.value};taxId=${invoice.taxId};total=$total"))
        invoice.id
    }

    fun cancel(c: CancelTaxInvoice): CommandOutcome = bus.execute(ModuleId.SALES, c) { cmd, ctx ->
        val original = invoices.byId(cmd.invoiceId) ?: throw DomainException(DomainError.NotFound("TAX_INVOICE"))
        ensure(original.scope == cmd.scope) { DomainError.InvalidInput("scope", "صورتحساب متعلق به این شعبه نیست.") }
        ensure(original.kind == TaxInvoiceKind.ORIGINAL && original.status == TaxInvoiceStatus.ACCEPTED) { DomainError.InvalidState("TAX_INVOICE", original.status.name) }
        ensure(invoices.all().none { it.refTaxId == original.taxId && it.status != TaxInvoiceStatus.REJECTED }) { DomainError.InvalidState("TAX_INVOICE", "CANCELLATION_EXISTS") }
        val serial = nextSerial()
        val cancellation = original.copy(
            id = GlobalId.new(), kind = TaxInvoiceKind.CANCELLATION, serial = serial,
            taxId = TaxIds.taxId(original.memoryId, original.date, serial), refTaxId = original.taxId, issuedAt = cmd.issuedAt,
            status = TaxInvoiceStatus.READY, referenceNumber = null, uid = null, messages = emptyList(), updatedAt = null,
        )
        invoices.save(cancellation)
        ctx.audit(AuditDraft("TAX_INVOICE_CANCEL", "TAX_INVOICE", cancellation.id.value, "of=${original.taxId};taxId=${cancellation.taxId}"))
        cancellation.id
    }

    fun record(c: RecordTaxInvoiceResult): CommandOutcome = bus.execute(ModuleId.SALES, c) { cmd, ctx ->
        val invoice = invoices.byId(cmd.invoiceId) ?: throw DomainException(DomainError.NotFound("TAX_INVOICE"))
        ensure(invoice.scope == cmd.scope) { DomainError.InvalidInput("scope", "صورتحساب متعلق به این شعبه نیست.") }
        val allowed = when (invoice.status) {
            TaxInvoiceStatus.READY -> setOf(TaxInvoiceStatus.SENT, TaxInvoiceStatus.ACCEPTED, TaxInvoiceStatus.REJECTED)
            TaxInvoiceStatus.SENT -> setOf(TaxInvoiceStatus.SENT, TaxInvoiceStatus.ACCEPTED, TaxInvoiceStatus.REJECTED)
            else -> emptySet()
        }
        ensure(cmd.status in allowed) { DomainError.InvalidState("TAX_INVOICE", invoice.status.name) }
        ensure(cmd.status != TaxInvoiceStatus.SENT || !cmd.referenceNumber.isNullOrBlank()) { DomainError.InvalidInput("referenceNumber", "شماره‌ی پیگیری لازم است.") }
        val updated = invoice.copy(
            status = cmd.status, referenceNumber = cmd.referenceNumber ?: invoice.referenceNumber, uid = cmd.uid ?: invoice.uid,
            messages = cmd.messages.map { it.take(500) }.take(30), updatedAt = cmd.at,
        )
        invoices.save(updated)
        // An accepted cancellation cancels its original.
        if (invoice.kind == TaxInvoiceKind.CANCELLATION && cmd.status == TaxInvoiceStatus.ACCEPTED) {
            invoices.all().firstOrNull { it.taxId == invoice.refTaxId }?.let { invoices.save(it.copy(status = TaxInvoiceStatus.CANCELLED, updatedAt = cmd.at)) }
        }
        ctx.audit(AuditDraft("TAX_INVOICE_RESULT", "TAX_INVOICE", invoice.id.value, "status=${cmd.status};ref=${cmd.referenceNumber.orEmpty()}"))
        invoice.id
    }

    private fun nextSerial(): Long = (invoices.all().maxOfOrNull { it.serial } ?: 0L) + 1

    private fun buyerType(customer: Customer): Int = when {
        customer.taxId.length == 11 -> 2
        customer.taxId.length == 10 -> 1
        customer.type == CustomerType.COMPANY -> 2
        else -> 1
    }
}

object TaxInvoiceMath {
    /** [total] spread over [weights] in proportion, largest remainders first; the parts add up exactly. */
    fun spread(total: Long, weights: List<Long>): List<Long> {
        val sum = weights.sum()
        if (total == 0L || sum == 0L) return weights.map { 0L }
        val base = weights.map { Ratio.mulDiv(total, it, sum, ir.sabou.kernel.Rounding.DOWN) }
        var left = total - base.sum()
        val order = weights.indices.sortedByDescending { i -> (java.math.BigInteger.valueOf(total) * java.math.BigInteger.valueOf(weights[i])).mod(java.math.BigInteger.valueOf(sum)) }
        val out = base.toMutableList()
        for (i in order) { if (left == 0L) break; out[i] += 1; left-- }
        return out
    }
}
