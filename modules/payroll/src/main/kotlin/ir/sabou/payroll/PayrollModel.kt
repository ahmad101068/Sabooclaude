package ir.sabou.payroll

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Money
import ir.sabou.kernel.Scope

data class Employee(
    val id: GlobalId,
    val name: String,
    val nationalId: String,
    val scope: Scope.Branch,
    val monthlySalary: Money,
    val isActive: Boolean = true,
    /** First day of work; null = employed before any period the app knows of. */
    val startDate: BusinessDate? = null,
    /** Last day of work once the employment has ended. */
    val endDate: BusinessDate? = null,
) {
    /** Days of [from]..[to] on which this person was employed (0 when not employed at all). */
    fun employedDays(from: BusinessDate, to: BusinessDate): Int {
        if (!isActive && endDate == null) return 0
        val first = maxOf(from.epochDay, startDate?.epochDay ?: Long.MIN_VALUE)
        val last = minOf(to.epochDay, endDate?.epochDay ?: Long.MAX_VALUE)
        return if (last < first) 0 else (last - first + 1).toInt()
    }
}

data class AttendanceRecord(
    val id: GlobalId,
    val employeeId: GlobalId,
    val date: BusinessDate,
    val workedMinutes: Int,
    val overtimeMinutes: Int,
    val absentMinutes: Int,
)

data class Payslip(
    val employeeId: GlobalId,
    val baseSalary: Money,
    val absenceDeduction: Money,
    val overtimePay: Money,
    val gross: Money,
    val insurableBase: Money,
    val employeeInsurance: Money,
    val employerInsurance: Money,
    val unemploymentInsurance: Money,
    val taxableIncome: Money,
    val incomeTax: Money,
    val net: Money,
    /** Paid days when the person worked only part of the month (hired or left during it); null = whole month. */
    val payableDays: Int? = null,
)

enum class RunStatus { DRAFT, APPROVED, REVERSED }

data class PayrollRun(
    val id: GlobalId,
    val scope: Scope.Branch,
    val from: BusinessDate,
    val to: BusinessDate,
    val policyVersion: String,
    val payslips: List<Payslip>,
    val status: RunStatus,
    val calculatedBy: GlobalId,
    val approvedBy: GlobalId?,
    val accrualJournalId: GlobalId?,
)

data class SalaryPayment(
    val id: GlobalId,
    val runId: GlobalId,
    val employeeId: GlobalId,
    val treasuryAccountId: GlobalId,
    val amount: Money,
    val date: BusinessDate,
    val reversed: Boolean,
)

enum class LiabilityKind { INSURANCE, INCOME_TAX }

data class Remittance(val id: GlobalId, val scope: Scope.Branch, val kind: LiabilityKind, val amount: Money, val date: BusinessDate)

interface PersonnelStore {
    fun employee(id: GlobalId): Employee?
    fun employees(scope: Scope.Branch): List<Employee>
    fun saveEmployee(employee: Employee)
    fun attendance(employeeId: GlobalId, from: BusinessDate, to: BusinessDate): List<AttendanceRecord>
    fun attendanceOn(employeeId: GlobalId, date: BusinessDate): AttendanceRecord?
    fun saveAttendance(record: AttendanceRecord)
}

interface PayrollStore {
    fun run(id: GlobalId): PayrollRun?
    fun runs(scope: Scope.Branch): List<PayrollRun>
    fun saveRun(run: PayrollRun)
    fun payment(id: GlobalId): SalaryPayment?
    fun payments(runId: GlobalId): List<SalaryPayment>
    fun savePayment(payment: SalaryPayment)
    fun remittances(scope: Scope.Branch, kind: LiabilityKind): List<Remittance>
    fun saveRemittance(remittance: Remittance)
}
