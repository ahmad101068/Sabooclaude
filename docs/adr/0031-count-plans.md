# ADR-0031: Count plans (repeating, partial counts)

Status: accepted · 2026-10

## Context

Counts were blind, ordered by shelf, and could be partial. But nothing said **what** to count **when**:
losses on meat or saffron showed up only at the month-end count. Back-office practice (and tools such as
MarketMan, R365) is to count the few high-value items daily and the rest weekly or monthly.

## Decision

- A **count plan** (`count_plans`, schema 12) names a store, a list of items (empty = every active item) and a
  frequency: daily, weekly on a weekday, or monthly on a day of the Jalali month (a shorter month uses its last day).
- Each plan has a current period (from its latest scheduled day). It is **due** until a count of the plan
  (pending or approved, not rejected) is submitted on or after that day.
- A plan count records `planId` and **must cover every item of the plan** (missing items are refused; the sheet
  offers «zero the rest»). Free counts remain as before.
- The sheet can be narrowed to one shelf and shows how many items are counted.
- Due plans show as a home to-do and a phone alert (`COUNT_DUE`), once per period.
- The plans screen suggests the «A» items: those that together hold 70 % of the store's stock value.
- Approval, variance reasons, and the separation of duties are unchanged.

## Consequences

- Plans are retired (inactive), never deleted, so past counts keep their plan.
- Kernel now owns `JalaliCalendar.weekdayIndex` and `monthLength` (formatting and the dashboard use them).
