#!/usr/bin/env bash
# Runs the instrumented tests on the CI emulator. Kept as a script: the emulator action runs each
# line of its `script` separately, which loses pipefail and conditionals.
set -o pipefail
args=(--no-daemon --stacktrace :app:connectedDebugAndroidTest :app:assembleReleaseCheck)
if [ ! -f gradle/verification-metadata.xml ]; then
  args=(--no-daemon --write-verification-metadata sha256 domainBuild :app:assembleDebug :app:assembleRelease :app:lintDebug :app:connectedDebugAndroidTest :app:assembleReleaseCheck)
fi
./gradlew "${args[@]}" 2>&1 | tee device-tests.log || exit 1

# The minified release build (R8) must install and start: a shrinking problem would crash it here (ADR-0048).
{
  echo "--- release check (minified build) ---"
  apk=$(ls app/build/outputs/apk/releaseCheck/*.apk | head -1)
  adb install -r "$apk" || exit 1
  adb logcat -c
  adb shell am start -W -n ir.sabou.erp.check/ir.sabou.app.MainActivity || exit 1
  sleep 20
  adb shell pidof ir.sabou.erp.check || { echo "release check: the app is not running"; adb logcat -d -b crash; exit 1; }
  if adb logcat -d -b crash | grep -q "ir.sabou.erp.check"; then echo "release check: crash"; adb logcat -d -b crash; exit 1; fi
  echo "release check: started and running"
} 2>&1 | tee -a device-tests.log
