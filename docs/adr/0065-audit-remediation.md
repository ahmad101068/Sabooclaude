# ADR-0065: Remediation of the October 2026 audit

Status: accepted (2026-10)

## Context

The audit of 1405/08/04 (main @ 3c36596) recorded twelve findings, SABOU-AUDIT-001 to 012, each with a probe that
reproduced it. This ADR records how each was fixed and what remains for the owner.

## Decisions

- **002: document date rule.** `DatePolicy` (ledger), checked in `Ledger.post`, the one place every financial
  document passes:
  - Nothing is dated after today in Iran's time. The exception is payroll runs and depreciation, which may carry the
    end of the current Jalali month.
  - Nothing is dated more than 45 days back unless the person holds the new `BACKDATE` permission. Owner, manager and
    accountant hold it; cashier, storekeeper and kitchen do not.
  - The app opens its core with `SabouCore.restaurantDatePolicy()`. Tests and tools pass none, so their fixed dates keep
    working.
- **003: food cost of a sale beyond book stock.** A shortage is settled, or written off by a count, on the day it was
  consumed (the sale) when that day can still take a document. Otherwise it falls back to the day the goods came in.
  The stock movement carries the same date, so the inventory account equals the stock value on every date. Open
  shortages now block the month close.
- **004 / 005 / 009 (dashboard):**
  - The headline day is the last day of the period with recorded sales.
  - Satisfaction needs the scorecard's minimum of five ratings.
  - Alert errors are no longer hidden.
  - The latest sales are ordered by day, then document number.
- **006 (CI):**
  - Every job runs on `ubuntu-24.04`.
  - A run without release signing shows a warning.
  - Report artifacts are reduced in PR #76.
- **007 (tax system):** receipts are matched by our uid only. An invoice without an answer stays ready and is reported as
  waiting, never as refused.
- **008:** the new `BANK_VIEW` permission covers bank, card-terminal and cheque accounts and the cheques. Without it,
  treasury viewing shows cash boxes only, which is all the cashier sees. Choosing an account to settle a sale is unchanged.
- **010:** migrations 2 and 3 are tested on populated databases.
- **011:**
  - Owners, managers, accountants and anyone who sees pay or the bank are signed out after 10 idle minutes. Others are
    signed out after 30.
  - The app signs out after 5 minutes in the background. The open form is kept and comes back at the next sign-in.
- **012:** rial totals use `sumExact` / `sumOfExact`, which fail on overflow instead of wrapping.

## Not fixed in code

- **001:** the release signing key is the owner's own and cannot be made by the code. CI now warns on every run until
  the four `SABOU_*` secrets exist (docs/RELEASE.md). Then only `sabou-release-apk` is installed.

## Consequences

- Recording a sale or invoice for a day more than 45 days ago needs the owner or the manager.
- A month cannot be closed while a sale is waiting for its ingredients' invoice or a count.
