# ADR-0006 — Plain SQL persistence, forward migrations, one composition root

**Status:** Accepted (2026-10-08)

## Context
The previous app used Room with destructive or ad-hoc schema handling and wired its modules in several places, so capabilities and stores could be created more than once (AUD-014, AUD-002). Room also needs annotation processing (KSP), which cannot be verified in every build environment.

## Decision
- Persistence is a small `SqlDatabase` port (execute, query, begin/commit/rollback, changes). Android implements it over SQLCipher's `SupportSQLiteDatabase`; JVM tests and tools use `JdbcSqlDatabase` over sqlite-jdbc. The same store code and the same schema run on both.
- Each aggregate is stored as one JSON document per row plus the columns that queries need. Balances that the books depend on (journal lines, treasury movements, stock balances) are real columns and are summed in SQL.
- The database enforces what must never happen: posted journals, journal lines, treasury and stock movements and audit events are immutable (triggers); journal lines are one-sided and non-negative; stock cannot go negative; foreign keys are on (the core refuses to start otherwise); one reversal per document (unique `reversal_of`).
- Schema changes are numbered, forward-only migrations in `Schema.migrations`, applied in one transaction. The app refuses to open a database whose version is newer than it knows. Every migration gets a test. Migration 1 may still change until the first release; after that, never.
- `SabouCore` in `modules/core` is the only composition root. It migrates, seeds the chart of accounts, creates the database epoch, issues every posting capability exactly once and exposes read models (`Overview`) that apply the same permission and branch rules as commands.

## Consequences
No code generation in the data layer, so the whole core builds and is tested on a plain JVM against a real SQLite file (restart, rollback, idempotent replay, tamper and rollback detection). The Android layer is thin: an adapter, key management and UI.
