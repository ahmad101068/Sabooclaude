# ADR-0038: Shifts, labour cost and a PIN time clock

Status: accepted · 2026-10

## Context

Attendance was typed per person per day (worked, overtime, absent minutes). There was no schedule, so labour cost
was known only after payroll, and nothing compared who was planned with who came.

## Decision

- **Shifts** (`shifts`): employee, day, start and end (may pass midnight, up to 16 hours), break, station. A person
  cannot have overlapping shifts (checked across neighbouring days). A week can be copied to an empty week.
  Permission `SHIFT_MANAGE`.
- **Labour view** per day: planned hours and their cost — monthly salary plus the employer's insurance share over the
  standard month of the statutory policy in force (192 hours when none is configured) — against the **labour budget**
  (salaries and employer insurance budgets pro-rated, the same budgets as ADR-0034) and against **expected sales**
  (the ADR-0033 forecast × menu prices), as labour % of sales.
- **Time clock** on the shared phone (permission `TIME_CLOCK`, given to cashiers and storekeepers): the person picks
  their name and types their own 4–6 digit PIN (PBKDF2, stored apart from the employee; never in a fingerprint or
  audit). Five wrong PINs lock that person for 5 minutes. The phone's date must be within a day of the clock.
  Punches (`punches`) are immutable; IN and OUT alternate.
- **Day time**: punches paired IN→OUT; worked time less the planned break (unless they clocked out for it); late
  beyond 10 minutes of grace; overtime beyond the plan; absence short of it. **Confirming a day** writes every
  employee's attendance in one transaction through the existing attendance rules (employment dates, approved
  payroll months), refused while someone has clocked in without clocking out.

## Consequences

- Payroll is unchanged: it still reads attendance; the clock only fills it.
- Corrections of a wrong punch are made on the attendance record (audited), not by editing punches.
