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
    override fun nextPersonnelNumber() = (employees.values.maxOfOrNull { it.personnelNumber } ?: 0) + 1
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
    private val advances = LinkedHashMap<GlobalId, ir.sabou.payroll.Advance>()
    override fun advance(id: GlobalId) = advances[id]
    override fun advances(employeeId: GlobalId) = advances.values.filter { it.employeeId == employeeId }
    override fun advancesOf(scope: Scope.Branch) = advances.values.filter { it.scope == scope }
    override fun saveAdvance(advance: ir.sabou.payroll.Advance) { advances[advance.id] = advance }
    override fun snapshot(): Any = listOf(LinkedHashMap(runs), LinkedHashMap(payments), remittances.toList(), LinkedHashMap(advances))
    @Suppress("UNCHECKED_CAST")
    override fun restore(snapshot: Any) {
        val (r, p, m, a) = snapshot as List<Any>
        runs.clear(); runs.putAll(r as Map<GlobalId, PayrollRun>); payments.clear(); payments.putAll(p as Map<GlobalId, SalaryPayment>)
        remittances.clear(); remittances.addAll(m as List<Remittance>)
        advances.clear(); advances.putAll(a as Map<GlobalId, ir.sabou.payroll.Advance>)
    }
}

class InMemoryPolicyStore : ir.sabou.payroll.PolicyStore, Transactional {
    private val rows = mutableListOf<ir.sabou.payroll.StatutoryPolicy>()
    override fun all() = rows.toList()
    override fun save(policy: ir.sabou.payroll.StatutoryPolicy) { rows += policy }
    override fun snapshot(): Any = rows.toList()
    @Suppress("UNCHECKED_CAST")
    override fun restore(snapshot: Any) { rows.clear(); rows.addAll(snapshot as List<ir.sabou.payroll.StatutoryPolicy>) }
}

class InMemoryShiftStore : ir.sabou.payroll.ShiftStore {
    private val shifts = LinkedHashMap<GlobalId, ir.sabou.payroll.Shift>()
    private val punches = ArrayList<ir.sabou.payroll.Punch>()
    private val pins = HashMap<GlobalId, String>()
    override fun shift(id: GlobalId) = shifts[id]
    override fun shifts(scope: ir.sabou.kernel.Scope.Branch, from: ir.sabou.kernel.BusinessDate, to: ir.sabou.kernel.BusinessDate) =
        shifts.values.filter { it.scope == scope && it.date >= from && it.date <= to }.sortedBy { it.date }
    override fun saveShift(shift: ir.sabou.payroll.Shift) { shifts[shift.id] = shift }
    override fun deleteShift(id: GlobalId) { shifts.remove(id) }
    override fun punches(scope: ir.sabou.kernel.Scope.Branch, from: ir.sabou.kernel.BusinessDate, to: ir.sabou.kernel.BusinessDate) =
        punches.filter { it.scope == scope && it.date >= from && it.date <= to }
    override fun savePunch(punch: ir.sabou.payroll.Punch) { punches += punch }
    override fun clockPin(employeeId: GlobalId) = pins[employeeId]
    override fun saveClockPin(employeeId: GlobalId, hash: String?) { if (hash == null) pins.remove(employeeId) else pins[employeeId] = hash }
    private val pinFailures = HashMap<GlobalId, ir.sabou.payroll.PinFailures>()
    override fun pinFailures(employeeId: GlobalId) = pinFailures[employeeId] ?: ir.sabou.payroll.PinFailures(0, 0L)
    override fun savePinFailures(employeeId: GlobalId, failures: ir.sabou.payroll.PinFailures) { pinFailures[employeeId] = failures }
}

class InMemoryStaffDocumentStore : ir.sabou.payroll.StaffDocumentStore {
    private val rows = ArrayList<ir.sabou.payroll.StaffDocument>()
    override fun byId(id: GlobalId) = rows.firstOrNull { it.id == id }
    override fun of(employeeId: GlobalId) = rows.filter { it.employeeId == employeeId }
    override fun all(scope: ir.sabou.kernel.Scope.Branch) = rows.filter { it.scope == scope }
    override fun save(document: ir.sabou.payroll.StaffDocument) { rows += document }
}
