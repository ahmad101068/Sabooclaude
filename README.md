# Sabou ERP

Restaurant management for Iranian restaurants: daily sales, cash and bank, purchasing and inventory, accounting, personnel and payroll. Device-first (encrypted, offline), sync-ready.

This is a ground-up rebuild. The previous code base (`sabou-manager-v3-`) was audited on 2026-10-08; this architecture removes the root causes found there by design. See `docs/adr`.

## Modules

| Module | Responsibility | Depends on |
|---|---|---|
| `modules/kernel` | Money (integer Rial), Quantity (micro-units), ids, dates, domain errors | — |
| `modules/platform` | Roles/permissions, branch scope, command pipeline, idempotency, audit chain, events, integrity anchors, users/branches/PIN login | kernel |
| `modules/ledger` | Chart of accounts with control accounts, journals, ownership rules, period locks, manual accounting | platform |
| `modules/treasury` | Cash boxes and bank accounts per branch, receipts, payments, transfers, counts | ledger |
| `modules/inventory` | Items, locations, per-location weighted average, waste, counts, transfers, versioned recipes | ledger |
| `modules/purchasing` | Suppliers, purchase invoices, payments, reversals, returns | inventory, treasury |
| `modules/sales` | Daily sales posted atomically (stock + revenue + treasury + receivables), customers, collections, day close | inventory, treasury |
| `modules/payroll` | Employees, attendance, payroll runs with segregation of duties, payments, remittances, owner-entered yearly policies | treasury |
| `modules/backup` | Streaming AES-GCM backup container (format 4) | kernel |
| `modules/persistence` | SQL schema v1 + forward migrations, immutability triggers, SQL stores for every port | sales, purchasing, payroll |
| `modules/core` | Single composition root, read models, Persian formatting + Jalali calendar, Persian error messages | persistence |
| `app` | Android: SQLCipher adapter, Keystore keys, signed anchors, backup/restore, Compose UI (RTL, Vazirmatn) | core, backup |

## Build

```bash
./gradlew domainBuild                              # all JVM modules + tests
./gradlew :app:assembleDebug :app:lintDebug        # Android app (needs Android SDK)
python3 scripts/offline_build.py <jars-dir>        # offline fallback; enforces module boundaries
```

### Dependency verification
Gradle checks the SHA-256 of every downloaded artifact against `gradle/verification-metadata.xml` (generated on Linux CI). Building on another OS (Windows/macOS) needs that OS's `aapt2` checksum once: run `./gradlew --write-verification-metadata sha256 :app:assembleDebug` locally and review the diff before committing. When upgrading a dependency, regenerate the file the same way.

## Status (2026-10-09)

- JVM modules: **135 tests passing** offline (unit, end-to-end on a real SQLite file, restart, rollback, replay, tamper and rollback detection, factory reset, 80-year Jalali round trip). Spot mutation checks confirm key rules are guarded.
- CI (`.github/workflows/ci.yml`): Gradle `domainBuild` with all tests; the Android app compiles, passes lint and produces a debug APK (artifact `sabou-debug-apk`, signed with the committed debug key so updates install over each other); instrumented tests run on an Android emulator (SQLCipher, Keystore, backup/restore, rollback detection, encrypted drafts, every page).
- Payroll legal values are not shipped: the owner enters each year's parameters (after professional review); payroll fails closed until then.
- Unfinished forms survive the system closing the app (ADR-0010). Partial-month payroll is prorated (default ÷30).
- Management reports (ADR-0011): profit and loss by day and branch with drill-down, food and labour cost %, end of day, item mix and margins, actual vs theoretical usage, attendance; every report and payroll (with one payslip per page) exports to Excel and PDF. Prep items with production and recipe yields.
- Purchasing (ADR-0012): purchase orders and delivery, invoice photos/PDFs, expense lines (also for another branch), unknown lines held for review, supplier item names, return credit settling other open invoices, supplier delivery days and cut-off, approved suppliers, suggested purchases from par levels and planned dishes, price change alerts; comps booked apart from waste.
- Before production: a round of hands-on testing on the restaurant's own devices, the yearly payroll values reviewed by a tax/insurance advisor, and a release signing key.
