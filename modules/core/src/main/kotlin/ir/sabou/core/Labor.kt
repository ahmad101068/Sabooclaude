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
import ir.sabou.sales.PriceList

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

data class TimeRow(val employee: Employee, val time: DayTime)

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
        val prices = PriceList(core.menuPrices)
        return (0L..6L).map { offset ->
            val date = start.plusDays(offset)
            val policy = runCatching { core.policyRegistry.forPeriod(date, date) }.getOrNull()
            val day = shifts[date].orEmpty().sortedBy { it.startMinute }
            val cost = Money.sum(day.mapNotNull { s -> employees[s.employeeId]?.let { core.shifts.cost(it, s.minutes, policy) } })
            val budget = core.budgetStore.entries().filter { it.scope == branch && it.account in LABOR_ACCOUNTS && it.from <= date && it.to >= date }
                .sumOf { Ratio.mulDiv(it.amount.rial, 1, it.to.epochDay - it.from.epochDay + 1) }
            val sales = runCatching { core.prep.forecast(branch, date) }.getOrDefault(emptyList())
                .sumOf { f -> prices.unitPriceOn(f.item.id, branch, date)?.let { Ratio.mulDiv(it.rial, f.portions.micros, ir.sabou.kernel.Quantity.SCALE) } ?: 0L }
            LaborDay(date, day.map { ShiftView(it, employees[it.employeeId]?.name.orEmpty()) }, day.sumOf { it.minutes }, cost, Money.of(budget), Money.of(sales))
        }
    }

    /** Planned against clocked time for one day, per employee. */
    fun day(branch: Scope.Branch, date: BusinessDate): List<TimeRow> {
        actor(Permission.ATTENDANCE_RECORD, Permission.SHIFT_MANAGE, Permission.PERSONNEL_VIEW).require(branch)
        val employees = core.personnel.employees(branch).associateBy { it.id }
        return core.shifts.day(branch, date).mapNotNull { t -> employees[t.employeeId]?.let { TimeRow(it, t) } }.sortedBy { it.employee.name }
    }

    /** Who can clock in on this phone: employed today, with a clock PIN. Names only. */
    fun clockList(branch: Scope.Branch, date: BusinessDate): List<Pair<GlobalId, String>> {
        actor(Permission.TIME_CLOCK).require(branch)
        return core.personnel.employees(branch).filter { it.employedDays(date, date) == 1 && core.shiftStore.clockPin(it.id) != null }
            .sortedWith(compareBy(Comparator { a: String, b: String -> Fa.compareAlphabetical(a, b) }) { it.name }).map { it.id to it.name }
    }

    /** The employee's punches today, for the clock screen («آمدید ۸:۰۵»). */
    fun punchesToday(branch: Scope.Branch, date: BusinessDate, employeeId: GlobalId): List<ir.sabou.payroll.Punch> {
        actor(Permission.TIME_CLOCK).require(branch)
        return core.shiftStore.punches(branch, date, date).filter { it.employeeId == employeeId }.sortedBy { it.minute }
    }
}
