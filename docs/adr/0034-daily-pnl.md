# ADR-0034: Daily P&L with accruals, against budget

Status: accepted · 2026-10

## Context

Budget-vs-actual existed for any period, and the P&L by day. But a day's P&L flatters the day: salaries are booked
when the payroll month is approved and rent when its bill is recorded, so 29 days look profitable and one looks
terrible. Restaurant back-office tools show a «daily flash P&L» with accrued labour and overheads.

## Decision

- `Reports.dailyPnl(branch, date)` groups the books into **sales**, **food cost** (cost of sales, waste, comps,
  stock variance), **labour** (salaries, employer insurance) and **other expenses**, for the day and the month so far,
  each against the budget pro-rated by days.
- Sales and food cost are always the books (they are recorded daily).
- Labour and other expense accounts **with a budget this month** are accrued: the day carries the budget per day;
  the month so far carries the actual once anything is booked, else the budget so far. Every such figure is marked
  as an estimate on screen. Accounts without a budget always show their actual.
- The month's sales run rate (sales so far ÷ days elapsed × days in month) is shown against the month's sales budget.
- Read-only; no tables.

## Consequences

- Without budgets the report is the plain books; with them it is a fair daily picture. Setting a monthly budget for
  salaries and rent is what makes it useful, and the screen links to budgets.
