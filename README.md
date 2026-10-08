# Sabou ERP

Restaurant management for Iranian restaurants: daily sales, cash and bank, purchasing and inventory, accounting, personnel and payroll.

This is a ground-up rebuild. The previous code base (`sabou-manager-v3-`) was audited on 2026-10-08; the architecture here removes the root causes found there by design. See `docs/adr`.

## Modules

| Module | Responsibility | Depends on |
|---|---|---|
| `modules/kernel` | Money (integer Rial), Quantity (micro-units), ids, dates, domain errors | — |
| `modules/platform` | Roles and permissions, branch scope, command pipeline, idempotency, audit chain, events, integrity anchors | kernel |
| `modules/ledger` | Chart of accounts with control accounts, journals, ownership rules, period locks, manual accounting | platform |
| `modules/treasury` | Cash boxes and bank accounts per branch, receipts, payments, transfers between branches, counts | ledger |
| `modules/inventory` | Items, branch locations, per-location weighted average, receipts/consumption gateway, waste, counts, inter-branch transfers, versioned recipes | ledger |
| `modules/purchasing` | Suppliers, purchase invoices (stock + AP), payments via treasury incl. from the central bank, payment/invoice reversal, returns at invoice price | inventory, treasury |

Planned next: `sales`, `payroll`, then `data-room` (Room/SQLCipher adapters), `app` (Compose UI) and `backup`.

## Build

```bash
./gradlew build                                  # compile and run all tests (CI)
python3 scripts/offline_build.py <jars-dir>      # offline fallback; enforces module boundaries
```

## Status
Domain modules kernel, platform, ledger, treasury, inventory, purchasing: 45 JVM tests passing. Visual language approved (ADR-0005). Not production ready.
