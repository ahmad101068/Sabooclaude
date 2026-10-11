# ADR-0023 — Owner recovery code and fingerprint sign-in

**Status:** Accepted (2026-10-11). Field feedback: a forgotten owner PIN had no way back but restoring a backup.

## Decision
1. **Recovery code.** The owner issues a one-time recovery code of 16 random digits (shown once as four groups, to be written on paper), after proving the current PIN. Only its PBKDF2 hash is stored. With it, the owner sets a new PIN without signing in; wrong codes count like wrong PINs (the same growing lock). A successful recovery spends the code; a new code replaces the old one. Only owners have one; other users are reset by an owner as before. Issuing, failures and recovery are audited.
2. **Fingerprint sign-in.** On Android 9+, a user can seal their PIN with an Android Keystore AES-GCM key that requires a strong biometric for every use and is invalidated when a new fingerprint is enrolled. The sealed PIN is kept in no-backup storage; a fingerprint unseals it and the ordinary login checks it, so locks, audit and permissions are those of typing it. Enabling requires proving the PIN (`IdentityService.confirmPin`). It is forgotten when the PIN no longer works, and whenever the database is restored or reset.
3. A PIN-change page («من ← امنیت ورود») holds PIN change, fingerprint and the recovery code; the home screen reminds an owner without a code.

## Not done (needs a server)
SMS or e-mail one-time codes.
