# ADR-0058: Writes on the server — serialized per tenant, reads concurrent

Status: accepted (2026-10) · gate G3 of ADR-0056

## Context

The core was built for one writer: the main phone; the second phone forwards its commands (ADR-0042) and
`SqlUnitOfWork` serializes transactions. Its correctness under that model is proven: moving average per location,
document numbers per series, idempotency, shortages, approvals. A server serves many users of a tenant at once,
from several server processes. Row-level concurrency (optimistic versions on every aggregate, row locks on stock
balances, sequences for numbering) would have to be added to every module and re-proven — a large risk for a
throughput a restaurant does not need: a busy restaurant posts a few commands a minute.

## Decision

- **Commands of one tenant are serialized**, across all server processes, by a PostgreSQL transaction advisory lock
  on the tenant (`pg_advisory_xact_lock`) taken at the start of each command's transaction. Different tenants run in
  parallel. This keeps the core's proven single-writer model exactly.
- **Reads run concurrently** in their own read-only transactions, outside the lock.
- Transactions use PostgreSQL's default `READ COMMITTED` under the lock; a command sees everything committed before it.
- Measured limits decide when to move further: if a tenant's command latency under load passes the agreed target,
  the hot paths (stock balance, numbering) get row-level locking first, with their own concurrency tests.

## Consequences

- No double consumption, no duplicate numbers and no lost update across processes, without changing a rule.
- A long command delays the others of the same tenant; commands stay short (no network calls inside a
  transaction — the tax system and SMS already run outside it).
- Tests: two processes posting into one tenant concurrently give the same books as posting one after the other;
  one command replayed 20 times concurrently posts once (handoff SYNC-001).
