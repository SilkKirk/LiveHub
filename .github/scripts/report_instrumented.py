#!/usr/bin/env python3
"""Surfaces failing instrumented tests as GitHub check-run annotations.

`connectedDebugAndroidTest` prints a lot and then one exit code, so a red emulator run
reports "The process '/usr/bin/sh' failed with exit code 1" and nothing else. The test output
lives in `build/outputs/androidTest-results/` and the XML results carry the class, the test
name and the exception — including for a failure inside a Compose rule's `setContent`, which
is how the screenshot tests fail.

A **passing** run must produce no annotation at all: an earlier version fell through to the
log-scraping path whenever the XML contained no failures, which turned ordinary packaging
warnings ("Unable to strip the following libraries...") into a red annotation on a green
build. The log is only consulted when the gradle invocation itself failed, or when no result
XML exists at all.
"""

import glob
import os
import sys
import xml.etree.ElementTree as ET

MAX_ANNOTATIONS = 30
RESULT_PATTERNS = [
    os.path.join("**", "androidTest-results", "**", "TEST-*.xml"),
    os.path.join("**", "outputs", "androidTest-results", "**", "*.xml"),
]
LOG = "instrumented.log"

# Lines that mean the gradle run itself broke, as opposed to a test assertion.
BUILD_FAILURE_MARKERS = (
    "FAILURE: Build failed",
    "BUILD FAILED",
    "> Task :app:connectedDebugAndroidTest FAILED",
)


def emit(message: str) -> None:
    print(f"::error title=instrumented tests::{message[:6000]}")


def result_paths():
    paths = []
    for pattern in RESULT_PATTERNS:
        paths.extend(glob.glob(pattern, recursive=True))
    return sorted(set(paths))


def read_log():
    if not os.path.exists(LOG):
        return []
    with open(LOG, encoding="utf-8", errors="replace") as handle:
        return handle.read().splitlines()


def build_failed(log_lines) -> bool:
    return any(marker in line for line in log_lines for marker in BUILD_FAILURE_MARKERS)


def report_from_xml(paths) -> int:
    """Emits one annotation per failing case. Returns the number of failures."""
    total = 0
    failed = 0
    emitted = 0
    for path in paths:
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        for case in root.iter("testcase"):
            total += 1
            problems = [child for child in case if child.tag in ("failure", "error")]
            if not problems:
                continue
            failed += 1
            if emitted >= MAX_ANNOTATIONS:
                continue
            emitted += 1
            classname = case.get("classname") or "?"
            name = case.get("name") or "?"
            message = " ".join((problems[0].get("message") or "").split())
            if not message:
                message = " ".join((problems[0].text or "").split())[:400]
            emit(f"{classname}.{name} — {message[:800]}")

    print(f"instrumented: {total} test(s), {failed} failure(s)")
    return failed


def report_from_log(log_lines) -> None:
    interesting = [
        line.strip()
        for line in log_lines
        if any(
            marker in line
            for marker in (
                "FAILED",
                "AssertionError",
                "IllegalStateException",
                "Caused by",
                "No connected devices",
                "AdbCommandRejectedException",
                "ShellCommandUnresponsiveException",
                "FAILURE: Build failed",
                "BUILD FAILED",
            )
        )
    ]
    if not interesting:
        interesting = [line.strip() for line in log_lines[-25:] if line.strip()]
    emit(" | ".join(interesting[:40]) if interesting else "no diagnostic output captured")


def main() -> int:
    paths = result_paths()
    log_lines = read_log()

    if paths:
        failed = report_from_xml(paths)
        if failed == 0 and not build_failed(log_lines):
            # Green run: stay silent.
            return 0
        if failed > 0:
            return 0

    # Either no XML at all, or the build broke without producing results. If there is
    # neither a result file nor a log, this is not running inside the CI step at all
    # (a local invocation, say), so saying nothing is correct.
    if not paths and not log_lines:
        print("no instrumented results or log to inspect")
        return 0

    if build_failed(log_lines) or not paths:
        report_from_log(log_lines)
    return 0


if __name__ == "__main__":
    sys.exit(main())
