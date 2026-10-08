# ADR-0004 — Audit chain, anchors and legitimate rebase

**Status:** Accepted (2026-10-08)

## Context
The previous startup check bricked the app after a factory reset (AUD-001) or a restore applied by a background worker (AUD-006). It also loaded the whole audit chain into memory on every start (AUD-007).

## Decision
- The audit chain is verified page by page from the last verified `AuditCheckpoint`. Memory use is bounded by the page size.
- An external `AnchorStore` (an HMAC-signed file outside the database) records `CHECKPOINT` anchors at startup and `REBASE` anchors **before** any factory reset, restore or first install replaces the database.
- `IntegrityGuard.verify` only reports `RollbackDetected`. The app shows a recovery screen (restore from backup or reset); it never hard-fails with no way out.

## Consequences
A legitimate reset or restore always leaves a valid trail. A silent database swap is still detected.
