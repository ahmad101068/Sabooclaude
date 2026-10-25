# ADR-0064: Management dashboard

Status: accepted (2026-10)

## Context

The owner wants one screen that shows the state of the restaurant at a glance — like a BI dashboard: headline
figures with their movement, the week's sales trend, sales by category and by way of payment, monthly costs and
profit, best-selling dishes, suggested actions and the state of operations. Every figure already exists in a
report; what is missing is a single summary, and menu categories to group sales by.

## Decision

- `ManagementDashboards.of(branch, from, to)` (core) builds `ManagementDashboard` from the same reports the app
  already has — profit and loss, product mix, menu engineering, sales settlements, the scorecard (ADR-0061) and
  field records (ADR-0062). It needs LEDGER_VIEW and access to the branch. Nothing is computed twice in a
  different way: a dashboard figure equals the figure of the report it links to.
- Headline figures: the last day's sales against the day before; gross profit and food cost % against the
  comparable previous period; customer satisfaction (share of ratings 4–5 from the feedback records); open alerts.
  Preparation time is shown as not measured — the point-of-sale exports do not carry it — rather than invented.
- Week: the seven days to `to` against the seven before, day by day.
- Categories: a menu item gets an optional `category` (≤ 40 characters, `SetMenuItemCategory`, RECIPE_MANAGE,
  audited `MENU_ITEM_CATEGORY`); items without one are grouped as «بدون دسته» and the card says where to set it.
- Payments: cash, card, bank/online, cheque and credit, from the settlements of posted sales and the kind of the
  treasury account.
- Months: six Jalali months of personnel cost, food cost and operating profit.
- Top five dishes by sales with their actual margin and menu-engineering class; the first four scorecard findings
  as suggested actions; the five area scores; the latest five sales recorded (posted or reversed). The app records
  daily sales, not live orders, and the card says so.
- Screen: one column on a phone, two at ≥ 600 dp, three at ≥ 900 dp; each card opens its report. The dashboard
  exports to Excel/PDF like every report (`ReportTables.managementDashboard`).

## Consequences

- One more read path over existing reports; it reads profit and loss for ten small periods, well within the
  scorecard's budget on the volume benchmark.
- Older menu items read with an empty category; no migration is needed (documents are JSON).
