#!/usr/bin/env bash
#
# Launches the *release* APK on the already-running emulator and fails if it does not stay
# up.
#
# Why a separate file: `reactivecircus/android-emulator-runner` executes its `script` input
# line by line, each through `sh -c`, so anything spanning lines — `if ... fi`, `for ... done`
# — is a syntax error there. Keeping the logic in one file means it is one command, and it
# can also be run locally without the action.
#
# Every other automated check in the repository runs the debug build, where minification is
# off. v0.9.0 shipped from exactly that blind spot and crashed on launch with no dialog.
#
# Usage: release_smoke.sh <apk-path> <out-dir>
set -uo pipefail

APK="${1:?apk path required}"
OUT="${2:?output directory required}"
PACKAGE="com.livehub.app"
ACTIVITY="$PACKAGE/.MainActivity"
WAIT_SECONDS="${SMOKE_WAIT_SECONDS:-15}"

mkdir -p "$OUT"
FAILED=0

echo "=== release smoke test: $APK ==="
if [ ! -f "$APK" ]; then
  echo "::error::release APK was not produced at $APK"
  exit 1
fi

if ! adb install -r "$APK" > "$OUT/install.txt" 2>&1; then
  echo "::error::release APK could not be installed"
  cat "$OUT/install.txt"
  exit 1
fi
echo "installed"

adb logcat -c || true
# The ATD image boots with the screen off and no launcher, so a capture taken without waking
# it came out as a single flat colour even though the app had drawn its first frame. Waking
# and dismissing the keyguard makes the screenshot an actual check that something rendered.
adb shell input keyevent KEYCODE_WAKEUP > /dev/null 2>&1 || true
adb shell wm dismiss-keyguard > /dev/null 2>&1 || true
sleep 1
adb shell am start -W -n "$ACTIVITY" > "$OUT/start.txt" 2>&1 || true
cat "$OUT/start.txt"

sleep "$WAIT_SECONDS"

adb logcat -d > "$OUT/logcat.txt" 2>&1 || true

if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then
  echo "::error::release APK crashed on launch"
  echo "--- crash ---"
  grep -A 40 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -n 60
  FAILED=1
fi

if adb shell pidof "$PACKAGE" > "$OUT/pid.txt" 2>&1; then
  echo "release APK is alive after ${WAIT_SECONDS}s (pid $(cat "$OUT/pid.txt"))"
  adb shell input keyevent KEYCODE_WAKEUP > /dev/null 2>&1 || true
  adb exec-out screencap -p > "$OUT/launch.png" 2>/dev/null || true

  # Alive is not the same as showing something. A Compose error that leaves a blank window
  # still keeps the process running, so the resumed activity is checked explicitly: if our
  # activity is not the one on screen, the app either crashed back to nothing or never came
  # to the foreground. Text-based, unlike decoding the screenshot.
  adb shell dumpsys activity activities > "$OUT/activities.txt" 2>&1 || true
  if grep -q "ResumedActivity.*$PACKAGE/" "$OUT/activities.txt"; then
    echo "release activity is resumed and on screen"
  else
    echo "::error::release activity is not resumed after ${WAIT_SECONDS}s"
    grep -iE "ResumedActivity|$PACKAGE" "$OUT/activities.txt" | head -n 20 || true
    FAILED=1
  fi
else
  echo "::error::release APK is not running after ${WAIT_SECONDS}s"
  cat "$OUT/pid.txt" 2>/dev/null || true
  grep -iE "livehub|$PACKAGE" "$OUT/logcat.txt" | tail -n 40 || true
  FAILED=1
fi

adb shell am force-stop "$PACKAGE" > /dev/null 2>&1 || true

echo "=== release smoke exit $FAILED ==="
exit "$FAILED"