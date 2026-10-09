package ir.sabou.payroll

import ir.sabou.kernel.BranchId
import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.Clock
import ir.sabou.kernel.DomainException
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Scope
import ir.sabou.ledger.Ledger
import ir.sabou.ledger.LedgerAccessRegistry
import ir.sabou.ledger.StandardAccounts
import ir.sabou.ledger.memory.InMemoryAccountStore
import ir.sabou.ledger.memory.InMemoryJournalStore
import ir.sabou.ledger.memory.InMemoryPeriodStore
import ir.sabou.payroll.memory.InMemoryPayrollStore
import ir.sabou.payroll.memory.InMemoryPersonnelStore
import ir.sabou.platform.Actor
import ir.sabou.platform.CommandBus
import ir.sabou.platform.ModuleId
import ir.sabou.platform.Role
import ir.sabou.platform.memory.InMemoryAuditStore
import ir.sabou.platform.memory.InMemoryEventLog
import ir.sabou.platform.memory.InMemoryIdempotencyStore
import ir.sabou.platform.memory.InMemoryUnitOfWork
import ir.sabou.platform.memory.MutableSession
import ir.sabou.treasury.OpenTreasuryAccount
import ir.sabou.treasury.ReceiptPurpose
import ir.sabou.treasury.RecordReceipt
import ir.sabou.treasury.TreasuryGateway
import ir.sabou.treasury.TreasuryKind
import ir.sabou.treasury.TreasuryOperations
import ir.sabou.treasury.memory.InMemoryMovementStore
import ir.sabou.treasury.memory.InMemoryTreasuryAccountStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PayrollTest {
    private val branch = Scope.Branch(BranchId(GlobalId.new()))
    private val other = Scope.Branch(BranchId(GlobalId.new()))
    private val owner = Actor(GlobalId.new(), "owner", Role.OWNER, emptySet())
    private val accountant = Actor(GlobalId.new(), "accountant", Role.ACCOUNTANT, setOf(branch.branchId))
    private val session = MutableSession(owner)
    private val uow = InMemoryUnitOfWork()
    private val journals = InMemoryJournalStore()
    private val tAccounts = InMemoryTreasuryAccountStore(); private val movements = InMemoryMovementStore()
    private val personnel = InMemoryPersonnelStore(); private val payrollStore = InMemoryPayrollStore()
    private val bus = CommandBus(session, uow, InMemoryIdempotencyStore().also { uow.register(it) },
        InMemoryAuditStore().also { uow.register(it) }, InMemoryEventLog(), Clock { 1L }) { "e1" }
    private val registry = LedgerAccessRegistry()
    private val ledger = Ledger(registry, InMemoryAccountStore(StandardAccounts.chart()), journals, InMemoryPeriodStore())
    private val treasuryCap = registry.issue(ModuleId.TREASURY)
    private val treasury = TreasuryGateway(ledger, treasuryCap, tAccounts, movements)
    private val treasuryOps = TreasuryOperations(bus, treasury, treasuryCap, tAccounts)
    private val from = BusinessDate(20_000)
    private val to = BusinessDate(20_029)

    /** Test parameters only; real yearly values are configured after professional review. */
    private val policy = StatutoryPolicy(
        version = "TEST", from = BusinessDate(19_000), to = BusinessDate(21_000), standardMonthlyMinutes = 11_520,
        overtimeMultiplierPercent = 140, employeeInsuranceBp = 700, employerInsuranceBp = 2_000, unemploymentInsuranceBp = 300,
        maxInsurableMonthly = Money.of(300_000_000), insuranceTaxExemptNumerator = 2, insuranceTaxExemptDenominator = 7,
        taxBrackets = listOf(TaxBracket(Money.of(10_000_000), 0), TaxBracket(Money.of(20_000_000), 1_000), TaxBracket(null, 2_000)),
    )
    private val ops = PayrollOperations(bus, ledger, registry.issue(ModuleId.PAYROLL), treasury, personnel, payrollStore, StatutoryPolicyRegistry(listOf(policy)))

    init { uow.register(journals, tAccounts, movements, personnel, payrollStore) }

    private fun code(block: () -> Unit) = assertFailsWith<DomainException> { block() }.error.code
    private fun rial(v: Long) = Money.of(v)
    private val cash = treasuryOps.openAccount(OpenTreasuryAccount(GlobalId.new(), branch, "صندوق", TreasuryKind.CASH)).resultId
    private val chef = ops.registerEmployee(RegisterEmployee(GlobalId.new(), branch, "سرآشپز", "0084575948", rial(30_000_000))).resultId

    init {
        treasuryOps.receipt(RecordReceipt(GlobalId.new(), branch, cash, ReceiptPurpose.OWNER_CAPITAL, rial(100_000_000), from, "آورده"))
        ops.recordAttendance(RecordAttendance(GlobalId.new(), branch, chef, from, 0, 0, 960))
        ops.recordAttendance(RecordAttendance(GlobalId.new(), branch, chef, from.plusDays(1), 480, 576, 0))
        ops.recordAttendance(RecordAttendance(GlobalId.new(), branch, chef, from.plusDays(2), 480, 576, 0))
    }

    private fun calculate(): GlobalId {
        session.actor = accountant
        return ops.calculate(CalculatePayroll(GlobalId.new(), branch, from, to)).resultId.also { session.actor = owner }
    }

    @Test fun payslipFollowsThePolicyExactly() {
        val slip = payrollStore.run(calculate())!!.payslips.single()
        assertEquals(2_500_000, slip.absenceDeduction.rial)
        assertEquals(4_200_000, slip.overtimePay.rial)
        assertEquals(31_700_000, slip.gross.rial)
        assertEquals(2_219_000, slip.employeeInsurance.rial)
        assertEquals(31_066_000, slip.taxableIncome.rial)        // 2/7 of employee insurance exempt
        assertEquals(3_213_200, slip.incomeTax.rial)
        assertEquals(26_267_800, slip.net.rial)
        assertEquals(6_340_000, slip.employerInsurance.rial)
        assertEquals(951_000, slip.unemploymentInsurance.rial)
    }

    @Test fun theCalculatorCannotApproveAndApprovalPostsABalancedAccrual() {
        val run = calculate()
        session.actor = Actor(accountant.userId, "accountant", Role.OWNER, emptySet())   // same person, even with rights
        assertEquals("INVALID_STATE:PAYROLL_RUN:SAME_PERSON_CALCULATED", code { ops.approve(ApprovePayroll(GlobalId.new(), branch, run)) })
        session.actor = owner
        ops.approve(ApprovePayroll(GlobalId.new(), branch, run))
        assertEquals(31_700_000, ledger.balance(StandardAccounts.SALARIES, branch).rial)
        assertEquals(-26_267_800, ledger.balance(StandardAccounts.PAYROLL_PAYABLE, branch).rial)
        assertEquals(-(2_219_000L + 6_340_000 + 951_000), ledger.balance(StandardAccounts.INSURANCE_PAYABLE, branch).rial)
        assertEquals(-3_213_200, ledger.balance(StandardAccounts.PAYROLL_TAX_PAYABLE, branch).rial)
    }

    @Test fun salaryIsPaidUpToNetAndLiabilitiesAreRemitted() {
        val run = calculate(); ops.approve(ApprovePayroll(GlobalId.new(), branch, run))
        ops.pay(PaySalary(GlobalId.new(), branch, run, chef, cash, rial(20_000_000), to))
        assertEquals("INVALID_STATE:PAYSLIP:AMOUNT_EXCEEDS_UNPAID", code { ops.pay(PaySalary(GlobalId.new(), branch, run, chef, cash, rial(6_267_801), to)) })
        ops.pay(PaySalary(GlobalId.new(), branch, run, chef, cash, rial(6_267_800), to))
        assertEquals(0, ledger.balance(StandardAccounts.PAYROLL_PAYABLE, branch).rial)
        ops.remit(RemitLiability(GlobalId.new(), branch, LiabilityKind.INSURANCE, cash, rial(9_510_000), to))
        assertEquals(0, ledger.balance(StandardAccounts.INSURANCE_PAYABLE, branch).rial)
        assertEquals("INVALID_STATE:PAYROLL_LIABILITY:AMOUNT_EXCEEDS_OUTSTANDING", code {
            ops.remit(RemitLiability(GlobalId.new(), branch, LiabilityKind.INSURANCE, cash, rial(1), to))
        })
        assertEquals(100_000_000L - 26_267_800 - 9_510_000, treasury.balance(cash))
    }

    @Test fun paidOrRemittedPayrollCannotBeReversedAndApprovedPeriodLocksAttendance() {
        val run = calculate(); ops.approve(ApprovePayroll(GlobalId.new(), branch, run))
        val payment = ops.pay(PaySalary(GlobalId.new(), branch, run, chef, cash, rial(1_000_000), to)).resultId
        assertEquals("INVALID_STATE:PAYROLL_RUN:HAS_PAYMENTS", code { ops.reverse(ReversePayroll(GlobalId.new(), branch, run, to, "اشتباه")) })
        assertEquals("INVALID_STATE:ATTENDANCE:PERIOD_APPROVED", code {
            ops.recordAttendance(RecordAttendance(GlobalId.new(), branch, chef, from.plusDays(3), 480, 0, 0))
        })
        ops.reversePayment(ReverseSalaryPayment(GlobalId.new(), branch, payment, to, "پرداخت اشتباه"))
        ops.reverse(ReversePayroll(GlobalId.new(), branch, run, to, "محاسبه اشتباه"))
        assertEquals(0, ledger.balance(StandardAccounts.SALARIES).rial)
        assertEquals(100_000_000, treasury.balance(cash))
    }

    @Test fun inputRulesAndFailClosedPolicy() {
        assertEquals("INVALID_INPUT:nationalId", code { ops.registerEmployee(RegisterEmployee(GlobalId.new(), branch, "گارسون", "0084575949", rial(1))) })
        assertTrue(NationalId.isValid("۰۰۸۴۵۷۵۹۴۸"))
        assertFalse(NationalId.isValid("1111111111"))
        assertEquals("INVALID_STATE:PAYROLL_POLICY:NOT_CONFIGURED", code {
            ops.calculate(CalculatePayroll(GlobalId.new(), branch, BusinessDate(30_000), BusinessDate(30_029)))
        })
    }

    @Test fun ownerDefinesNonOverlappingYearlyPolicies() {
        val store = ir.sabou.payroll.memory.InMemoryPolicyStore().also { uow.register(it) }
        val admin = PolicyAdministration(bus, store)
        val next = policy.copy(version = "1406", from = BusinessDate(21_001), to = BusinessDate(21_365))
        admin.define(DefinePayrollPolicy(GlobalId.new(), next))
        assertEquals(next, admin.registry.forPeriod(BusinessDate(21_010), BusinessDate(21_039)))
        assertEquals("INVALID_STATE:PAYROLL_POLICY:OVERLAPPING_PERIOD",
            code { admin.define(DefinePayrollPolicy(GlobalId.new(), next.copy(version = "X", from = BusinessDate(21_300), to = BusinessDate(21_400)))) })
        assertEquals("INVALID_STATE:PAYROLL_POLICY:DUPLICATE_VERSION",
            code { admin.define(DefinePayrollPolicy(GlobalId.new(), next.copy(from = BusinessDate(22_000), to = BusinessDate(22_100)))) })
        session.actor = accountant
        assertEquals("PERMISSION_DENIED:PAYROLL_APPROVE",
            code { admin.define(DefinePayrollPolicy(GlobalId.new(), next.copy(version = "Y", from = BusinessDate(23_000), to = BusinessDate(23_100)))) })
        assertEquals(1, admin.policies().size)
    }

    @Test fun aRunCoversExactlyOneMonthAndDuplicateIdsInPersianDigitsAreCaught() {
        session.actor = accountant
        assertEquals("INVALID_INPUT:period", code { ops.calculate(CalculatePayroll(GlobalId.new(), branch, from, from)) })
        session.actor = owner
        assertEquals("INVALID_STATE:EMPLOYEE:DUPLICATE_NATIONAL_ID",
            code { ops.registerEmployee(RegisterEmployee(GlobalId.new(), branch, "تکراری", "۰۰۸۴۵۷۵۹۴۸", rial(1_000))) })
    }

    @Test fun branchScopeAppliesToPayroll() {
        session.actor = accountant
        assertTrue(code { ops.calculate(CalculatePayroll(GlobalId.new(), other, from, to)) }.startsWith("SCOPE_DENIED"))
    }
}
