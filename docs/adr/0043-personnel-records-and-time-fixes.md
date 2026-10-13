# ADR-0043 — Personnel: salary history, time corrections, rehire, settings

**Status:** Accepted (2026-10-13)

## Context
A review of the personnel module found: the salary could not be changed; a night shift's clock-out after midnight
became the next day's clock-in; a forgotten clock-out blocked the day with no way to fix it; a person who left could
not come back; a restaurant with a single owner could never approve payroll; confirming a day overwrote attendance
typed by hand; wrong clock PINs were forgotten when the app restarted.

## Decision
- **Salary history** — `ChangeSalary` adds a salary from a date (`Employee.salaryChanges`); a date inside an approved
  month is refused. A month with a change is paid day by day (each salary for its days over the month, or over the
  30-day basis when the person worked part of it). Labour cost uses the salary of the shift's day.
- **Night shifts** — a punch after midnight closes yesterday's open clock-in when it is at most 16 hours later; it is
  stored on yesterday's page as 24:00 + the time, so the night is one work day.
- **Punch corrections** — punches stay immutable: a manager adds a missing one (`AddPunch`, with a reason) or cancels a
  wrong one by a record that voids it (`VoidPunch`). Both are refused in an approved month.
- **Rehire** — `RehireEmployee` keeps the same record and personnel number; the earlier employment moves to
  `pastEmployments`, which still count for the months they cover. Registering the same national ID again points to it.
- **The only approver** — the person who calculated a run may approve it only when nobody else may approve payroll;
  the approval is audited as self-approved.
- **Attendance source** — records are `MANUAL` or `CLOCK`; confirming a day keeps manual records unless asked.
- **PIN lock** — wrong clock PINs are counted in the database (`clock_pins.fails`, `locked_until`, schema 20): five
  lock for 5 minutes, each further one doubles it, up to a day. The bus now supports `failAfterCommit`: the handler's
  writes are kept and the command still fails (nothing is recorded for idempotency).
- **Payroll settings** (owner): deduct planned breaks (off by default), overtime pricing (statutory / fixed per hour /
  none), minimum daily overtime, lateness grace.

## Stage 2 — the employee file, advances, pay types, moves, clock photos
- **File** — `EmployeeProfile`: phone, address, emergency contact, father's name, job title, insurance number, Sheba
  (checked, ISO 13616), marital status, children, contract type and end; a photo (`EMPLOYEE_PHOTO` attachment). The
  audit names the fields changed, not their values.
- **Advances and loans** — `GiveAdvance` pays from a cash or bank account into «مساعده پرسنل» (1401); each payslip
  takes one installment (`PayLine.Kind.ADVANCE`, an advance in one), as far as net pay allows; what is still owed in the
  last month of work is taken whole. Cash repayment (`RepayAdvance`) and cancelling a mistake before anything was paid
  back (`ReverseAdvance`). Approval credits 1401 with the installments. Schema 21: `salary_advances`.
- **Payslip lines** — `Payslip.lines`: earnings (with insurable / taxable flags), advance installments, deductions;
  deductions never take net below zero.
- **Pay types** — monthly, daily (a day's wage for each day with work) and hourly (for the minutes worked); a salary
  change may change the type from its date. Overtime and absence are priced on a month-equivalent rate; absence only
  reduces monthly pay.
- **Moving branches** — `TransferEmployee` from the day after the old branch's last approved month (or before the
  person started), with no later shifts, attendance or draft run there. Each branch pays only its days
  (`Employee.branchOn`); documents follow the person.
- **Clock photos** — an option (off by default): the clock takes a photo with each punch (`PUNCH_PHOTO`), shown beside
  it on the day's time.
