# ADR-0025 — Nightly automatic backup

**Status:** Accepted (2026-10-11). The user chose: the backup password stays on the phone, sealed.

## Decision
1. **Where.** A folder the owner picks with the system picker (memory card, a cloud-synced folder, USB drive); the app keeps a persistable permission to it. The newest seven automatic backups are kept (`sabou-auto-yyyy-MM-dd_HHmm.sabou`); manual backups are never touched.
2. **Password.** Asked once when switching it on (at least 10 characters, typed twice) and sealed with an Android Keystore key on this phone. The files are ordinary encrypted backups: restoring one on another phone needs only that password, which the owner must keep apart from the phone.
3. **When and as whom.** A daily system job (persisted, not on low battery) runs with nobody signed in, on behalf of the person who switched it on: `SabouCore.authorizeScheduledBackup` allows it only while that person is active and may create backups, audits `BACKUP_SCHEDULED` as theirs, and verifies the full audit chain first, as a manual backup does. A failed run deletes its partial file and is retried by the system.
4. **Watching it.** The backup page shows the last success and the last error, with «پشتیبان همین حالا»; the home screen warns when it is off, failed, or older than two days. Restoring or resetting the database switches it off (the person and folder may no longer apply).
