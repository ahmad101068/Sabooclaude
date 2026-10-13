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
    /** Paid minutes: with breaks not deducted (the default), the whole shift. */
    fun paidMinutes(deductBreaks: Boolean): Int = if (deductBreaks) minutes else endMinute - startMinute
}

/**
 * One clock-in or clock-out. [date] is the work day and [minute] counts from its midnight: an OUT after midnight
 * of a night shift is 24:00 + the time. Punches are never changed: a manager adds a missing one ([manualBy],
 * [reason]) or cancels a wrong one by a record that [voids] it.
 */
data class Punch(
    val id: GlobalId,
    val scope: Scope.Branch,
    val employeeId: GlobalId,
    val date: BusinessDate,
    val minute: Int,
    val kind: Kind,
    val atEpochMillis: Long,
    val manualBy: GlobalId? = null,
    val reason: String = "",
    val voids: GlobalId? = null,
) {
    enum class Kind { IN, OUT }

    companion object {
        /** The punches in effect: cancelled ones and the cancelling records left out, in time order. */
        fun effective(punches: List<Punch>): List<Punch> {
            val voided = punches.mapNotNull { it.voids }.toSet()
            return punches.filter { it.voids == null && it.id !in voided }.sortedBy { it.minute }
        }
    }
}

/** Wrong clock PINs: how many in a row, and locked until when. */
data class PinFailures(val count: Int, val lockedUntil: Long)

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
    fun pinFailures(employeeId: GlobalId): PinFailures
    fun savePinFailures(employeeId: GlobalId, failures: PinFailures)
}

/** What one day's punches add up to against the plan. Minutes. */
data class DayTime(
    val employeeId: GlobalId,
    val date: BusinessDate,
    val planned: Int,
    val worked: Int,
    val late: Int,
    val openPunch: Boolean,
    /** Overtime shorter than this is not counted (settings). */
    val overtimeMinimum: Int = 0,
) {
    /** Beyond the plan (only when there was a plan), when at least the minimum. */
    val overtime: Int get() = (if (planned > 0) maxOf(0, worked - planned) else 0).takeIf { it >= overtimeMinimum } ?: 0
    /** Short of the plan. */
    val absent: Int get() = maxOf(0, planned - worked)
}

object TimeMath {
    /**
     * Pairs IN→OUT in order; an unpaired IN at the end is an open punch (still at work, or forgot to clock out) and
     * counts nothing. Worked time is the sum of the pairs minus the planned breaks.
     */
    fun day(employeeId: GlobalId, date: BusinessDate, shifts: List<Shift>, punches: List<Punch>, settings: PayrollSettings = PayrollSettings()): DayTime {
        val sorted = Punch.effective(punches)
        var worked = 0; var pairs = 0; var open: Punch? = null
        for (p in sorted) {
            when (p.kind) {
                Punch.Kind.IN -> if (open == null) open = p
                Punch.Kind.OUT -> open?.let { worked += p.minute - it.minute; pairs++; open = null }
            }
        }
        val deduct = settings.deductPlannedBreaks
        val planned = shifts.sumOf { it.paidMinutes(deduct) }
        // When the restaurant deducts breaks (an option, off by default): the planned break is taken off only when the
        // person did not clock out for it, and only from time longer than the break.
        val breaks = if (deduct && pairs <= shifts.size) shifts.sumOf { it.breakMinutes } else 0
        val firstIn = sorted.firstOrNull { it.kind == Punch.Kind.IN }?.minute
        val start = shifts.minOfOrNull { it.startMinute }
        val late = if (firstIn != null && start != null) maxOf(0, firstIn - start - settings.lateGraceMinutes) else 0
        return DayTime(employeeId, date, planned, maxOf(0, worked - if (worked > breaks) breaks else 0), late, open != null, settings.overtimeMinimumMinutes)
    }

    /** A night shift's IN still open the next morning is closed by an OUT up to this long after it. */
    const val NIGHT_CARRY_MINUTES = 16 * 60
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

/**
 * Turns a day's punches into attendance (worked, overtime, absence) through the ordinary attendance record. A day
 * typed by hand is kept unless [replaceManual].
 */
@NoDocument
data class ConfirmDayTime(override val commandId: GlobalId, override val scope: Scope.Branch, val date: BusinessDate, val replaceManual: Boolean = false) : Command {
    override val requiredPermission = Permission.ATTENDANCE_RECORD
    override fun fingerprint() = "$scope|${date.epochDay}|$replaceManual"
}

/** A manager adds a missing clock-in or clock-out (forgot to clock out), with why. */
@NoDocument
data class AddPunch(
    override val commandId: GlobalId,
    override val scope: Scope.Branch,
    val employeeId: GlobalId,
    val date: BusinessDate,
    val minute: Int,
    val kind: Punch.Kind,
    val reason: String,
) : Command {
    override val requiredPermission = Permission.ATTENDANCE_RECORD
    override fun fingerprint() = "$scope|$employeeId|${date.epochDay}|$minute|$kind|$reason"
}

/** A manager cancels a wrong punch (twice by mistake, the wrong name), with why. */
@NoDocument
data class VoidPunch(override val commandId: GlobalId, override val scope: Scope.Branch, val punchId: GlobalId, val date: BusinessDate, val reason: String) : Command {
    override val requiredPermission = Permission.ATTENDANCE_RECORD
    override fun fingerprint() = "$scope|$punchId|${date.epochDay}|$reason"
}

class ShiftOperations(
    private val bus: CommandBus,
    private val personnel: PersonnelStore,
    private val shifts: ShiftStore,
    private val payroll: PayrollOperations,
    private val settings: () -> PayrollSettings = { PayrollSettings() },
    private val payrollRuns: (Scope.Branch) -> List<PayrollRun> = { emptyList() },
) {
    fun save(c: SaveShift): CommandOutcome = bus.execute(ModuleId.PAYROLL, c) { cmd, ctx ->
        val employee = employee(cmd.employeeId, cmd.scope)
        ensure(employee.employedOn(cmd.date)) { DomainError.InvalidState("EMPLOYEE", "NOT_EMPLOYED_ON_DATE") }
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
        if (pin != null) shifts.savePinFailures(employee.id, PinFailures(0, 0L))
        ctx.audit(AuditDraft(if (pin == null) "CLOCK_PIN_REMOVE" else "CLOCK_PIN_SET", "EMPLOYEE", employee.id.value, ""))
        employee.id
    }

    fun punch(c: ClockPunch): CommandOutcome = bus.execute(ModuleId.PAYROLL, c) { cmd, ctx ->
        val employee = employee(cmd.employeeId, cmd.scope)
        ensure(employee.employedOn(cmd.date)) { DomainError.InvalidState("EMPLOYEE", "NOT_EMPLOYED_ON_DATE") }
        ensure(cmd.minute in 0 until 36 * 60) { DomainError.InvalidInput("time", "زمان معتبر نیست.") }
        // The phone's date must be today's (within a day of the clock) so a punch cannot be back-dated.
        val clockDay = Math.floorDiv(ctx.nowEpochMillis, 86_400_000L)
        ensure(kotlin.math.abs(cmd.date.epochDay - clockDay) <= 1) { DomainError.InvalidInput("date", "تاریخ گوشی درست نیست.") }
        val failed = shifts.pinFailures(employee.id)
        ensure(ctx.nowEpochMillis >= failed.lockedUntil) { DomainError.InvalidState("CLOCK_PIN", "LOCKED:${(failed.lockedUntil - ctx.nowEpochMillis + 999) / 1_000}") }
        val hash = shifts.clockPin(employee.id) ?: throw DomainException(DomainError.InvalidState("CLOCK_PIN", "NOT_SET"))
        if (!PinHasher.verify(ir.sabou.kernel.NationalId.normalize(cmd.pin).toCharArray(), hash)) {
            // Counted in the database (kept when the app restarts): 5 wrong → 5 minutes, then each further wrong PIN
            // doubles the lock, up to a day. The count is kept although the punch fails.
            val count = failed.count + 1
            val lock = if (count < MAX_FAILURES) 0L else minOf(MAX_LOCK_MILLIS, LOCK_MILLIS shl minOf(count - MAX_FAILURES, 20))
            shifts.savePinFailures(employee.id, PinFailures(count, if (lock > 0) ctx.nowEpochMillis + lock else 0L))
            ctx.audit(AuditDraft("CLOCK_PIN_FAILURE", "EMPLOYEE", employee.id.value, "count=$count"))
            ctx.failAfterCommit(DomainError.InvalidInput("pin", "رمز درست نیست."))
            return@execute employee.id
        }
        if (failed.count > 0) shifts.savePinFailures(employee.id, PinFailures(0, 0L))
        val (day, minute) = workDay(cmd.scope, employee.id, cmd.date, cmd.minute)
        val today = Punch.effective(shifts.punches(cmd.scope, day, day).filter { it.employeeId == employee.id })
        ensure(today.none { kotlin.math.abs(it.minute - minute) < 1 }) { DomainError.InvalidState("PUNCH", "DUPLICATE") }
        val kind = if (today.lastOrNull()?.kind == Punch.Kind.IN) Punch.Kind.OUT else Punch.Kind.IN
        val punch = Punch(GlobalId.new(), cmd.scope, employee.id, day, minute, kind, ctx.nowEpochMillis)
        shifts.savePunch(punch)
        ctx.audit(AuditDraft("CLOCK_${kind.name}", "EMPLOYEE", employee.id.value, "day=${day.epochDay};minute=$minute"))
        punch.id
    }

    /**
     * The work day of a punch at [date] [minute] (phone time): a night shift's IN still open from yesterday is closed
     * on yesterday's page (24:00 + the time), so the night is one work day, not two.
     */
    fun workDay(scope: Scope.Branch, employeeId: GlobalId, date: BusinessDate, minute: Int): Pair<BusinessDate, Int> {
        if (minute >= 1_440) return date to minute
        val yesterday = date.plusDays(-1)
        val open = Punch.effective(shifts.punches(scope, yesterday, yesterday).filter { it.employeeId == employeeId }).lastOrNull()
            ?.takeIf { it.kind == Punch.Kind.IN }
        return if (open != null && minute + 1_440 - open.minute in 1..TimeMath.NIGHT_CARRY_MINUTES) yesterday to minute + 1_440 else date to minute
    }

    fun addPunch(c: AddPunch): CommandOutcome = bus.execute(ModuleId.PAYROLL, c) { cmd, ctx ->
        val employee = employee(cmd.employeeId, cmd.scope)
        ensure(employee.employedOn(cmd.date)) { DomainError.InvalidState("EMPLOYEE", "NOT_EMPLOYED_ON_DATE") }
        ensure(cmd.minute in 0 until 36 * 60) { DomainError.InvalidInput("time", "زمان معتبر نیست.") }
        val reason = cmd.reason.trim()
        ensure(reason.length in 3..200) { DomainError.InvalidInput("reason", "دلیل را بنویسید.") }
        notInApprovedPeriod(cmd.scope, cmd.date)
        val day = Punch.effective(shifts.punches(cmd.scope, cmd.date, cmd.date).filter { it.employeeId == employee.id })
        ensure(day.none { it.minute == cmd.minute }) { DomainError.InvalidState("PUNCH", "DUPLICATE") }
        val punch = Punch(GlobalId.new(), cmd.scope, employee.id, cmd.date, cmd.minute, cmd.kind, ctx.nowEpochMillis, manualBy = ctx.actor.userId, reason = reason)
        shifts.savePunch(punch)
        ctx.audit(AuditDraft("PUNCH_ADD", "EMPLOYEE", employee.id.value, "day=${cmd.date.epochDay};minute=${cmd.minute};${cmd.kind};$reason"))
        punch.id
    }

    fun voidPunch(c: VoidPunch): CommandOutcome = bus.execute(ModuleId.PAYROLL, c) { cmd, ctx ->
        val reason = cmd.reason.trim()
        ensure(reason.length in 3..200) { DomainError.InvalidInput("reason", "دلیل را بنویسید.") }
        val all = shifts.punches(cmd.scope, cmd.date, cmd.date)
        val punch = all.firstOrNull { it.id == cmd.punchId && it.voids == null } ?: throw DomainException(DomainError.NotFound("PUNCH"))
        ensure(all.none { it.voids == punch.id }) { DomainError.InvalidState("PUNCH", "ALREADY_VOIDED") }
        notInApprovedPeriod(cmd.scope, punch.date)
        val record = punch.copy(id = GlobalId.new(), atEpochMillis = ctx.nowEpochMillis, manualBy = ctx.actor.userId, reason = reason, voids = punch.id)
        shifts.savePunch(record)
        ctx.audit(AuditDraft("PUNCH_VOID", "EMPLOYEE", punch.employeeId.value, "day=${punch.date.epochDay};minute=${punch.minute};${punch.kind};$reason"))
        record.id
    }

    private fun notInApprovedPeriod(scope: Scope.Branch, date: BusinessDate) =
        ensure(payrollRuns(scope).none { it.status == RunStatus.APPROVED && date >= it.from && date <= it.to }) { DomainError.InvalidState("ATTENDANCE", "PERIOD_APPROVED") }

    /** Each employee's planned and worked time on [date] (planned shifts, punches). */
    fun day(scope: Scope.Branch, date: BusinessDate): List<DayTime> {
        val planned = shifts.shifts(scope, date, date).groupBy { it.employeeId }
        val punched = shifts.punches(scope, date, date).groupBy { it.employeeId }
        val options = settings()
        return (planned.keys + punched.keys).map { TimeMath.day(it, date, planned[it].orEmpty(), punched[it].orEmpty(), options) }
    }

    /** Every employee's day in one transaction, through the ordinary attendance rules (employment, approved periods). */
    fun confirmDay(c: ConfirmDayTime): CommandOutcome = bus.execute(ModuleId.PAYROLL, c) { cmd, ctx ->
        val times = day(cmd.scope, cmd.date)
        ensure(times.isNotEmpty()) { DomainError.InvalidState("PUNCH", "NOTHING") }
        ensure(times.none { it.openPunch }) { DomainError.InvalidState("PUNCH", "OPEN:${times.count { it.openPunch }}") }
        var kept = 0
        times.forEach { t ->
            // A day typed by hand is someone's decision: kept unless the manager chose to replace it.
            if (!cmd.replaceManual && personnel.attendanceOn(t.employeeId, cmd.date)?.source == AttendanceRecord.Source.MANUAL) { kept++; return@forEach }
            val regular = if (t.planned > 0) minOf(t.worked, t.planned) else t.worked
            payroll.applyAttendance(ctx, cmd.scope, t.employeeId, cmd.date, regular, t.overtime, t.absent, AttendanceRecord.Source.CLOCK)
        }
        ctx.audit(AuditDraft("DAY_TIME_CONFIRM", "ATTENDANCE", cmd.date.epochDay.toString(), "employees=${times.size};kept_manual=$kept"))
        GlobalId.new()
    }

    /**
     * The cost of [minutes] of [employee]'s time: monthly salary plus the employer's insurance share, over the
     * standard month of the policy in force (or 192 hours, 44 a week, when none is configured).
     */
    fun cost(employee: Employee, minutes: Int, policy: StatutoryPolicy?, date: BusinessDate): Money {
        val salary = employee.salaryOn(date).rial
        val monthly = salary + (policy?.let { Ratio.mulDiv(salary, it.employerInsuranceBp.toLong(), 10_000) } ?: 0L)
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
        const val MAX_LOCK_MILLIS = 24 * 60 * 60_000L
        const val DEFAULT_MONTH_MINUTES = 192 * 60
    }
}
