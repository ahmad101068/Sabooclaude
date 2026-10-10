# ADR-0020 — Root fixes: percentages, recipe graph, counted day close, self-approval

**Status:** Accepted (2026-10-10). From the audits of 2026-10-10 (P0-5).

## Decisions
1. **One percentage change.** `Ratio.changeBp(previous, now)` is exact (BigInteger), rounds half away from zero, saturates instead of overflowing, and is `null` without a positive base ("first price", not "0 %"). Price changes, the purchase form, the dashboard and exports use it; other UI ratios use `Ratio.mulDiv`.
2. **Prepared items are a graph.** Publishing a prep recipe that would close a cycle (counting every version in force on or after its date) is refused with the path. Purchase suggestions expand prepared items in topological order at any depth — each item once, for its whole need, after what is already prepared — and return the problems (no recipe, cycle in older data) with the suggestion instead of silently leaving ingredients out.
3. **The day closes with a cash count.** `CloseSalesDay` names the cash box and goes through the treasury count (`TreasuryGateway.count`, shared with reconciliation): a numbered cash-count document, and a shortage/overage booked to 6108 cash over/short. The day keeps the count, the difference and the document.
4. **Posting rights of system accounts are code.** At startup the stored chart's system accounts take the posting modules of `StandardAccounts` (names and active flags stay as kept), so a right added in a new version reaches older databases.
5. **Owner self-approval is a policy.** Allowed or not, with an optional total cap (default: allowed, no cap, for a one-person business). A self-approval always records a reason and is marked as such on the invoice and in the audit trail. Others still cannot approve what they recorded.

## Consequences
- Day-close shortages now reach profit and loss (they used to stay only on the day record).
- An invoice recorded and approved by the owner shows "(ثبت‌کننده · reason)".
