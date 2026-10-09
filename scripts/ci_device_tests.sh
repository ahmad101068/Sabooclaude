#!/usr/bin/env bash
# Runs the instrumented tests on the CI emulator. Kept as a script: the emulator action runs each
# line of its `script` separately, which loses pipefail and conditionals.
set -o pipefail
args=(--no-daemon --stacktrace :app:connectedDebugAndroidTest)
if [ ! -f gradle/verification-metadata.xml ]; then
  args=(--no-daemon --write-verification-metadata sha256 domainBuild :app:assembleDebug :app:assembleRelease :app:lintDebug :app:connectedDebugAndroidTest)
fi
./gradlew "${args[@]}" 2>&1 | tee device-tests.log
