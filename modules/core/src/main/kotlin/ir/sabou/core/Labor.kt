package ir.sabou.core

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Ratio
import ir.sabou.kernel.Scope
import ir.sabou.payroll.DayTime
import ir.sabou.payroll.Employee
import ir.sabou.payroll.Shift
import ir.sabou.platform.Actor
import ir.sabou.platform.Permission

data class ShiftView(val shift: Shift, val employee: String)

/**
 * One day of the schedule (ADR-0038): the shifts, their minutes and cost, the labour budget for the day (salaries
 * and employer insurance budgets pro-rated) and the expected sales (forecast × menu price), so labour % is seen
 * before the day, not after the payroll.
 */
data class LaborDay(
    val date: BusinessDate,
    val shifts: List<ShiftView>,
    val minutes: Int,
    val cost: Money,
    val budget: Money,
    val expectedSales: Money,
) {
    val laborBp: Long? get() = if (expectedSales.rial > 0) Ratio.mulDiv(cost.rial, 10_000, expectedSales.rial) else null
}

/** One person's day: planned against clocked, their punches, and whether the day was typed by hand. */
data class TimeRow(val employee: Employee, val time: DayTime, val punches: List<ir.sabou.payroll.Punch> = emptyList(), val manual: Boolean = false)

/** A staff document with its files (those of every earlier version it corrected too) and how many times it was corrected. */
data class StaffDocumentView(val document: ir.sabou.payroll.StaffDocument, val files: List<ir.sabou.platform.Attachment>, val corrections: Int = 0)

/** An employee's document that needs attention (ADR-0039). */
data class DocumentIssueView(val employee: Employee, val issue: ir.sabou.payroll.DocumentIssue)

class Labor internal constructor(private val core: SabouCore) {
    private fun actor(vararg anyOf: Permission): Actor {
        val a = core.session.currentActor() ?: throw DomainException(DomainError.AuthenticationRequired)
        if (anyOf.none { a.role.allows(it) }) throw DomainException(DomainError.PermissionDenied(anyOf.first().name))
        return a
    }

    private fun Actor.require(scope: Scope) {
        if (!canAccess(scope)) throw DomainException(DomainError.ScopeDenied(ir.sabou.platform.CommandContext.scopeLabel(scope)))
    }

    fun week(branch: Scope.Branch, start: BusinessDate): List<LaborDay> {
        actor(Permission.SHIFT_MANAGE, Permission.PERSONNEL_VIEW).require(branch)
        val employees = core.personnel.employees(branch).associateBy { it.id }
        val shifts = core.shiftStore.shifts(branch, start, start.plusDays(6)).groupBy { it.date }
        // Expected sales from the sales forecast (weekday pattern × trend × special days).
        val expected = runCatching { core.forecasts.sales(branch, start, 7).days.associate { it.date to it.expected } }.getOrDefault(emptyMap())
        return (0L..6L).map { offset ->
            val date = start.plusDays(offset)
            val policy = runCatching { core.policyRegistry.forPeriod(date, date) }.getOrNull()
            val day = shifts[date].orEmpty().sortedBy { it.startMinute }
            val deduct = core.payrollSettings.current().deductPlannedBreaks
            val cost = Money.sum(day.mapNotNull { s -> employees[s.employeeId]?.let { core.shifts.cost(it, s.paidMinutes(deduct), policy, date) } })
            val budget = core.budgetStore.entries().filter { it.scope == branch && it.account in LABOR_ACCOUNTS && it.from <= date && it.to >= date }
                .sumOf { Ratio.mulDiv(it.amount.rial, 1, it.to.epochDay - it.from.epochDay + 1) }
            val sales = expected[date] ?: 0L
            LaborDay(date, day.map { ShiftView(it, employees[it.employeeId]?.name.orEmpty()) }, day.sumOf { it.paidMinutes(deduct) }, cost, Money.of(budget), Money.of(sales))
        }
    }

    /** Planned against clocked time for one day, per employee. */
    fun day(branch: Scope.Branch, date: BusinessDate): List<TimeRow> {
        actor(Permission.ATTENDANCE_RECORD, Permission.SHIFT_MANAGE, Permission.PERSONNEL_VIEW).require(branch)
        val employees = core.personnel.employees(branch).associateBy { it.id }
        val punches = core.shiftStore.punches(branch, date, date).groupBy { it.employeeId }
        return core.shifts.day(branch, date).mapNotNull { t ->
            employees[t.employeeId]?.let {
                TimeRow(it.copy(monthlySalary = Money.ZERO, salaryChanges = emptyList(), nationalId = ""), t,
                    ir.sabou.payroll.Punch.effective(punches[it.id].orEmpty()),
                    core.personnel.attendanceOn(it.id, date)?.source == ir.sabou.payroll.AttendanceRecord.Source.MANUAL)
            }
        }.sortedBy { it.employee.name }
    }

    /** Who can clock in on this phone: employed today, with a clock PIN. Names only. */
    fun clockList(branch: Scope.Branch, date: BusinessDate): List<Pair<GlobalId, String>> {
        actor(Permission.TIME_CLOCK).require(branch)
        return core.personnel.employees(branch).filter { it.employedDays(date, date) == 1 && core.shiftStore.clockPin(it.id) != null }
            .sortedWith(compareBy(Comparator { a: String, b: String -> Fa.compareAlphabetical(a, b) }) { it.name }).map { it.id to it.name }
    }

    /**
     * The employee's punches of the work day now, for the clock screen («آمدید ۸:۰۵»): after midnight, a night
     * shift still open from yesterday.
     */
    fun punchesToday(branch: Scope.Branch, date: BusinessDate, employeeId: GlobalId, minute: Int): List<ir.sabou.payroll.Punch> {
        actor(Permission.TIME_CLOCK).require(branch)
        val (day, _) = core.shifts.workDay(branch, employeeId, date, minute)
        return ir.sabou.payroll.Punch.effective(core.shiftStore.punches(branch, day, day).filter { it.employeeId == employeeId })
    }

    fun employee(employeeId: GlobalId): Employee {
        val a = actor(Permission.PERSONNEL_MANAGE, Permission.PERSONNEL_VIEW)
        val employee = core.personnel.employee(employeeId) ?: throw DomainException(DomainError.NotFound("EMPLOYEE"))
        a.require(employee.scope)
        return employee
    }

    /** An employee's documents in use (renewed ones included as history), newest first, with their files. */
    fun documents(employeeId: GlobalId): List<StaffDocumentView> {
        val a = actor(Permission.PERSONNEL_MANAGE, Permission.PERSONNEL_VIEW)
        val employee = core.personnel.employee(employeeId) ?: throw DomainException(DomainError.NotFound("EMPLOYEE"))
        a.require(employee.scope)
        val all = core.staffDocumentStore.of(employeeId)
        // Corrected and removed versions stay in the history (and the audit), not in the list.
        return ir.sabou.payroll.StaffDocumentRules.live(all).sortedByDescending { it.recordedAtEpochMillis }.map { d ->
            val versions = ir.sabou.payroll.StaffDocumentRules.versions(d, all)
            StaffDocumentView(d, versions.flatMap { core.attachments.of(ir.sabou.payroll.StaffDocumentOperations.OWNER, it.id) }, versions.size - 1)
        }
    }

    /** Missing health cards / contracts and documents expired or expiring within 30 days, for the branch's staff. */
    fun documentIssues(branch: Scope.Branch, today: BusinessDate): List<DocumentIssueView> {
        actor(Permission.PERSONNEL_MANAGE, Permission.PERSONNEL_VIEW).require(branch)
        val docs = core.staffDocumentStore.all(branch)
        return core.personnel.employees(branch).flatMap { e ->
            ir.sabou.payroll.StaffDocumentRules.issues(e, docs, today).map { DocumentIssueView(e, it) }
        }.sortedWith(compareBy({ !it.issue.expired && !it.issue.missing }, { it.issue.daysLeft ?: Long.MIN_VALUE }))
    }
}
