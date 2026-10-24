#!/usr/bin/env python3
"""T-05 — a mutation runner: does a test actually catch the bug it claims to?

    python3 tools/mutate.py spec.json

spec.json is a list of mutants:
    [{"file": "kernel/src/main/kotlin/zues/kernel/Time.kt",
      "old": "plusDays(days.toLong())", "new": "plusDays(days.toLong() + 1)",
      "task": ":kernel:test",                    # optional, default ":app:test"
      "tests": "zues.kernel.SysTimeTest"}]       # optional Gradle --tests filter

Each mutant is applied alone, its tests run, the file restored — even on error or Ctrl-C.
KILLED = a test failed · SURVIVED = all passed · COMPILE-ERROR = the mutant did not build (not a kill).
Exit 0 only when every mutant is KILLED.
"""
import json, os, re, signal, subprocess, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ENV = {**os.environ, 'LC_ALL': 'C.UTF-8', 'LANG': 'C.UTF-8', 'TESTCONTAINERS_RYUK_DISABLED': 'true'}
TIMEOUT = 1800   # a mutant that loops forever is a kill, not a hang holding the file mutated
COMPILE = re.compile(r"compile\w*Kotlin' FAILED|Compilation (error|failed)|^e: ", re.M)


def run(m: dict) -> str:
    path = ROOT / m['file']
    original = path.read_text(encoding='utf-8')
    if original.count(m['old']) != 1:
        return f"BAD-SPEC (old text found {original.count(m['old'])}× — must be exactly once)"
    cmd = ['./gradlew', '-q', '--console=plain', m.get('task', ':app:test'), '--rerun']
    if m.get('tests'):
        cmd += ['--tests', m['tests']]
    try:
        path.write_text(original.replace(m['old'], m['new']), encoding='utf-8')
        p = subprocess.run(cmd, cwd=ROOT, env=ENV, capture_output=True, text=True, timeout=TIMEOUT)
    except subprocess.TimeoutExpired:
        return 'KILLED (timeout)'
    finally:
        path.write_text(original, encoding='utf-8')
    out = p.stdout + p.stderr
    if p.returncode == 0:
        return 'SURVIVED'
    if COMPILE.search(out):
        return 'COMPILE-ERROR'
    if 'There were failing tests' in out or 'tests completed' in out:
        return 'KILLED'
    return 'ERROR (build failed, not in a test) — ' + next((l for l in out.splitlines() if 'wrong' in l or 'rror' in l), '?')


def main() -> int:
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(143))   # SIGTERM unwinds through `finally` too
    mutants = json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))
    results = [run(m) for m in mutants]
    for m, r in zip(mutants, results):
        print(f"{r:<14} {m['file']}: {m['old']!r} → {m['new']!r}")
    return 0 if all(r == 'KILLED' for r in results) else 1


if __name__ == '__main__':
    sys.exit(main())
