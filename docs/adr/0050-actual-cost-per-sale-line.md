# ADR-0050: Actual cost of each sale line

Status: accepted (2026-10)

## Context

Item mix and menu engineering (ADR-0011, ADR-0028) price each dish with today's recipe and today's average stock
cost. That is right for deciding prices and the menu, and the screen says so, but it cannot answer "what did a
portion of this dish actually cost us last month": after a price rise, last month's margins look worse than they
were. The books know the true cost only for the whole sale (`DailySale.cost`, the cost of sales posted with it),
not per dish.

## Decision

- **At posting**, each `SaleLine` keeps `cost`: its share of what the sale took from the kitchen at the moving
  average of that moment. An ingredient's issued cost is split over the lines by the quantity each line's recipe
  asked of it (`Ratio.allocate`, exact), so the lines always add up to `DailySale.cost`. A line whose dish had no
  recipe on the sale's date keeps no cost (unknown, not zero).
- `SaleLine.costShort`: part of an ingredient was not in book stock (ADR-0022); that part is costed only when the
  shortage is settled, so the line's cost is too low until then. Reports say so.
- **Item mix** shows, beside today's cost, the **actual cost per portion**, actual margin and actual food-cost %
  over the portions that carry a cost ("based on N of M portions" when not all do). Same permission rule as
  today's cost (inventory, purchase or recipe roles). Excel/PDF export has the same columns.
- Menu engineering keeps classifying on today's costs: it is a decision about the menu as it is now.
- Stored in the sale's document (`cost`, `costShort` per line); no schema change. Sales posted before this keep no
  line cost and are left out of the actual figures, not estimated.

## Consequences

- Historical dish margins come from what really left stock, at that day's cost, and reconcile with the cost of
  sales in the books (line costs sum to the sale's cost).
- A reversed sale keeps its lines but is not counted (only posted sales are).
