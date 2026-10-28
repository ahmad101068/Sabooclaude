package ir.sabou.core

import ir.sabou.persistence.DevDatabases
import ir.sabou.persistence.ClosableSqlDatabase

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.JalaliCalendar
import ir.sabou.kernel.Money
import ir.sabou.kernel.Scope
import ir.sabou.ledger.BudgetPeriod
import ir.sabou.ledger.SetBudget
import ir.sabou.ledger.StandardAccounts
import ir.sabou.payroll.ClockPunch
import ir.sabou.payroll.ConfirmDayTime
import ir.sabou.payroll.CopyWeek
import ir.sabou.payroll.RegisterEmployee
import ir.sabou.payroll.SaveShift
import ir.sabou.payroll.SetClockPin
import ir.sabou.platform.memory.InMemoryAnchorStore
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Shifts, labour cost against budget, the PIN clock and confirming a day into attendance (ADR-0038). */
class ShiftsTest {
    private val dir = Files.createTempDirectory("shifts")
    private val db = DevDatabases.open(dir.resolve("s.db"))
    private val day = JalaliCalendar.date(1405, 7, 20)
    private var now = day.epochDay * 86_400_000L + 9 * 3_600_000L
    private val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { now })
    private fun id() = GlobalId.new()
    private fun code(block: () -> Unit) = assertFailsWith<DomainException> { block() }.error.code

    @AfterTest fun close() { db.close(); dir.toFile().deleteRecursively() }

    @Test fun planClockAndConfirm() {
        core.bootstrap("شعبه یک", "مالک", "owner", "123456".toCharArray())
        val branch = Scope.Branch(core.overview.branches().single().id)
        // This restaurant deducts planned breaks (an option; off by default).
        core.payrollSettings.save(ir.sabou.payroll.SavePayrollSettings(id(), ir.sabou.payroll.PayrollSettings(deductPlannedBreaks = true)))
        // 192,000,000 a month over 192 hours (no statutory policy configured): 1,000,000 an hour.
        val ali = core.payroll.registerEmployee(RegisterEmployee(id(), branch, "علی", "0012345679", Money.of(192_000_000), JalaliCalendar.date(1405, 1, 1))).resultId
        val sara = core.payroll.registerEmployee(RegisterEmployee(id(), branch, "سارا", "0084575948", Money.of(96_000_000), JalaliCalendar.date(1405, 1, 1))).resultId
        core.shifts.save(SaveShift(id(), branch, null, ali, day, 9 * 60, 17 * 60, 60, "کباب‌پز"))       // 7 hours
        assertEquals("INVALID_STATE:SHIFT:OVERLAP", code { core.shifts.save(SaveShift(id(), branch, null, ali, day, 16 * 60, 20 * 60, 0, "سالن")) })
        core.shifts.save(SaveShift(id(), branch, null, sara, day, 18 * 60, 26 * 60, 0, "صندوق"))         // until 02:00
        core.budgets.set(SetBudget(id(), branch, StandardAccounts.SALARIES,
            listOf(BudgetPeriod(JalaliCalendar.date(1405, 7, 1), JalaliCalendar.date(1405, 7, 30), Money.of(300_000_000)))))

        val week = core.labor.week(branch, day)
        val today = week.first()
        assertEquals(15 * 60, today.minutes)
        assertEquals(7_000_000L + 4_000_000L, today.cost.rial)                  // 7 h × 1,000,000 + 8 h × 500,000
        assertEquals(10_000_000L, today.budget.rial)
        core.shifts.copyWeek(CopyWeek(id(), branch, day, day.plusDays(7)))
        assertEquals(2, core.labor.week(branch, day.plusDays(7)).first().shifts.size)

        // The clock: wrong PINs lock the person for a while; the right one punches in, then out.
        core.shifts.setPin(SetClockPin(id(), branch, ali, "۲۴۶۸"))
        core.shifts.setPin(SetClockPin(id(), branch, sara, "1357"))
        assertEquals(listOf("سارا", "علی"), core.labor.clockList(branch, day).map { it.second })
        repeat(5) { assertEquals("INVALID_INPUT:pin", code { punchAt(ClockPunch(id(), branch, sara, "0000", day, 18 * 60)) }) }
        assertTrue(code { punchAt(ClockPunch(id(), branch, sara, "1357", day, 18 * 60)) }.startsWith("INVALID_STATE:CLOCK_PIN:LOCKED"))
        punchAt(ClockPunch(id(), branch, ali, "2468", day, 9 * 60 + 20))
        assertEquals("INVALID_STATE:PUNCH:OPEN:1", code { core.shifts.confirmDay(ConfirmDayTime(id(), branch, day)) })
        punchAt(ClockPunch(id(), branch, ali, "2468", day, 18 * 60))
        assertEquals("INVALID_INPUT:date", code { core.shifts.punch(ClockPunch(id(), branch, ali, "2468", day.plusDays(-5), 9 * 60)) })

        val row = core.labor.day(branch, day).single { it.employee.name == "علی" }
        assertEquals(520 - 60, row.time.worked)                                   // 9:20–18:00 less the planned hour
        assertEquals(10, row.time.late)                                           // 20 minutes, 10 of grace
        assertEquals(40, row.time.overtime)
        core.shifts.confirmDay(ConfirmDayTime(id(), branch, day))
        val attendance = core.personnel.attendanceOn(ali, day)!!
        assertEquals(420, attendance.workedMinutes); assertEquals(40, attendance.overtimeMinutes); assertEquals(0, attendance.absentMinutes)
        assertEquals(480, core.personnel.attendanceOn(sara, day)!!.absentMinutes)   // planned, never clocked in
    }

    /** A punch made at the time it states, in Iran: the main phone's clock is moved there first (SABOU-AUDIT-006). */
    private fun punchAt(c: ClockPunch) = run { now = c.date.epochDay * 86_400_000L + (c.minute - 210) * 60_000L; core.shifts.punch(c) }
}
