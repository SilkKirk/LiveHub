#!/usr/bin/env python3
"""Reject an `IconButton` whose click target cannot be 48dp.

The 48dp touch minimum has been broken in this project twice, both times in a way that looked
fixed:

  * round 61 — `size(32.dp)` on an `IconButton`;
  * round 65 — `size(48.dp).padding(8.dp)`, a 48dp node around a 32dp clickable area, because
    `IconButton` applies Material's minimum *inside* the caller's modifier.

Neither was caught by a test. For ten rounds a `lint { error += "TouchTargetSizeCheck" }` line
claimed to guard the third occurrence, and it was fiction: that string is not a Gradle lint issue
id, so lint only ever answered `UnknownIssueId` and enforced nothing.

Why this is a source check and not a test
-----------------------------------------
Both runtime metrics were tried and both are unusable, measured rather than assumed:

  * `touchBoundsInRoot` — Compose normalises it to the minimum touch target size for **any** node
    carrying a click action. A 32dp `IconButton` and even a hand-rolled 32dp
    `Box { Modifier.clickable {} }` both report 48dp, so the metric can never fail.
  * `boundsInRoot` — truthful, but Material's `FilterChip` is 36dp and its `Switch` 32dp *by
    design* (both with a 48dp touch target), so it flags 12 legitimate controls across 搜索, 设置
    and 同步.

That leaves the convention itself as the thing worth enforcing, which is what this script does.
It is the same shape as `check_assert_order.py`: a mistake that already happened twice, cheap to
detect in source, and impossible to state as a runtime property.

Rules
-----
For every `IconButton(` call:

  1. it must pass a `modifier = …` — Material's default state layer is 40dp, below the minimum;
  2. if that modifier carries a literal `.size(<n>.dp)`, `n` must be at least 48;
  3. the modifier must not contain `.padding(` — padding inside the caller's modifier is what
     shrank the clickable in round 65 while leaving the node at 48dp.

A size expressed as a named constant (`Modifier.size(AVATAR_SIZE_DP.dp)`) is accepted as-is: this
check cannot evaluate constants, and refusing them would only teach people to inline magic
numbers.

Exits non-zero with one line per finding so CI surfaces it as a normal failure.
"""

import pathlib
import re
import sys

# Source roots that may draw UI. `core:*` and `platform:*` are plain JVM and draw nothing.
UI_ROOTS = ("app/src/main", "core/designsystem/src/main", "app/src/test", "app/src/androidTest")

CALL = re.compile(r"\bIconButton\s*\(")
# A literal dp size, e.g. `.size(32.dp)` or `.size(width = 32.dp, height = 40.dp)`.
LITERAL_SIZE = re.compile(r"\.size\s*\(([^)]*)\)")
DP_LITERAL = re.compile(r"(\d+(?:\.\d+)?)\.dp")
MIN_DP = 48.0


def split_arguments(call_text):
    """Splits the inside of a call into top-level arguments, respecting nesting and strings."""
    args, depth, current, in_string, escaped = [], 0, [], False, False
    for char in call_text:
        if in_string:
            current.append(char)
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            continue
        if char == '"':
            in_string = True
            current.append(char)
        elif char in "([{":
            depth += 1
            current.append(char)
        elif char in ")]}":
            if depth == 0:
                args.append("".join(current))
                return args
            depth -= 1
            current.append(char)
        elif char == "," and depth == 0:
            args.append("".join(current))
            current = []
        else:
            current.append(char)
    args.append("".join(current))
    return args


def calls_in(source):
    """Yields (line_number, argument_text) for every IconButton call."""
    for match in CALL.finditer(source):
        line = source.count("\n", 0, match.start()) + 1
        args = split_arguments(source[match.end():])
        yield line, [arg.strip() for arg in args]


def modifier_argument(args):
    """The `modifier = …` argument text, or None when the call passes none."""
    for arg in args:
        if arg.lstrip().startswith("modifier"):
            return arg
    return None


def violations(path, source):
    found = []
    for line, args in calls_in(source):
        modifier = modifier_argument(args)
        if modifier is None:
            found.append((line, "no `modifier =`; Material's default target is 40dp"))
            continue

        # Rule 3: padding inside the caller's modifier shrinks the clickable area.
        if ".padding(" in modifier.replace(" ", ""):
            found.append((line, "`padding` inside the IconButton modifier shrinks the click target"))
            continue

        # Rule 2: a literal size must clear the minimum.
        for size_call in LITERAL_SIZE.finditer(modifier):
            numbers = [float(value) for value in DP_LITERAL.findall(size_call.group(1))]
            if not numbers:
                continue
            smallest = min(numbers)
            if smallest < MIN_DP:
                found.append(
                    (line, f"`{size_call.group(0)}` is under {MIN_DP:.0f}dp (was {smallest:g}dp)")
                )
    return found


def main():
    root = pathlib.Path(__file__).resolve().parents[2]
    problems = 0
    for relative_root in UI_ROOTS:
        for path in sorted((root / relative_root).rglob("*.kt")):
            if not path.is_file():
                continue
            for line, why in violations(path, path.read_text(encoding="utf-8")):
                problems += 1
                print(f"::error file={path.relative_to(root)},line={line}::IconButton: {why}")
    if problems:
        print(f"{problems} IconButton call(s) cannot reach the 48dp minimum.")
        print("Give each one `modifier = Modifier.size(48.dp)`; see Loop 61/65 in docs/ITERATIONS.md.")
        return 1
    print("touch targets: ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
