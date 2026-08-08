#!/usr/bin/env python3
"""Enforce that every locale strings.xml carries the same string keys as the default.

Android silently falls back to the default locale for any missing key, so a
locale that lags behind `values/strings.xml` renders partially in English. This
tool fails the build when a locale diverges from the default *beyond what the
baseline records*, matching the spirit of the repo's `lint-baseline.xml`:

  * Keys newly added to the default but not mirrored in a locale  -> fail
  * Keys newly removed from the default but still present in a locale -> fail
  * Pre-existing drift recorded in the baseline file -> allowed (tracked)

The baseline is a JSON file mapping each locale qualifier to the missing/extra
key lists that existed when the baseline was last generated. Regenerate it with
`--update-baseline` whenever a translation batch catches the drift up.

Usage:
    python3 tools/check_locale_keys.py
    python3 tools/check_locale_keys.py --update-baseline
    python3 tools/check_locale_keys.py --baseline /tmp/baseline.json
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys
import xml.etree.ElementTree as ET

REPOSITORY_ROOT = pathlib.Path(__file__).resolve().parents[1]
DEFAULT_RES_DIR = REPOSITORY_ROOT / "app" / "src" / "main" / "res"
DEFAULT_BASELINE = REPOSITORY_ROOT / "tools" / "locale-keys-baseline.json"


def parse_string_keys(path: pathlib.Path) -> set[str]:
    """Return the set of `<string>` name attributes in an Android strings.xml."""
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as error:
        raise ValueError(f"malformed XML in {path}: {error}") from error
    keys: set[str] = set()
    for elem in root:
        if elem.tag != "string":
            continue
        name = elem.get("name")
        if not name:
            raise ValueError(f"<string> without a name attribute in {path}")
        keys.add(name)
    return keys


def locale_strings_files(res_dir: pathlib.Path) -> list[tuple[str, pathlib.Path]]:
    """List (qualifier, strings.xml path) for every locale folder that has one.

    Theme-only folders such as `values-night/` carry no strings.xml and are
    skipped by construction.
    """
    files: list[tuple[str, pathlib.Path]] = []
    for folder in sorted(res_dir.iterdir()):
        if not folder.is_dir() or not folder.name.startswith("values-"):
            continue
        strings_file = folder / "strings.xml"
        if strings_file.is_file():
            files.append((folder.name, strings_file))
    return files


def compute_drift(res_dir: pathlib.Path) -> dict[str, dict[str, list[str]]]:
    """Return per-locale drift vs the default: {qualifier: {missing, extra}}."""
    default_file = res_dir / "values" / "strings.xml"
    if not default_file.is_file():
        raise FileNotFoundError(f"default strings.xml not found: {default_file}")
    default_keys = parse_string_keys(default_file)

    drift: dict[str, dict[str, list[str]]] = {}
    for qualifier, strings_file in locale_strings_files(res_dir):
        keys = parse_string_keys(strings_file)
        drift[qualifier] = {
            "missing": sorted(default_keys - keys),
            "extra": sorted(keys - default_keys),
        }
    return drift


def load_baseline(path: pathlib.Path) -> dict[str, dict[str, list[str]]]:
    if not path.is_file():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def evaluate(
    drift: dict[str, dict[str, list[str]]],
    baseline: dict[str, dict[str, list[str]]],
) -> tuple[list[str], list[str]]:
    """Return (errors, notes) comparing live drift against the baseline.

    An entry is an error only when it is *new* relative to the baseline: a key
    missing (or extra) in a locale that was not already recorded as such.
    """
    errors: list[str] = []
    notes: list[str] = []

    # A locale recorded in the baseline that no longer ships a strings.xml (or
    # whose folder was deleted) is a regression the live drift can never show.
    for qualifier in sorted(set(baseline) - set(drift)):
        errors.append(f"{qualifier}: strings.xml missing entirely (baseline has {len(baseline[qualifier].get('missing', []))} missing + {len(baseline[qualifier].get('extra', []))} extra entries)")

    for qualifier in sorted(drift):
        live_missing = set(drift[qualifier]["missing"])
        live_extra = set(drift[qualifier]["extra"])

        base = baseline.get(qualifier, {})
        base_missing = set(base.get("missing", []))
        base_extra = set(base.get("extra", []))

        new_missing = sorted(live_missing - base_missing)
        new_extra = sorted(live_extra - base_extra)
        fixed = sorted((base_missing | base_extra) - (live_missing | live_extra))

        if new_missing:
            errors.append(
                f"{qualifier}: {len(new_missing)} key(s) missing vs default "
                f"(not in baseline): {', '.join(new_missing)}"
            )
        if new_extra:
            errors.append(
                f"{qualifier}: {len(new_extra)} key(s) not in default "
                f"(not in baseline): {', '.join(new_extra)}"
            )
        if fixed:
            notes.append(
                f"{qualifier}: {len(fixed)} baseline entr(y/ies) now in sync "
                "- run --update-baseline to refresh"
            )
    return errors, notes


def write_baseline(
    drift: dict[str, dict[str, list[str]]], path: pathlib.Path
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(drift, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--res-dir",
        type=pathlib.Path,
        default=DEFAULT_RES_DIR,
        help="path to the app res directory (default: %(default)s)",
    )
    parser.add_argument(
        "--baseline",
        type=pathlib.Path,
        default=DEFAULT_BASELINE,
        help="baseline drift file (default: %(default)s)",
    )
    parser.add_argument(
        "--update-baseline",
        action="store_true",
        help="regenerate the baseline from the current state and exit 0",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        drift = compute_drift(args.res_dir)
    except (FileNotFoundError, ValueError) as error:
        print(f"locale key parity check ERROR: {error}", file=sys.stderr)
        return 2

    if args.update_baseline:
        write_baseline(drift, args.baseline)
        total_missing = sum(len(d["missing"]) for d in drift.values())
        total_extra = sum(len(d["extra"]) for d in drift.values())
        print(
            f"baseline updated: {args.baseline} "
            f"({len(drift)} locales, {total_missing} missing, {total_extra} extra)"
        )
        return 0

    baseline = load_baseline(args.baseline)
    errors, notes = evaluate(drift, baseline)

    for note in notes:
        print(f"note: {note}", file=sys.stderr)
    if errors:
        print(
            "locale key parity check FAILED - every locale must mirror "
            "values/strings.xml keys (run --update-baseline only after "
            "catching the drift up):",
            file=sys.stderr,
        )
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1

    total_missing = sum(len(d["missing"]) for d in drift.values())
    total_extra = sum(len(d["extra"]) for d in drift.values())
    print(
        f"locale key parity OK ({len(drift)} locales checked; "
        f"{total_missing} baseline-missing, {total_extra} baseline-extra tracked)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
