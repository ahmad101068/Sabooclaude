# ADR-0048: Release builds

Status: accepted (2026-10)

## Context

The app has only ever been installed as the debug build (`ir.sabou.erp.debug`, public debug key). The release build
(`ir.sabou.erp`) is shrunk by R8 and signed with a private key that must never be in the repository; CI could build
it when the signing secrets exist, but nothing published it and nothing checked that the shrunk app still runs.

## Decision

- `release.yml`: on a `v*` tag (or by hand) the domain tests run, the release APK is built and signed from the four
  `SABOU_*` secrets, its certificate is printed, and a GitHub Release is published with the APK and its SHA-256.
  The tag must match `VERSION_NAME`. Missing secrets fail the run with a clear message.
- `scripts/make_release_key.sh` creates the key once (PKCS12, RSA 4096, 100 years) and prints the secret values;
  `.gitignore` keeps `*.jks`, `*.keystore` and `*.jks.b64` out of the repository. `docs/RELEASE.md` is the guide.
- R8 keeps Sabou's own classes whole (`-keep class ir.sabou.**`): commands are recognised by annotations, routes and
  drafts are `Serializable` in the saved state. Libraries are still shrunk.
- A `releaseCheck` build type — the release build signed with the debug key, id `ir.sabou.erp.check` — is installed
  and started on the CI emulator after the device tests; a crash fails the run.

## Consequences

- Moving from the test app to the real one is a backup and a restore (different app, different data).
- Losing the key means users must uninstall and restore from a backup to get updates.
