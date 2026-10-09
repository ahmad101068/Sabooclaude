# ADR-0011 — Management reports, export, prep items and yields

**Status:** Accepted (2026-10-09)

## Context
The competitor review showed the gaps that matter most to an owner: no profit and loss by day and branch, no food/labour cost ratios, no end-of-day summary, no item margins, no actual-vs-theoretical usage, nothing that could leave the phone as a file, no in-house prep items and no trim/cook yield in recipes.

## Decision
- **Reports are a read path** (`ir.sabou.core.Reports`), with the same permission and scope checks as `Overview`. Nothing is stored twice: figures come from the journal (`dailyTotals`, `entriesTouching`), sales, stock movements and attendance.
  - Profit and loss for any period, all visible scopes or one branch, per branch and per day; every line drills down to its journal lines. Ratios are on food sales (4101): food cost = cost of sales + waste + stock variance; labour = salaries + employer insurance (booked when a payroll month is approved).
  - End-of-day flash, product mix with share and per-portion margin (today's average costs, falling back to the last purchase price), actual vs theoretical usage per item, attendance and overtime totals.
- **Stock movement index** (schema v2): an immutable `stock_movement_index` row per movement line (item, location, date, kind, quantity, value), back-filled by the migration, so period reports do not scan JSON documents.
- **Export:** every report builds `ReportTable`s (typed cells). The same tables become an `.xlsx` (pure JVM writer, right-to-left sheet, numeric Toman cells) or a PDF (Android `PdfDocument`, Vazirmatn, RTL, header repeated on each page, landscape for wide tables, one payslip per page). The user chooses where to save.
- **Prep items:** an item may be *prepared*; it has versioned prep recipes (output quantity + lines). `RecordProduction` takes ingredients out at average cost and puts the item in at the same total value; no journal entry, because inventory value does not change.
- **Yield:** every recipe line has a yield percent (1–100). Stock is consumed as net ÷ yield, in menu and prep recipes.
- **Item details:** par level, shelf (count sheets follow it), allergen text (shown on recipes), preferred and approved suppliers (used by purchasing).
- **Daily sales** carry optional guest and bill counts for average spend.

## Consequences
Labour in short periods shows only after the month's payroll is approved. Theoretical cost uses current average costs, not the historical cost of each sale day; the P&L uses the posted cost.
