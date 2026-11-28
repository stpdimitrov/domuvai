#!/usr/bin/env python3
"""T-10 (#342): what tools/check.sh hands to the gate pack per lane. A copy runs against a stub gates.sh and docker."""
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

CHECK = Path(__file__).with_name("check.sh")


def check(lane):
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        (root / "tools").mkdir()
        (root / "app/src/test/kotlin/zues/app/money").mkdir(parents=True)
        shutil.copy(CHECK, root / "tools/check.sh")
        for stub, body in (("tools/gates.sh", 'echo "TESTS=$GATES_TESTS"'), ("docker", "exit 1")):
            (root / stub).write_text(f"#!/bin/sh\n{body}\n")
            (root / stub).chmod(0o755)
        env = {**os.environ, "PATH": f"{root}{os.pathsep}{os.environ['PATH']}"}
        env.pop("GATES_TESTS", None)
        done = subprocess.run(["bash", str(root / "tools/check.sh"), lane], env=env, capture_output=True, text=True)
        return done.returncode, done.stdout


class CheckSh(unittest.TestCase):
    def test_web_selects_the_web_check_and_says_nothing_about_docker(self):
        self.assertEqual(check("web"), (0, "TESTS=npm --prefix web run --silent check\n"))

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
