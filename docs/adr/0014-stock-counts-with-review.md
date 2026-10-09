# ADR-0014 — Stock counts with review and reasons

**Status:** Accepted (2026-10-10)

## Context
A count used to replace the book quantity at once. Whoever counted could change stock by any amount, with no reason asked and no screen to see past counts. The journal and audit trail kept the effect, but not the why or the who-checked.

## Decision
- **Two steps, two people.**
  - Counting (INVENTORY_COUNT) records what is on the shelf and the book quantity at that moment; nothing changes.
  - Review (INVENTORY_ADJUST: owner, manager) approves or rejects.
  - The person who counted cannot approve their own count; the owner is excepted for a one-person shop.
  - One pending count per location.
- **A reason for every difference.** The reasons are a fixed list: unrecorded waste, unrecorded use, wrong purchase/transfer entry, previous count wrong, missing, or other with a note. They are given on approval, and each one is stored on the line and in the variance journal's memo.
- **Measured at count time.** The difference counted − book (at count time) is posted on approval at today's average cost, so sales between counting and approval are not undone.
- **Blind counting.** The count sheet and a pending count's differences show book quantities only to reviewers.
- **History.** Every count — pending, approved or rejected — keeps:
  - who counted and who reviewed;
  - the lines, differences, values and reasons;
  - the reason for a rejection.

  Counts are listed per branch, exportable to Excel/PDF, and pending ones appear on the reviewer's Home.

## Consequences
The old one-step command is removed. Schema v6 adds `stock_counts`. A storekeeper still sees quantities on the stock screen (INVENTORY_VIEW); counting is blind on the count sheet only.
