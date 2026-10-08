#!/usr/bin/env python3
"""
Gate 11 — the rule catalogue's version is written once (PM-SYS-010).

docs/RULES.md is the catalogue, and its header states its version. Every other place that states it is a copy: the
machine-readable mirror, the constant every computed record is stamped with, the index, the project brief, the
stage document and the event examples. A copy that disagrees is the silent drift the rule forbids — a record stamped
with one version under a catalogue that says another — so it fails the build here, by file.

The mirror is also checked whole: docs/rules.json must be what tools/build_rules.py makes from RULES.md today.
"""
import json
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCE = ("docs/RULES.md", r"^\*\*Version:\*\* (\d+\.\d+) ")
# file → a pattern whose first group is the version that file states. Every match in the file must agree.
COPIES = {
    "law/src/main/kotlin/zues/law/Constants.kt": r'const val CATALOGUE_VERSION: String = "([^"]+)"',
    "docs/INDEX.md": r"rule catalogue v(\d+\.\d+)",
    "CLAUDE.md": r"catalogue v(\d+\.\d+)",
    "docs/STAGE1.md": r"catalogue version (\d+\.\d+)",
    "docs/FUNCTIONAL.md": r"^Rule catalogue v(\d+\.\d+)",
    "tools/build_events.py": r'"law_version":"(\d+\.\d+)"',          # the examples' value, not the schema's type
}


def main() -> int:
    read = lambda path: (ROOT / path).read_text(encoding="utf-8")
    stated = re.findall(SOURCE[1], read(SOURCE[0]), re.M)
    if len(stated) != 1:
        print(f"  ✗ {SOURCE[0]} states its version {len(stated)} time(s) in its header — exactly one is needed")
        return 1
    version, problems = stated[0], []

    mirror = json.loads(read("docs/rules.json"))["meta"]["version"]
    if mirror != version:
        problems.append(f"docs/rules.json says {mirror}")
    for path, pattern in COPIES.items():
        found = re.findall(pattern, read(path), re.M)
        if not found:
            problems.append(f"{path} no longer states the version where this check looks ({pattern})")
        problems += [f"{path} says {other}" for other in sorted(set(found) - {version})]

    # the mirror, whole: regenerate it beside the real one and compare
    before = read("docs/rules.json")
    made = subprocess.run([sys.executable, str(ROOT / "tools/build_rules.py")], cwd=ROOT / "docs", capture_output=True, text=True)
    after = read("docs/rules.json")
    (ROOT / "docs/rules.json").write_text(before, encoding="utf-8")          # a check changes nothing
    if made.returncode != 0:
        problems.append(f"tools/build_rules.py failed: {made.stderr.strip()[-200:]}")
    elif after != before:
        problems.append("docs/rules.json is not what tools/build_rules.py makes from RULES.md — run it from docs/ and commit")

    for problem in problems:
        print(f"  ✗ the catalogue is v{version} ({SOURCE[0]}), but {problem}")
    if not problems:
        print(f"OK  catalogue v{version}  ·  {len(COPIES) + 1} copies agree with {SOURCE[0]}  ·  the mirror is the generator's")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
