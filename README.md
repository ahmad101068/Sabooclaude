# Sabou ERP

Restaurant management for Iranian restaurants: daily sales, cash and bank, purchasing and inventory, accounting, personnel and payroll. Device-first (encrypted, offline), sync-ready.

This is a ground-up rebuild. The previous code base (`sabou-manager-v3-`) was audited on 2026-10-08; this architecture removes the root causes found there by design. See `docs/adr`.

## Modules

| Module | Responsibility | Depends on |
|---|---|---|
| `modules/kernel` | Money (integer Rial), Quantity (micro-units), ids, dates, domain errors | — |
| `modules/platform` | Roles/permissions, branch scope, command pipeline, idempotency, audit chain, events, integrity anchors, users/branches/PIN login | kernel |
| `modules/ledger` | Chart of accounts with control accounts, journals, ownership rules, period locks, manual accounting, budgets | platform |
| `modules/treasury` | Cash boxes and bank accounts per branch, receipts, payments, transfers, counts, cheque boxes and books | ledger |
| `modules/inventory` | Items, locations, per-location weighted average, waste, counts, transfers, versioned recipes | ledger |
| `modules/purchasing` | Suppliers, orders, purchase invoices (goods, expense and held lines, attachments), approvals, payments, reversals, returns and supplier credit | inventory, treasury |
| `modules/sales` | Daily sales posted atomically (stock + revenue + treasury + receivables), customers, collections, day close | inventory, treasury |
| `modules/payroll` | Employees, attendance, payroll runs with segregation of duties, payments, remittances, owner-entered yearly policies | treasury |
| `modules/assets` | Fixed assets, depreciation runs, disposal | treasury |
| `modules/backup` | Streaming AES-GCM backup container (format 4) | kernel |
| `modules/posimport` | Reading Excel/CSV exports: the POS (Sepand) day reports and bank statements, with their own totals checked | kernel |
| `modules/persistence` | SQL schema v1 + forward migrations (now v18, with the change log for two phones), immutability triggers, SQL stores for every port | sales, purchasing, payroll, assets |
| `modules/core` | Single composition root, read models, Persian formatting + Jalali calendar, Persian error messages | persistence, posimport |
| `modules/sync` | Two phones, one set of books (ADR-0042): encrypted LAN link, pairing with code comparison, main-phone server, second-phone client and replication | core |
| `app` | Android: SQLCipher adapter, Keystore keys, signed anchors, backup/restore, Compose UI (RTL, Vazirmatn) | core, backup |

## Build

```bash
./gradlew domainBuild                              # all JVM modules + tests
./gradlew :app:assembleDebug :app:lintDebug        # Android app (needs Android SDK)
python3 scripts/offline_build.py <jars-dir>        # offline fallback; enforces module boundaries
```

### Dependency verification
Gradle checks the SHA-256 of every downloaded artifact against `gradle/verification-metadata.xml` (generated on Linux CI). Building on another OS (Windows/macOS) needs that OS's `aapt2` checksum once: run `./gradlew --write-verification-metadata sha256 :app:assembleDebug` locally and review the diff before committing. When upgrading a dependency, regenerate the file the same way.

## Status (2026-10-12)

- JVM modules: **all tests passing** offline (run `scripts/offline_build.py`; 270+ at ADR-0042) (unit, end-to-end on a real SQLite file, restart, rollback, replay, tamper and rollback detection, factory reset, 80-year Jalali round trip). Spot mutation checks confirm key rules are guarded.
- CI (`.github/workflows/ci.yml`): Gradle `domainBuild` with all tests; the Android app compiles, passes lint and produces a debug APK (artifact `sabou-debug-apk`, signed with the committed debug key so updates install over each other); instrumented tests run on an Android emulator (SQLCipher, Keystore, backup/restore, rollback detection, encrypted drafts, every page).
- Payroll legal values are not shipped: the owner enters each year's parameters (after professional review); payroll fails closed until then.
- Unfinished forms survive the system closing the app (ADR-0010). Partial-month payroll is prorated (default ÷30).
- Management reports (ADR-0011): profit and loss by day and branch with drill-down, food and labour cost %, end of day, item mix and margins, actual vs theoretical usage, attendance; every report and payroll (with one payslip per page) exports to Excel and PDF. Prep items with production and recipe yields.
- Purchasing (ADR-0012): purchase orders and delivery, invoice photos/PDFs, expense lines (also for another branch), unknown lines held for review, supplier item names, return credit settling other open invoices, supplier delivery days and cut-off, approved suppliers, suggested purchases from par levels and planned dishes, price change alerts; comps booked apart from waste.
- Stock counts (ADR-0014): blind count, review by a second person with a reason for every difference, full history.
- Accounting (ADR-0013): cheque boxes and cheque books (received/issued cheques with Sayad id, due list, deposit, collect, bounce, settle, endorse to suppliers, leaf-sized print), multi-step invoice approval rules with separate approve/unapprove permissions, monthly budgets with budget-vs-actual, customers' cheques in the daily sale, supplier refunds of return credit, fixed assets with straight-line or declining-balance depreciation and disposal.
- Since 2026-10-09 (ADR-0017 … 0041): purchase packs; Rial everywhere; menu prices with overrides; dashboard; several sales a day; sales beyond book stock (shortages settled later); owner recovery code and fingerprint; phone alerts; nightly encrypted backup; POS (Sepand) day import; volume benchmark; menu engineering and food-cost target; order ↔ invoice matching and possible duplicates; expiry lots (first expired, first out); count plans; par per delivery weekday; daily prep plan and allergens; daily P&L with accruals vs budget; bank statement reconciliation; month-end close checklist; tax system (Moadian) readiness — data only; shifts, labour cost and a PIN time clock; staff documents with expiry; global search; sales forecast with special days.
- ADR-0042: two phones, one set of books — the main phone keeps the books and serves a paired second phone over the restaurant Wi-Fi (commands forwarded, row-level replication, encrypted pairing with on-screen code comparison).
- ADR-0046 … 0049: SMS alerts (phone SIM or Kavenegar/SMS.ir) and orders sent to suppliers by SMS; electronic invoices with the buyer's details and sending them to the tax system (API v2: JWS token, JWS + JWE invoice, inquiry, cancellation; off by default); tagged signed releases (`docs/RELEASE.md`) with a minified-build check on the emulator; cheque print calibration per bank; published 1405 payroll values as a fill-in suggestion.
- ADR-0050: every posted sale line keeps its actual ingredient cost (at that day's stock cost); the item mix shows actual margin per dish beside today's.
- ADR-0051: 13-week cash forecast (balances, receivables with each customer's usual delay, cheques, supplier invoices, payroll, budgets, sales pattern) with scenarios and a low-cash alert; credit customers' share of sales and payment habits.
- ADR-0052: sales channels (dine-in, takeaway, delivery, online, corporate) chosen per sale or split from the POS bills' «نوع فروش»; net sales by channel in the item mix.
- ADR-0053: why profit changed between two periods — volume, mix, prices, ingredient costs, discounts, losses, labour, other costs and income, adding up to the books to the Rial, with a plain Persian summary.
- ADR-0054: purchase suggestions from the dish forecast and the supplier's delivery days; a run-out alert when expected sales finish an ingredient before its next delivery.
- ADR-0055: the sales forecast's back-test also reports weighted error (WAPE) and bias.
- Before production: a round of hands-on testing on the restaurant's own devices; the four signing secrets added to the repository (key delivered separately); the 1405 payroll values and the depreciation classes confirmed by a tax/insurance advisor; the cheque positions calibrated with a test print per bank; the first electronic invoice checked in the taxpayer's کارپوشه (the protocol follows the published client libraries, not yet a live submission).
