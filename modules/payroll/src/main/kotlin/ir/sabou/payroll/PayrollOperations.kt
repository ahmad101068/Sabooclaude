package ir.sabou.payroll

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

data class RegisterEmployee(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val name: String,
    val nationalId: String,
    val monthlySalary: Money,
) : Command {
    override val requiredPermission = Permission.PERSONNEL_MANAGE
    override fun fingerprint() = "$scope|$name|$nationalId|${monthlySalary.rial}"
}

data class RecordAttendance(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val employeeId: GlobalId,
    val date: BusinessDate,
    val workedMinutes: Int,
    val overtimeMinutes: Int,
    val absentMinutes: Int,
) : Command {
    override val requiredPermission = Permission.ATTENDANCE_RECORD
    override fun fingerprint() = "$scope|$employeeId|${date.epochDay}|$workedMinutes|$overtimeMinutes|$absentMinutes"
}

data class CalculatePayroll(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val from: BusinessDate,
    val to: BusinessDate,
) : Command {
    override val requiredPermission = Permission.PAYROLL_CALCULATE
    override fun fingerprint() = "$scope|${from.epochDay}|${to.epochDay}"
}

data class ApprovePayroll(override val commandId: GlobalId, override val scope: Scope.Branch, val runId: GlobalId) : Command {
    override val requiredPermission = Permission.PAYROLL_APPROVE
    override fun fingerprint() = "$scope|$runId"
}

data class ReversePayroll(override val commandId: GlobalId, override val scope: Scope.Branch, val runId: GlobalId, val date: BusinessDate, val reason: String) : Command {
    override val requiredPermission = Permission.PAYROLL_APPROVE
    override fun fingerprint() = "$scope|$runId|${date.epochDay}|$reason"
}

data class PaySalary(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val runId: GlobalId,
    val employeeId: GlobalId,
    val treasuryAccountId: GlobalId,
    val amount: Money,
    val date: BusinessDate,
) : Command {
    override val requiredPermission = Permission.PAYROLL_PAY
    override fun fingerprint() = "$scope|$runId|$employeeId|$treasuryAccountId|${amount.rial}|${date.epochDay}"
}

data class ReverseSalaryPayment(override val commandId: GlobalId, override val scope: Scope.Branch, val paymentId: GlobalId, val date: BusinessDate, val reason: String) : Command {
    override val requiredPermission = Permission.PAYROLL_PAY
    override fun fingerprint() = "$scope|$paymentId|${date.epochDay}|$reason"
}

/** Pays the insurance organisation or the tax office out of the accrued liability. */
data class RemitLiability(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val kind: LiabilityKind,
    val treasuryAccountId: GlobalId,
    val amount: Money,
    val date: BusinessDate,
) : Command {
    override val requiredPermission = Permission.PAYROLL_PAY
    override fun fingerprint() = "$scope|$kind|$treasuryAccountId|${amount.rial}|${date.epochDay}"
}

class PayrollOperations(
    private val bus: CommandBus,
    private val ledger: Ledger,
    private val capability: PostingCapability,
    private val treasury: TreasuryGateway,
    private val personnel: PersonnelStore,
    private val payroll: PayrollStore,
    private val policies: StatutoryPolicyRegistry,
) {
    init {
        require(capability.module == ModuleId.PAYROLL)
    }

    fun unpaidNet(runId: GlobalId, employeeId: GlobalId): Money {
        val run = payroll.run(runId) ?: throw DomainException(DomainError.NotFound("PAYROLL_RUN"))
        if (run.status != RunStatus.APPROVED) return Money.ZERO
        val net = run.payslips.firstOrNull { it.employeeId == employeeId }?.net ?: return Money.ZERO
        return net - Money.sum(payroll.payments(runId).filter { it.employeeId == employeeId && !it.reversed }.map { it.amount })
    }

    /** Accrued but not yet remitted insurance or tax for a branch. */
    fun outstandingLiability(scope: Scope.Branch, kind: LiabilityKind): Money {
        val accrued = Money.sum(payroll.runs(scope).filter { it.status == RunStatus.APPROVED }.flatMap { it.payslips }.map {
            when (kind) {
                LiabilityKind.INSURANCE -> it.employeeInsurance + it.employerInsurance + it.unemploymentInsurance
                LiabilityKind.INCOME_TAX -> it.incomeTax
            }
        })
        return accrued - Money.sum(payroll.remittances(scope, kind).map { it.amount })
    }

    fun registerEmployee(c: RegisterEmployee): CommandOutcome = bus.execute(ModuleId.PAYROLL, c) { cmd, ctx ->
        val name = cmd.name.trim()
        ensure(name.length in 2..120) { DomainError.InvalidInput("name", "نام کارمند الزامی است.") }
        ensure(NationalId.isValid(cmd.nationalId)) { DomainError.InvalidInput("nationalId", "کد ملی معتبر نیست.") }
        val nationalId = NationalId.normalize(cmd.nationalId)
        ensure(personnel.employees(cmd.scope).none { NationalId.normalize(it.nationalId) == nationalId }) { DomainError.InvalidState("EMPLOYEE", "DUPLICATE_NATIONAL_ID") }
        val employee = Employee(GlobalId.new(), name, nationalId, cmd.scope, cmd.monthlySalary)
        personnel.saveEmployee(employee)
        ctx.audit(AuditDraft("EMPLOYEE_REGISTER", "EMPLOYEE", employee.id.value, name))
        employee.id
    }

    fun recordAttendance(c: RecordAttendance): CommandOutcome = bus.execute(ModuleId.PAYROLL, c) { cmd, ctx ->
        val employee = requireEmployee(cmd.employeeId, cmd.scope)
        ensure(listOf(cmd.workedMinutes, cmd.overtimeMinutes, cmd.absentMinutes).all { it in 0..1_440 } &&
            cmd.workedMinutes + cmd.overtimeMinutes <= 1_440) { DomainError.InvalidInput("minutes", "زمان حضور معتبر نیست.") }
        ensure(payroll.runs(cmd.scope).none { it.status == RunStatus.APPROVED && cmd.date >= it.from && cmd.date <= it.to }) {
            DomainError.InvalidState("ATTENDANCE", "PERIOD_APPROVED")
        }
        val existing = personnel.attendanceOn(employee.id, cmd.date)
        val record = AttendanceRecord(existing?.id ?: GlobalId.new(), employee.id, cmd.date, cmd.workedMinutes, cmd.overtimeMinutes, cmd.absentMinutes)
        personnel.saveAttendance(record)
        ctx.audit(AuditDraft(if (existing == null) "ATTENDANCE_RECORD" else "ATTENDANCE_CORRECT", "EMPLOYEE", employee.id.value,
            "day=${cmd.date.epochDay};w=${cmd.workedMinutes};ot=${cmd.overtimeMinutes};abs=${cmd.absentMinutes}"))
        record.id
    }

    /** Creates (or recalculates) the draft run for a period. A draft has no accounting effect. */
    fun calculate(c: CalculatePayroll): CommandOutcome = bus.execute(ModuleId.PAYROLL, c) { cmd, ctx ->
        ensure(cmd.from <= cmd.to) { DomainError.InvalidInput("period", "بازه حقوق معتبر نیست.") }
        // Salaries are monthly: a run covers one whole payroll month (29–31 days), never a fraction of one.
        val days = cmd.to.epochDay - cmd.from.epochDay + 1
        ensure(days in 29..31) { DomainError.InvalidInput("period", "دوره حقوق باید یک ماه کامل باشد.") }
        val overlapping = payroll.runs(cmd.scope).filter { it.status != RunStatus.REVERSED && it.from <= cmd.to && it.to >= cmd.from }
        ensure(overlapping.all { it.status == RunStatus.DRAFT && it.from == cmd.from && it.to == cmd.to }) { DomainError.InvalidState("PAYROLL_RUN", "PERIOD_OVERLAP") }
        val policy = policies.forPeriod(cmd.from, cmd.to)
        val payslips = personnel.employees(cmd.scope).filter { it.isActive }.map { e ->
            val records = personnel.attendance(e.id, cmd.from, cmd.to)
            policy.calculate(e.id, e.monthlySalary, records.sumOf { it.absentMinutes.toLong() }, records.sumOf { it.overtimeMinutes.toLong() })
        }
        ensure(payslips.isNotEmpty()) { DomainError.InvalidState("PAYROLL_RUN", "NO_EMPLOYEES") }
        val run = PayrollRun(overlapping.firstOrNull()?.id ?: GlobalId.new(), cmd.scope, cmd.from, cmd.to, policy.version, payslips,
            RunStatus.DRAFT, ctx.actor.userId, null, null)
        payroll.saveRun(run)
        ctx.audit(AuditDraft("PAYROLL_CALCULATE", "PAYROLL_RUN", run.id.value, "employees=${payslips.size};net=${Money.sum(payslips.map { it.net }).rial}"))
        run.id
    }

    /** Approval posts the accrual. The person who calculated a run can never approve it. */
    fun approve(c: ApprovePayroll): CommandOutcome = bus.execute(ModuleId.PAYROLL, c) { cmd, ctx ->
        val run = requireRun(cmd.runId, cmd.scope)
        ensure(run.status == RunStatus.DRAFT) { DomainError.InvalidState("PAYROLL_RUN", run.status.name) }
        ensure(run.calculatedBy != ctx.actor.userId) { DomainError.InvalidState("PAYROLL_RUN", "SAME_PERSON_CALCULATED") }
        val s = run.payslips
        val gross = Money.sum(s.map { it.gross })
        val employerCost = Money.sum(s.map { it.employerInsurance + it.unemploymentInsurance })
        val net = Money.sum(s.map { it.net })
        val insurance = Money.sum(s.map { it.employeeInsurance + it.employerInsurance + it.unemploymentInsurance })
        val tax = Money.sum(s.map { it.incomeTax })
        val lines = buildList {
            if (!gross.isZero) add(LineDraft(StandardAccounts.SALARIES, debit = gross, memo = "حقوق ناخالص", by = capability))
            if (!employerCost.isZero) add(LineDraft(StandardAccounts.EMPLOYER_INSURANCE, debit = employerCost, memo = "بیمه سهم کارفرما و بیکاری", by = capability))
            if (!net.isZero) add(LineDraft(StandardAccounts.PAYROLL_PAYABLE, credit = net, memo = "خالص پرداختنی", by = capability))
            if (!insurance.isZero) add(LineDraft(StandardAccounts.INSURANCE_PAYABLE, credit = insurance, memo = "بیمه", by = capability))
            if (!tax.isZero) add(LineDraft(StandardAccounts.PAYROLL_TAX_PAYABLE, credit = tax, memo = "مالیات حقوق", by = capability))
        }
        // A month in which nobody earned anything (all absent) is approved without an accounting entry.
        val journal = if (lines.isEmpty()) null else ledger.post(ctx, capability, JournalDraft(run.to, run.scope, RUN, run.id, "حقوق دوره", lines))
        payroll.saveRun(run.copy(status = RunStatus.APPROVED, approvedBy = ctx.actor.userId, accrualJournalId = journal?.id))
        ctx.audit(AuditDraft("PAYROLL_APPROVE", "PAYROLL_RUN", run.id.value, "gross=${gross.rial};net=${net.rial}"))
        run.id
    }

    fun reverse(c: ReversePayroll): CommandOutcome = bus.execute(ModuleId.PAYROLL, c) { cmd, ctx ->
        val run = requireRun(cmd.runId, cmd.scope)
        ensure(run.status == RunStatus.APPROVED) { DomainError.InvalidState("PAYROLL_RUN", run.status.name) }
        ensure(payroll.payments(run.id).none { !it.reversed }) { DomainError.InvalidState("PAYROLL_RUN", "HAS_PAYMENTS") }
        // Liabilities already remitted to the authorities cannot be un-accrued.
        ensure(
            outstandingLiability(run.scope, LiabilityKind.INSURANCE) >= Money.sum(run.payslips.map { it.employeeInsurance + it.employerInsurance + it.unemploymentInsurance }) &&
                outstandingLiability(run.scope, LiabilityKind.INCOME_TAX) >= Money.sum(run.payslips.map { it.incomeTax }),
        ) { DomainError.InvalidState("PAYROLL_RUN", "LIABILITY_ALREADY_REMITTED") }
        run.accrualJournalId?.let { ledger.reverse(ctx, capability, emptySet(), it, cmd.date, cmd.reason) }
        payroll.saveRun(run.copy(status = RunStatus.REVERSED))
        ctx.audit(AuditDraft("PAYROLL_REVERSE", "PAYROLL_RUN", run.id.value, cmd.reason.trim()))
        run.id
    }

    fun pay(c: PaySalary): CommandOutcome = bus.execute(ModuleId.PAYROLL, c) { cmd, ctx ->
        val run = requireRun(cmd.runId, cmd.scope)
        ensure(run.status == RunStatus.APPROVED) { DomainError.InvalidState("PAYROLL_RUN", run.status.name) }
        ensure(!cmd.amount.isZero && cmd.amount <= unpaidNet(run.id, cmd.employeeId)) { DomainError.InvalidState("PAYSLIP", "AMOUNT_EXCEEDS_UNPAID") }
        ensure(treasury.account(cmd.treasuryAccountId).scope == run.scope) { DomainError.InvalidInput("account", "حساب پرداخت متعلق به این شعبه نیست.") }
        val paymentId = GlobalId.new()
        val employee = personnel.employee(cmd.employeeId)!!
        treasury.settle(ctx, capability, cmd.treasuryAccountId, Direction.PAYMENT, cmd.amount, cmd.date, PAYMENT, paymentId, "پرداخت حقوق ${employee.name}",
            listOf(LineDraft(StandardAccounts.PAYROLL_PAYABLE, debit = cmd.amount, memo = employee.name, by = capability)))
        payroll.savePayment(SalaryPayment(paymentId, run.id, employee.id, cmd.treasuryAccountId, cmd.amount, cmd.date, reversed = false))
        paymentId
    }

    fun reversePayment(c: ReverseSalaryPayment): CommandOutcome = bus.execute(ModuleId.PAYROLL, c) { cmd, ctx ->
        val payment = payroll.payment(cmd.paymentId) ?: throw DomainException(DomainError.NotFound("SALARY_PAYMENT"))
        requireRun(payment.runId, cmd.scope)
        ensure(!payment.reversed) { DomainError.InvalidState("SALARY_PAYMENT", "ALREADY_REVERSED") }
        treasury.reverseDocument(ctx, capability, PAYMENT, payment.id, cmd.date, cmd.reason)
        payroll.savePayment(payment.copy(reversed = true))
        payment.id
    }

    fun remit(c: RemitLiability): CommandOutcome = bus.execute(ModuleId.PAYROLL, c) { cmd, ctx ->
        ensure(!cmd.amount.isZero && cmd.amount <= outstandingLiability(cmd.scope, cmd.kind)) { DomainError.InvalidState("PAYROLL_LIABILITY", "AMOUNT_EXCEEDS_OUTSTANDING") }
        ensure(treasury.account(cmd.treasuryAccountId).scope == cmd.scope) { DomainError.InvalidInput("account", "حساب پرداخت متعلق به این شعبه نیست.") }
        val id = GlobalId.new()
        val account = if (cmd.kind == LiabilityKind.INSURANCE) StandardAccounts.INSURANCE_PAYABLE else StandardAccounts.PAYROLL_TAX_PAYABLE
        treasury.settle(ctx, capability, cmd.treasuryAccountId, Direction.PAYMENT, cmd.amount, cmd.date, REMITTANCE, id,
            if (cmd.kind == LiabilityKind.INSURANCE) "پرداخت بیمه" else "پرداخت مالیات حقوق",
            listOf(LineDraft(account, debit = cmd.amount, by = capability)))
        payroll.saveRemittance(Remittance(id, cmd.scope, cmd.kind, cmd.amount, cmd.date))
        ctx.audit(AuditDraft("PAYROLL_REMIT", "PAYROLL_LIABILITY", id.value, "${cmd.kind}:${cmd.amount.rial}"))
        id
    }

    private fun requireEmployee(id: GlobalId, scope: Scope.Branch): Employee {
        val e = personnel.employee(id) ?: throw DomainException(DomainError.NotFound("EMPLOYEE"))
        ensure(e.scope == scope) { DomainError.InvalidInput("scope", "کارمند متعلق به این شعبه نیست.") }
        return e
    }

    private fun requireRun(id: GlobalId, scope: Scope.Branch): PayrollRun {
        val run = payroll.run(id) ?: throw DomainException(DomainError.NotFound("PAYROLL_RUN"))
        ensure(run.scope == scope) { DomainError.InvalidInput("scope", "لیست حقوق متعلق به این شعبه نیست.") }
        return run
    }

    companion object {
        const val RUN = "PAYROLL_RUN"
        const val PAYMENT = "SALARY_PAYMENT"
        const val REMITTANCE = "PAYROLL_REMITTANCE"
    }
}
