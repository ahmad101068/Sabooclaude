# ADR-0015 — Crash-safe database replacement, quarantine and the write barrier

**Status:** Accepted (2026-10-10). Supersedes the replacement part of ADR-0004.

## Context
Two independent audits of `3ad863a` (2026-10-10) found root causes in how the database is replaced:
- The `REBASE` anchor named only the new epoch and was written before the file swap. A crash or a failed rename in between left the untouched database flagged as a rollback, and the staged copy was deleted on the next start (lockout; the user could only restore again or erase).
- Factory reset deleted the database and its key; the recovery screen offered that reset (and restore) without signing in, also for failures that may pass (a busy file, a transient Keystore error).
- Nothing stopped writes while a database was being replaced or exported.
- A database whose anchor file had been deleted was treated as a first start, so the same access that swaps in an older copy could hide it.
- Restore still accepted the old plain-SQLite payload (v1), which is decrypted to disk unencrypted.

## Decision
1. **Two-phase anchor.** Replacement records a `PENDING_REBASE` with the new epoch *and* the epoch being replaced. Until the next startup checkpoint either database is genuine; no third one is. The first healthy start writes a `CHECKPOINT`, which settles it. A brand-new database takes the pending epoch, so an interrupted reset completes.
2. **Write barrier.** `SqlUnitOfWork` is the single path of every write. `exclusive {}` waits for the transaction in progress and keeps new ones out (used for the backup export); `seal()` refuses every later transaction. `SabouCore.acceptReplacement` authorizes, audits, records the pending anchor and seals, in one exclusive section.
3. **Quarantine, never delete.** Before a restore swaps files, the current database is copied (with its still-wrapped key) to `noBackupFilesDir/quarantine/<time>-<reason>/`; a reset moves it there. The newest three are kept.
4. **Who may replace data.** With a readable database the core decides: a signed-in user with `BACKUP_RESTORE`/`FACTORY_RESET`, or a database without any user (first run on a new phone). Without a readable database, only a *provably permanent* failure (Keystore key lost, file not readable with this key) allows it without signing in. A failure that may pass offers only "try again". On an integrity failure the owner signs in first.
5. **Anchor missing.** The database records that it has been anchored; a missing anchor for such a database is `ANCHOR_MISSING`, not a first start.
6. **Restore** verifies the candidate with the full audit chain (`verifyAuditFull`), and refuses payloads other than v2 (`unsupported_payload`) before writing anything.

## Consequences
- A crash at any step of a restore or reset leaves a database that opens normally (device tests inject a failure after every step).
- Data replaced by a restore or reset can be recovered from quarantine by support; it costs one extra copy of the database on the device for a restore.
- Writes attempted while a replacement is running fail with `INVALID_STATE:DATABASE:SEALED:<reason>` instead of landing in a file that is about to be replaced.
- v1 backups can no longer be restored; there are none in the field.
