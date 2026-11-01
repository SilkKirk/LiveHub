#!/usr/bin/env python3
"""Surfaces failing JVM unit tests as GitHub check-run annotations.

`./gradlew test` prints progress and one exit code, so a red build says nothing about
*which* test failed. The JUnit XML results carry the class, the test name and the first
lines of the assertion message; each failure becomes one `::error::` annotation.

Exits 0 even when tests failed — the gradle step owns the build result, and this script
must never be the reason a run is red.
"""

import glob
import os
import sys
import xml.etree.ElementTree as ET

MAX_ANNOTATIONS = 30
PATTERN = os.path.join("**", "build", "test-results", "**", "TEST-*.xml")


def main() -> int:
    reports = glob.glob(PATTERN, recursive=True)
    if not reports:
        print(f"no JUnit XML reports matched {PATTERN}")
        return 0

    total = 0
    failed = 0
    emitted = 0
    for path in reports:
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        suite = root if root.tag == "testsuite" else root.find("testsuite")
        if suite is None:
            continue
        for case in suite.iter("testcase"):
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
                message = " ".join((problems[0].text or "").split())[:300]
            print(
                f"::error title=test failed::{classname}.{name} — {message[:800]}"
            )

    print(f"{total} test(s), {failed} failure(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
