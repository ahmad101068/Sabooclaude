package ir.sabou.payroll.memory

import ir.sabou.kernel.BusinessDate
import ir.sabou.kernel.GlobalId
import ir.sabou.kernel.Scope
import ir.sabou.payroll.AttendanceRecord
import ir.sabou.payroll.Employee
import ir.sabou.payroll.LiabilityKind
import ir.sabou.payroll.PayrollRun
import ir.sabou.payroll.PayrollStore
import ir.sabou.payroll.PersonnelStore
import ir.sabou.payroll.Remittance
import ir.sabou.payroll.SalaryPayment
import ir.sabou.platform.memory.Transactional

class InMemoryPersonnelStore : PersonnelStore, Transactional {
    private val employees = LinkedHashMap<GlobalId, Employee>()
    private val attendance = LinkedHashMap<GlobalId, AttendanceRecord>()
    override fun employee(id: GlobalId) = employees[id]
    override fun employees(scope: Scope.Branch) = employees.values.filter { it.scope == scope }
    override fun saveEmployee(employee: Employee) { employees[employee.id] = employee }
    override fun attendance(employeeId: GlobalId, from: BusinessDate, to: BusinessDate) =
        attendance.values.filter { it.employeeId == employeeId && it.date >= from && it.date <= to }
    override fun attendanceOn(employeeId: GlobalId, date: BusinessDate) = attendance.values.firstOrNull { it.employeeId == employeeId && it.date == date }
    override fun saveAttendance(record: AttendanceRecord) { attendance[record.id] = record }
    override fun snapshot(): Any = LinkedHashMap(employees) to LinkedHashMap(attendance)
    @Suppress("UNCHECKED_CAST")
    override fun restore(snapshot: Any) {
        val (e, a) = snapshot as Pair<Map<GlobalId, Employee>, Map<GlobalId, AttendanceRecord>>
        employees.clear(); employees.putAll(e); attendance.clear(); attendance.putAll(a)
    }
}

class InMemoryPayrollStore : PayrollStore, Transactional {
    private val runs = LinkedHashMap<GlobalId, PayrollRun>()
    private val payments = LinkedHashMap<GlobalId, SalaryPayment>()
    private val remittances = mutableListOf<Remittance>()
    override fun run(id: GlobalId) = runs[id]
    override fun runs(scope: Scope.Branch) = runs.values.filter { it.scope == scope }
    override fun saveRun(run: PayrollRun) { runs[run.id] = run }
    override fun payment(id: GlobalId) = payments[id]
    override fun payments(runId: GlobalId) = payments.values.filter { it.runId == runId }
    override fun savePayment(payment: SalaryPayment) { payments[payment.id] = payment }
    override fun remittances(scope: Scope.Branch, kind: LiabilityKind) = remittances.filter { it.scope == scope && it.kind == kind }
    override fun saveRemittance(remittance: Remittance) { remittances += remittance }
    override fun snapshot(): Any = Triple(LinkedHashMap(runs), LinkedHashMap(payments), remittances.toList())
    @Suppress("UNCHECKED_CAST")
    override fun restore(snapshot: Any) {
        val (r, p, m) = snapshot as Triple<Map<GlobalId, PayrollRun>, Map<GlobalId, SalaryPayment>, List<Remittance>>
        runs.clear(); runs.putAll(r); payments.clear(); payments.putAll(p); remittances.clear(); remittances.addAll(m)
    }
}
