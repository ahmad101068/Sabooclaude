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
