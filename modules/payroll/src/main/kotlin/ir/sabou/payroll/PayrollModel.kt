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
    val birthDate: ir.sabou.kernel.JalaliDay? = null,
    /** Personnel number: assigned in registration order across the whole organization, never reused. */
    val personnelNumber: Int = 0,
    /** Salary changes after [monthlySalary] (the first salary), each from a date; in date order. */
    val salaryChanges: List<SalaryChange> = emptyList(),
    /** Earlier employments, for a person who left and came back. */
    val pastEmployments: List<Employment> = emptyList(),
) {
    /** Days of [from]..[to] on which this person was employed (0 when not employed at all). */
    fun employedDays(from: BusinessDate, to: BusinessDate): Int {
        val past = pastEmployments.sumOf { overlap(from, to, it.start, it.end) }
        if (!isActive && endDate == null) return past
        return past + overlap(from, to, startDate, endDate)
    }

    /** Whether [date] is a working day of an employment of this person. */
    fun employedOn(date: BusinessDate) = employedDays(date, date) == 1

    /** The monthly salary in force on [date]. */
    fun salaryOn(date: BusinessDate): Money = salaryChanges.lastOrNull { it.from <= date }?.monthlySalary ?: monthlySalary

    /** The salary in force on each employed day of [from]..[to], as (salary, days) runs in date order. */
    fun salaryDays(from: BusinessDate, to: BusinessDate): List<Pair<Money, Int>> {
        val out = ArrayList<Pair<Money, Int>>()
        var day = from
        while (day <= to) {
            if (employedOn(day)) {
                val s = salaryOn(day)
                if (out.isNotEmpty() && out.last().first == s) out[out.size - 1] = s to out.last().second + 1 else out += s to 1
            }
            day = day.plusDays(1)
        }
        return out
    }

    private fun overlap(from: BusinessDate, to: BusinessDate, start: BusinessDate?, end: BusinessDate?): Int {
        val first = maxOf(from.epochDay, start?.epochDay ?: Long.MIN_VALUE)
        val last = minOf(to.epochDay, end?.epochDay ?: Long.MAX_VALUE)
        return if (last < first) 0 else (last - first + 1).toInt()
    }
}

/** A new monthly salary from [from] (yearly raise, promotion), with why. */
data class SalaryChange(val from: BusinessDate, val monthlySalary: Money, val reason: String) : java.io.Serializable

/** A finished employment of someone who later came back. */
data class Employment(val start: BusinessDate?, val end: BusinessDate) : java.io.Serializable

data class AttendanceRecord(
    val id: GlobalId,
    val employeeId: GlobalId,
    val date: BusinessDate,
    val workedMinutes: Int,
    val overtimeMinutes: Int,
    val absentMinutes: Int,
    /** Typed by a person, or taken from the time clock; confirming a day keeps typed records unless told otherwise. */
    val source: Source = Source.MANUAL,
) {
    enum class Source { MANUAL, CLOCK }
}

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
    /** The next free personnel number of the organization (all branches). */
    fun nextPersonnelNumber(): Int
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
