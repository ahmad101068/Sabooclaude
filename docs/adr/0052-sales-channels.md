# ADR-0052: Sales channels

Status: accepted (2026-10)

## Context

Contract sales to companies are a large share of the restaurant's sales, and growing dine-in and takeaway is a goal.
A sale recorded only amounts, lines and settlements: no report could say how much came from the hall, takeaway,
delivery, ordering apps or companies. Sepand's invoice export carries «نوع فروش» on every bill, but its item report
does not, so dishes cannot be split by channel; the money can.

## Decision

- `SalesChannel`: dine-in, takeaway, delivery, online, corporate, other. `DailySale.channels`: the sale's net food
  sales (after discount, before service and tax) by channel with their bills; empty when not recorded.
- `SaveSaleDraft.channels` are **proportions**: one channel with any amount for a whole sale (the form's picker), or
  the POS split. The domain scales them exactly to the sale's net food sales (`Ratio.allocate`), so the recorded
  shares always add up, whatever the lines or the discount became. Duplicates are refused.
- POS import: bills grouped by «نوع فروش» (void bills left out, returns netted; a channel whose returns outweigh its
  sales is left out), mapped by keywords (سالن/حضوری → dine-in, بیرون‌بر → takeaway, پیک → delivery,
  اسنپ/آنلاین → online, شرکت/قرارداد → corporate, anything else → other).
- Item mix shows net sales by channel with share and average bill (sales without a channel are their own row);
  Excel/PDF export adds the table. The sale view and the import preview show the split.
- Stored in the sale's document (`channels`); no schema change.

## Consequences

- Channel figures reconcile with net food sales to the Rial.
- Dishes per channel stay unknown until a POS export gives lines per bill.
