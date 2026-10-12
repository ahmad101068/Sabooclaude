# ADR-0042 — Two phones, one set of books (LAN sync)

**Status:** Accepted (2026-10-12)

## Context
The restaurant uses two phones and wants both to work on the same books at the lowest cost. There is no server
(ADR-0008) and internet in Iran is unreliable; both phones are in the restaurant on its Wi-Fi.

Two phones each writing their own copy and merging later cannot keep the rules that make the books trustworthy:
one document-number sequence, one cash balance, one stock balance, day and month locks, two-person approvals.
Something must decide, in one place.

## Decision
**One phone is the main phone (hub) and owns the database. The second phone (replica) keeps an exact copy for
reading and sends every change to the main phone.** Nothing is merged.

- **Commands** — on the replica, `CommandBus` runs the usual local checks (signed in, permission, scope) and then
  forwards the command (`CommandForwarder`). The main phone runs it in its single pipeline for the person signed
  in on the replica (`Session.actAs`): idempotency, numbering, period locks, audit — unchanged. Every command class
  is `Serializable`; a test walks every command's fields. A reflective `CommandDispatcher` finds the one public
  entry point per command type (a test requires one for every command); where the core wraps a module entry point
  with checks (the month-end checklist), the core's entry point is the route, so the checklist is judged on the
  main phone's books.
- **Sign-in** — PINs are checked on the main phone (`IdentityService.verifyRemoteLogin`), which counts wrong
  attempts, locks, and audits the sign-in with the phone's name. The replica only starts the local session.
  Managing users, PINs, recovery codes, backups and restores happen on the main phone (`HUB_ONLY`).
- **Replication** — schema v18 adds `sync_changes`: a trigger on every table logs the primary key of each
  insert/update/delete (`SyncLog.ensureTriggers` runs after every migration, so future tables are covered). The
  main phone answers "changes since N" with the *current* row of each logged key, read in one transaction; the
  replica applies a batch in one transaction with foreign keys deferred, matching rows by primary key, and records
  its cursor in `sync_state` (never replicated). Immutable tables only ever receive inserts, so their triggers hold.
- **The replica never writes its own records.** Its audit store refuses appends (`ReplicaAuditGuard`), so its audit
  chain is always a prefix of the main phone's and verifies, and its anchor checkpoints stay valid.
- **Full copy** — pairing, a gap (cursor older than what was pruned), replaced books on the main phone (epoch
  changed) or a batch that does not apply → the replica takes a full copy: the same SQLCipher export as a backup,
  taken under `exclusive` at a log position, installed through the restore pipeline (verify → stage → announce →
  swap). The staged file records the cursor before it is swapped in, so file and cursor cannot disagree.
- **Wire** — TCP on the restaurant's Wi-Fi, no third-party code. Pairing: ephemeral P-256 ECDH; both screens show a
  six-digit code derived from the shared key and the owner confirms on both (numeric comparison, as in Bluetooth);
  one phone per five-minute window. Sessions: HKDF of the pairing key with both nonces → AES-256-GCM per direction,
  counter nonces (no replay, reorder or tampering). Messages are Java-serialized and read back through an allow
  list (`ir.sabou.*`, JDK/Kotlin data types). Both phones must run the same build.
- **Android** — the main phone runs a foreground service (`connectedDevice`) with a Wi-Fi lock and announces
  itself by network service discovery (`_sabou._tcp`, named by its id); it restarts after a reboot. The replica
  finds it by that name, or by an address typed in. Pairing keys and roles live outside the database, sealed by
  the Keystore. After each command the replica catches up to that command's effects before the screen refreshes;
  a background long-poll copies the main phone's own changes within about a second.

## Consequences
- The second phone needs the main phone to record anything; without it, it shows the last copy read-only and the
  form stays filled (same command id on retry, so a command that did run is not recorded twice).
- Backups are taken on the main phone only.
- The protocol is the one a server would speak later: moving the main phone's role to a server (ADR-0008) reuses
  the change log, the dispatcher and the wire.
