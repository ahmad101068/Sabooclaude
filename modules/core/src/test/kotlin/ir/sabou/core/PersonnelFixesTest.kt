package ir.sabou.core

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.JalaliCalendar
import ir.sabou.kernel.Money
import ir.sabou.kernel.Scope
import ir.sabou.payroll.AddPunch
import ir.sabou.payroll.ApprovePayroll
import ir.sabou.payroll.CalculatePayroll
import ir.sabou.payroll.ChangeSalary
import ir.sabou.payroll.ClockPunch
import ir.sabou.payroll.ConfirmDayTime
import ir.sabou.payroll.EndEmployment
import ir.sabou.payroll.OvertimeMode
import ir.sabou.payroll.PayrollSettings
import ir.sabou.payroll.Punch
import ir.sabou.payroll.RecordAttendance
import ir.sabou.payroll.RegisterEmployee
import ir.sabou.payroll.RehireEmployee
import ir.sabou.payroll.SavePayrollSettings
import ir.sabou.payroll.SaveShift
import ir.sabou.payroll.SetClockPin
import ir.sabou.payroll.StatutoryPolicy
import ir.sabou.payroll.TaxBracket
import ir.sabou.payroll.VoidPunch
import ir.sabou.persistence.JdbcSqlDatabase
import ir.sabou.platform.Role
import ir.sabou.platform.memory.InMemoryAnchorStore
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Salary history, night shifts, punch corrections, rehire, the only approver, kept manual days, PIN lock, options. */
class PersonnelFixesTest {
    private val dir = Files.createTempDirectory("personnel")
    private val file = dir.resolve("p.db")
    private val db = JdbcSqlDatabase(DriverManager.getConnection("jdbc:sqlite:$file"))
    private val day = JalaliCalendar.date(1405, 7, 20)
    private var now = day.epochDay * 86_400_000L + 9 * 3_600_000L
    private val mehr = JalaliCalendar.date(1405, 7, 1) to JalaliCalendar.date(1405, 7, 30)
    private val policy = StatutoryPolicy(
        "TEST", JalaliCalendar.date(1405, 1, 1), JalaliCalendar.date(1405, 12, 29), standardMonthlyMinutes = 12_000,
        overtimeMultiplierPercent = 140, employeeInsuranceBp = 0, employerInsuranceBp = 0, unemploymentInsuranceBp = 0,
        maxInsurableMonthly = Money.of(1_000_000_000), insuranceTaxExemptNumerator = 0, insuranceTaxExemptDenominator = 1,
        taxBrackets = listOf(TaxBracket(null, 0)),
    )
    private val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { now }, listOf(policy))
    private fun id() = GlobalId.new()
    private fun code(block: () -> Unit) = assertFailsWith<DomainException> { block() }.error.code
    private lateinit var branch: Scope.Branch

    private fun start() {
        core.bootstrap("شعبه یک", "مالک", "owner", "123456".toCharArray())
        branch = Scope.Branch(core.overview.branches().single().id)
    }

    private fun hire(name: String, nid: String, salary: Long) =
        core.payroll.registerEmployee(RegisterEmployee(id(), branch, name, nid, Money.of(salary), JalaliCalendar.date(1405, 1, 1))).resultId

    @AfterTest fun close() { db.close(); dir.toFile().deleteRecursively() }

    @Test fun aSalaryChangeDuringTheMonthIsPaidDayByDayAndTheOnlyOwnerMayApprove() {
        start()
        val ali = hire("علی", "0012345679", 300_000_000)
        assertEquals("INVALID_INPUT:monthlySalary", code { core.payroll.registerEmployee(RegisterEmployee(id(), branch, "صفر", "0084575948", Money.ZERO)) })
        // From the 16th of a 30-day month: 15 days at 300 M and 15 at 360 M.
        core.payroll.changeSalary(ChangeSalary(id(), branch, ali, JalaliCalendar.date(1405, 7, 16), Money.of(360_000_000), "افزایش"))
        val run = core.payroll.calculate(CalculatePayroll(id(), branch, mehr.first, mehr.second)).resultId
        assertEquals(330_000_000, core.payrollStore.run(run)!!.payslips.single().baseSalary.rial)
        // The owner is the only one who may approve: their own calculation is approved and recorded as such.
        core.payroll.approve(ApprovePayroll(id(), branch, run))
        // An approved month keeps its salary.
        assertEquals("INVALID_STATE:SALARY:IN_APPROVED_PERIOD", code {
            core.payroll.changeSalary(ChangeSalary(id(), branch, ali, JalaliCalendar.date(1405, 7, 20), Money.of(400_000_000), ""))
        })
        assertEquals(360_000_000, core.personnel.employee(ali)!!.salaryOn(day).rial)
        assertEquals(300_000_000, core.personnel.employee(ali)!!.salaryOn(JalaliCalendar.date(1405, 7, 1)).rial)
    }

    @Test fun withASecondOwnerTheCalculatorCannotApprove() {
        start()
        hire("علی", "0012345679", 300_000_000)
        core.identity.createUser("owner2", "شریک", Role.OWNER, emptySet(), "654321".toCharArray())
        val run = core.payroll.calculate(CalculatePayroll(id(), branch, mehr.first, mehr.second)).resultId
        assertEquals("INVALID_STATE:PAYROLL_RUN:SAME_PERSON_CALCULATED", code { core.payroll.approve(ApprovePayroll(id(), branch, run)) })
    }

    @Test fun overtimeOptions() {
        start()
        val ali = hire("علی", "0012345679", 120_000_000)
        core.payroll.recordAttendance(RecordAttendance(id(), branch, ali, day, 480, 120, 0))
        fun overtime(): Long = core.payrollStore.run(core.payroll.calculate(CalculatePayroll(id(), branch, mehr.first, mehr.second)).resultId)!!.payslips.single().overtimePay.rial
        assertEquals(1_680_000, overtime())                              // 120 M ÷ 12,000 min × 120 min × 1.4
        core.payrollSettings.save(SavePayrollSettings(id(), PayrollSettings(overtimeMode = OvertimeMode.FIXED_HOURLY, overtimeHourlyRate = Money.of(500_000))))
        assertEquals(1_000_000, overtime())                              // 2 hours × 500,000
        core.payrollSettings.save(SavePayrollSettings(id(), PayrollSettings(overtimeMode = OvertimeMode.NONE)))
        assertEquals(0, overtime())
        assertEquals("INVALID_INPUT:overtimeHourlyRate", code { core.payrollSettings.save(SavePayrollSettings(id(), PayrollSettings(overtimeMode = OvertimeMode.FIXED_HOURLY))) })
    }

    @Test fun aNightShiftIsOneWorkDayAndAForgottenOutIsAddedByTheManager() {
        start()
        val sara = hire("سارا", "0084575948", 120_000_000)
        val ali = hire("علی", "0012345679", 120_000_000)
        core.shifts.setPin(SetClockPin(id(), branch, sara, "1357"))
        core.shifts.setPin(SetClockPin(id(), branch, ali, "2468"))
        core.shifts.save(SaveShift(id(), branch, null, sara, day, 18 * 60, 26 * 60, 30, "صندوق"))
        core.shifts.punch(ClockPunch(id(), branch, sara, "1357", day, 18 * 60))
        // 01:30 the next morning, on the phone's next day: closes yesterday's shift.
        now += 16 * 3_600_000L
        core.identity.login("owner", "123456".toCharArray())   // the session timed out overnight
        core.shifts.punch(ClockPunch(id(), branch, sara, "1357", day.plusDays(1), 90))
        val night = core.labor.day(branch, day).single { it.employee.name == "سارا" }
        assertEquals(listOf(Punch.Kind.IN to 18 * 60, Punch.Kind.OUT to 25 * 60 + 30), night.punches.map { it.kind to it.minute })
        // Breaks are not deducted by default: 7.5 hours worked, 8 planned.
        assertEquals(450, night.time.worked); assertEquals(480, night.time.planned)
        assertEquals(emptyList(), core.labor.day(branch, day.plusDays(1)))

        // Ali forgot to clock out: the manager adds it, and cancels a punch made twice by mistake.
        core.shifts.punch(ClockPunch(id(), branch, ali, "2468", day.plusDays(1), 9 * 60))
        core.shifts.punch(ClockPunch(id(), branch, ali, "2468", day.plusDays(1), 9 * 60 + 2))
        val twice = core.labor.day(branch, day.plusDays(1)).single().punches.last()
        core.shifts.voidPunch(VoidPunch(id(), branch, twice.id, day.plusDays(1), "دو بار زده شد"))
        assertEquals("INVALID_STATE:PUNCH:OPEN:1", code { core.shifts.confirmDay(ConfirmDayTime(id(), branch, day.plusDays(1))) })
        assertEquals("INVALID_INPUT:reason", code { core.shifts.addPunch(AddPunch(id(), branch, ali, day.plusDays(1), 17 * 60, Punch.Kind.OUT, "")) })
        core.shifts.addPunch(AddPunch(id(), branch, ali, day.plusDays(1), 17 * 60, Punch.Kind.OUT, "خروج نزده بود"))
        assertEquals(480, core.labor.day(branch, day.plusDays(1)).single().time.worked)

        // A day typed by hand is kept when the day is confirmed, unless asked.
        core.payroll.recordAttendance(RecordAttendance(id(), branch, ali, day.plusDays(1), 300, 0, 0))
        assertTrue(core.labor.day(branch, day.plusDays(1)).single().manual)
        core.shifts.confirmDay(ConfirmDayTime(id(), branch, day.plusDays(1)))
        assertEquals(300, core.personnel.attendanceOn(ali, day.plusDays(1))!!.workedMinutes)
        core.shifts.confirmDay(ConfirmDayTime(id(), branch, day.plusDays(1), replaceManual = true))
        assertEquals(480, core.personnel.attendanceOn(ali, day.plusDays(1))!!.workedMinutes)
    }

    @Test fun wrongPinsStayCountedAndTheLockGrows() {
        start()
        val ali = hire("علی", "0012345679", 120_000_000)
        core.shifts.setPin(SetClockPin(id(), branch, ali, "2468"))
        repeat(5) { assertEquals("INVALID_INPUT:pin", code { core.shifts.punch(ClockPunch(id(), branch, ali, "0000", day, 9 * 60)) }) }
        assertEquals(5, core.shiftStore.pinFailures(ali).count)
        assertTrue(code { core.shifts.punch(ClockPunch(id(), branch, ali, "2468", day, 9 * 60)) }.startsWith("INVALID_STATE:CLOCK_PIN:LOCKED"))
        now += 6 * 60_000L
        assertEquals("INVALID_INPUT:pin", code { core.shifts.punch(ClockPunch(id(), branch, ali, "1111", day, 9 * 60 + 6)) })
        // The sixth wrong PIN locks for 10 minutes; still locked after 6.
        now += 6 * 60_000L
        assertTrue(code { core.shifts.punch(ClockPunch(id(), branch, ali, "2468", day, 9 * 60 + 12)) }.startsWith("INVALID_STATE:CLOCK_PIN:LOCKED"))
        now += 5 * 60_000L
        core.shifts.punch(ClockPunch(id(), branch, ali, "2468", day, 9 * 60 + 17))
        assertEquals(0, core.shiftStore.pinFailures(ali).count)
    }

    @Test fun someoneWhoLeftComesBackAsTheSamePerson() {
        start()
        val ali = hire("علی", "0012345679", 120_000_000)
        val number = core.personnel.employee(ali)!!.personnelNumber
        core.payroll.endEmployment(EndEmployment(id(), branch, ali, JalaliCalendar.date(1405, 6, 31)))
        assertEquals("INVALID_STATE:EMPLOYEE:DUPLICATE_NATIONAL_ID_ENDED", code {
            core.payroll.registerEmployee(RegisterEmployee(id(), branch, "علی", "0012345679", Money.of(1)))
        })
        core.payroll.rehire(RehireEmployee(id(), branch, ali, JalaliCalendar.date(1405, 7, 16), Money.of(150_000_000)))
        val back = assertNotNull(core.personnel.employee(ali))
        assertEquals(number, back.personnelNumber)
        assertEquals(15, back.employedDays(mehr.first, mehr.second))
        assertEquals(31, back.employedDays(JalaliCalendar.date(1405, 6, 1), JalaliCalendar.date(1405, 6, 31)))
        val slip = core.payrollStore.run(core.payroll.calculate(CalculatePayroll(id(), branch, mehr.first, mehr.second)).resultId)!!.payslips.single()
        assertEquals(75_000_000, slip.baseSalary.rial)                   // 150 M × 15 / 30
        assertEquals("INVALID_STATE:EMPLOYEE:STILL_EMPLOYED", code { core.payroll.rehire(RehireEmployee(id(), branch, ali, day, Money.of(1))) })
        assertEquals(BusinessDate(JalaliCalendar.date(1405, 7, 16).epochDay), back.startDate)
    }
}
