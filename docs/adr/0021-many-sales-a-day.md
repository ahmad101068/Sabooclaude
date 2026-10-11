# ADR-0021 — Many sales a day

**Status:** Accepted (2026-10-11). Field feedback: "در یک روز فقط یک فروش ثبت می‌شود".

## Context
A branch's day held one `DailySale`: the whole day typed as one document. Bills, shifts or a POS export could not be recorded one by one, a mistake meant reversing the whole day, and a second sale on the same day was impossible.

## Decision
1. A day has any number of sales (`DailySale` keeps its name for stored data; the UI says «فروش»). Each is drafted, posted and reversed on its own and gets its own number (series فر, per branch and fiscal year).
2. `SaveSaleDraft.saleId` null creates a draft, otherwise replaces that draft; the date of a draft does not change. A draft that will not be posted is **discarded** (kept on record as DISCARDED, audited).
3. The day is closed separately: every sale must be posted, reversed or discarded (`SALES_DAY:HAS_DRAFTS`), then the cash box is counted (ADR-0020).
4. Day reports and the home screen add up the day's posted sales; a draft never counts as a sale.

## Consequences
- No data migration: an existing day's single sale is simply the first of its day.
- Next: a sale is not refused for a book-stock shortage (sold is sold); the shortage goes to a review queue (ADR-0022).
