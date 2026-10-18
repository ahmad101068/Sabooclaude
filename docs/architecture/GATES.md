# Gates toward the multi-restaurant server (ADR-0056)

Each gate closes with evidence that anyone can rerun. Status is updated in the PR that closes it.

| Gate | Status | Evidence | Rerun |
|---|---|---|---|
| G0 — allocation fails closed (P1-03); golden financial cases | closed with PR #68 | `GoldenCasesTest` (11), `MoneyTest` properties; CI run of the PR | `./gradlew domainBuild` or `python3 scripts/offline_build.py <jars>` |
| G1 — tenant model (ADR-0057) | closed with the ADR-0060 PR | `TenantIsolationTest`: schema owned by a login role per tenant; cross-tenant reads and writes refused, no `RESET ROLE`/`SET ROLE` escape | `SABOU_DB=postgres SABOU_PG_URL=… ./gradlew domainBuild` |
| G2 — PostgreSQL, schema per tenant, whole suite on PostgreSQL | closed with the ADR-0060 PR | CI job `postgres` (postgres:16): the domain suite on PostgreSQL, SQLite-file tests skipped | same as G1 |
| G3 — per-tenant serialized writes (ADR-0058) | closed with the ADR-0060 PR | `TenantIsolationTest.writesOfOneTenantAreSerializedAcrossProcesses`: two cores, 50 journals numbered 1..50, 20 concurrent replays post once, audit chain healthy | same as G1 |
| G4 — server module (HTTP, OpenAPI, OIDC) | open — needs ADR-0059 hosting decision | | |
| G5 — Android client mode, web panel | open | | |
| G6 — KPI and data quality, rules, cases, actions | open | | |
| G7 — read-only AI gateway | open — needs ADR-0059 AI decision | | |
| G8 — final audit, pilot, release | open | | |

## Handoff findings (architecture audit, phase 1) and where they stand

| ID | Finding | Status |
|---|---|---|
| P1-01 | No tenant boundary | closed: schema and login role per tenant, tested (ADR-0060) |
| P1-02 | A paired phone gets the full copy without a user session | by design for one restaurant (ADR-0042); the server replaces LAN pairing with per-user tokens (G4/G5) |
| P1-03 | `Ratio.allocate` loses a positive total over zero weights | fixed in G0 (fails closed) |
| P1-04 | Java serialization in the LAN sync protocol | sync stays phone-only; the server protocol is OpenAPI/JSON (G4) |
| P1-05 | `usesMenuItem` scans sale documents with LIKE | open: sale lines are still JSON on both databases; relational lines when the server needs them (ADR-0060) |
| P1-06 | No central server, assessment or AI | G4–G7 |
| P1-07 | Build and tests not verified | verified: CI on a79e163 green in all four jobs (JVM tests, Android build and lint, emulator device tests); offline build 365/365 on Kotlin 2.1.20 |
