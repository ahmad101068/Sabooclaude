package ir.sabou.payroll

import ir.sabou.kernel.NationalId
import ir.sabou.kernel.JalaliCalendar
import ir.sabou.kernel.JalaliDay
import ir.sabou.kernel.Sheba

import ir.sabou.platform.NoDocument

import ir.sabou.platform.DocumentSeries

import ir.sabou.platform.IssuesDocument

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
import ir.sabou.platform.CommandOutcome
import ir.sabou.platform.ModuleId
import ir.sabou.platform.Permission
import ir.sabou.treasury.Direction
import ir.sabou.treasury.TreasuryGateway

@NoDocument
data class RegisterEmployee(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val name: String,
    val nationalId: String,
    val monthlySalary: Money,
    /** Set when the person starts during a payroll month; their first month is then prorated. */
    val startDate: BusinessDate? = null,
    val birthDate: JalaliDay? = null,
    /** [monthlySalary] is a month's salary, or a day's / an hour's wage. */
    val payType: PayType = PayType.MONTHLY,
) : Command {
    override val requiredPermission = Permission.PERSONNEL_MANAGE
    override fun fingerprint() = "$scope|$name|$nationalId|${monthlySalary.rial}|${startDate?.epochDay}|${birthDate?.packed()}|$payType"
}

/** The person's file: contact, job, insurance number, bank account, family, contract. */
@NoDocument
data class UpdateEmployeeProfile(override val commandId: GlobalId, override val scope: Scope.Branch, val employeeId: GlobalId, val profile: EmployeeProfile) : Command {
    override val requiredPermission = Permission.PERSONNEL_MANAGE
    override fun fingerprint() = "$scope|$employeeId|$profile"
}

/**
 * Moves a person to another branch from [effectiveFrom]. The old branch must have paid them up to the day before
 * (its approved payroll ends there, or they had not started), and has no shifts or attendance for them after it.
 */
@NoDocument
data class TransferEmployee(override val commandId: GlobalId, override val scope: Scope.Branch, val employeeId: GlobalId, val toBranch: Scope.Branch, val effectiveFrom: BusinessDate) : Command {
    override val requiredPermission = Permission.PERSONNEL_MANAGE
    override fun fingerprint() = "$scope|$employeeId|$toBranch|${effectiveFrom.epochDay}"
}

/** Corrects a person's name, national ID and birth date (the personnel number never changes). */
@NoDocument
data class UpdateEmployeeDetails(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val employeeId: GlobalId,
    val name: String,
    val nationalId: String,
    val birthDate: JalaliDay?,
) : Command {
    override val requiredPermission = Permission.PERSONNEL_MANAGE
    override fun fingerprint() = "$scope|$employeeId|$name|$nationalId|${birthDate?.packed()}"
}

/** A new monthly salary from [effectiveFrom] (a yearly raise, a promotion). Earlier months keep the salary they had. */
@NoDocument
data class ChangeSalary(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val employeeId: GlobalId,
    val effectiveFrom: BusinessDate,
    val monthlySalary: Money,
    val reason: String,
    /** Changes how the person is paid as well; null keeps it. */
    val payType: PayType? = null,
) : Command {
    override val requiredPermission = Permission.PERSONNEL_MANAGE
    override fun fingerprint() = "$scope|$employeeId|${effectiveFrom.epochDay}|${monthlySalary.rial}|$reason|$payType"
}

/** Someone who left comes back from [startDate]: same person, same personnel number, a new employment. */
@NoDocument
data class RehireEmployee(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val employeeId: GlobalId,
    val startDate: BusinessDate,
    val monthlySalary: Money,
    val payType: PayType? = null,
) : Command {
    override val requiredPermission = Permission.PERSONNEL_MANAGE
    override fun fingerprint() = "$scope|$employeeId|${startDate.epochDay}|${monthlySalary.rial}|$payType"
}

/** Ends an employment after [lastDay]; that month is paid only up to it, later months not at all. */
@NoDocument
data class EndEmployment(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val employeeId: GlobalId,
    val lastDay: BusinessDate,
) : Command {
    override val requiredPermission = Permission.PERSONNEL_MANAGE
    override fun fingerprint() = "$scope|$employeeId|${lastDay.epochDay}"
}

@NoDocument
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

@IssuesDocument(DocumentSeries.PAYROLL)
data class CalculatePayroll(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val from: BusinessDate,
    val to: BusinessDate,
) : Command {
    override val requiredPermission = Permission.PAYROLL_CALCULATE
    override fun fingerprint() = "$scope|${from.epochDay}|${to.epochDay}"
}

@NoDocument
data class ApprovePayroll(override val commandId: GlobalId, override val scope: Scope.Branch, val runId: GlobalId) : Command {
    override val requiredPermission = Permission.PAYROLL_APPROVE
    override fun fingerprint() = "$scope|$runId"
}

@IssuesDocument(DocumentSeries.REVERSAL)
data class ReversePayroll(override val commandId: GlobalId, override val scope: Scope.Branch, val runId: GlobalId, val date: BusinessDate, val reason: String) : Command {
    override val requiredPermission = Permission.PAYROLL_APPROVE
    override fun fingerprint() = "$scope|$runId|${date.epochDay}|$reason"
}

@IssuesDocument(DocumentSeries.PAYMENT)
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

@IssuesDocument(DocumentSeries.REVERSAL)
data class ReverseSalaryPayment(override val commandId: GlobalId, override val scope: Scope.Branch, val paymentId: GlobalId, val date: BusinessDate, val reason: String) : Command {
    override val requiredPermission = Permission.PAYROLL_PAY
    override fun fingerprint() = "$scope|$paymentId|${date.epochDay}|$reason"
}

/** Pays the insurance organisation or the tax office out of the accrued liability. */
@IssuesDocument(DocumentSeries.PAYMENT)
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
    /** The restaurant's payroll options (overtime pricing…). */
    private val settings: () -> PayrollSettings = { PayrollSettings() },
    /** Whether someone other than this user may approve payroll (a second owner); with nobody else, the owner may approve their own calculation. */
    private val otherApproverExists: (GlobalId) -> Boolean = { true },
    /** Whether the person has shifts planned in a branch from a date (they block a transfer). */
    private val hasShiftsFrom: (GlobalId, Scope.Branch, BusinessDate) -> Boolean = { _, _, _ -> false },
    /** Pay elements, one-off lines and leave; without it (module tests) none apply. */
    private val elements: PayElementStore? = null,
    /** Fridays, and the branch's holidays. */
    private val isHoliday: (Scope.Branch, BusinessDate) -> Boolean = { _, d -> JalaliCalendar.weekdayIndex(d) == 6 },
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

    /** Every branch this person has been paid in. */
    private fun scopesOf(e: Employee): Set<Scope.Branch> = setOf(e.scope) + e.branchMoves.map { it.previous }

    /** What approved payslips have taken back of [advance]. */
    fun deducted(advance: Advance): Money {
        val e = personnel.employee(advance.employeeId) ?: return Money.ZERO
        return Money.sum(scopesOf(e).flatMap { payroll.runs(it) }.filter { it.status == RunStatus.APPROVED }
            .flatMap { r -> r.payslips.filter { it.employeeId == e.id }.flatMap { it.lines } }.filter { it.code == advance.code }.map { it.amount })
    }

    fun advanceView(advance: Advance): AdvanceView {
        val deducted = deducted(advance)
        val owed = if (advance.reversed) 0L else maxOf(0L, advance.amount.rial - deducted.rial - advance.repaid().rial)
        return AdvanceView(advance, deducted, Money.of(owed))
    }

    /** This month's installments: each advance given by the end of the month, while something is owed; all of it in the last month of work. */
    private fun advanceLines(e: Employee, to: BusinessDate): List<PayLine> {
        val leaving = e.endDate != null && e.endDate <= to
        return payroll.advances(e.id).filter { !it.reversed && it.date <= to }.sortedBy { it.date }.mapNotNull { a ->
            val owed = advanceView(a).outstanding
            val take = if (leaving) owed else minOf(owed, a.installment)
            PayLine(a.code, if (a.isLoan) "قسط وام" else "مساعده", take, PayLine.Kind.ADVANCE).takeIf { !take.isZero }
        }
    }

    fun giveAdvance(c: GiveAdvance): CommandOutcome = bus.execute(ModuleId.PAYROLL, c) { cmd, ctx ->
        val employee = requireEmployee(cmd.employeeId, cmd.scope)
        ensure(employee.employedOn(cmd.date)) { DomainError.InvalidState("EMPLOYEE", "NOT_EMPLOYED_ON_DATE") }
        ensure(!cmd.amount.isZero && cmd.installments in 1..60) { DomainError.InvalidInput("installments", "تعداد قسط بین ۱ تا ۶۰ است.") }
        ensure(cmd.note.length <= 120) { DomainError.InvalidInput("note", "متن بیش از حد طولانی است.") }
        ensure(treasury.account(cmd.treasuryAccountId).scope == cmd.scope) { DomainError.InvalidInput("account", "حساب پرداخت متعلق به این شعبه نیست.") }
        val advance = Advance(GlobalId.new(), cmd.scope, employee.id, cmd.amount, cmd.date, cmd.installments, cmd.note.trim(), cmd.treasuryAccountId)
        treasury.settle(ctx, capability, cmd.treasuryAccountId, Direction.PAYMENT, cmd.amount, cmd.date, ADVANCE, advance.id,
            (if (advance.isLoan) "وام " else "مساعده ") + employee.name,
            listOf(LineDraft(StandardAccounts.EMPLOYEE_ADVANCE, debit = cmd.amount, memo = employee.name, by = capability)))
        payroll.saveAdvance(advance)
        ctx.number(DocumentSeries.PAYMENT, cmd.date, advance.id)
        ctx.audit(AuditDraft("ADVANCE_GIVE", "EMPLOYEE", employee.id.value, "amount=${cmd.amount.rial};installments=${cmd.installments}"))
        advance.id
    }

    fun repayAdvance(c: RepayAdvance): CommandOutcome = bus.execute(ModuleId.PAYROLL, c) { cmd, ctx ->
        val advance = requireAdvance(cmd.advanceId, cmd.scope)
        ensure(!cmd.amount.isZero && cmd.amount <= advanceView(advance).outstanding) { DomainError.InvalidState("ADVANCE", "AMOUNT_EXCEEDS_OUTSTANDING") }
        ensure(treasury.account(cmd.treasuryAccountId).scope == cmd.scope) { DomainError.InvalidInput("account", "حساب دریافت متعلق به این شعبه نیست.") }
        val employee = personnel.employee(advance.employeeId)!!
        val id = GlobalId.new()
        treasury.settle(ctx, capability, cmd.treasuryAccountId, Direction.RECEIPT, cmd.amount, cmd.date, ADVANCE_REPAYMENT, id, "بازپرداخت ${employee.name}",
            listOf(LineDraft(StandardAccounts.EMPLOYEE_ADVANCE, credit = cmd.amount, memo = employee.name, by = capability)))
        payroll.saveAdvance(advance.copy(repayments = advance.repayments + AdvanceRepayment(id, cmd.amount, cmd.date, cmd.treasuryAccountId)))
        ctx.number(DocumentSeries.RECEIPT, cmd.date, id)
        id
    }

    fun reverseAdvance(c: ReverseAdvance): CommandOutcome = bus.execute(ModuleId.PAYROLL, c) { cmd, ctx ->
        val advance = requireAdvance(cmd.advanceId, cmd.scope)
        ensure(!advance.reversed) { DomainError.InvalidState("ADVANCE", "ALREADY_REVERSED") }
        // Once part of it was paid back (from a salary or in cash) it is no longer a mistake to undo.
        ensure(deducted(advance).isZero && advance.repaid().isZero) { DomainError.InvalidState("ADVANCE", "PARTLY_REPAID") }
        ensure(cmd.reason.trim().length >= 3) { DomainError.InvalidInput("reason", "دلیل را بنویسید.") }
        treasury.reverseDocument(ctx, capability, ADVANCE, advance.id, cmd.date, cmd.reason)
        payroll.saveAdvance(advance.copy(reversed = true))
        ctx.number(DocumentSeries.REVERSAL, cmd.date, cmd.commandId, reverses = DocumentSeries.PAYMENT to advance.id)
        advance.id
    }

    private fun requireAdvance(id: GlobalId, scope: Scope.Branch): Advance {
        val a = payroll.advance(id) ?: throw DomainException(DomainError.NotFound("ADVANCE"))
        ensure(a.scope == scope) { DomainError.InvalidInput("scope", "این مساعده متعلق به این شعبه نیست.") }
        return a
    }

    // ------------------------------------------------------------ Pay elements, one-off lines, leave

    fun savePayElement(c: SavePayElement): CommandOutcome = bus.execute(ModuleId.PAYROLL, c) { cmd, ctx ->
        val el = cmd.element.copy(name = cmd.element.name.trim())
        ensure(el.name.length in 2..60) { DomainError.InvalidInput("name", "نام عامل حقوقی را بنویسید.") }
        ensure(el.kind != PayLine.Kind.ADVANCE) { DomainError.InvalidInput("kind", "مزایا یا کسور.") }
        ensure(el.amount > 0 && (el.basis != PayElement.Basis.PERCENT_OF_BASE || el.amount <= 10_000)) { DomainError.InvalidInput("amount", "مبلغ یا درصد معتبر نیست.") }
        store().saveElement(el)
        ctx.audit(AuditDraft("PAY_ELEMENT_SAVE", "PAY_ELEMENT", el.id.value, el.toString()))
        el.id
    }

    fun addAdjustment(c: AddPayAdjustment): CommandOutcome = bus.execute(ModuleId.PAYROLL, c) { cmd, ctx ->
        val title = cmd.title.trim()
        ensure(title.length in 2..60) { DomainError.InvalidInput("title", "شرح را بنویسید.") }
        ensure(cmd.kind != PayLine.Kind.ADVANCE && cmd.employeeIds.isNotEmpty()) { DomainError.InvalidInput("employees", "کارمند را انتخاب کنید.") }
        ensure((cmd.amount != null && !cmd.amount.isZero) != (cmd.percentOfSalary != null && cmd.percentOfSalary in 1..100_000)) {
            DomainError.InvalidInput("amount", "مبلغ یا درصدی از حقوق را وارد کنید.")
        }
        ensure(payroll.runs(cmd.scope).none { it.status == RunStatus.APPROVED && it.from == cmd.periodFrom }) { DomainError.InvalidState("PAYROLL_RUN", "APPROVED") }
        val ids = cmd.employeeIds.distinct().map { id ->
            val e = requireEmployee(id, cmd.scope)
            val amount = cmd.amount ?: Money.of(Ratio.mulDiv(e.salaryOn(cmd.periodFrom).rial, cmd.percentOfSalary!!.toLong(), 10_000))
            val adj = PayAdjustment(GlobalId.new(), cmd.scope, e.id, cmd.periodFrom, title, cmd.kind, amount, cmd.insurable, cmd.taxable)
            store().saveAdjustment(adj)
            ctx.audit(AuditDraft("PAY_ADJUSTMENT_ADD", "EMPLOYEE", e.id.value, "period=${cmd.periodFrom.epochDay};${cmd.kind};${amount.rial};$title"))
            adj.id
        }
        ids.first()
    }

    fun removeAdjustment(c: RemovePayAdjustment): CommandOutcome = bus.execute(ModuleId.PAYROLL, c) { cmd, ctx ->
        val adj = store().adjustment(cmd.adjustmentId) ?: throw DomainException(DomainError.NotFound("PAY_ADJUSTMENT"))
        ensure(adj.scope == cmd.scope) { DomainError.InvalidInput("scope", "متعلق به این شعبه نیست.") }
        ensure(payroll.runs(cmd.scope).none { it.status == RunStatus.APPROVED && it.from == adj.periodFrom }) { DomainError.InvalidState("PAYROLL_RUN", "APPROVED") }
        store().deleteAdjustment(adj.id)
        ctx.audit(AuditDraft("PAY_ADJUSTMENT_REMOVE", "EMPLOYEE", adj.employeeId.value, "period=${adj.periodFrom.epochDay};${adj.amount.rial};${adj.title}"))
        adj.id
    }

    fun recordLeave(c: RecordLeave): CommandOutcome = bus.execute(ModuleId.PAYROLL, c) { cmd, ctx ->
        val e = requireEmployee(cmd.employeeId, cmd.scope)
        val options = settings()
        ensure(cmd.from <= cmd.to && cmd.to.epochDay - cmd.from.epochDay < 62) { DomainError.InvalidInput("to", "بازه‌ی مرخصی معتبر نیست.") }
        val minutes = cmd.minutes ?: options.workdayMinutes
        ensure(minutes in 15..options.workdayMinutes && (cmd.minutes == null || cmd.from == cmd.to)) { DomainError.InvalidInput("minutes", "مدت مرخصی ساعتی معتبر نیست.") }
        ensure(cmd.note.length <= 120) { DomainError.InvalidInput("note", "متن بیش از حد طولانی است.") }
        // Fridays and holidays are not working days: a week's leave is six days.
        val days = generateSequence(cmd.from) { it.plusDays(1) }.takeWhile { it <= cmd.to }.filter { !isHoliday(cmd.scope, it) }.toList()
        ensure(days.isNotEmpty()) { DomainError.InvalidInput("from", "این روزها تعطیل است.") }
        ensure(days.all { e.employedOn(it) }) { DomainError.InvalidState("EMPLOYEE", "NOT_EMPLOYED_ON_DATE") }
        ensure(days.none { d -> payroll.runs(cmd.scope).any { it.status == RunStatus.APPROVED && d >= it.from && d <= it.to } }) { DomainError.InvalidState("ATTENDANCE", "PERIOD_APPROVED") }
        val existing = store().leaves(e.id).filter { !it.cancelled }
        ensure(days.none { d -> existing.any { it.date == d } }) { DomainError.InvalidState("LEAVE", "OVERLAP") }
        if (cmd.kind == Leave.Kind.ANNUAL) {
            val left = LeaveRules.balance(e, existing, options, cmd.to).left
            ensure(left >= minutes.toLong() * days.size) { DomainError.InvalidState("LEAVE", "NOT_ENOUGH:${left}") }
        }
        val ids = days.map { d -> Leave(GlobalId.new(), cmd.scope, e.id, d, cmd.kind, minutes, cmd.note.trim()).also(store()::saveLeave).id }
        ctx.audit(AuditDraft("LEAVE_RECORD", "EMPLOYEE", e.id.value, "${cmd.kind};${cmd.from.epochDay}-${cmd.to.epochDay};days=${days.size};minutes=$minutes"))
        ids.first()
    }

    fun cancelLeave(c: CancelLeave): CommandOutcome = bus.execute(ModuleId.PAYROLL, c) { cmd, ctx ->
        val leave = store().leave(cmd.leaveId) ?: throw DomainException(DomainError.NotFound("LEAVE"))
        requireEmployee(leave.employeeId, cmd.scope)
        ensure(!leave.cancelled) { DomainError.InvalidState("LEAVE", "CANCELLED") }
        ensure(payroll.runs(cmd.scope).none { it.status == RunStatus.APPROVED && leave.date >= it.from && leave.date <= it.to }) { DomainError.InvalidState("ATTENDANCE", "PERIOD_APPROVED") }
        store().saveLeave(leave.copy(cancelled = true))
        ctx.audit(AuditDraft("LEAVE_CANCEL", "EMPLOYEE", leave.employeeId.value, "day=${leave.date.epochDay};${leave.kind}"))
        leave.id
    }

    fun leaveBalance(e: Employee, on: BusinessDate): LeaveBalance = LeaveRules.balance(e, elements?.leaves(e.id).orEmpty(), settings(), on)

    private fun store(): PayElementStore = elements ?: throw DomainException(DomainError.InvalidState("PAY_ELEMENTS", "NOT_AVAILABLE"))

    fun registerEmployee(c: RegisterEmployee): CommandOutcome = bus.execute(ModuleId.PAYROLL, c) { cmd, ctx ->
        val name = cmd.name.trim()
        ensure(name.length in 2..120) { DomainError.InvalidInput("name", "نام کارمند الزامی است.") }
        ensure(NationalId.isValid(cmd.nationalId)) { DomainError.InvalidInput("nationalId", "کد ملی معتبر نیست.") }
        ensure(cmd.monthlySalary.rial > 0) { DomainError.InvalidInput("monthlySalary", "حقوق ماهانه را وارد کنید.") }
        val nationalId = NationalId.normalize(cmd.nationalId)
        // Days inside a month already approved could never be paid (that month is closed for attendance and recalculation).
        cmd.startDate?.let { start ->
            ensure(payroll.runs(cmd.scope).none { it.status == RunStatus.APPROVED && it.to >= start }) { DomainError.InvalidState("EMPLOYEE", "START_IN_APPROVED_PERIOD") }
        }
        personnel.employees(cmd.scope).firstOrNull { NationalId.normalize(it.nationalId) == nationalId }?.let { same ->
            // Someone who left and comes back is the same person: «بازگشت به کار», not a second record.
            throw DomainException(DomainError.InvalidState("EMPLOYEE", if (same.endDate != null) "DUPLICATE_NATIONAL_ID_ENDED" else "DUPLICATE_NATIONAL_ID"))
        }
        cmd.birthDate?.let { validBirthDate(it, ctx.nowEpochMillis) }
        val employee = Employee(
            GlobalId.new(), name, nationalId, cmd.scope, cmd.monthlySalary, startDate = cmd.startDate,
            birthDate = cmd.birthDate, personnelNumber = personnel.nextPersonnelNumber(), payType = cmd.payType,
        )
        personnel.saveEmployee(employee)
        ctx.audit(AuditDraft("EMPLOYEE_REGISTER", "EMPLOYEE", employee.id.value, name + ";no=${employee.personnelNumber}" + (cmd.startDate?.let { ";start=${it.epochDay}" } ?: "")))
        employee.id
    }

    fun updateEmployeeDetails(c: UpdateEmployeeDetails): CommandOutcome = bus.execute(ModuleId.PAYROLL, c) { cmd, ctx ->
        val employee = requireEmployee(cmd.employeeId, cmd.scope)
        val name = cmd.name.trim()
        ensure(name.length in 2..120) { DomainError.InvalidInput("name", "نام کارمند الزامی است.") }
        ensure(NationalId.isValid(cmd.nationalId)) { DomainError.InvalidInput("nationalId", "کد ملی معتبر نیست.") }
        val nationalId = NationalId.normalize(cmd.nationalId)
        ensure(personnel.employees(cmd.scope).none { it.id != employee.id && NationalId.normalize(it.nationalId) == nationalId }) {
            DomainError.InvalidState("EMPLOYEE", "DUPLICATE_NATIONAL_ID")
        }
        cmd.birthDate?.let { validBirthDate(it, ctx.nowEpochMillis) }
        personnel.saveEmployee(employee.copy(name = name, nationalId = nationalId, birthDate = cmd.birthDate))
        // The audit keeps what changed, so a wrong correction can be traced.
        val changes = buildList {
            if (name != employee.name) add("name=${employee.name}->$name")
            if (nationalId != employee.nationalId) add("nationalId=${employee.nationalId}->$nationalId")
            if (cmd.birthDate != employee.birthDate) add("birth=${employee.birthDate?.packed()}->${cmd.birthDate?.packed()}")
        }
        ctx.audit(AuditDraft("EMPLOYEE_UPDATE", "EMPLOYEE", employee.id.value, changes.joinToString(";")))
        employee.id
    }

    /** A worker is at least 10 and at most 100 years old by the clock. */
    private fun validBirthDate(birth: JalaliDay, nowEpochMillis: Long) {
        val age = birth.yearsUntil(JalaliCalendar.of(Math.floorDiv(nowEpochMillis, 86_400_000L)))
        ensure(age in 10..100) { DomainError.InvalidInput("birthDate", "تاریخ تولد معتبر نیست.") }
    }

    fun changeSalary(c: ChangeSalary): CommandOutcome = bus.execute(ModuleId.PAYROLL, c) { cmd, ctx ->
        val employee = requireEmployee(cmd.employeeId, cmd.scope)
        ensure(cmd.monthlySalary.rial > 0) { DomainError.InvalidInput("monthlySalary", "حقوق ماهانه را وارد کنید.") }
        val reason = cmd.reason.trim()
        ensure(reason.length <= 120) { DomainError.InvalidInput("reason", "متن بیش از حد طولانی است.") }
        ensure(employee.startDate == null || cmd.effectiveFrom > employee.startDate) { DomainError.InvalidInput("effectiveFrom", "تاریخ تغییر باید بعد از شروع کار باشد.") }
        ensure(employee.endDate == null) { DomainError.InvalidState("EMPLOYEE", "ALREADY_ENDED") }
        // A month already approved keeps the salary it was paid with.
        ensure(payroll.runs(cmd.scope).none { it.status == RunStatus.APPROVED && it.to >= cmd.effectiveFrom }) { DomainError.InvalidState("SALARY", "IN_APPROVED_PERIOD") }
        val before = employee.salaryOn(cmd.effectiveFrom)
        val type = cmd.payType ?: employee.payTypeOn(cmd.effectiveFrom)
        val changes = (employee.salaryChanges.filter { it.from != cmd.effectiveFrom } + SalaryChange(cmd.effectiveFrom, cmd.monthlySalary, reason, type)).sortedBy { it.from }
        personnel.saveEmployee(employee.copy(salaryChanges = changes))
        ctx.audit(AuditDraft("SALARY_CHANGE", "EMPLOYEE", employee.id.value, "from=${cmd.effectiveFrom.epochDay};${before.rial}->${cmd.monthlySalary.rial};$reason"))
        employee.id
    }

    fun rehire(c: RehireEmployee): CommandOutcome = bus.execute(ModuleId.PAYROLL, c) { cmd, ctx ->
        val employee = requireEmployee(cmd.employeeId, cmd.scope)
        val ended = employee.endDate ?: throw DomainException(DomainError.InvalidState("EMPLOYEE", "STILL_EMPLOYED"))
        ensure(cmd.startDate > ended) { DomainError.InvalidInput("startDate", "شروع دوباره باید بعد از آخرین روز کار قبلی باشد.") }
        ensure(cmd.monthlySalary.rial > 0) { DomainError.InvalidInput("monthlySalary", "حقوق ماهانه را وارد کنید.") }
        ensure(payroll.runs(cmd.scope).none { it.status == RunStatus.APPROVED && it.to >= cmd.startDate }) { DomainError.InvalidState("EMPLOYEE", "START_IN_APPROVED_PERIOD") }
        val back = employee.copy(
            isActive = true, startDate = cmd.startDate, endDate = null,
            pastEmployments = employee.pastEmployments + Employment(employee.startDate, ended),
            salaryChanges = (employee.salaryChanges.filter { it.from < cmd.startDate } +
                SalaryChange(cmd.startDate, cmd.monthlySalary, "بازگشت به کار", cmd.payType ?: employee.payTypeOn(ended))).sortedBy { it.from },
        )
        personnel.saveEmployee(back)
        ctx.audit(AuditDraft("EMPLOYEE_REHIRE", "EMPLOYEE", employee.id.value, "start=${cmd.startDate.epochDay};salary=${cmd.monthlySalary.rial}"))
        employee.id
    }

    fun updateProfile(c: UpdateEmployeeProfile): CommandOutcome = bus.execute(ModuleId.PAYROLL, c) { cmd, ctx ->
        val employee = requireEmployee(cmd.employeeId, cmd.scope)
        val p = cmd.profile.let {
            it.copy(phone = it.phone.trim(), address = it.address.trim(), emergencyContact = it.emergencyContact.trim(), fatherName = it.fatherName.trim(),
                jobTitle = it.jobTitle.trim(), insuranceNumber = NationalId.normalize(it.insuranceNumber), sheba = if (it.sheba.isBlank()) "" else Sheba.normalize(it.sheba))
        }
        ensure(listOf(p.phone, p.emergencyContact, p.fatherName, p.jobTitle, p.insuranceNumber).all { it.length <= 80 } && p.address.length <= 300) {
            DomainError.InvalidInput("text", "متن بیش از حد طولانی است.")
        }
        ensure(p.sheba.isEmpty() || Sheba.isValid(p.sheba)) { DomainError.InvalidInput("sheba", "شماره شبا درست نیست (IR و ۲۴ رقم).") }
        ensure(p.insuranceNumber.isEmpty() || p.insuranceNumber.all { it.isDigit() }) { DomainError.InvalidInput("insuranceNumber", "شماره بیمه فقط رقم است.") }
        ensure(p.children in 0..20) { DomainError.InvalidInput("children", "تعداد فرزند معتبر نیست.") }
        personnel.saveEmployee(employee.copy(profile = p))
        // Which fields changed, not their values (the file holds personal data).
        val changed = EmployeeProfile::class.java.declaredFields.filter { f ->
            f.isAccessible = true; f.get(employee.profile) != f.get(p)
        }.joinToString(",") { it.name }
        ctx.audit(AuditDraft("EMPLOYEE_PROFILE", "EMPLOYEE", employee.id.value, changed))
        employee.id
    }

    fun transfer(c: TransferEmployee): CommandOutcome = bus.execute(ModuleId.PAYROLL, c) { cmd, ctx ->
        val employee = requireEmployee(cmd.employeeId, cmd.scope)
        ctx.requireScope(cmd.toBranch)
        ensure(cmd.toBranch != cmd.scope) { DomainError.InvalidInput("toBranch", "شعبه‌ی مقصد همان شعبه‌ی فعلی است.") }
        ensure(employee.endDate == null) { DomainError.InvalidState("EMPLOYEE", "ALREADY_ENDED") }
        // The old branch pays up to the day before: its last approved month ends there, or the person had not started yet.
        val paidTo = payroll.runs(cmd.scope).filter { it.status == RunStatus.APPROVED && it.payslips.any { s -> s.employeeId == employee.id } }.maxOfOrNull { it.to }
        val notStarted = employee.startDate != null && employee.startDate >= cmd.effectiveFrom && paidTo == null
        ensure(notStarted || paidTo?.plusDays(1) == cmd.effectiveFrom) { DomainError.InvalidState("EMPLOYEE", "TRANSFER_NOT_AT_PAID_END") }
        ensure(payroll.runs(cmd.scope).none { it.status == RunStatus.DRAFT && it.to >= cmd.effectiveFrom && it.payslips.any { s -> s.employeeId == employee.id } }) {
            DomainError.InvalidState("EMPLOYEE", "TRANSFER_DRAFT_RUN")
        }
        ensure(!hasShiftsFrom(employee.id, cmd.scope, cmd.effectiveFrom) && personnel.attendance(employee.id, cmd.effectiveFrom, cmd.effectiveFrom.plusDays(3_650)).isEmpty()) {
            DomainError.InvalidState("EMPLOYEE", "TRANSFER_HAS_LATER_RECORDS")
        }
        personnel.saveEmployee(employee.copy(scope = cmd.toBranch, branchMoves = employee.branchMoves + BranchMove(cmd.effectiveFrom, cmd.scope)))
        ctx.audit(AuditDraft("EMPLOYEE_TRANSFER", "EMPLOYEE", employee.id.value, "to=${ir.sabou.platform.CommandContext.scopeLabel(cmd.toBranch)};from=${cmd.effectiveFrom.epochDay}"))
        employee.id
    }

    fun endEmployment(c: EndEmployment): CommandOutcome = bus.execute(ModuleId.PAYROLL, c) { cmd, ctx ->
        val employee = requireEmployee(cmd.employeeId, cmd.scope)
        ensure(employee.endDate == null && employee.isActive) { DomainError.InvalidState("EMPLOYEE", "ALREADY_ENDED") }
        ensure(employee.startDate == null || cmd.lastDay >= employee.startDate) { DomainError.InvalidInput("lastDay", "آخرین روز کار نمی‌تواند پیش از شروع کار باشد.") }
        // An approved month already paid this person in full: the end date cannot fall before its last day.
        ensure(payroll.runs(cmd.scope).none { it.status == RunStatus.APPROVED && it.to > cmd.lastDay && it.payslips.any { s -> s.employeeId == employee.id } }) {
            DomainError.InvalidState("EMPLOYEE", "PERIOD_APPROVED")
        }
        personnel.saveEmployee(employee.copy(isActive = false, endDate = cmd.lastDay))
        ctx.audit(AuditDraft("EMPLOYEE_END", "EMPLOYEE", employee.id.value, "last=${cmd.lastDay.epochDay}"))
        employee.id
    }

    fun recordAttendance(c: RecordAttendance): CommandOutcome = bus.execute(ModuleId.PAYROLL, c) { cmd, ctx ->
        applyAttendance(ctx, cmd.scope, cmd.employeeId, cmd.date, cmd.workedMinutes, cmd.overtimeMinutes, cmd.absentMinutes, AttendanceRecord.Source.MANUAL)
    }

    /** One day's attendance under the attendance rules; also used when a day's punches are confirmed (ADR-0038). */
    internal fun applyAttendance(
        ctx: ir.sabou.platform.CommandContext, scope: Scope.Branch, employeeId: GlobalId, date: BusinessDate,
        worked: Int, overtime: Int, absent: Int, source: AttendanceRecord.Source, night: Int = 0,
    ): GlobalId {
        val employee = requireEmployee(employeeId, scope)
        ensure(employee.employedDays(date, date) == 1) { DomainError.InvalidState("EMPLOYEE", "NOT_EMPLOYED_ON_DATE") }
        ensure(listOf(worked, overtime, absent).all { it in 0..1_440 } && worked + overtime <= 1_440) { DomainError.InvalidInput("minutes", "زمان حضور معتبر نیست.") }
        ensure(payroll.runs(scope).none { it.status == RunStatus.APPROVED && date >= it.from && date <= it.to }) {
            DomainError.InvalidState("ATTENDANCE", "PERIOD_APPROVED")
        }
        val existing = personnel.attendanceOn(employee.id, date)
        val record = AttendanceRecord(existing?.id ?: GlobalId.new(), employee.id, date, worked, overtime, absent, source, night)
        personnel.saveAttendance(record)
        ctx.audit(AuditDraft(if (existing == null) "ATTENDANCE_RECORD" else "ATTENDANCE_CORRECT", "EMPLOYEE", employee.id.value,
            "day=${date.epochDay};w=$worked;ot=$overtime;abs=$absent;src=$source" + (existing?.let { ";was=${it.workedMinutes}/${it.overtimeMinutes}/${it.absentMinutes}/${it.source}" } ?: "")))
        return record.id
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
        val payslips = payslips(cmd.scope, cmd.from, cmd.to, policy)
        ensure(payslips.isNotEmpty()) { DomainError.InvalidState("PAYROLL_RUN", "NO_EMPLOYEES") }
        val run = PayrollRun(overlapping.firstOrNull()?.id ?: GlobalId.new(), cmd.scope, cmd.from, cmd.to, policy.version, payslips,
            RunStatus.DRAFT, ctx.actor.userId, null, null)
        payroll.saveRun(run)
        ctx.audit(AuditDraft("PAYROLL_CALCULATE", "PAYROLL_RUN", run.id.value, "employees=${payslips.size};net=${Money.sum(payslips.map { it.net }).rial}"))
        ctx.number(DocumentSeries.PAYROLL, cmd.to, run.id)
        run.id
    }

    /** Approval posts the accrual. The person who calculated a run can never approve it. */
    fun approve(c: ApprovePayroll): CommandOutcome = bus.execute(ModuleId.PAYROLL, c) { cmd, ctx ->
        val run = requireRun(cmd.runId, cmd.scope)
        ensure(run.status == RunStatus.DRAFT) { DomainError.InvalidState("PAYROLL_RUN", run.status.name) }
        // Two people: one calculates, another approves. A restaurant with a single person who may approve (the only
        // owner) would otherwise never pay salaries: then that person approves their own calculation, recorded as such.
        val self = run.calculatedBy == ctx.actor.userId
        ensure(!self || !otherApproverExists(ctx.actor.userId)) { DomainError.InvalidState("PAYROLL_RUN", "SAME_PERSON_CALCULATED") }
        // What is approved must be what the data says now: a hire, a leaver, a salary or attendance change
        // after the calculation makes the draft stale.
        ensure(payslips(run.scope, run.from, run.to, policies.forPeriod(run.from, run.to)) == run.payslips) {
            DomainError.InvalidState("PAYROLL_RUN", "STALE")
        }
        val s = run.payslips
        val gross = Money.sum(s.map { it.gross })
        val employerCost = Money.sum(s.map { it.employerInsurance + it.unemploymentInsurance })
        val net = Money.sum(s.map { it.net })
        val advances = Money.sum(s.map { it.total(PayLine.Kind.ADVANCE) })
        val deductions = Money.sum(s.map { it.total(PayLine.Kind.DEDUCTION) })
        val insurance = Money.sum(s.map { it.employeeInsurance + it.employerInsurance + it.unemploymentInsurance })
        val tax = Money.sum(s.map { it.incomeTax })
        val lines = buildList {
            if (!gross.isZero) add(LineDraft(StandardAccounts.SALARIES, debit = gross, memo = "حقوق ناخالص", by = capability))
            if (!employerCost.isZero) add(LineDraft(StandardAccounts.EMPLOYER_INSURANCE, debit = employerCost, memo = "بیمه سهم کارفرما و بیکاری", by = capability))
            if (!net.isZero) add(LineDraft(StandardAccounts.PAYROLL_PAYABLE, credit = net, memo = "خالص پرداختنی", by = capability))
            if (!insurance.isZero) add(LineDraft(StandardAccounts.INSURANCE_PAYABLE, credit = insurance, memo = "بیمه", by = capability))
            if (!tax.isZero) add(LineDraft(StandardAccounts.PAYROLL_TAX_PAYABLE, credit = tax, memo = "مالیات حقوق", by = capability))
            // Installments pay advances back; other deductions (meals, damages…) lower the salary cost.
            if (!advances.isZero) add(LineDraft(StandardAccounts.EMPLOYEE_ADVANCE, credit = advances, memo = "بازپرداخت مساعده و وام", by = capability))
            if (!deductions.isZero) add(LineDraft(StandardAccounts.SALARIES, credit = deductions, memo = "کسورات", by = capability))
        }
        // A month in which nobody earned anything (all absent) is approved without an accounting entry.
        val journal = if (lines.isEmpty()) null else ledger.post(ctx, capability, JournalDraft(run.to, run.scope, RUN, run.id, "حقوق دوره", lines))
        payroll.saveRun(run.copy(status = RunStatus.APPROVED, approvedBy = ctx.actor.userId, accrualJournalId = journal?.id))
        ctx.audit(AuditDraft("PAYROLL_APPROVE", "PAYROLL_RUN", run.id.value, "gross=${gross.rial};net=${net.rial}" + if (self) ";self_approved=only_approver" else ""))
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
        ctx.number(DocumentSeries.REVERSAL, cmd.date, cmd.commandId, reverses = DocumentSeries.PAYROLL to run.id)
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
        ctx.number(DocumentSeries.PAYMENT, cmd.date, paymentId)
        paymentId
    }

    fun reversePayment(c: ReverseSalaryPayment): CommandOutcome = bus.execute(ModuleId.PAYROLL, c) { cmd, ctx ->
        val payment = payroll.payment(cmd.paymentId) ?: throw DomainException(DomainError.NotFound("SALARY_PAYMENT"))
        requireRun(payment.runId, cmd.scope)
        ensure(!payment.reversed) { DomainError.InvalidState("SALARY_PAYMENT", "ALREADY_REVERSED") }
        treasury.reverseDocument(ctx, capability, PAYMENT, payment.id, cmd.date, cmd.reason)
        payroll.savePayment(payment.copy(reversed = true))
        ctx.number(DocumentSeries.REVERSAL, cmd.date, cmd.commandId, reverses = DocumentSeries.PAYMENT to payment.id)
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
        ctx.number(DocumentSeries.PAYMENT, cmd.date, id)
        id
    }

    /**
     * Everyone employed on at least one day of the month; someone hired or leaving during it is prorated. A salary
     * changed during the month is paid day by day: each salary for the days it was in force.
     */
    private fun payslips(scope: Scope.Branch, from: BusinessDate, to: BusinessDate, policy: StatutoryPolicy): List<Payslip> {
        val periodDays = (to.epochDay - from.epochDay + 1).toInt()
        val options = settings()
        return personnel.employees(scope).mapNotNull { e ->
            // Days employed here (a person moved from another branch is paid there until the move).
            val days = generateSequence(from) { it.plusDays(1) }.takeWhile { it <= to }.filter { e.employedOn(it) && e.branchOn(it) == scope }.toList()
            val employed = days.size
            if (employed == 0) return@mapNotNull null
            val records = personnel.attendance(e.id, from, to).filter { it.date in days }
            val partial = employed < periodDays
            val monthlyDays = days.filter { e.payTypeOn(it) == PayType.MONTHLY }.toSet()
            val last = days.last()
            // The rate of a month, for pricing overtime and absence: a day's wage × 30, an hour's × the standard month.
            val monthly = when (e.payTypeOn(last)) {
                PayType.MONTHLY -> e.salaryOn(last)
                PayType.DAILY -> Money.of(Math.multiplyExact(e.salaryOn(last).rial, 30L))
                PayType.HOURLY -> Money.of(Ratio.mulDiv(e.salaryOn(last).rial, policy.standardMonthlyMinutes.toLong(), 60))
            }
            val runs = e.salaryDays(days.first(), days.last())
            val base = if (monthlyDays.size == days.size) {
                // One salary all month: the usual rule. Several: each for its days, over the month (or the 30-day basis when partial).
                if (runs.size == 1) null else {
                    val divisor = if (partial) policy.prorationDays.toLong() else periodDays.toLong()
                    Money.of(minOf(runs.maxOf { it.first.rial }, runs.sumOf { (s, d) -> Ratio.mulDiv(s.rial, d.toLong(), divisor) }))
                }
            } else {
                // Daily and hourly pay: a day's wage for each day worked, an hour's for each hour worked (from attendance).
                val worked = records.associate { it.date to it.workedMinutes }
                val divisor = if (partial) policy.prorationDays.toLong() else periodDays.toLong()
                Money.of(days.sumOf { d ->
                    val rate = e.salaryOn(d).rial
                    when (e.payTypeOn(d)) {
                        PayType.MONTHLY -> Ratio.mulDiv(rate, 1, divisor)
                        PayType.DAILY -> if ((worked[d] ?: 0) > 0) rate else 0L
                        PayType.HOURLY -> Ratio.mulDiv(rate, (worked[d] ?: 0).toLong(), 60)
                    }
                })
            }
            val overtimeMinutes = records.sumOf { it.overtimeMinutes.toLong() }
            val overtimePay = when (options.overtimeMode) {
                OvertimeMode.STATUTORY -> null
                OvertimeMode.FIXED_HOURLY -> Money.of(Ratio.mulDiv(options.overtimeHourlyRate.rial, overtimeMinutes, 60))
                OvertimeMode.NONE -> Money.ZERO
            }
            // Leave on a day covers its absence; unpaid and sick leave are deducted like absence (insurance pays sick days).
            val leaves = elements?.leaves(e.id).orEmpty().filter { !it.cancelled && it.date in days }
            val leaveOn = leaves.groupBy { it.date }.mapValues { (_, l) -> l.sumOf { it.minutes } }
            // Absence is deducted only from days paid monthly (a daily or hourly wage pays only the time worked).
            val absent = records.filter { it.date in monthlyDays }.sumOf { maxOf(0, it.absentMinutes - (leaveOn[it.date] ?: 0)).toLong() } +
                leaves.filter { it.kind != Leave.Kind.ANNUAL && it.date in monthlyDays }.sumOf { it.minutes.toLong() }
            val baseAmount = base ?: if (partial) Money.of(minOf(monthly.rial, Ratio.mulDiv(monthly.rial, employed.toLong(), policy.prorationDays.toLong()))) else monthly
            val earnings = extraLines(e, scope, from, to, days, partial, employed, periodDays, monthly, baseAmount, records, leaves, policy, options)
            policy.calculate(e.id, monthly, absent, overtimeMinutes,
                payableDays = employed.takeIf { partial && monthlyDays.size == days.size }, base = base, overtimePay = overtimePay,
                lines = earnings.filter { it.kind == PayLine.Kind.EARNING } + advanceLines(e, to) + earnings.filter { it.kind == PayLine.Kind.DEDUCTION })
        }
    }

    /**
     * Pay elements, one-off lines, night and holiday premiums, annual leave of daily / hourly workers, eidi, severance
     * and unused leave on leaving. Rates: the month's rate over the standard month (an hour's pay).
     */
    private fun extraLines(
        e: Employee, scope: Scope.Branch, from: BusinessDate, to: BusinessDate, days: List<BusinessDate>, partial: Boolean, employed: Int, periodDays: Int,
        monthly: Money, base: Money, records: List<AttendanceRecord>, leaves: List<Leave>, policy: StatutoryPolicy, options: PayrollSettings,
    ): List<PayLine> = buildList {
        fun perMinute(minutes: Long, bp: Long = 10_000) = Money.of(Ratio.mulDiv(monthly.rial, Math.multiplyExact(minutes, bp), policy.standardMonthlyMinutes * 10_000L))
        val store = elements
        store?.elements()?.filter { it.appliesTo(e, to) }?.forEach { el ->
            val full = el.monthly(e, base, to)
            // Monthly amounts follow the days worked in a partial month, like the salary.
            val amount = if (partial && el.basis != PayElement.Basis.PERCENT_OF_BASE)
                Money.of(minOf(full.rial, Ratio.mulDiv(full.rial, employed.toLong(), policy.prorationDays.toLong()))) else full
            if (!amount.isZero) add(PayLine("ELEMENT:${el.id.value}", el.name, amount, el.kind, el.insurable, el.taxable))
        }
        store?.adjustments(scope, from)?.filter { it.employeeId == e.id }?.forEach {
            add(PayLine("ADJ:${it.id.value}", it.title, it.amount, it.kind, it.insurable, it.taxable))
        }
        if (options.nightPremiumBp > 0) {
            val night = records.sumOf { it.nightMinutes.toLong() }
            if (night > 0) add(PayLine("NIGHT", "شب‌کاری", perMinute(night, options.nightPremiumBp.toLong()), PayLine.Kind.EARNING, insurable = true, taxable = true))
        }
        if (options.holidayPremiumBp > 0) {
            val holiday = records.filter { isHoliday(scope, it.date) }.sumOf { (it.workedMinutes + it.overtimeMinutes).toLong() }
            if (holiday > 0) add(PayLine("HOLIDAY", "جمعه‌کاری و تعطیل‌کاری", perMinute(holiday, options.holidayPremiumBp.toLong()), PayLine.Kind.EARNING, insurable = true, taxable = true))
        }
        // Annual leave of someone paid by the day or hour: their wage for that time.
        val paidLeave = leaves.filter { it.kind == Leave.Kind.ANNUAL && e.payTypeOn(it.date) != PayType.MONTHLY }.sumOf { l ->
            val rate = e.salaryOn(l.date).rial
            if (e.payTypeOn(l.date) == PayType.DAILY) Ratio.mulDiv(rate, l.minutes.toLong(), options.workdayMinutes.toLong()) else Ratio.mulDiv(rate, l.minutes.toLong(), 60)
        }
        if (paidLeave > 0) add(PayLine("LEAVE_PAY", "مرخصی استحقاقی", Money.of(paidLeave), PayLine.Kind.EARNING, insurable = true, taxable = true))

        val year = JalaliCalendar.yearOf(to)
        val yearStart = JalaliCalendar.date(year, 1, 1)
        val yearEnd = JalaliCalendar.date(year, 12, JalaliCalendar.monthLength(year, 12))
        val yearDays = yearEnd.epochDay - yearStart.epochDay + 1
        val leaving = e.endDate != null && e.endDate >= from && e.endDate <= to
        // N days of wages = monthly × N ÷ 30 in one exact step (a truncated day rate times 60 lost up to 59 Rial).
        fun daysOfWage(days: Long): Long = Ratio.mulDiv(monthly.rial, days, 30)
        fun paidThisYear(code: String) = scopesOf(e).flatMap { payroll.runs(it) }.any { r ->
            r.status == RunStatus.APPROVED && JalaliCalendar.yearOf(r.to) == year && r.payslips.any { p -> p.employeeId == e.id && p.lines.any { it.code == code } }
        }
        val eidiMonth = JalaliCalendar.of(to).month == options.eidiMonth
        if (options.eidiEnabled && (eidiMonth || leaving) && !paidThisYear("EIDI:$year")) {
            // Days of salary for a full year (capped), for the days worked this year.
            val full = Money.of(daysOfWage(options.eidiDays.toLong())).let { if (options.eidiCap.rial > 0) minOf(it, options.eidiCap) else it }
            val worked = e.employedDays(yearStart, if (leaving) e.endDate!! else yearEnd).toLong()
            val eidi = Money.of(Ratio.mulDiv(full.rial, worked, yearDays))
            val exempt = minOf(eidi, Money.of(Ratio.mulDiv(options.eidiTaxExempt.rial, worked, yearDays)))
            if (!exempt.isZero) add(PayLine("EIDI:$year", "عیدی (معاف از مالیات)", exempt, PayLine.Kind.EARNING, insurable = false, taxable = false))
            if (eidi > exempt) add(PayLine("EIDI:$year", "عیدی", eidi - exempt, PayLine.Kind.EARNING, insurable = false, taxable = true))
        }
        if (options.sanavatEnabled) {
            val start = e.startDate
            val sanavatDays = when {
                options.sanavatYearly && eidiMonth && !leaving && !paidThisYear("SANAVAT:$year") -> e.employedDays(yearStart, yearEnd).toLong()
                // On leaving: the whole employment, or only this year's days when earlier years were paid yearly.
                leaving && start != null -> if (options.sanavatYearly) e.employedDays(yearStart, e.endDate!!).toLong() else e.endDate!!.epochDay - start.epochDay + 1
                else -> 0L
            }
            if (sanavatDays > 0) add(PayLine(if (leaving) "SANAVAT:EXIT" else "SANAVAT:$year", "سنوات", Money.of(Ratio.mulDiv(daysOfWage(options.sanavatDays.toLong()), sanavatDays, 365)),
                PayLine.Kind.EARNING, insurable = false, taxable = false))
        }
        if (leaving && options.leavePayoutOnExit && store != null) {
            val left = LeaveRules.balance(e, store.leaves(e.id), options, e.endDate!!).left
            if (left > 0) add(PayLine("LEAVE_PAYOUT", "مرخصی ذخیره (بازخرید)", perMinute(left), PayLine.Kind.EARNING, insurable = true, taxable = true))
        }
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
        const val ADVANCE = "EMPLOYEE_ADVANCE"
        const val ADVANCE_REPAYMENT = "EMPLOYEE_ADVANCE_REPAYMENT"
    }
}
