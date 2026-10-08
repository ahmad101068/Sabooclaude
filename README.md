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

## Status (2026-10-09)

- JVM modules: **86 tests passing** offline (unit, end-to-end on a real SQLite file, restart, rollback, replay, tamper and rollback detection, factory reset, 80-year Jalali round trip). Spot mutation checks confirm key rules are guarded.
- **Gradle itself and the Android app have not been compiled yet** in the build environment (Maven/Google repositories were unreachable). CI (`.github/workflows/ci.yml`) is the first real compile of `app/`; expect a short round of compile fixes there.
- Payroll legal values are not shipped: the owner enters each year's parameters (after professional review); payroll fails closed until then.
- Not production ready until CI is green and the app has been tested on devices.
