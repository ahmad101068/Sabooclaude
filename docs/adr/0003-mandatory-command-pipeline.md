# ADR-0003 — Mandatory command pipeline and module boundaries

**Status:** Accepted (2026-10-08)

## Context
Authorization and branch scope were opt-in per repository method. Several modules forgot them (AUD-004, AUD-012). The previous app was a single 98k-line module.

## Decision
- Every state change is a `Command`, executed by `CommandBus` in this fixed order: authenticate → permission → scope → transaction(idempotency → handler → audit → events). Handlers cannot skip it because a `CommandContext` cannot be created anywhere else.
- Records touched besides the declared scope are re-checked with `CommandContext.requireScope` (for example both ends of a transfer).
- Each business area is a separate Gradle module with explicit dependencies. `scripts/offline_build.py` compiles each module with only its declared dependencies, so boundary violations fail locally as well as in CI.
- The domain is synchronous and pure Kotlin. Android adapters (Room, UI) live in their own modules and call it on a background dispatcher inside one Room transaction.

## Consequences
Domain rules are fully testable on the JVM without an emulator. Room adapters need their own contract tests against the same in-memory reference behaviour.
