package ir.sabou.core

import ir.sabou.persistence.DevDatabases
import ir.sabou.persistence.ClosableSqlDatabase

import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.JalaliCalendar
import ir.sabou.kernel.Money
import ir.sabou.kernel.Scope
import ir.sabou.kernel.Sheba
import ir.sabou.ledger.StandardAccounts
import ir.sabou.payroll.ApprovePayroll
import ir.sabou.payroll.CalculatePayroll
import ir.sabou.payroll.ClockPunch
import ir.sabou.payroll.EmployeeProfile
import ir.sabou.payroll.EndEmployment
import ir.sabou.payroll.GiveAdvance
import ir.sabou.payroll.MaritalStatus
import ir.sabou.payroll.PayLine
import ir.sabou.payroll.PayType
import ir.sabou.payroll.PayrollSettings
import ir.sabou.payroll.RecordAttendance
import ir.sabou.payroll.RegisterEmployee
import ir.sabou.payroll.RepayAdvance
import ir.sabou.payroll.ReverseAdvance
import ir.sabou.payroll.SavePayrollSettings
import ir.sabou.payroll.SetClockPin
import ir.sabou.payroll.StatutoryPolicy
import ir.sabou.payroll.TaxBracket
import ir.sabou.payroll.TransferEmployee
import ir.sabou.payroll.UpdateEmployeeProfile
import ir.sabou.platform.AttachmentInput
import ir.sabou.platform.memory.InMemoryAnchorStore
import ir.sabou.treasury.OpenTreasuryAccount
import ir.sabou.treasury.ReceiptPurpose
import ir.sabou.treasury.RecordReceipt
import ir.sabou.treasury.TreasuryKind
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Advances and loans, daily and hourly pay, the employee file, moving branches, clock photos. */
class PersonnelRecordsTest {
    private val dir = Files.createTempDirectory("records")
    private val db = DevDatabases.open(dir.resolve("r.db"))
    private val day = JalaliCalendar.date(1405, 7, 10)
    private var now = day.epochDay * 86_400_000L + 9 * 3_600_000L
    private fun month(m: Int) = JalaliCalendar.date(1405, m, 1) to JalaliCalendar.date(1405, m, JalaliCalendar.monthLength(1405, m))
    private val policy = StatutoryPolicy(
        "TEST", JalaliCalendar.date(1405, 1, 1), JalaliCalendar.date(1405, 12, 29), standardMonthlyMinutes = 12_000,
        overtimeMultiplierPercent = 140, employeeInsuranceBp = 700, employerInsuranceBp = 2_000, unemploymentInsuranceBp = 300,
        maxInsurableMonthly = Money.of(1_000_000_000), insuranceTaxExemptNumerator = 0, insuranceTaxExemptDenominator = 1,
        taxBrackets = listOf(TaxBracket(null, 0)),
    )
    private val core = SabouCore.open(db, InMemoryAnchorStore(), Clock { now }, listOf(policy))
    private fun id() = GlobalId.new()
    private fun code(block: () -> Unit) = assertFailsWith<DomainException> { block() }.error.code
    private lateinit var branch: Scope.Branch
    private var cash: GlobalId = GlobalId.new()

    private fun start() {
        core.bootstrap("شعبه یک", "مالک", "owner", "123456".toCharArray())
        branch = Scope.Branch(core.overview.branches().single().id)
        cash = core.treasury.openAccount(OpenTreasuryAccount(id(), branch, "صندوق", TreasuryKind.CASH)).resultId
        core.treasury.receipt(RecordReceipt(id(), branch, cash, ReceiptPurpose.OWNER_CAPITAL, Money.of(500_000_000), JalaliCalendar.date(1405, 7, 1), "آورده"))
    }

    private fun slip(m: Int, approve: Boolean = false) = core.payroll.calculate(CalculatePayroll(id(), branch, month(m).first, month(m).second)).resultId.let { run ->
        if (approve) core.payroll.approve(ApprovePayroll(id(), branch, run))
        core.payrollStore.run(run)!!
    }

    @AfterTest fun close() { db.close(); dir.toFile().deleteRecursively() }

    @Test fun anAdvanceAndALoanArePaidBackFromSalaryAndTheRestOnLeaving() {
        start()
        val ali = core.payroll.registerEmployee(RegisterEmployee(id(), branch, "علی", "0012345679", Money.of(100_000_000), JalaliCalendar.date(1405, 1, 1))).resultId
        core.payroll.giveAdvance(GiveAdvance(id(), branch, ali, cash, Money.of(10_000_000), day, 1, "مساعده مهر"))
        val loan = core.payroll.giveAdvance(GiveAdvance(id(), branch, ali, cash, Money.of(30_000_000), day, 3, "وام")).resultId
        assertEquals(40_000_000, core.ledger.balance(StandardAccounts.EMPLOYEE_ADVANCE).rial)

        val mehr = slip(7, approve = true).payslips.single()
        assertEquals(listOf(10_000_000L, 10_000_000L), mehr.lines.filter { it.kind == PayLine.Kind.ADVANCE }.map { it.amount.rial })
        assertEquals(100_000_000 - 7_000_000 - 20_000_000, mehr.net.rial)       // 7 % insurance, both installments
        assertEquals(20_000_000, core.ledger.balance(StandardAccounts.EMPLOYEE_ADVANCE).rial)
        // A loan partly paid back cannot be cancelled; cash repayment lowers what is left.
        assertEquals("INVALID_STATE:ADVANCE:PARTLY_REPAID", code { core.payroll.reverseAdvance(ReverseAdvance(id(), branch, loan, day, "اشتباه")) })
        core.payroll.repayAdvance(RepayAdvance(id(), branch, loan, cash, Money.of(5_000_000), JalaliCalendar.date(1405, 8, 2)))
        // Leaving in Aban: all that is still owed (15 M) comes out of the last payslip.
        core.payroll.endEmployment(EndEmployment(id(), branch, ali, JalaliCalendar.date(1405, 8, 30)))
        assertEquals(15_000_000, slip(8, approve = true).payslips.single().total(PayLine.Kind.ADVANCE).rial)
        assertEquals(0, core.ledger.balance(StandardAccounts.EMPLOYEE_ADVANCE).rial)
        assertTrue(core.labor.advances(ali).all { it.outstanding.isZero })
    }

    @Test fun aMistakenAdvanceIsCancelled() {
        start()
        val ali = core.payroll.registerEmployee(RegisterEmployee(id(), branch, "علی", "0012345679", Money.of(100_000_000), JalaliCalendar.date(1405, 1, 1))).resultId
        val a = core.payroll.giveAdvance(GiveAdvance(id(), branch, ali, cash, Money.of(10_000_000), day, 1, "")).resultId
        core.payroll.reverseAdvance(ReverseAdvance(id(), branch, a, day, "برای کس دیگری بود"))
        assertEquals(0, core.ledger.balance(StandardAccounts.EMPLOYEE_ADVANCE).rial)
        assertEquals(emptyList(), slip(7).payslips.single().lines)
    }

    @Test fun dailyAndHourlyWorkersArePaidForTheTimeWorked() {
        start()
        val day1 = JalaliCalendar.date(1405, 7, 3)
        val daily = core.payroll.registerEmployee(RegisterEmployee(id(), branch, "روزمزد", "0012345679", Money.of(5_000_000), JalaliCalendar.date(1405, 1, 1), payType = PayType.DAILY)).resultId
        val hourly = core.payroll.registerEmployee(RegisterEmployee(id(), branch, "ساعتی", "0084575948", Money.of(800_000), JalaliCalendar.date(1405, 1, 1), payType = PayType.HOURLY)).resultId
        listOf(day1, day1.plusDays(1), day1.plusDays(2)).forEach { core.payroll.recordAttendance(RecordAttendance(id(), branch, daily, it, 480, 0, 0)) }
        core.payroll.recordAttendance(RecordAttendance(id(), branch, hourly, day1, 300, 0, 0))
        core.payroll.recordAttendance(RecordAttendance(id(), branch, hourly, day1.plusDays(1), 90, 0, 0))
        val run = slip(7)
        assertEquals(15_000_000, run.payslips.single { it.employeeId == daily }.baseSalary.rial)    // 3 days × 5 M
        assertEquals(5_200_000, run.payslips.single { it.employeeId == hourly }.baseSalary.rial)    // 6.5 h × 800 k
    }

    @Test fun theEmployeeFile() {
        start()
        val ali = core.payroll.registerEmployee(RegisterEmployee(id(), branch, "علی", "0012345679", Money.of(100_000_000))).resultId
        assertTrue(Sheba.isValid("IR03 0170 0000 0011 0000 0000 00"))
        assertFalse(Sheba.isValid("IR040170000000110000000000"))
        assertEquals("INVALID_INPUT:sheba", code { core.payroll.updateProfile(UpdateEmployeeProfile(id(), branch, ali, EmployeeProfile(sheba = "IR040170000000110000000000"))) })
        core.payroll.updateProfile(UpdateEmployeeProfile(id(), branch, ali, EmployeeProfile(phone = "09120000000", sheba = "۰۳۰۱۷۰۰۰۰۰۰۰۱۱۰۰۰۰۰۰۰۰۰۰",
            maritalStatus = MaritalStatus.MARRIED, children = 2, jobTitle = "سرآشپز")))
        val p = core.personnel.employee(ali)!!.profile
        assertEquals("IR030170000000110000000000", p.sheba); assertEquals(2, p.children); assertEquals("سرآشپز", p.jobTitle)
        core.staffDocuments.setPhoto(ir.sabou.payroll.SetEmployeePhoto(id(), branch, ali, AttachmentInput("me.jpg", "image/jpeg", ByteArray(800) { 3 })))
        assertEquals("me.jpg", core.labor.photo(ali)?.fileName)
    }

    @Test fun movingToAnotherBranchAfterTheLastPaidMonth() {
        start()
        val second = Scope.Branch(core.identity.createBranch("شعبه دو"))
        val ali = core.payroll.registerEmployee(RegisterEmployee(id(), branch, "علی", "0012345679", Money.of(100_000_000), JalaliCalendar.date(1405, 1, 1))).resultId
        assertEquals("INVALID_STATE:EMPLOYEE:TRANSFER_NOT_AT_PAID_END", code {
            core.payroll.transfer(TransferEmployee(id(), branch, ali, second, JalaliCalendar.date(1405, 8, 1)))
        })
        slip(7, approve = true)
        core.payroll.transfer(TransferEmployee(id(), branch, ali, second, JalaliCalendar.date(1405, 8, 1)))
        assertEquals(second, core.personnel.employee(ali)!!.scope)
        assertEquals(branch, core.personnel.employee(ali)!!.branchOn(JalaliCalendar.date(1405, 7, 30)))
        val aban = core.payroll.calculate(CalculatePayroll(id(), second, month(8).first, month(8).second)).resultId
        assertEquals(100_000_000, core.payrollStore.run(aban)!!.payslips.single().baseSalary.rial)
        // Documents follow the person: still listed as needing attention in the new branch.
        assertTrue(core.labor.documentIssues(second, day).any { it.employee.id == ali })
    }

    @Test fun theClockCanRequireAPhoto() {
        start()
        val ali = core.payroll.registerEmployee(RegisterEmployee(id(), branch, "علی", "0012345679", Money.of(100_000_000), JalaliCalendar.date(1405, 1, 1))).resultId
        core.shifts.setPin(SetClockPin(id(), branch, ali, "2468"))
        core.payrollSettings.save(SavePayrollSettings(id(), PayrollSettings(clockPhotoRequired = true)))
        assertEquals("INVALID_INPUT:photo", code { punchAt(ClockPunch(id(), branch, ali, "2468", day, 540)) })
        punchAt(ClockPunch(id(), branch, ali, "2468", day, 540, AttachmentInput("p.jpg", "image/jpeg", ByteArray(600) { 1 })))
        assertEquals(1, core.labor.day(branch, day).single().photos.values.single().size)
    }

    /** A punch made at the time it states, in Iran: the main phone's clock is moved there first (SABOU-AUDIT-006). */
    private fun punchAt(c: ClockPunch) = run { now = c.date.epochDay * 86_400_000L + (c.minute - 210) * 60_000L
        // Moving the clock that far ends the owner's session; the clock screen stays signed in on the real phone.
        try { core.shifts.punch(c) } catch (e: ir.sabou.kernel.DomainException) {
            if (e.error.code != "AUTHENTICATION_REQUIRED") throw e
            core.identity.login("owner", "123456".toCharArray()); core.shifts.punch(c)
        }
    }
}
