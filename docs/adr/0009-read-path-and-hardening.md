# ADR-0009 — One guarded read path, and the hardening round of 2026-10-09

**Status:** Accepted (2026-10-09)

## Context
An independent review of v1 (two reviewers, 32 findings) showed that screens read stores directly, so roles saw data their permissions did not allow (salaries, other branches' ledgers, purchases). It also found domain gaps: paying while recording an invoice without PURCHASE_PAY, full-month salary for any period length, a reversed invoice blocking its number, rounding on partial returns, weak audit-chain checks, and unsafe restore ordering.

## Decision
- **Reads:** `Overview` is the only read path for the app. Every method checks the signed-in actor, a permission and the branch scope. The raw stores, gateways and the ledger are `internal` to `modules/core`, so the app cannot bypass this at compile time. Tabs, menus and quick actions are shown by permission.
- **Commands:** a command may declare `additionalPermissions` (an invoice with immediate payment also needs PURCHASE_PAY). Shared reference data (items, suppliers, menu, recipes) is `sharedCatalog`: it lives in the organization scope but needs only its own permission.
- **Roles:** closing periods and full backups are not branch-manager tasks; full backups are owner-only (a backup contains every user's PIN hash).
- **Audit chain:** verification starts from the genesis link (position 1, empty previous hash), checks contiguous positions and per-epoch sequences. Startup verifies incrementally from the last HMAC-signed checkpoint (which now records the position). A full verification runs before every backup and on every restore candidate.
- **Payroll:** a run covers one whole month (29–31 days); the UI picks a Jalali month. National ids are stored normalized. Tax rates are bounded; the insurance tax exemption ratio is entered by the owner.
- **Purchasing:** a reversed invoice frees its number; returning the last unit credits exactly the remaining value.
- **Users:** the owner edits role and branches (never re-activating a user or touching their PIN), re-activates, resets a PIN; every user can change their own PIN.
- **Events:** kept locally for 90 days until sync exists; afterwards only delivered events are pruned.
- **App safety:** bootstrap is one transaction; restore swaps files atomically and always re-opens; a lost Keystore key is reported instead of silently replaced; plaintext temp files are removed at start; the anchor file is fsynced; SQLite corruption never deletes the file; destructive actions ask for confirmation.
- **Supply chain:** Gradle verifies SHA-256 of every dependency (`gradle/verification-metadata.xml`) and CI validates the wrapper.

## Consequences
Reads are safe by construction rather than by convention. Startup cost no longer grows with history. Partial-month payroll (hires/leavers mid-month) is not supported yet and is rejected with a clear message.
