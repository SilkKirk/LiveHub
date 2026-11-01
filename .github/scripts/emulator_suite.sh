#!/usr/bin/env bash
#
# Everything that has to run while the emulator is still alive.
#
# `reactivecircus/android-emulator-runner` tears the device down as soon as its `script`
# input finishes, and it executes that input **line by line** through `sh -c` — so a
# multi-line `if ... fi` written inline is a syntax error, and shell variables do not
# survive from one line to the next. Both facts bit: an inline smoke test failed with
# "Syntax error: end of file unexpected (expecting \"fi\")" before it ever ran.
#
# Keeping the whole suite in one invoked script solves both problems at once.
#
# Exit code is non-zero if either the instrumented tests or the release smoke test failed.
set -uo pipefail

WORKSPACE="${GITHUB_WORKSPACE:-$(pwd)}"
cd "$WORKSPACE" || exit 1

TEST_EXIT=0
SMOKE_EXIT=0

echo "=== instrumented tests ==="
./gradlew connectedDebugAndroidTest --stacktrace > "$WORKSPACE/instrumented.log" 2>&1
TEST_EXIT=$?
# Surface which test failed: the gradle output alone is unreadable through the API, and a
# screenshot-test crash otherwise reports only "exit code 1".
python3 .github/scripts/report_instrumented.py || true
if [ "$TEST_EXIT" != "0" ]; then
  echo "=== instrumented log tail ==="
  tail -n 60 "$WORKSPACE/instrumented.log" || true
fi

# ScreenshotTest writes the renders into the shared Download folder, so a plain pull works
# while the device is still up.
echo "=== pull screenshots ==="
mkdir -p "$WORKSPACE/ui-screenshots"
adb shell ls -la /sdcard/Download/livehub-shots || true
adb pull /sdcard/Download/livehub-shots "$WORKSPACE/ui-screenshots" || true
ls -la "$WORKSPACE/ui-screenshots" || true

echo "=== release smoke ==="
bash .github/scripts/release_smoke.sh \
  "$WORKSPACE/app/build/outputs/apk/release/app-release.apk" \
  "$WORKSPACE/release-smoke"
SMOKE_EXIT=$?

echo "instrumented=$TEST_EXIT smoke=$SMOKE_EXIT"

# Report the test failure first when both fail: it is the more actionable of the two.
if [ "$TEST_EXIT" != "0" ]; then
  exit "$TEST_EXIT"
fi
exit "$SMOKE_EXIT"