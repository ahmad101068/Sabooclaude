package ir.sabou.purchasing

import ir.sabou.platform.NoDocument

import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Scope
import ir.sabou.kernel.ensure
import ir.sabou.platform.AuditDraft
import ir.sabou.platform.Command
import ir.sabou.platform.CommandBus
import ir.sabou.platform.CommandOutcome
import ir.sabou.platform.ModuleId
import ir.sabou.platform.Permission
import ir.sabou.platform.Role

/** A new or changed approval rule (owner only). Rules apply to invoices recorded after the change. */
@NoDocument
data class SaveApprovalRule(
    override val commandId: GlobalId,
    /** null = a new rule. */
    val ruleId: GlobalId?,
    val name: String,
    val branch: Scope.Branch?,
    val supplierId: GlobalId?,
    val category: InvoiceCategory?,
    val minAmount: Money,
    val steps: Int,
    val isActive: Boolean = true,
) : Command {
    override val requiredPermission = Permission.APPROVAL_RULES
    override val scope: Scope = Scope.Organization
    override fun fingerprint() = "$ruleId|$name|$branch|$supplierId|$category|${minAmount.rial}|$steps|$isActive"
}

/** One approval step on an invoice. */
@NoDocument
data class ApproveInvoice(override val commandId: GlobalId, override val scope: Scope.Branch, val invoiceId: GlobalId) : Command {
    override val requiredPermission = Permission.PURCHASE_APPROVE
    override fun fingerprint() = "$scope|$invoiceId"
}

/** Takes every approval back (only while nothing has been paid). */
@NoDocument
data class UnapproveInvoice(override val commandId: GlobalId, override val scope: Scope.Branch, val invoiceId: GlobalId, val reason: String) : Command {
    override val requiredPermission = Permission.PURCHASE_UNAPPROVE
    override fun fingerprint() = "$scope|$invoiceId|$reason"
}

class ApprovalOperations(
    private val bus: CommandBus,
    private val rules: ApprovalRuleStore,
    private val purchases: PurchaseStore,
) {
    fun saveRule(c: SaveApprovalRule): CommandOutcome = bus.execute(ModuleId.PURCHASING, c) { cmd, ctx ->
        val name = cmd.name.trim()
        ensure(name.length in 2..80) { DomainError.InvalidInput("name", "نام قانون الزامی است.") }
        ensure(cmd.steps in 1..3) { DomainError.InvalidInput("steps", "تعداد مراحل تأیید بین ۱ تا ۳ است.") }
        val existing = cmd.ruleId?.let { rules.byId(it) ?: throw DomainException(DomainError.NotFound("APPROVAL_RULE")) }
        val rule = ApprovalRule(existing?.id ?: GlobalId.new(), name, cmd.branch, cmd.supplierId, cmd.category, cmd.minAmount, cmd.steps, cmd.isActive)
        rules.save(rule)
        ctx.audit(AuditDraft(if (existing == null) "APPROVAL_RULE_CREATE" else "APPROVAL_RULE_UPDATE", "APPROVAL_RULE", rule.id.value,
            "branch=${rule.branch};supplier=${rule.supplierId};category=${rule.category};min=${rule.minAmount.rial};steps=${rule.steps};active=${rule.isActive}"))
        rule.id
    }

    fun approve(c: ApproveInvoice): CommandOutcome = bus.execute(ModuleId.PURCHASING, c) { cmd, ctx ->
        val invoice = invoice(cmd.invoiceId, cmd.scope)
        ensure(invoice.approvals.size < invoice.requiredApprovals) { DomainError.InvalidState("PURCHASE_INVOICE", "ALREADY_APPROVED") }
        val me = ctx.actor.userId
        // Separation of duties: each step by someone else, and not by whoever recorded it (the owner excepted).
        ensure(invoice.approvals.none { it.userId == me }) { DomainError.InvalidState("PURCHASE_INVOICE", "SAME_APPROVER") }
        ensure(invoice.recordedBy != me || ctx.actor.role == Role.OWNER) { DomainError.InvalidState("PURCHASE_INVOICE", "RECORDER_CANNOT_APPROVE") }
        val approval = Approval(me, ctx.actor.displayName, ctx.nowEpochMillis)
        purchases.saveInvoice(invoice.copy(approvals = invoice.approvals + approval))
        ctx.audit(AuditDraft("PURCHASE_INVOICE_APPROVE", "PURCHASE_INVOICE", invoice.id.value, "step=${invoice.approvals.size + 1}/${invoice.requiredApprovals}"))
        invoice.id
    }

    fun unapprove(c: UnapproveInvoice): CommandOutcome = bus.execute(ModuleId.PURCHASING, c) { cmd, ctx ->
        val invoice = invoice(cmd.invoiceId, cmd.scope)
        ensure(invoice.approvals.isNotEmpty()) { DomainError.InvalidState("PURCHASE_INVOICE", "NOT_APPROVED") }
        ensure(cmd.reason.trim().length in 3..300) { DomainError.InvalidInput("reason", "دلیل الزامی است.") }
        ensure(purchases.payments(invoice.id).none { !it.reversed }) { DomainError.InvalidState("PURCHASE_INVOICE", "HAS_ACTIVE_PAYMENTS") }
        purchases.saveInvoice(invoice.copy(approvals = emptyList()))
        ctx.audit(AuditDraft("PURCHASE_INVOICE_UNAPPROVE", "PURCHASE_INVOICE", invoice.id.value, cmd.reason.trim()))
        invoice.id
    }

    private fun invoice(id: GlobalId, scope: Scope.Branch): PurchaseInvoice {
        val invoice = purchases.invoice(id) ?: throw DomainException(DomainError.NotFound("PURCHASE_INVOICE"))
        ensure(invoice.scope == scope) { DomainError.InvalidInput("scope", "فاکتور متعلق به این شعبه نیست.") }
        ensure(invoice.status == InvoiceStatus.POSTED) { DomainError.InvalidState("PURCHASE_INVOICE", invoice.status.name) }
        return invoice
    }
}
