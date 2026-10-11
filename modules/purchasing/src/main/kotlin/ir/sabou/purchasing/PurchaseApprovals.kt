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

/** One approval step on an invoice. [reason] is required when the owner approves an invoice they recorded. */
@NoDocument
data class ApproveInvoice(override val commandId: GlobalId, override val scope: Scope.Branch, val invoiceId: GlobalId, val reason: String? = null) : Command {
    override val requiredPermission = Permission.PURCHASE_APPROVE
    override fun fingerprint() = "$scope|$invoiceId|${reason?.trim()}"
}

/** The owner's self-approval policy (owner only). */
@NoDocument
data class SetSelfApprovalPolicy(override val commandId: GlobalId, val allowed: Boolean, val maxAmount: Money?) : Command {
    override val requiredPermission = Permission.APPROVAL_RULES
    override val scope: Scope = Scope.Organization
    override fun fingerprint() = "$allowed|${maxAmount?.rial}"
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
        val self = invoice.recordedBy == me
        ensure(!self || ctx.actor.role == Role.OWNER) { DomainError.InvalidState("PURCHASE_INVOICE", "RECORDER_CANNOT_APPROVE") }
        val reason = cmd.reason?.trim()?.takeIf { self }
        if (self) {
            // The owner approving their own invoice: only as the policy allows, and always saying why.
            val policy = rules.selfApproval()
            ensure(policy.allowed) { DomainError.InvalidState("PURCHASE_INVOICE", "SELF_APPROVAL_DISABLED") }
            ensure(policy.maxAmount == null || invoice.total <= policy.maxAmount) { DomainError.InvalidState("PURCHASE_INVOICE", "SELF_APPROVAL_OVER_LIMIT") }
            ensure((reason?.length ?: 0) in 3..300) { DomainError.InvalidInput("reason", "تأیید فاکتوری که خودتان ثبت کرده‌اید دلیل لازم دارد.") }
        }
        val approval = Approval(me, ctx.actor.displayName, ctx.nowEpochMillis, self, reason)
        purchases.saveInvoice(invoice.copy(approvals = invoice.approvals + approval))
        ctx.audit(AuditDraft("PURCHASE_INVOICE_APPROVE", "PURCHASE_INVOICE", invoice.id.value,
            "step=${invoice.approvals.size + 1}/${invoice.requiredApprovals}" + if (self) ";self;reason=$reason" else ""))
        invoice.id
    }

    fun setSelfApproval(c: SetSelfApprovalPolicy): CommandOutcome = bus.execute(ModuleId.PURCHASING, c) { cmd, ctx ->
        ensure(cmd.maxAmount?.isZero != true) { DomainError.InvalidInput("amount", "سقف باید بیشتر از صفر باشد؛ برای منع کامل، تأیید خودی را خاموش کنید.") }
        val policy = SelfApprovalPolicy(cmd.allowed, cmd.maxAmount)
        rules.saveSelfApproval(policy)
        ctx.audit(AuditDraft("SELF_APPROVAL_POLICY", "APPROVAL_POLICY", "self", "allowed=${policy.allowed};max=${policy.maxAmount?.rial}"))
        GlobalId.new()
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
