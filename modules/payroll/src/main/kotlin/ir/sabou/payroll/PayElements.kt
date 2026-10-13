package ir.sabou.payroll

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.JalaliCalendar
import ir.sabou.kernel.Money
import ir.sabou.kernel.Ratio
import ir.sabou.kernel.Scope
import ir.sabou.platform.Command
import ir.sabou.platform.NoDocument
import ir.sabou.platform.Permission

/**
 * A pay element (عامل حقوقی) the restaurant defines once — housing allowance, food voucher, child allowance, seniority
 * pay, a monthly deduction — with whether it is insurable and taxable. The yearly legal amounts are the owner's to
 * enter (after professional review); nothing is assumed. Paid every month to everyone, or to the people chosen.
 */
data class PayElement(
    val id: GlobalId,
    val name: String,
    val kind: PayLine.Kind,
    val basis: Basis,
    /** Rial per month (FIXED), per child, per year of service; basis points of the base salary for PERCENT_OF_BASE. */
    val amount: Long,
    val insurable: Boolean,
    val taxable: Boolean,
    /** Paid in months ending on or after this date; null = always. */
    val from: BusinessDate? = null,
    val active: Boolean = true,
    /** Empty = everyone. */
    val employees: Set<GlobalId> = emptySet(),
) : java.io.Serializable {
    enum class Basis { FIXED, PER_CHILD, PER_SERVICE_YEAR, PERCENT_OF_BASE }

    /** The month's amount for one person, before prorating. */
    fun monthly(e: Employee, base: Money, on: BusinessDate): Money = when (basis) {
        Basis.FIXED -> Money.of(amount)
        Basis.PER_CHILD -> Money.of(Math.multiplyExact(amount, e.profile.children.toLong()))
        Basis.PER_SERVICE_YEAR -> Money.of(Math.multiplyExact(amount, serviceYears(e, on).toLong()))
        Basis.PERCENT_OF_BASE -> Money.of(Ratio.mulDiv(base.rial, amount, 10_000))
    }

    fun appliesTo(e: Employee, to: BusinessDate) = active && (from == null || from <= to) && (employees.isEmpty() || e.id in employees)

    companion object {
        /** Whole years of service up to [on], counting earlier employments. */
        fun serviceYears(e: Employee, on: BusinessDate): Int {
            val start = e.startDate ?: return 0
            val days = e.pastEmployments.sumOf { p -> p.start?.let { (p.end.epochDay - it.epochDay + 1) } ?: 0L } + (on.epochDay - start.epochDay + 1)
            return (days / 365).toInt()
        }
    }
}

/**
 * A one-off line on one month's payslip of one person: a bonus (پاداش), a commission, a fine, a meal deduction.
 * [periodFrom] is the first day of the payroll month it belongs to.
 */
data class PayAdjustment(
    val id: GlobalId,
    val scope: Scope.Branch,
    val employeeId: GlobalId,
    val periodFrom: BusinessDate,
    val title: String,
    val kind: PayLine.Kind,
    val amount: Money,
    val insurable: Boolean,
    val taxable: Boolean,
) : java.io.Serializable

interface PayElementStore {
    fun elements(): List<PayElement>
    fun saveElement(element: PayElement)
    fun adjustments(scope: Scope.Branch, periodFrom: BusinessDate): List<PayAdjustment>
    fun adjustment(id: GlobalId): PayAdjustment?
    fun saveAdjustment(adjustment: PayAdjustment)
    fun deleteAdjustment(id: GlobalId)
    fun leaves(employeeId: GlobalId): List<Leave>
    fun leave(id: GlobalId): Leave?
    fun saveLeave(leave: Leave)
}

@NoDocument
data class SavePayElement(override val commandId: GlobalId, val element: PayElement) : Command {
    override val requiredPermission = Permission.PAYROLL_APPROVE
    override val scope: Scope = Scope.Organization
    override fun fingerprint() = element.toString()
}

/**
 * Adds a one-off line to the payslips of [employeeIds] for the month starting [periodFrom]: a fixed [amount] each, or
 * [percentOfSalary] basis points of each one's salary (a bonus of half a month's salary = 5,000).
 */
@NoDocument
data class AddPayAdjustment(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val employeeIds: List<GlobalId>,
    val periodFrom: BusinessDate,
    val title: String,
    val kind: PayLine.Kind,
    val amount: Money?,
    val percentOfSalary: Int?,
    val insurable: Boolean,
    val taxable: Boolean,
) : Command {
    override val requiredPermission = Permission.PAYROLL_CALCULATE
    override fun fingerprint() = "$scope|$employeeIds|${periodFrom.epochDay}|$title|$kind|${amount?.rial}|$percentOfSalary|$insurable|$taxable"
}

@NoDocument
data class RemovePayAdjustment(override val commandId: GlobalId, override val scope: Scope.Branch, val adjustmentId: GlobalId) : Command {
    override val requiredPermission = Permission.PAYROLL_CALCULATE
    override fun fingerprint() = "$scope|$adjustmentId"
}

/** Leave: annual (paid, from the balance), sick (unpaid by the employer; insurance pays), unpaid. */
data class Leave(
    val id: GlobalId,
    val scope: Scope.Branch,
    val employeeId: GlobalId,
    val date: BusinessDate,
    val kind: Kind,
    /** A whole day is the workday of the settings; an hourly leave is shorter. */
    val minutes: Int,
    val note: String,
    val cancelled: Boolean = false,
) : java.io.Serializable {
    enum class Kind { ANNUAL, SICK, UNPAID }
}

/** Leave from [from] to [to] (each day [minutes] long; null = a whole workday), or for some hours of one day. */
@NoDocument
data class RecordLeave(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val employeeId: GlobalId,
    val from: BusinessDate,
    val to: BusinessDate,
    val kind: Leave.Kind,
    val minutes: Int?,
    val note: String,
) : Command {
    override val requiredPermission = Permission.ATTENDANCE_RECORD
    override fun fingerprint() = "$scope|$employeeId|${from.epochDay}|${to.epochDay}|$kind|$minutes|$note"
}

@NoDocument
data class CancelLeave(override val commandId: GlobalId, override val scope: Scope.Branch, val leaveId: GlobalId) : Command {
    override val requiredPermission = Permission.ATTENDANCE_RECORD
    override fun fingerprint() = "$scope|$leaveId"
}

/** Annual leave of one person in one Jalali year, in minutes. */
data class LeaveBalance(val year: Int, val carried: Long, val earned: Long, val used: Long) {
    val left: Long get() = carried + earned - used
}

object LeaveRules {
    /**
     * Year by year: leave is earned for each employed day (the yearly entitlement × days ÷ days of the year); what is
     * left at the end of a year carries over up to the cap, the rest lapses.
     */
    fun balances(e: Employee, leaves: List<Leave>, settings: PayrollSettings, untilYear: Int): List<LeaveBalance> {
        val firstYear = (listOfNotNull(e.startDate) + e.pastEmployments.mapNotNull { it.start }).minOfOrNull { JalaliCalendar.yearOf(it) } ?: untilYear
        val perYear = settings.leaveDaysPerYear.toLong() * settings.workdayMinutes
        val cap = settings.leaveCarryDays.toLong() * settings.workdayMinutes
        var carried = 0L
        return (firstYear..untilYear).map { y ->
            val start = JalaliCalendar.date(y, 1, 1)
            val end = JalaliCalendar.date(y, 12, JalaliCalendar.monthLength(y, 12))
            val yearDays = end.epochDay - start.epochDay + 1
            val earned = perYear * e.employedDays(start, end) / yearDays
            val used = leaves.filter { !it.cancelled && it.kind == Leave.Kind.ANNUAL && it.date >= start && it.date <= end }.sumOf { it.minutes.toLong() }
            LeaveBalance(y, carried, earned, used).also { carried = minOf(cap, maxOf(0L, it.left)) }
        }
    }

    /** The balance on [date]: this year's so far counted as earned for the whole year, as Iranian practice allows. */
    fun balance(e: Employee, leaves: List<Leave>, settings: PayrollSettings, date: BusinessDate): LeaveBalance =
        balances(e, leaves, settings, JalaliCalendar.yearOf(date)).last()
}
