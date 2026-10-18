# ADR-0056: Evolve this repository toward the multi-restaurant server

Status: accepted (2026-10) · owner's decision to build the "Sabou Intelligence" target (handoff of 2026-10-17)

## Context

The owner wants Sabou to become a commercial multi-restaurant product: a central server, web panel and Android
client, a 20-dimension assessment, diagnosis and corrective actions, and a read-only AI advisor. An architecture
handoff (13 phases, gate-based, no dates) proposes building it in a new repository and treating this one as a
read-only reference whose rules would be re-implemented and re-tested.

This repository already has what that plan asks the new one to prove first: pure-Kotlin domain modules with no
Android dependency; every write through one command pipeline with idempotency, permission and scope checks and an
audit chain (ADR-0003); an append-only ledger with reversals and period locks; per-location moving average;
versioned recipes; cost snapshots per sale line (ADR-0050); stores behind interfaces (in-memory and SQL); and
365 tests that pass on the official toolchain and in CI with an emulator. Re-implementing it would discard the
strongest evidence the project has: that these rules work.

## Decision

- **Evolve, don't rewrite.** The server, web panel and intelligence engines are added to this repository as new
  modules over the same domain. The Android app keeps working throughout; nothing is switched off before its
  replacement passes the same tests.
- **The existing test suite is the golden master.** Every new persistence or deployment must pass the same suite
  (plus the handoff's golden cases, `GoldenCasesTest`). A rule moves only when its tests move with it.
- **Gates, not dates.** Each gate closes with evidence (CI run, test log, command to rerun):

| Gate | Work | Exit evidence |
|---|---|---|
| G0 | Allocation fails closed (audit P1-03); golden financial cases | CI green, `GoldenCasesTest` |
| G1 | Tenant model (ADR-0057): one core per tenant; identity and memberships in a platform store | two-tenant negative tests |
| G2 | PostgreSQL adapter, schema per tenant, the whole suite run on PostgreSQL | same results on SQLite and PostgreSQL; cross-tenant access denied by the database |
| G3 | Server write concurrency (ADR-0058) | concurrent command tests, replay ×20 = one posting |
| G4 | `server` module: HTTP + OpenAPI + OIDC over the command pipeline | the two vertical slices of the handoff (purchase → payable, sale → COGS → collection) end to end |
| G5 | Android as a client (standalone mode kept), web panel | emulator and browser E2E, online/offline switching |
| G6 | KPI layer with data-quality status, formula version and source lineage; rules, cases, actions | no figure without source and status |
| G7 | Read-only AI gateway (ADR-0059) | eval: prompt injection, cross-tenant requests, unsupported claims |
| G8 | Final audit, pilot at Sabou Eshgh, commercial release | accounting reconciliation with the accountant, restore rehearsal |

- **From the handoff we keep** its non-negotiables: tenant isolation from the first server line; posted documents
  immutable, corrections by reversal; no AI write path to ledger, stock, payroll or approvals; KPIs without enough
  data are «نامعلوم», never zero; every gate with real evidence.
- **We depart from it** where this code base makes a better path cheaper or safer: tenant per core instead of a
  `tenant_id` in every model (ADR-0057); per-tenant serialized writes before row-level concurrency (ADR-0058); one
  language (Kotlin) for forecasting until a model beats the baseline on the back-test (ADR-0055); assessment
  dimensions start with those that have data, the others shown as not rated until their data is captured.

## Consequences

- The handoff's phases map onto the gates above; its acceptance IDs (FIN, INV, SEC, SYNC, KPI, AI, OPS) are kept
  as test names so its matrix can be filled from CI.
- `docs/architecture/GATES.md` records each gate's status and evidence.
