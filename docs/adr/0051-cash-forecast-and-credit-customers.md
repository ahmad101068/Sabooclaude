# ADR-0051: Cash forecast and credit customers

Status: accepted (2026-10)

## Context

The books know every dated obligation (receivables, cheques, supplier invoices, approved payroll, budgets), and the
sales forecast (ADR-0041) knows the weekly pattern, but nothing put them together: the owner could not see whether
the till and the bank would cover the coming weeks. A large share of the restaurant's sales are on credit to
companies, so when they pay, and how much depends on one of them, matters as much as the sales themselves.

## Decision

- **`CashFlows.forecast(branch, today, scenario)`** (LEDGER_VIEW): 13 weeks from today, starting from the branch's
  cash, bank, card terminal and petty cash balances. Items, each dated and marked *document* or *estimate*:
  - receivables at due date + that customer's usual delay (amount-weighted, over debts fully collected);
  - customers' cheques in hand or deposited at their due date; our uncleared cheques at theirs, bounced ones now;
  - supplier invoices still owed at their due date;
  - approved unpaid salaries now; insurance and salary tax owed at the month's end; then each month like the last
    approved payroll (net at the month's end, dues at the next);
  - sales to come: the 7-day normal pattern of the sales forecast repeated, × special days, × the scenario; split
    by how the last 8 weeks were settled (cash/card now, credit after the usual collection time);
  - goods to buy: forecast sales × the last 8 weeks' purchases per Rial sold, paid after the usual supplier terms;
  - running costs: each budgeted expense month (not stock, payroll, depreciation or other non-cash accounts) less
    what is already booked this month, spread over its remaining days.
- Weeks open where the previous one closed; the items add up exactly to the last closing balance.
- **Scenarios**: base, cautious (sales −15 %, every customer 30 days later), hopeful (sales +10 %), and one
  customer buying a given share less (their credit sales and the goods for them leave the forecast).
- Owed amounts with no date in the books (sales tax, loans, asset purchase debt, customers' bounced cheques) are
  listed beside the forecast, not put on a date.
- **`CashFlows.customers(branch, today)`**: each credit customer's sales in the last 90 days and share of the
  branch's sales, outstanding and overdue, usual delay after the due date and days from sale to collection; top-1
  and top-3 shares.
- **Alert `CASH_LOW`**: the base case ends a week below zero within 4 weeks — only once there are sales to forecast
  from (without them the forecast sees bills but no income; bills have their own alert).
- Screens «پیش‌بینی نقدینگی» and «مشتریان نسیه» under reports; both export to Excel/PDF.

## Consequences

- Everything is exact Rial arithmetic from the books and stated patterns; the assumptions are shown with the
  forecast so the owner can judge them. No model guesses any figure.
- Without a budget for rent and running costs, or without an approved payroll, those lines are missing and the
  forecast says so.
