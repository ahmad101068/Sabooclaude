# ADR-0060: One set of stores, two databases — the PostgreSQL adapter

Status: accepted
Date: 2026-10-18
Gates: G1, G2, G3 (ADR-0056)

## Context

The phone keeps its books in SQLite/SQLCipher; the multi-restaurant server (ADR-0056) needs PostgreSQL, with one
schema per tenant owned by that tenant's own role (ADR-0057) and per-tenant serialized writes (ADR-0058). Writing a
second set of stores would double the code that carries the money rules and let the two drift apart.

## Decision

1. **The stores and migrations stay as they are, written once.** `PostgresSqlDatabase` implements the same
   `SqlDatabase` and translates the few SQLite constructs the code base uses (`PostgresDialect`):
   `INTEGER` → `BIGINT`, `BLOB` → `BYTEA`, `AUTOINCREMENT` → identity, `CAST(… AS INTEGER)` → `BIGINT`, `PRAGMA` →
   nothing, immutable-table triggers → one plpgsql function `sabou_immutable()` raising the same `IMMUTABLE:<table>`
   message. Every table gets a `rowid` identity column so `ORDER BY rowid` keeps meaning insertion order. Anything
   the dialect does not know passes unchanged, and a trigger it cannot translate fails loudly.
2. **`SqlDatabase.dialect`** lets the rare dialect-specific code ask: the phone-sync triggers (`SyncLog`) are not
   created on PostgreSQL — the server has no LAN replica. Catalogue questions go through `tableNames()` /
   `columnsOf()`. Queries with optional filters build their `WHERE` clause instead of `(? IS NULL OR …)`, which
   PostgreSQL cannot type.
3. **A tenant's core logs in as the tenant's role** (`LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS`), which
   owns its schema and nothing else; `search_path` is that schema only. A privileged connection that `SET ROLE`s
   was rejected: `RESET ROLE` undoes it from inside the session.
4. **Writes of one tenant are serialized across processes** by `pg_advisory_xact_lock(<tenant key>)` at the start of
   every transaction, so document numbering, the audit chain and command idempotency behave exactly as on a phone.
5. **The whole test suite runs on both databases.** Tests open stores through `DevDatabases`: SQLite by default,
   PostgreSQL with `SABOU_DB=postgres SABOU_PG_URL=jdbc:postgresql://…` (one schema per test database). CI runs the
   domain suite twice — the `postgres` job uses a `postgres:16` service. Tests that are about SQLite files
   (`VACUUM INTO` backups, file tampering, LAN sync between phones) are skipped on PostgreSQL with an assumption;
   `TenantIsolationTest` runs on PostgreSQL only.
6. The JDBC driver (`org.postgresql:postgresql:42.7.4`) is a **test runtime** dependency only, pinned in the
   dependency-verification metadata; the jar's checksum was cross-checked against the pgjdbc GitHub release.
   The server module (G4) will take it as a runtime dependency there.

## Evidence

- On PostgreSQL 16: every domain test passes except those skipped as SQLite-only.
- `TenantIsolationTest`: another tenant's records are invisible by id and unreachable by qualified name, read or
  write; `RESET ROLE` and `SET ROLE` give no way out; posted documents are immutable in the database; two cores of
  one tenant posting 50 journals concurrently number them 1..50 with exact balances, 20 concurrent replays of one
  command post once, and a fresh core verifies the audit chain as healthy.

## Consequences

- One code path for the money rules on phone and server; a regression on either database fails CI.
- Sale lines are still JSON documents; relational lines on PostgreSQL (handoff P1-05) remain to be done when the
  server needs them.
- Schema and role provisioning belong to the platform layer (G4); the adapter only creates a missing schema in
  development.
