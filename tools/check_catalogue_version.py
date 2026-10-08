#!/usr/bin/env python3
"""
Gate 11 — the rule catalogue's version is written once (PM-SYS-010).

docs/RULES.md is the catalogue, and its header states its version. Every other place that states it is a copy: the
machine-readable mirror, the constant every computed record is stamped with, the index, the project brief, the
stage document, the functional specification, the events generator and each event example it publishes. A copy that disagrees is the silent drift the rule forbids — a record stamped
with one version under a catalogue that says another — so it fails the build here, by file.

The mirror is also checked whole: docs/rules.json must be what tools/build_rules.py makes from RULES.md today.
"""
import json
import pathlib
import re
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
N = r"(\d+(?:\.\d+)+)"                                   # 1.3, 1.10, 1.3.1 — whole, never the first two parts of it
SOURCE = ("docs/RULES.md", rf"^\*\*Version:\*\* {N} ")
# However a document words it — "catalogue v1.3", "Catalogue version 1.3", "catalogue is v1.3" — every mention must agree.
IN_PROSE = rf"(?i)catalogue\s+(?:is\s+)?(?:v|version\s+){N}"
# file → a pattern whose first group is the version that file states. Every match in the file must agree.
COPIES = {
    "law/src/main/kotlin/zues/law/Constants.kt": r'const val CATALOGUE_VERSION: String = "([^"]+)"',
    "docs/INDEX.md": IN_PROSE,
    "CLAUDE.md": IN_PROSE,
    "docs/STAGE1.md": IN_PROSE,
    "docs/FUNCTIONAL.md": IN_PROSE,
    "tools/build_events.py": rf'"law_version":"{N}"',       # the events generator's value for its examples, not the schema's type
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

    # every published event example carries the version in its envelope
    examples = sorted((ROOT / "docs/events").glob("*.example.json"))
    if not examples:
        problems.append("docs/events holds no event example")
    for example in examples:
        carried = json.loads(example.read_text(encoding="utf-8")).get("law_version")
        if carried != version:
            problems.append(f"{example.relative_to(ROOT)} says {carried}")

    # the mirror, whole: regenerate it from RULES.md in a directory of its own — the real file is never written
    with tempfile.TemporaryDirectory() as scratch:
        (pathlib.Path(scratch) / "RULES.md").write_bytes((ROOT / "docs/RULES.md").read_bytes())
        made = subprocess.run([sys.executable, str(ROOT / "tools/build_rules.py")], cwd=scratch, capture_output=True, text=True)
        if made.returncode != 0:
            problems.append(f"tools/build_rules.py failed: {(made.stderr or made.stdout).strip()[-200:]}")
        elif (pathlib.Path(scratch) / "rules.json").read_bytes() != (ROOT / "docs/rules.json").read_bytes():
            problems.append("docs/rules.json is not what tools/build_rules.py makes from RULES.md — run it from docs/ and commit")

    for problem in problems:
        print(f"  ✗ the catalogue is v{version} ({SOURCE[0]}), but {problem}")
    if not problems:
        print(f"OK  catalogue v{version}  ·  {len(COPIES) + 1} copies and {len(examples)} event examples agree with {SOURCE[0]}  ·  the mirror is the generator's")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
