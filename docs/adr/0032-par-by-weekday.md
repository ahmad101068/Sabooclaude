# ADR-0032: Par level per delivery weekday

Status: accepted · 2026-10

## Context

Purchase suggestions restored one par level per item. A restaurant's need is not flat across the week: the
Wednesday or Thursday delivery has to last through the weekend, the Saturday one does not. A single par either
over-stocks perishables early in the week or runs out on Friday.

## Decision

- `Item.parByWeekday` (0 = Saturday … 6 = Friday) overrides the usual `parLevel` for **a delivery arriving on that
  weekday**. A weekday without its own value uses the usual par.
- Suggestions take the delivery date from the item's preferred supplier's schedule (next delivery whose order
  deadline has not passed, ADR of supplier delivery days) and restore that weekday's par. The suggestion carries
  the delivery day when a weekday par was used, and the screen says so («تحویل پنجشنبه»).
- A weekday par must be positive and not below the item's reorder point. Stored in the item document (no schema
  change).
- The low-stock alert still uses the reorder point (`minimumStock`), unchanged.

## Consequences

- Items without a preferred supplier with delivery days always use the usual par.
- A sales forecast (stage 5) can later propose these values from history instead of typing them.
