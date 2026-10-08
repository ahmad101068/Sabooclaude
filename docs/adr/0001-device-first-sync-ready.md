# ADR-0001 — Device-first, sync-ready

**Status:** Accepted (2026-10-08)

## Context
Version 1 runs on one Android device per restaurant without a server. A server will be added later.
The previous code base had a disabled sync layer and device-local backups only, and it relied on in-process state for integrity decisions.

## Decision
- Every business record has a `GlobalId` (UUID), never only an auto-increment id.
- Every command appends `DomainEvent`s in the same transaction as the change. Today they are the local history; later they become the sync stream.
- Documents are immutable after posting. Corrections are new documents (reversals), so merging data from many devices never needs update conflicts on posted data.
- Off-device backup is a first-class requirement of version 1, not a later phase.

## Consequences
Adding the server later is additive: an upload worker for the event log and a server-side ledger that replays the same commands.
