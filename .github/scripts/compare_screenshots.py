#!/usr/bin/env python3
"""Compare two directories of screenshot renders, tolerating rasteriser noise.

Byte-identical PNGs were the project's evidence that a change did not touch the UI. Round 114
measured what that claim is actually worth: two runs of the *same* code differed in up to fourteen
pixels of one channel by 1/255 on several follow screens — antialiasing rounding, not a change. Byte
identity is therefore a strong hint, not an invariant, and "0 files changed" was being read as proof
when it is really a probability.

This tool states the judgement explicitly instead: a file counts as changed only when the differing
pixels exceed `--max-pixels` or any single channel moves by more than `--max-delta`. Everything below
those thresholds is reported as `noise`, with the numbers, so a reviewer can see what was tolerated
rather than being told "no changes".

    python3 .github/scripts/compare_screenshots.py BASE_DIR CURRENT_DIR
    python3 .github/scripts/compare_screenshots.py --self-test

Exit status is 0 when nothing changed beyond the tolerance and no baseline file disappeared, 1
otherwise. New files are reported but do not fail: adding a render is an intended change. Both sides
are searched recursively and matched by file name, because the emulator step pulls the device's
`livehub-shots/` directory into `ui-screenshots/` rather than flattening it; an empty directory is an
error (exit 2), not "seventy-one missing renders".

The thresholds themselves are the part that must not drift, so [stats] and [classify] are pure and
`--self-test` exercises them with synthetic pixels. That is what CI runs (`--self-test`), which is
also why Pillow is imported lazily: the judgement is verifiable on a machine with no image library,
and only an actual directory comparison needs one (`pip install pillow`).
"""

import argparse
import sys
from pathlib import Path


def stats(first, second):
    """Pure: (differing pixels, largest single-channel delta) between two RGB sequences."""
    pixels = 0
    worst = 0
    for left, right in zip(first, second):
        delta = max(abs(a - b) for a, b in zip(left, right))
        if delta:
            pixels += 1
            if delta > worst:
                worst = delta
    return pixels, worst


def classify(pixels, delta, max_pixels, max_delta):
    """Pure: is this difference a change, or rasteriser noise?"""
    return pixels > max_pixels or delta > max_delta


def decode(path):
    from PIL import Image

    return Image.open(path).convert("RGB")


def png_files(directory):
    """Every PNG under [directory], keyed by file name.

    Recursive on purpose. The emulator step pulls the device's `livehub-shots/` directory *into*
    `ui-screenshots/`, so the renders sit one level down — and the first version of this comparison
    therefore reported all 71 baselines as MISSING on its first CI run (round 143). The layout is the
    emulator suite's business, not this tool's; what the tool needs is "the set of renders".
    """
    return {path.name: path for path in sorted(directory.rglob("*.png"))}


def measure(base_path, current_path):
    """(diff_pixels, max_channel_delta, bbox) for two renders, or None when the sizes differ."""
    from PIL import ImageChops

    base = decode(base_path)
    current = decode(current_path)
    if base.size != current.size:
        return None
    bbox = ImageChops.difference(base, current).getbbox()
    if bbox is None:
        return 0, 0, None
    pixels, delta = stats(base.crop(bbox).getdata(), current.crop(bbox).getdata())
    return pixels, delta, bbox


def self_test(max_pixels=64, max_delta=2):
    """Exercises the judgement with synthetic pixels: inside the tolerance passes, outside fails.

    A comparator that answered "noise" to everything would be worse than the byte hashes it
    replaced, so both directions are asserted. Runs without Pillow.
    """
    failures = []
    black = (10, 20, 30)

    def check(label, second, expect_changed):
        pixels, delta = stats([black], [second])
        changed = classify(pixels, delta, max_pixels, max_delta)
        if changed != expect_changed:
            failures.append(f"{label}: pixels={pixels} delta={delta} changed={changed}")

    check("identical", black, expect_changed=False)
    check("noise: one channel, by 1", (11, 20, 30), expect_changed=False)
    check("noise: the other direction", (10, 20, 29), expect_changed=False)
    check("colour change: one channel, far outside the floor", (200, 20, 30), expect_changed=True)
    check("colour change: one channel, just outside the floor", (13, 20, 30), expect_changed=True)

    # A wide, shallow difference is still a change even though no channel moved much: the pixel
    # count has to be able to decide on its own.
    wide_pixels, wide_delta = stats([black] * 100, [(11, 20, 30)] * 100)
    if not classify(wide_pixels, wide_delta, max_pixels, max_delta):
        failures.append(f"wide: {wide_pixels}px delta {wide_delta} was swallowed")

    if failures:
        for line in failures:
            print(f"self-test FAILED {line}")
        return 1
    print("compare_screenshots self-test: ok")
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--self-test", action="store_true", help="verify the judgement itself and exit")
    parser.add_argument("base", type=Path, nargs="?", help="baseline directory of *.png")
    parser.add_argument("current", type=Path, nargs="?", help="directory of *.png captured now")
    parser.add_argument(
        "--max-pixels",
        type=int,
        default=64,
        help="differing pixels still counted as noise (default 64; the worst observed noise was 14)",
    )
    parser.add_argument(
        "--max-delta",
        type=int,
        default=2,
        help="largest per-channel difference still counted as noise (default 2; observed noise is 1)",
    )
    args = parser.parse_args()

    if args.self_test:
        return self_test(args.max_pixels, args.max_delta)

    if args.base is None or args.current is None:
        parser.error("both BASE_DIR and CURRENT_DIR are required unless --self-test is given")
    if not args.base.is_dir() or not args.current.is_dir():
        print(f"both arguments must be directories: {args.base} {args.current}", file=sys.stderr)
        return 2

    try:
        import PIL  # noqa: F401
    except ImportError:
        print("Pillow is required to compare directories: pip install pillow", file=sys.stderr)
        return 2

    base = png_files(args.base)
    current = png_files(args.current)
    if not base:
        print(f"no baseline PNGs under {args.base}", file=sys.stderr)
        return 2
    # "Nothing to compare" is not "nothing changed": an empty current directory is a broken pull, and
    # reporting it as 71 missing renders buries the real cause under seventy lines.
    if not current:
        print(f"no PNGs under {args.current} — nothing was captured or pulled", file=sys.stderr)
        return 2

    changed, noisy, missing = [], [], []
    for name, base_path in base.items():
        if name not in current:
            missing.append(name)
            continue
        result = measure(base_path, current[name])
        if result is None:
            changed.append((name, "size differs"))
            continue
        pixels, delta, bbox = result
        if not pixels:
            continue
        is_change = classify(pixels, delta, args.max_pixels, args.max_delta)
        why = f"{pixels}px, delta {delta}"
        if is_change:
            why += f", bbox {bbox}"
        (changed if is_change else noisy).append((name, why))

    new = sorted(set(current) - set(base))

    for name, why in noisy:
        print(f"noise    {name}: {why}")
    for name, why in changed:
        print(f"CHANGED  {name}: {why}")
    for name in missing:
        print(f"MISSING  {name}")
    for name in new:
        print(f"new      {name}")

    print(
        f"\n{len(base)} baseline renders (from {args.base}); "
        f"{len(changed)} changed, {len(missing)} missing, {len(noisy)} within noise, {len(new)} new"
    )
    print(f"tolerance: <= {args.max_pixels}px and channel delta <= {args.max_delta}")

    if changed or missing:
        print("\nA CHANGED or MISSING render is a visual regression until explained.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
