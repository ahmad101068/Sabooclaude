package ir.sabou.core

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.JalaliCalendar
import ir.sabou.kernel.Money
import ir.sabou.kernel.Scope
import ir.sabou.payroll.AddPayAdjustment
import ir.sabou.payroll.CalculatePayroll
import ir.sabou.payroll.ClockPunch
import ir.sabou.payroll.ConfirmDayTime
import ir.sabou.payroll.EmployeeProfile
import ir.sabou.payroll.EndEmployment
import ir.sabou.payroll.Leave
import ir.sabou.payroll.PayElement
import ir.sabou.payroll.PayLine
import ir.sabou.payroll.PayrollRun
import ir.sabou.payroll.PayrollSettings
import ir.sabou.payroll.RecordAttendance
import ir.sabou.payroll.RecordLeave
import ir.sabou.payroll.RegisterEmployee
import ir.sabou.payroll.SavePayElement
import ir.sabou.payroll.SavePayrollSettings
import ir.sabou.payroll.SetClockPin
import ir.sabou.payroll.StatutoryPolicy
import ir.sabou.payroll.TaxBracket
import ir.sabou.payroll.UpdateEmployeeProfile
import ir.sabou.persistence.JdbcSqlDatabase
import ir.sabou.platform.memory.InMemoryAnchorStore
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Pay elements, bonuses, leave, night and holiday premiums, eidi, severance and leave paid on leaving. */
class PersonnelPayTest {
    private val dir = Files.createTempDirectory("pay")
    private val db = JdbcSqlDatabase(DriverManager.getConnection("jdbc:sqlite:${dir.resolve("p.db")}"))
    private val day = JalaliCalendar.date(1405, 7, 10)
    private var now = day.epochDay * 86_400_000L + 9 * 3_600_000L
    private fun month(m: Int) = JalaliCalendar.date(1405, m, 1) to JalaliCalendar.date(1405, m, JalaliCalendar.monthLength(1405, m))
    // 120 M a month over 12,000 minutes: 10,000 a minute. No insurance or tax, to keep the figures plain.
    private val policy = StatutoryPolicy(
        "TEST", JalaliCalendar.date(1404, 1, 1), JalaliCalendar.date(1406, 12, 29), standardMonthlyMinutes = 12_000,
        overtimeMultiplierPercent = 140, employeeInsuranceBp = 0, employerInsuranceBp = 0, unemploymentInsuranceBp = 0,
        maxInsurableMonthly = Money.of(1_000_000_000), insuranceTaxExemptNumerator = 0, insuranceTaxExemptDenominator = 1,
        taxBrackets = listOf(TaxBracket(null, 0)),
    )
    private val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { now }, listOf(policy))
    private fun id() = GlobalId.new()
    private fun code(block: () -> Unit) = assertFailsWith<DomainException> { block() }.error.code
    private lateinit var branch: Scope.Branch

    private fun start(settings: PayrollSettings = PayrollSettings()) {
        core.bootstrap("شعبه یک", "مالک", "owner", "123456".toCharArray())
        branch = Scope.Branch(core.overview.branches().single().id)
        core.payrollSettings.save(SavePayrollSettings(id(), settings))
    }

    private fun hire(start: BusinessDate = JalaliCalendar.date(1402, 7, 10)) =
        core.payroll.registerEmployee(RegisterEmployee(id(), branch, "علی", "0012345679", Money.of(120_000_000), start)).resultId

    private fun run(m: Int): PayrollRun = core.payrollStore.run(core.payroll.calculate(CalculatePayroll(id(), branch, month(m).first, month(m).second)).resultId)!!
    private fun PayrollRun.lines() = payslips.single().lines.associate { it.title to it.amount.rial }

    @AfterTest fun close() { db.close(); dir.toFile().deleteRecursively() }

    @Test fun payElementsAndABonus() {
        start()
        val ali = hire()
        core.payroll.updateProfile(UpdateEmployeeProfile(id(), branch, ali, EmployeeProfile(children = 2)))
        listOf(
            PayElement(id(), "حق مسکن", PayLine.Kind.EARNING, PayElement.Basis.FIXED, 9_000_000, insurable = true, taxable = true),
            PayElement(id(), "حق اولاد", PayLine.Kind.EARNING, PayElement.Basis.PER_CHILD, 5_000_000, insurable = false, taxable = true),
            PayElement(id(), "پایه سنوات", PayLine.Kind.EARNING, PayElement.Basis.PER_SERVICE_YEAR, 1_000_000, insurable = true, taxable = true),
            PayElement(id(), "غذای پرسنل", PayLine.Kind.DEDUCTION, PayElement.Basis.PERCENT_OF_BASE, 500, insurable = false, taxable = false),
        ).forEach { core.payroll.savePayElement(SavePayElement(id(), it)) }
        // Half a month's salary as a bonus for everyone this month.
        core.payroll.addAdjustment(AddPayAdjustment(id(), branch, listOf(ali), month(7).first, "پاداش مهر", PayLine.Kind.EARNING, null, 5_000, true, true))
        val slip = run(7).payslips.single()
        val lines = slip.lines.associate { it.title to it.amount.rial }
        assertEquals(9_000_000, lines["حق مسکن"]); assertEquals(10_000_000, lines["حق اولاد"]); assertEquals(3_000_000, lines["پایه سنوات"])
        assertEquals(60_000_000, lines["پاداش مهر"]); assertEquals(6_000_000, lines["غذای پرسنل"])
        assertEquals(120_000_000 + 9_000_000 + 10_000_000 + 3_000_000 + 60_000_000, slip.gross.rial)
        assertEquals(slip.gross.rial - 6_000_000, slip.net.rial)
    }

    @Test fun leaveCoversAbsenceAndUnpaidLeaveIsDeducted() {
        start()
        val ali = hire()
        val d = JalaliCalendar.date(1405, 7, 5)
        // A planned day not clocked (absent all day) covered by annual leave: no deduction.
        core.payroll.recordAttendance(RecordAttendance(id(), branch, ali, d, 0, 0, 440))
        core.payroll.recordLeave(RecordLeave(id(), branch, ali, d, d, Leave.Kind.ANNUAL, null, ""))
        core.payroll.recordLeave(RecordLeave(id(), branch, ali, d.plusDays(1), d.plusDays(1), Leave.Kind.UNPAID, null, ""))
        assertEquals("INVALID_STATE:LEAVE:OVERLAP", code { core.payroll.recordLeave(RecordLeave(id(), branch, ali, d, d, Leave.Kind.SICK, null, "")) })
        assertEquals(4_400_000, run(7).payslips.single().absenceDeduction.rial)    // the unpaid day: 440 min × 10,000
        val (_, balance) = core.labor.leaves(ali, day)
        assertEquals(26L * 440 + 9L * 440 - 440, balance.left)                      // full year + carried 9 days − one day
        assertTrue(code { core.payroll.recordLeave(RecordLeave(id(), branch, ali, JalaliCalendar.date(1405, 8, 1), JalaliCalendar.date(1405, 9, 30), Leave.Kind.ANNUAL, null, "")) }
            .startsWith("INVALID"))
    }

    @Test fun nightAndHolidayPremiums() {
        start(PayrollSettings(nightPremiumBp = 3_500, holidayPremiumBp = 4_000))
        val ali = hire()
        core.shifts.setPin(SetClockPin(id(), branch, ali, "2468"))
        val night = day.plusDays(2)                                                       // not a Friday
        now = night.epochDay * 86_400_000L + 18 * 3_600_000L
        core.identity.login("owner", "123456".toCharArray())
        core.shifts.punch(ClockPunch(id(), branch, ali, "2468", night, 18 * 60))
        now += 8 * 3_600_000L
        core.identity.login("owner", "123456".toCharArray())
        core.shifts.punch(ClockPunch(id(), branch, ali, "2468", night.plusDays(1), 2 * 60))
        core.shifts.confirmDay(ConfirmDayTime(id(), branch, night))
        assertEquals(240, core.personnel.attendanceOn(ali, night)!!.nightMinutes)        // 22:00–02:00
        val friday = generateSequence(month(7).first) { it.plusDays(1) }.first { JalaliCalendar.weekdayIndex(it) == 6 }
        core.payroll.recordAttendance(RecordAttendance(id(), branch, ali, friday, 480, 0, 0))
        val lines = run(7).lines()
        assertEquals(840_000, lines["شب‌کاری"])                                        // 240 × 10,000 × 35 %
        assertEquals(1_920_000, lines["جمعه‌کاری و تعطیل‌کاری"])                        // 480 × 10,000 × 40 %
    }

    @Test fun eidiInTheLastMonthWithTheTaxFreePart() {
        start(PayrollSettings(eidiEnabled = true, eidiCap = Money.of(200_000_000), eidiTaxExempt = Money.of(50_000_000)))
        hire()
        val lines = run(12).payslips.single().lines.filter { it.code.startsWith("EIDI") }
        assertEquals(listOf(50_000_000L to false, 150_000_000L to true), lines.map { it.amount.rial to it.taxable })   // 60 days × 4 M = 240 M, capped at 200 M
        assertEquals(emptyList(), run(7).payslips.single().lines.filter { it.code.startsWith("EIDI") })
    }

    @Test fun leavingPaysEidiSeveranceAndUnusedLeave() {
        start(PayrollSettings(eidiEnabled = true, sanavatEnabled = true))
        val ali = hire(JalaliCalendar.date(1404, 7, 1))
        val end = JalaliCalendar.date(1405, 6, 31)
        core.payroll.endEmployment(EndEmployment(id(), branch, ali, end))
        val lines = run(6).payslips.single().lines.associate { it.code.substringBefore(':') to it.amount.rial }
        val yearDays = JalaliCalendar.date(1405, 12, JalaliCalendar.monthLength(1405, 12)).epochDay - JalaliCalendar.date(1405, 1, 1).epochDay + 1
        assertEquals(ir.sabou.kernel.Ratio.mulDiv(240_000_000L, 186, yearDays), lines["EIDI"])                      // 60 days × 4 M for 186 days of 1405
        val service = end.epochDay - JalaliCalendar.date(1404, 7, 1).epochDay + 1
        assertEquals(ir.sabou.kernel.Ratio.mulDiv(120_000_000L, service, 365), lines["SANAVAT"])                     // 30 days × 4 M per year of service
        assertTrue((lines["LEAVE_PAYOUT"] ?: 0) > 0)
    }
}
