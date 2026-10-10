package ir.sabou.payroll

import ir.sabou.platform.NoDocument

import ir.sabou.kernel.DomainError
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Scope
import ir.sabou.kernel.ensure
import ir.sabou.platform.AuditDraft
import ir.sabou.platform.Command
import ir.sabou.platform.CommandBus
import ir.sabou.platform.CommandOutcome
import ir.sabou.platform.ModuleId
import ir.sabou.platform.Permission

/** Stored yearly payroll parameters. Append-only: a version is never edited after it is defined. */
interface PolicyStore {
    fun all(): List<StatutoryPolicy>
    fun save(policy: StatutoryPolicy)
}

@NoDocument
data class DefinePayrollPolicy(override val commandId: GlobalId, val policy: StatutoryPolicy) : Command {
    override val requiredPermission = Permission.PAYROLL_APPROVE
    override val scope: Scope = Scope.Organization
    override fun fingerprint() = policy.toString()
}

/**
 * The owner enters each year's official values (after professional review). Periods may not
 * overlap, so exactly one policy applies to any payroll period or none does (fail closed).
 */
class PolicyAdministration(private val bus: CommandBus, private val store: PolicyStore) {
    val registry = StatutoryPolicyRegistry { store.all() }

    fun policies(): List<StatutoryPolicy> = store.all().sortedBy { it.from }

    fun define(c: DefinePayrollPolicy): CommandOutcome = bus.execute(ModuleId.PAYROLL, c) { cmd, ctx ->
        val p = cmd.policy
        ensure(p.version.isNotBlank() && p.version.length <= 40) { DomainError.InvalidInput("version", "نسخه الزامی است.") }
        ensure(p.to >= p.from) { DomainError.InvalidInput("to", "پایان دوره نمی‌تواند قبل از شروع باشد.") }
        val existing = store.all()
        ensure(existing.none { it.version == p.version }) { DomainError.InvalidState("PAYROLL_POLICY", "DUPLICATE_VERSION") }
        ensure(existing.none { it.from <= p.to && it.to >= p.from }) { DomainError.InvalidState("PAYROLL_POLICY", "OVERLAPPING_PERIOD") }
        store.save(p)
        ctx.audit(AuditDraft("PAYROLL_POLICY_DEFINE", "PAYROLL_POLICY", p.version, "from=${p.from.epochDay};to=${p.to.epochDay}"))
        GlobalId.new()
    }
}
