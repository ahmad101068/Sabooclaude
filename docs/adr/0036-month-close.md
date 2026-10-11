# ADR-0036: Month-end close checklist

Status: accepted · 2026-10

## Context

The ledger could lock a period (`ClosePeriod` / `ReopenPeriod`), but nothing in the app used it and nothing said
whether a month was ready: an open sales day, a purchase line still in 1302 or an unreconciled bank account would
be locked in silently. Accounting practice (and back-office tools) close a month against a checklist.

## Decision

- `Closing.checklist(year, month)` checks the Jalali month across the books:
  - **Blocking**: every day with posted sales closed; no sale drafts; purchase review queue empty (1302 = 0);
    inter-branch account nets to zero.
  - **Warnings**: invoice/order differences unreviewed; pending stock counts; open consumption shortages; a store
    with stock and no approved count this month; payroll not approved through month-end for a branch with
    employees; depreciation not run through month-end for a branch with assets; a bank account whose latest
    statement does not reach month-end or is not fully reconciled; cheques past due still in hand / not cleared.
- `Closing.close` refuses while any blocking item remains (`PERIOD:CHECKLIST:<key>`), then locks the month with the
  existing `ClosePeriod`; `reopen` needs a reason (`ReopenPeriod`, audited). The screen links each item to where it
  is fixed and only offers closing a month that has ended.
- `PeriodStore.locks()` lists locks so a month's state can be shown.

## Consequences

- Warnings never block: a small restaurant without payroll in the app or without bank statements can still close.
- Moadian readiness (P2-5) can add its own checks to the same list.
