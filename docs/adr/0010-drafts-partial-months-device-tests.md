# ADR-0010 — Unfinished forms, partial months, daily audit check, device tests

**Status:** Accepted (2026-10-09)

## Context
After the first install on a real phone, the remaining limitations of v1 were: forms lost when Android kills the app in the background; no notice when a write failed after its screen was left; a plaintext copy of the database during backup and restore; factory reset one tap away on the recovery screen; a full audit check only weekly; no partial-month payroll; and nothing ever executed on Android itself.

## Decision
- **Drafts:** each page gets its own `SaveableStateRegistry`; form fields use `rememberSaveable`. On `ON_STOP` the page's values, the back stack and the branch are marshalled, encrypted with a dedicated Keystore AES-GCM key and written to `no_backup/drafts.bin`. After the same user signs in again (within 12 hours, branch still allowed) the page re-opens with its values; the file is consumed on read and dropped if it fails to decrypt. PINs and backup passwords are never saveable. Command ids are saved with the form, so a write that completed just before the process died is recognised by idempotency rather than recorded twice.
- **Failed writes:** a write that fails after its screen was left shows an app-wide dialog.
- **Backup payload v2:** the database is exported into a temporary SQLCipher file under a fresh random key; the backup (format 4, password AES-GCM) carries `SABOUDB2 + key + encrypted file`. Restore keeps the candidate encrypted under that key while verifying it. v1 payloads (plain SQLite) are still restored.
- **Factory reset:** type «پاک» and wait 10 seconds, on the recovery screen and in settings.
- **Audit:** the whole chain is verified in the background once a day (after opening and on returning to the foreground); a failure moves the app to the recovery screen. Startup still forces a full check after 7 days without a successful one.
- **Payroll:** employees have an optional start date and an end date (`EndEmployment`). A month in which someone worked only some days pays salary × days ÷ `prorationDays` (policy field, default 30, 28–31), capped at the monthly salary; absence and overtime keep the monthly minute rate, and absence never exceeds the prorated base. Attendance is accepted only on employed days. An end date cannot fall inside a month already approved with full pay. Insurance ceilings and tax brackets stay monthly (a legal question for the yearly policy review).
- **Device tests:** CI runs instrumented tests on an Android 11 emulator: encrypted database and Keystore key across reopen, backup/restore (wrong password refused, nothing plaintext left), rollback detection with a real file swap, encrypted drafts through a Parcel, and every page opened with its draft saved.

## Consequences
The one remaining gap that code cannot close on the device alone is a rooted device deleting the anchor file; a server-side anchor (ADR-0008) closes it.
