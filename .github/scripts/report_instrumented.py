#!/usr/bin/env python3
"""Surfaces failing instrumented tests as GitHub check-run annotations.

`connectedDebugAndroidTest` prints a lot and then one exit code, so a red emulator run
reports "The process '/usr/bin/sh' failed with exit code 1" and nothing else. The test output
lives in `build/outputs/androidTest-results/` and the XML results are what carry the class,
the test name and the exception — including for a failure inside a Compose rule's
`setContent`, which is how the screenshot tests fail.

Falls back to grepping the captured gradle log when no result XML exists (a crash before any
test starts, or a device that never came up).
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


def emit(message: str) -> None:
    print(f"::error title=instrumented tests::{message[:6000]}")


def from_xml() -> bool:
    paths = []
    for pattern in RESULT_PATTERNS:
        paths.extend(glob.glob(pattern, recursive=True))
    if not paths:
        return False

    total = 0
    failed = 0
    emitted = 0
    for path in sorted(set(paths)):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        for case in root.iter("testcase"):
            total += 1
            problems = [c for c in case if c.tag in ("failure", "error")]
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
    return failed > 0


def from_log() -> None:
    if not os.path.exists(LOG):
        print(f"no {LOG} and no result XML; nothing to report")
        return

    lines = open(LOG, encoding="utf-8", errors="replace").read().splitlines()
    interesting = [
        line.strip()
        for line in lines
        if any(
            marker in line
            for marker in (
                "FAILED",
                "AssertionError",
                "IllegalStateException",
                "Caused by",
                "error:",
                "e: ",
                "No connected devices",
                "AdbCommandRejectedException",
                "ShellCommandUnresponsiveException",
            )
        )
    ]
    if not interesting:
        tail = [line.strip() for line in lines[-25:] if line.strip()]
        interesting = tail
    emit(" | ".join(interesting[:40]))


def main() -> int:
    if not from_xml():
        from_log()
    return 0


if __name__ == "__main__":
    sys.exit(main())
