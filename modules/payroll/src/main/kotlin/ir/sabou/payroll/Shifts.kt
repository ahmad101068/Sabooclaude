package ir.sabou.payroll

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.DomainError
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
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
import ir.sabou.platform.PinHasher

/**
 * A planned shift (ADR-0038). Minutes are from midnight of [date]; [endMinute] may pass midnight (up to 36:00) for a
 * shift that ends the next morning.
 */
data class Shift(
    val id: GlobalId,
    val scope: Scope.Branch,
    val employeeId: GlobalId,
    val date: BusinessDate,
    val startMinute: Int,
    val endMinute: Int,
    val breakMinutes: Int,
    /** Where (کباب‌پز، صندوق، سالن). */
    val station: String,
) {
    val minutes: Int get() = endMinute - startMinute - breakMinutes
}

/** One clock-in or clock-out with the employee's own PIN. [minute] is minutes from midnight of [date]. */
data class Punch(val id: GlobalId, val scope: Scope.Branch, val employeeId: GlobalId, val date: BusinessDate, val minute: Int, val kind: Kind, val atEpochMillis: Long) {
    enum class Kind { IN, OUT }
}

interface ShiftStore {
    fun shift(id: GlobalId): Shift?
    fun shifts(scope: Scope.Branch, from: BusinessDate, to: BusinessDate): List<Shift>
    fun saveShift(shift: Shift)
    fun deleteShift(id: GlobalId)
    fun punches(scope: Scope.Branch, from: BusinessDate, to: BusinessDate): List<Punch>
    fun savePunch(punch: Punch)
    /** The employee's clock PIN (PBKDF2), or null when they have none. */
    fun clockPin(employeeId: GlobalId): String?
    fun saveClockPin(employeeId: GlobalId, hash: String?)
}

/** What one day's punches add up to against the plan. Minutes. */
data class DayTime(val employeeId: GlobalId, val date: BusinessDate, val planned: Int, val worked: Int, val late: Int, val openPunch: Boolean) {
    /** Beyond the plan (only when there was a plan). */
    val overtime: Int get() = if (planned > 0) maxOf(0, worked - planned) else 0
    /** Short of the plan. */
    val absent: Int get() = maxOf(0, planned - worked)
}

object TimeMath {
    /**
     * Pairs IN→OUT in order; an unpaired IN at the end is an open punch (still at work, or forgot to clock out) and
     * counts nothing. Worked time is the sum of the pairs minus the planned breaks.
     */
    fun day(employeeId: GlobalId, date: BusinessDate, shifts: List<Shift>, punches: List<Punch>): DayTime {
        val sorted = punches.sortedBy { it.minute }
        var worked = 0; var pairs = 0; var open: Punch? = null
        for (p in sorted) {
            when (p.kind) {
                Punch.Kind.IN -> if (open == null) open = p
                Punch.Kind.OUT -> open?.let { worked += p.minute - it.minute; pairs++; open = null }
            }
        }
        val planned = shifts.sumOf { it.minutes }
        // A planned break is taken off only when the person did not clock out for it.
        val breaks = if (pairs <= shifts.size) shifts.sumOf { it.breakMinutes } else 0
        val firstIn = sorted.firstOrNull { it.kind == Punch.Kind.IN }?.minute
        val start = shifts.minOfOrNull { it.startMinute }
        val late = if (firstIn != null && start != null) maxOf(0, firstIn - start - LATE_GRACE) else 0
        return DayTime(employeeId, date, planned, maxOf(0, worked - if (worked > breaks) breaks else 0), late, open != null)
    }

    /** Minutes of lateness ignored (traffic, changing clothes). */
    const val LATE_GRACE = 10
}

@NoDocument
data class SaveShift(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val shiftId: GlobalId?,
    val employeeId: GlobalId,
    val date: BusinessDate,
    val startMinute: Int,
    val endMinute: Int,
    val breakMinutes: Int,
    val station: String,
) : Command {
    override val requiredPermission = Permission.SHIFT_MANAGE
    override fun fingerprint() = "$scope|$shiftId|$employeeId|${date.epochDay}|$startMinute|$endMinute|$breakMinutes|$station"
}

@NoDocument
data class DeleteShift(override val commandId: GlobalId, override val scope: Scope.Branch, val shiftId: GlobalId) : Command {
    override val requiredPermission = Permission.SHIFT_MANAGE
    override fun fingerprint() = "$scope|$shiftId"
}

/** Copies the week starting [fromWeek] to the week starting [toWeek] (days without shifts in the target only). */
@NoDocument
data class CopyWeek(override val commandId: GlobalId, override val scope: Scope.Branch, val fromWeek: BusinessDate, val toWeek: BusinessDate) : Command {
    override val requiredPermission = Permission.SHIFT_MANAGE
    override fun fingerprint() = "$scope|${fromWeek.epochDay}|${toWeek.epochDay}"
}

/** Sets (4–6 digits) or removes (null) an employee's clock PIN. */
@NoDocument
data class SetClockPin(override val commandId: GlobalId, override val scope: Scope.Branch, val employeeId: GlobalId, val pin: String?) : Command {
    override val requiredPermission = Permission.PERSONNEL_MANAGE
    // The PIN itself never enters the fingerprint (idempotency records are kept): a slow, salted digest of it does.
    override fun fingerprint() = "$scope|$employeeId|" + (pin?.let { ir.sabou.platform.PinHasher.fingerprint(it.toCharArray(), commandId.value) } ?: "none")
}

/**
 * An employee clocks in or out on the shared phone with their own PIN. [date] / [minute] are the phone's local time;
 * the recording time is kept as well.
 */
@NoDocument
data class ClockPunch(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val employeeId: GlobalId,
    val pin: String,
    val date: BusinessDate,
    val minute: Int,
) : Command {
    override val requiredPermission = Permission.TIME_CLOCK
    override fun fingerprint() = "$scope|$employeeId|${date.epochDay}|$minute|" + ir.sabou.platform.PinHasher.fingerprint(pin.toCharArray(), commandId.value)
}

/** Turns a day's punches into attendance (worked, overtime, absence) through the ordinary attendance record. */
@NoDocument
data class ConfirmDayTime(override val commandId: GlobalId, override val scope: Scope.Branch, val date: BusinessDate) : Command {
    override val requiredPermission = Permission.ATTENDANCE_RECORD
    override fun fingerprint() = "$scope|${date.epochDay}"
}

class ShiftOperations(
    private val bus: CommandBus,
    private val personnel: PersonnelStore,
    private val shifts: ShiftStore,
    private val payroll: PayrollOperations,
) {
    private val failures = HashMap<GlobalId, Pair<Int, Long>>()

    fun save(c: SaveShift): CommandOutcome = bus.execute(ModuleId.PAYROLL, c) { cmd, ctx ->
        val employee = employee(cmd.employeeId, cmd.scope)
        ensure(employee.employedDays(cmd.date, cmd.date) == 1) { DomainError.InvalidState("EMPLOYEE", "NOT_EMPLOYED_ON_DATE") }
        ensure(cmd.startMinute in 0 until 1_440 && cmd.endMinute - cmd.startMinute in 30..16 * 60 && cmd.endMinute <= 36 * 60) {
            DomainError.InvalidInput("time", "شیفت باید بین نیم ساعت و ۱۶ ساعت باشد.")
        }
        ensure(cmd.breakMinutes in 0..(cmd.endMinute - cmd.startMinute) / 2 && cmd.station.length <= 40) { DomainError.InvalidInput("break", "استراحت بیش از نیمی از شیفت است.") }
        val existing = cmd.shiftId?.let { shifts.shift(it) ?: throw DomainException(DomainError.NotFound("SHIFT")) }
        ensure(existing == null || existing.scope == cmd.scope) { DomainError.InvalidInput("scope", "این شیفت متعلق به این شعبه نیست.") }
        // The same person cannot be in two places at once.
        val clash = shifts.shifts(cmd.scope, cmd.date.plusDays(-1), cmd.date.plusDays(1)).filter { it.employeeId == employee.id && it.id != existing?.id }.any { s ->
            val offset = ((s.date.epochDay - cmd.date.epochDay) * 1_440).toInt()
            s.startMinute + offset < cmd.endMinute && cmd.startMinute < s.endMinute + offset
        }
        ensure(!clash) { DomainError.InvalidState("SHIFT", "OVERLAP") }
        val shift = Shift(existing?.id ?: GlobalId.new(), cmd.scope, employee.id, cmd.date, cmd.startMinute, cmd.endMinute, cmd.breakMinutes, cmd.station.trim())
        shifts.saveShift(shift)
        ctx.audit(AuditDraft(if (existing == null) "SHIFT_CREATE" else "SHIFT_UPDATE", "SHIFT", shift.id.value, "emp=${employee.id};day=${cmd.date.epochDay};${cmd.startMinute}-${cmd.endMinute}"))
        shift.id
    }

    fun delete(c: DeleteShift): CommandOutcome = bus.execute(ModuleId.PAYROLL, c) { cmd, ctx ->
        val shift = shifts.shift(cmd.shiftId) ?: throw DomainException(DomainError.NotFound("SHIFT"))
        ensure(shift.scope == cmd.scope) { DomainError.InvalidInput("scope", "این شیفت متعلق به این شعبه نیست.") }
        shifts.deleteShift(shift.id)
        ctx.audit(AuditDraft("SHIFT_DELETE", "SHIFT", shift.id.value, "emp=${shift.employeeId};day=${shift.date.epochDay}"))
        shift.id
    }

    fun copyWeek(c: CopyWeek): CommandOutcome = bus.execute(ModuleId.PAYROLL, c) { cmd, ctx ->
        ensure((cmd.toWeek.epochDay - cmd.fromWeek.epochDay) % 7 == 0L && cmd.toWeek != cmd.fromWeek) { DomainError.InvalidInput("week", "هفته‌ی مقصد باید هم‌روز هفته‌ی مبدأ باشد.") }
        val shift = cmd.toWeek.epochDay - cmd.fromWeek.epochDay
        val busy = shifts.shifts(cmd.scope, cmd.toWeek, cmd.toWeek.plusDays(6)).map { it.date }.toSet()
        val copied = shifts.shifts(cmd.scope, cmd.fromWeek, cmd.fromWeek.plusDays(6))
            .filter { it.date.plusDays(shift) !in busy }
            .filter { s -> personnel.employee(s.employeeId)?.employedDays(s.date.plusDays(shift), s.date.plusDays(shift)) == 1 }
            .map { it.copy(id = GlobalId.new(), date = it.date.plusDays(shift)) }
        copied.forEach(shifts::saveShift)
        ctx.audit(AuditDraft("SHIFT_COPY_WEEK", "SHIFT", cmd.toWeek.epochDay.toString(), "from=${cmd.fromWeek.epochDay};count=${copied.size}"))
        GlobalId.new()
    }

    fun setPin(c: SetClockPin): CommandOutcome = bus.execute(ModuleId.PAYROLL, c) { cmd, ctx ->
        val employee = employee(cmd.employeeId, cmd.scope)
        val pin = cmd.pin?.let(ir.sabou.kernel.NationalId::normalize)
        ensure(pin == null || (pin.matches(Regex("\\d{4,6}")) && pin.toSet().size > 1)) { DomainError.InvalidInput("pin", "رمز ساعت‌زنی ۴ تا ۶ رقم است و همه‌ی رقم‌ها یکی نباشند.") }
        shifts.saveClockPin(employee.id, pin?.let { PinHasher.hash(it.toCharArray()) })
        ctx.audit(AuditDraft(if (pin == null) "CLOCK_PIN_REMOVE" else "CLOCK_PIN_SET", "EMPLOYEE", employee.id.value, ""))
        employee.id
    }

    fun punch(c: ClockPunch): CommandOutcome = bus.execute(ModuleId.PAYROLL, c) { cmd, ctx ->
        val employee = employee(cmd.employeeId, cmd.scope)
        ensure(employee.employedDays(cmd.date, cmd.date) == 1) { DomainError.InvalidState("EMPLOYEE", "NOT_EMPLOYED_ON_DATE") }
        ensure(cmd.minute in 0 until 36 * 60) { DomainError.InvalidInput("time", "زمان معتبر نیست.") }
        // The phone's date must be today's (within a day of the clock) so a punch cannot be back-dated.
        val clockDay = Math.floorDiv(ctx.nowEpochMillis, 86_400_000L)
        ensure(kotlin.math.abs(cmd.date.epochDay - clockDay) <= 1) { DomainError.InvalidInput("date", "تاریخ گوشی درست نیست.") }
        val (fails, until) = failures[employee.id] ?: (0 to 0L)
        ensure(ctx.nowEpochMillis >= until) { DomainError.InvalidState("CLOCK_PIN", "LOCKED:${(until - ctx.nowEpochMillis + 999) / 1_000}") }
        val hash = shifts.clockPin(employee.id) ?: throw DomainException(DomainError.InvalidState("CLOCK_PIN", "NOT_SET"))
        if (!PinHasher.verify(ir.sabou.kernel.NationalId.normalize(cmd.pin).toCharArray(), hash)) {
            val next = fails + 1
            failures[employee.id] = next to if (next >= MAX_FAILURES) ctx.nowEpochMillis + LOCK_MILLIS else 0L
            throw DomainException(DomainError.InvalidInput("pin", "رمز درست نیست."))
        }
        failures.remove(employee.id)
        val today = shifts.punches(cmd.scope, cmd.date, cmd.date).filter { it.employeeId == employee.id }.sortedBy { it.minute }
        ensure(today.none { kotlin.math.abs(it.minute - cmd.minute) < 1 }) { DomainError.InvalidState("PUNCH", "DUPLICATE") }
        val kind = if (today.lastOrNull()?.kind == Punch.Kind.IN) Punch.Kind.OUT else Punch.Kind.IN
        val punch = Punch(GlobalId.new(), cmd.scope, employee.id, cmd.date, cmd.minute, kind, ctx.nowEpochMillis)
        shifts.savePunch(punch)
        ctx.audit(AuditDraft("CLOCK_${kind.name}", "EMPLOYEE", employee.id.value, "day=${cmd.date.epochDay};minute=${cmd.minute}"))
        punch.id
    }

    /** Each employee's planned and worked time on [date] (planned shifts, punches). */
    fun day(scope: Scope.Branch, date: BusinessDate): List<DayTime> {
        val planned = shifts.shifts(scope, date, date).groupBy { it.employeeId }
        val punched = shifts.punches(scope, date, date).groupBy { it.employeeId }
        return (planned.keys + punched.keys).map { TimeMath.day(it, date, planned[it].orEmpty(), punched[it].orEmpty()) }
    }

    /** Every employee's day in one transaction, through the ordinary attendance rules (employment, approved periods). */
    fun confirmDay(c: ConfirmDayTime): CommandOutcome = bus.execute(ModuleId.PAYROLL, c) { cmd, ctx ->
        val times = day(cmd.scope, cmd.date)
        ensure(times.isNotEmpty()) { DomainError.InvalidState("PUNCH", "NOTHING") }
        ensure(times.none { it.openPunch }) { DomainError.InvalidState("PUNCH", "OPEN:${times.count { it.openPunch }}") }
        times.forEach { t ->
            val regular = if (t.planned > 0) minOf(t.worked, t.planned) else t.worked
            payroll.applyAttendance(ctx, cmd.scope, t.employeeId, cmd.date, regular, t.overtime, t.absent)
        }
        ctx.audit(AuditDraft("DAY_TIME_CONFIRM", "ATTENDANCE", cmd.date.epochDay.toString(), "employees=${times.size}"))
        GlobalId.new()
    }

    /**
     * The cost of [minutes] of [employee]'s time: monthly salary plus the employer's insurance share, over the
     * standard month of the policy in force (or 192 hours, 44 a week, when none is configured).
     */
    fun cost(employee: Employee, minutes: Int, policy: StatutoryPolicy?): Money {
        val monthly = employee.monthlySalary.rial + (policy?.let { Ratio.mulDiv(employee.monthlySalary.rial, it.employerInsuranceBp.toLong(), 10_000) } ?: 0L)
        return Money.of(Ratio.mulDiv(monthly, minutes.toLong(), (policy?.standardMonthlyMinutes ?: DEFAULT_MONTH_MINUTES).toLong()))
    }

    private fun employee(id: GlobalId, scope: Scope.Branch): Employee {
        val e = personnel.employee(id) ?: throw DomainException(DomainError.NotFound("EMPLOYEE"))
        ensure(e.scope == scope) { DomainError.InvalidInput("scope", "این کارمند متعلق به این شعبه نیست.") }
        return e
    }

    companion object {
        const val MAX_FAILURES = 5
        const val LOCK_MILLIS = 5 * 60_000L
        const val DEFAULT_MONTH_MINUTES = 192 * 60
    }
}
