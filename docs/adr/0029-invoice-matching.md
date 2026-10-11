# ADR-0029: Order ↔ invoice matching and possible duplicates

Status: accepted · 2026-10

## Context

A supplier invoice was compared with its purchase order only on screen (red text). Nothing stopped paying an
invoice that billed more, at a higher price, for goods never ordered, or the same invoice entered twice under a
number typed differently. Back-office tools (Toast xtraCHEF, MarketMan, R365) block these at "match" time and send
them to someone who did not enter them.

## Decision

- When an invoice is recorded against an order, `InvoiceMatching.compare` records each difference: **quantity**,
  **price** (beyond ±2 %, so rounding is not noise), **not ordered**, **not delivered**.
- Any invoice of the same supplier with the **same total** on the **same day**, or under the **same number written
  differently** (Persian/Latin digits, dashes, leading zeros), is marked a **possible duplicate**. The same total on
  another day is normal (daily bread) and is not flagged.
- Differences are stored on the invoice (no new table; the invoice document carries them).
- An invoice with unreviewed differences **cannot be paid** (`NEEDS_MATCH_REVIEW`), including immediate payment at
  recording. `ReviewInvoiceMatch` (permission `PURCHASE_APPROVE`) accepts it with a 3–300 character note; the person
  who recorded it cannot review it, except the owner. Audited as `PURCHASE_MATCH_REVIEW`.
- A wrong invoice is not "edited to match": it is reversed or a return is recorded, as before.
- Visible as a home to-do, a section on «در انتظار تأیید», a card on the invoice, and a phone alert
  (`INVOICE_MATCH`, channel «مغایرت فاکتور با سفارش»).

## Consequences

- Review is independent of approval rules: an invoice may need both.
- Tolerances are constants for now (2 %); a per-supplier tolerance can come later if the field asks.
