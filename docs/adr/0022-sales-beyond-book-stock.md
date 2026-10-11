# ADR-0022 — A sale is never refused for a book-stock shortage

**Status:** Accepted (2026-10-11). Field feedback: a sale stayed unposted because the book stock was short.

## Context
Posting a sale consumed its recipe from book stock and refused the whole sale when any ingredient was short. The sale had happened; book stock lags reality (an invoice not yet recorded, a count error). Refusing it kept revenue, cash and the day out of the books.

## Decision
1. **Sold is sold.** A sale consumes what the book has at average cost; the rest becomes an open `StockShortage` (item, location, quantity, the consuming document). Stock never goes negative.
2. **Settled when goods come in.** Right after a purchase receipt, a transfer in or a production output of that item at that location, open shortages are taken from it oldest first at its average cost (COGS ← inventory), in a journal of the module that brought the goods.
3. **Written off by a count.** A count measures what is really there, so shortages still open for a counted item are closed at an estimate — the location's average cost, else the item's last purchase cost — as COGS ← inventory variance, keeping the cost of what was sold in the cost of sales.
4. **Cancelled with their document.** Reversing a sale cancels its shortages; what settlements took from stock comes back at the cost it left with, and write-offs are undone.
5. Waste, transfers and production out still require the stock (they are decisions, not records of what already happened).

## Consequences
- Food cost of a day may be completed a few days later (on the settlement date) — shown as "کسری مصرف باز" on the sale, the stock screen and the home to-do list.
- Schema 9 adds `stock_shortages`.
