#!/usr/bin/env python3
"""Reject assertion calls whose message argument is in the wrong position.

This repository has two assertion APIs with **opposite** argument orders:

  * `core:*` and `platform:*` are plain JVM modules on JUnit 5 (Jupiter), where the
    message goes **last**:
        assertTrue(condition, message)
        assertEquals(expected, actual, message)
        assertNull(actual, message)
  * `:app` is JUnit 4 (`org.junit.Assert`), where the message goes **first**:
        assertTrue(message, condition)
        assertEquals(message, expected, actual)
        assertNull(message, actual)

Writing one the other way is a compile error in the common case — but not always:
`assertEquals("msg", 1, count)` compiles happily in a Jupiter file (`expected` looks like a
string, `actual` looks like a string) and then fails at run time with
`expected: <msg> but was: <…>`, which reads like a genuine assertion failure rather than a
typo. That has cost this project four separate CI runs.

The check is deliberately narrow so it has no false positives:

  * it only inspects files under the JUnit 5 source sets (`core/` and `platform/`);
  * `assertTrue`/`assertFalse`/`assertNull`/`assertNotNull` with a string literal as the
    **first** argument are always wrong there, because a boolean or a null cannot be a literal;
  * `assertEquals` with three or more arguments is wrong when the first is a string literal
    **and** the last is not one — that shape can only be `(message, expected, actual)`.

Everything else is left alone, including `assertEquals(expected, actual)` and
`assertEquals(expected, actual, message)`.

Exits non-zero with one line per finding so CI surfaces it as a normal failure.
"""

import pathlib
import re
import sys

# Calls whose first argument can never legitimately be a string in a Jupiter assertion.
UNARY = ("assertTrue", "assertFalse", "assertNull", "assertNotNull")
JUPITER_ROOTS = ("core", "platform")

CALL = re.compile(r"\b(assert\w+)\s*\(")
# A *bare* string literal: `"…"` and nothing else. Deliberately not `startswith('"')`, because
# `assertTrue("S15" in names, "expected …")` starts with a literal and is a perfectly correct
# Jupiter call.
BARE_STRING_LITERAL = re.compile(r'^"(?:[^"\\]|\\.)*"$')


def is_bare_string_literal(argument):
    return bool(BARE_STRING_LITERAL.match(argument.strip()))


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
                return _trim_trailing_blank(args)
            depth -= 1
            current.append(char)
        elif char == "," and depth == 0:
            args.append("".join(current))
            current = []
        else:
            current.append(char)
    args.append("".join(current))
    return _trim_trailing_blank(args)


def _trim_trailing_blank(args):
    """Kotlin allows a trailing comma, which leaves one whitespace-only argument behind."""
    while args and not args[-1].strip():
        args.pop()
    return args


def calls_in(source):
    """Yields (line_number, function_name, arguments) for every assertion call."""
    for match in CALL.finditer(source):
        name = match.group(1)
        start = match.end()
        args = split_arguments(source[start:])
        if not args or len(args) < 2:
            continue
        line = source.count("\n", 0, match.start()) + 1
        yield line, name, [arg.strip() for arg in args]


def violations(path, source):
    found = []
    for line, name, args in calls_in(source):
        if not args:
            continue
        first_is_string = is_bare_string_literal(args[0])
        if name in UNARY and len(args) == 2 and first_is_string:
            found.append(
                (line, name, "the message is the first argument; Jupiter puts it last")
            )
        elif name == "assertEquals" and len(args) >= 3:
            last_is_string = is_bare_string_literal(args[-1])
            if first_is_string and not last_is_string:
                found.append(
                    (line, name, "looks like (message, expected, actual); Jupiter wants (expected, actual, message)")
                )
    return found


def main():
    root = pathlib.Path(__file__).resolve().parents[2]
    problems = 0
    for relative_root in JUPITER_ROOTS:
        for path in sorted((root / relative_root).rglob("*.kt")):
            if "src/test" not in path.as_posix():
                continue
            for line, name, why in violations(path, path.read_text(encoding="utf-8")):
                problems += 1
                print(f"::error file={path.relative_to(root)},line={line}::{name}: {why}")
    if problems:
        print(f"{problems} assertion(s) put the message in the JUnit 4 position.")
        print("These modules are JUnit 5: the message goes last. See loop-runbook.md section 4.")
        return 1
    print("assertion argument order: ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
