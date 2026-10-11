# ADR-0027 — Storage: measured before rebuilding

**Status:** Accepted (2026-10-11). Plan item P0-4 proposed moving to relational read models; this records why not now.

## Measurement
`VolumeBenchmarkTest` builds a year of a busy restaurant through the real commands on SQLite — 25 sales a day (9,125 sales), 8 purchase invoices every other day (1,460 invoices of 5 lines), 60 stock items, 40 dishes with recipes — then times the reads people wait for. On the development JVM (2026-10-11):

| Read | 60 days | 365 days |
|---|---|---|
| Home dashboard (month) | 7 ms | 27 ms |
| Profit and loss (period) | 6 ms | 48 ms |
| Day flash | 7 ms | 32 ms |
| Sales of a day | 1 ms | 6 ms |
| Price changes (week) | 11 ms | 48 ms |
| Last price paid per item | 6 ms | 32 ms |
| Invoice list | 18 ms | 130 ms |
| Alerts | 9 ms | 39 ms |
| Product mix (month) | 14 ms | 87 ms |

Writing stays flat: about 12 ms per command at 60 days and at a year (journal totals, stock balances and document counters are already indexed tables).

## Decision
- No storage rewrite now: at a year the slowest read is about a tenth of a second here (on a phone perhaps one second, the invoice list); the remaining full scans are over purchase invoices only and grow linearly.
- The benchmark runs in CI with 90 days on every change, with per-read budgets, so a regression is caught where it is introduced.
- When the invoice list or price reads near their budget (expected after several years), the fix is a purchase-line index table (item, date, unit price, invoice) and a paged invoice list — not a general rewrite.
