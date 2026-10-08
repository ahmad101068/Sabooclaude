# ADR-0007 — A thin Android shell over the tested core

**Status:** Accepted (2026-10-09)

## Decision
- `app/` contains only platform glue and UI: `AndroidSqlDatabase` (SQLCipher `SupportSQLiteDatabase` behind the core's `SqlDatabase` port), `DeviceKeys` (Android Keystore: AES-GCM wrap of the random 32-byte database passphrase, HMAC key for anchors), `FileAnchorStore` (HMAC-signed integrity anchors outside the database), `AppContainer` (open, backup, restore, factory reset) and Jetpack Compose screens.
- All business rules, formatting (Persian digits, Toman, Jalali calendar) and error wording live in JVM-tested modules (`core`, `persistence`, domain modules). The UI never computes a balance.
- Reads go through `Overview` or explicit stores and refresh after each successful command; every write goes through the command pipeline with a per-form command id, so a double tap is a replay, not a duplicate.
- Restore verifies a backup completely in isolation (decrypt → migrate → audit chain) before anything of the current database is touched, re-encrypts it with the device key, records a REBASE anchor, then swaps files. Factory reset announces the new epoch first. Both satisfy ADR-0004.
- minSdk 26 (PBKDF2WithHmacSHA256, `java.util.Base64`, `ThreadLocal.withInitial` exist natively). `FLAG_SECURE` keeps financial screens out of screenshots. No network permission.
- The Compose UI follows the approved visual language (ADR-0005): five tabs خانه، فروش، عملیات، مالی، من; icons are vector drawables generated from the design canvas; Vazirmatn is bundled (OFL).

## Known limits
- Backup writes a short-lived plaintext copy to the app cache (required by `sqlcipher_export`) and deletes it immediately.
- Deleting the anchor file on a rooted device defeats rollback detection; this is a device-level trust boundary until a server keeps anchors (ADR-0001).
