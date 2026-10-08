# ADR-0008 — Server: recommended stack, deferred

**Status:** Proposed (2026-10-09)

The user chose device-first; no server is built now. When multi-device sync is needed:
- **Kotlin + Ktor** on the JVM, reusing the domain modules and `ir.sabou.core` unchanged (they are pure Kotlin).
- **PostgreSQL** with a `SqlDatabase` adapter; the schema in `Schema.kt` is portable apart from SQLite triggers (rewrite as PostgreSQL rules/triggers).
- Sync is the `domain_events` stream (`SqlEventLog.unsynced`) with idempotent command ids; the server also holds integrity anchors, closing the rooted-device gap in ADR-0007.
- Hosting: any VPS reachable from Iran (e.g. a local provider) with automated encrypted backups.
