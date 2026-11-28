#!/usr/bin/env python3
"""T-10 (#342), T-11 (#533, #534): what tools/check.sh does per lane, and what a failing gate prints.
A copy of check.sh runs against a stub gates.sh, gradlew and docker."""
import json
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

CHECK = Path(__file__).with_name("check.sh")


def check(lane, gradle='echo "GRADLE $*" >> ran'):
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        (root / "tools").mkdir()
        (root / "app/src/test/kotlin/zues/app/money").mkdir(parents=True)
        shutil.copy(CHECK, root / "tools/check.sh")
        gates = 'cat ran 2>/dev/null; echo "TESTS=$GATES_TESTS"'   # prints what Gradle ran before the gate pack
        for stub, body in (("tools/gates.sh", gates), ("gradlew", gradle), ("docker", "exit 1")):
            (root / stub).write_text(f"#!/bin/sh\n{body}\n")
            (root / stub).chmod(0o755)
        env = {**os.environ, "PATH": f"{root}{os.pathsep}{os.environ['PATH']}"}
        env.pop("GATES_TESTS", None)
        done = subprocess.run(["bash", str(root / "tools/check.sh"), lane], env=env, capture_output=True, text=True)
        return done.returncode, done.stdout


class CheckSh(unittest.TestCase):
    def test_web_builds_the_spec_then_selects_the_web_check_and_says_nothing_about_docker(self):
        self.assertEqual(check("web"), (0,
            "GRADLE -q :app:test --tests zues.app.OpenApiContractTest\n"
            "TESTS=npm --prefix web run --silent check\n"))

    def test_web_stops_with_one_line_naming_the_cause_when_the_spec_cannot_be_built(self):
        cause = "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH."
        self.assertEqual(check("web", f'printf "\\n%s\\n\\nPlease set it.\\n" "{cause}" >&2; exit 1'),
                         (1, f"check.sh: could not build the API spec (needs JDK 21): {cause}\n"))

    def test_the_gate_pack_prints_a_budget_overrun_and_a_failing_node_test(self):
        function = re.search(r"^errors\(\) \{.*?^\}$", CHECK.with_name("gates.sh").read_text(), re.M | re.S).group()
        wanted = ["PM-SYS-015: /me is over the budget: 9 B, 1 B over", "✖ the resident page renders (3ms)"]
        with tempfile.NamedTemporaryFile("w", suffix=".log", encoding="utf-8") as log:
            log.write("\n".join(["✔ a refusal is not an error", *wanted]) + "\n")   # a passing line matches too
            log.flush()
            shown = subprocess.run(["bash", "-c", f'{function}\nerrors "$1"', "-", log.name],
                                   capture_output=True, text=True).stdout.splitlines()
        for line in wanted:
            self.assertIn(line, shown)

    def test_the_web_script_runs_test_types_build_and_budget_failing_on_the_first(self):
        package = json.loads((CHECK.parent.parent / "web/package.json").read_text())
        self.assertEqual(package["scripts"]["check"], "npm test && tsc --noEmit && npm run build && npm run budget")

    def test_unknown_lane_exits_2_with_the_message(self):
        self.assertEqual(check("nope"), (2, "check.sh: no tests for lane 'nope'\n"))

    def test_money_still_selects_its_gradle_package_and_warns_about_docker(self):
        self.assertEqual(check("money"), (0,
            "! Docker is down — every *IT will SKIP\n"
            "TESTS=./gradlew -q :app:test --tests zues.app.money.* --tests zues.app.OpenApiContractTest\n"))


if __name__ == "__main__":
    unittest.main()
