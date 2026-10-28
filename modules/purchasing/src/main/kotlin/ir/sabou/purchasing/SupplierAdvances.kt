package ir.sabou.purchasing

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Scope
import ir.sabou.platform.Command
import ir.sabou.platform.DocumentSeries
import ir.sabou.platform.IssuesDocument
import ir.sabou.platform.Permission
import ir.sabou.treasury.ChequeDetails

/**
 * Money paid to a supplier before its invoice (پیش‌پرداخت), held in 1403 until set against invoices (ADR-0045).
 * [bridgeJournalId]: the branch side when it was paid from another scope's account.
 */
data class SupplierAdvance(
    val id: GlobalId,
    val supplierId: GlobalId,
    val scope: Scope.Branch,
    val treasuryAccountId: GlobalId,
    val amount: Money,
    val date: BusinessDate,
    val note: String,
    val bridgeJournalId: GlobalId?,
    val reversed: Boolean = false,
)

interface SupplierAdvanceStore {
    fun byId(id: GlobalId): SupplierAdvance?
    fun of(supplierId: GlobalId): List<SupplierAdvance>
    fun save(advance: SupplierAdvance)
}

@IssuesDocument(DocumentSeries.PAYMENT)
data class PaySupplierAdvance(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val supplierId: GlobalId,
    val treasuryAccountId: GlobalId,
    val amount: Money,
    val date: BusinessDate,
    val note: String,
    val cheque: ChequeDetails? = null,
) : Command {
    override val requiredPermission get() = Permission.PURCHASE_PAY
    override fun fingerprint() = "$scope|$supplierId|$treasuryAccountId|${amount.rial}|${date.epochDay}|$note|${cheque?.fingerprint()}"
}

/** Sets (part of) an advance against an invoice of the same supplier and branch. */
@IssuesDocument(DocumentSeries.CREDIT_SETTLEMENT)
data class ApplySupplierAdvance(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val advanceId: GlobalId,
    val invoiceId: GlobalId,
    val amount: Money,
    val date: BusinessDate,
) : Command {
    override val requiredPermission get() = Permission.PURCHASE_PAY
    override fun fingerprint() = "$scope|$advanceId|$invoiceId|${amount.rial}|${date.epochDay}"
}

/** Takes back an advance paid by mistake (none of it set against an invoice). */
@IssuesDocument(DocumentSeries.REVERSAL)
data class ReverseSupplierAdvance(override val commandId: GlobalId, override val scope: Scope.Branch, val advanceId: GlobalId, val date: BusinessDate, val reason: String) : Command {
    override val requiredPermission get() = Permission.PURCHASE_REVERSE
    override fun fingerprint() = "$scope|$advanceId|${date.epochDay}|$reason"
}

/**
 * Pays several invoices of one supplier at once: [amount] goes to its approved open invoices in the branch, oldest due
 * first; each invoice gets its own payment document.
 */
@IssuesDocument(DocumentSeries.PAYMENT)
data class PaySupplier(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val supplierId: GlobalId,
    val treasuryAccountId: GlobalId,
    val amount: Money,
    val date: BusinessDate,
) : Command {
    override val requiredPermission get() = Permission.PURCHASE_PAY
    override fun fingerprint() = "$scope|$supplierId|$treasuryAccountId|${amount.rial}|${date.epochDay}"
}
