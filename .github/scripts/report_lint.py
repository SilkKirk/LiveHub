#!/usr/bin/env python3
"""Turns the Android lint XML report into GitHub check-run annotations.

Lint writes its findings to a report file and prints only a one-line failure to the job
log. Reading the raw log back through the API needs admin rights, but annotations are
readable by anyone with `checks: read`, so each finding is echoed as a workflow command.

Severity mapping matters: a lint *warning* annotated as `::error` inflates the error count
and hides the finding that actually failed the build, which is exactly what happened once
already. Warnings stay warnings, and the errors are additionally summarised in one
annotation so they can be read without scrolling.

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
    errors = [i for i in findings if (i.get("severity") or "").lower() == "error"]
    warnings = [i for i in findings if (i.get("severity") or "").lower() != "error"]
    print(f"{len(findings)} lint finding(s): {len(errors)} error(s), {len(warnings)} warning(s)")

    def describe(issue):
        severity = issue.get("severity") or "?"
        issue_id = issue.get("id") or "?"
        message = " ".join((issue.get("message") or "").split())
        location = issue.find("location")
        path = (location.get("file") if location is not None else None) or "?"
        line = (location.get("line") if location is not None else None) or "1"
        # GitHub rejects annotations whose path is not repo-relative.
        if path.startswith(os.getcwd() + os.sep):
            path = os.path.relpath(path, os.getcwd())
        return severity, issue_id, message, path, line

    for issue in findings[:MAX_ANNOTATIONS]:
        severity, issue_id, message, path, line = describe(issue)
        command = "error" if severity.lower() == "error" else "warning"
        print(f"::{command} file={path},line={line}::{severity}: {issue_id}: {message}")

    # One annotation that names only the real errors, so the cause of a red build is
    # readable at a glance instead of being buried among version-bump advisories.
    if errors:
        summary = " | ".join(
            f"{issue_id} @ {path}:{line} — {message[:160]}"
            for _, issue_id, message, path, line in (describe(i) for i in errors[:15])
        )
        print(f"::error title=lint errors ({len(errors)})::{summary[:6000]}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
