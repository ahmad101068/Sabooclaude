# ADR-0030: Expiry lots (first expired, first out)

Status: accepted · 2026-10

## Context

Perishables (dairy, meat, sauces made in-house) were tracked only as a quantity and an average cost per location.
Nobody was told that yogurt bought three days ago expired yesterday. Restaurant back-office tools (MarketMan,
Apicbase) track expiry per batch and warn before it passes.

## Decision

- **Lots are a quantity layer, not a costing method.** Valuation stays weighted-average per location; journals,
  reports and the inventory account never read lots. A lot bug can therefore never unbalance the books.
- A lot (`stock_lots`, schema 11) is created when goods come in with an expiry: the date typed on the invoice line,
  else the item's **shelf life** (`Item.shelfLifeDays`) counted from the invoice date; production uses the shelf
  life from the production date. An expiry before the receipt date is refused.
- **Everything that takes stock out takes it from lots first, earliest expiry first** (sales consumption, waste,
  transfers, production, count losses, shortage settlement). Goods with no known expiry are used last. This is the
  kitchen rule, and it lets «waste the expired lot» actually remove that lot.
- A **transfer carries** the lots it took to the destination; reversing a transfer brings them back. Reversing a
  receipt takes back that receipt's own lot first.
- Invariant: the lots of an item at a location never hold more than its stock.
- Visible: a card on the stock screen (expiring within 3 days or past), a home to-do (expired or expiring today),
  a phone alert (`EXPIRY`: expiring tomorrow, today, or past).

## Consequences

- Reversing a sale returns its goods without dates (a sale does not record which lots it used).
- One invoice line per item, so one expiry per item per invoice for now.
- Counting by lot (P1-3) can later correct dates found on the shelf.
