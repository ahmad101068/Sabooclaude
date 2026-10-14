#!/usr/bin/env bash
# Creates the release signing key once (docs/RELEASE.md). Keep the .jks file and the password safe and
# OUT of the repository: every future update must be signed with this same key.
set -euo pipefail
out="${1:-sabou-release.jks}"
if [ -e "$out" ]; then echo "$out already exists; refusing to overwrite a signing key." >&2; exit 1; fi
command -v keytool >/dev/null || { echo "keytool (Java) is needed." >&2; exit 1; }
password="$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 24)"
keytool -genkeypair -v -keystore "$out" -storetype PKCS12 -alias sabou -keyalg RSA -keysize 4096 -validity 36500 \
  -storepass "$password" -keypass "$password" -dname "CN=Sabou, O=Sabou, C=IR" >/dev/null
base64 -w0 "$out" > "$out.b64" 2>/dev/null || base64 "$out" | tr -d '\n' > "$out.b64"
echo "Created $out (and $out.b64 for the SABOU_KEYSTORE_B64 secret)."
echo
echo "SABOU_KEYSTORE_PASSWORD = $password"
echo "SABOU_KEY_ALIAS         = sabou"
echo "SABOU_KEY_PASSWORD      = $password"
echo
echo "Certificate fingerprint (compare it before installing updates):"
keytool -list -v -keystore "$out" -storepass "$password" -alias sabou | grep -E "SHA256:" || true
