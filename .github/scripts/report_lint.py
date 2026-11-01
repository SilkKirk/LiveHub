#!/usr/bin/env python3
"""Turns the Android lint XML report into GitHub check-run annotations.

Lint writes its findings to a report file and prints only a one-line failure to the
job log. Reading the raw log back through the API needs admin rights, but annotations
are readable by anyone with `checks: read`, so a finding is echoed as a
``::error file=…,line=…::`` workflow command.

Prints nothing and exits 0 when there is no report, so the step never fails on its own.
"""

import os
import sys
import xml.etree.ElementTree as ET

REPORT = os.path.join("app", "build", "reports", "lint-results-debug.xml")
MAX_ANNOTATIONS = 50


def main() -> int:
    if not os.path.exists(REPORT):
        print(f"no lint report at {REPORT}")
        return 0

    try:
        root = ET.parse(REPORT).getroot()
    except ET.ParseError as error:
        print(f"could not parse {REPORT}: {error}")
        return 0

    findings = root.findall("issue")
    print(f"{len(findings)} lint finding(s)")

    for issue in findings[:MAX_ANNOTATIONS]:
        severity = issue.get("severity") or "?"
        issue_id = issue.get("id") or "?"
        message = " ".join((issue.get("message") or "").split())
        location = issue.find("location")
        path = (location.get("file") if location is not None else None) or "?"
        line = (location.get("line") if location is not None else None) or "1"
        # GitHub rejects annotations whose path is not repo-relative.
        if path.startswith(os.getcwd() + os.sep):
            path = os.path.relpath(path, os.getcwd())
        print(f"::error file={path},line={line}::{severity}: {issue_id}: {message}")

    if len(findings) > MAX_ANNOTATIONS:
        print(f"... and {len(findings) - MAX_ANNOTATIONS} more")
    return 0


if __name__ == "__main__":
    sys.exit(main())
