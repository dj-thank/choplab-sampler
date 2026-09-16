#!/usr/bin/env python3
"""Supplemental tests only: no Android SDK, Gradle/JUnit or hardware validation."""
from pathlib import Path
import argparse
import os
import re
import shutil
import subprocess
import tempfile


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, help="Optional directory for test logs")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    compiler = shutil.which("kotlinc")
    if not compiler:
        raise SystemExit("kotlinc is required (tested with Kotlin CLI 1.9.0 / JDK 21)")
    home = Path(compiler).resolve().parents[1]
    dependencies = [home / "lib/kotlin-test.jar", home / "lib/kotlinx-coroutines-core-jvm.jar"]
    if not all(path.is_file() for path in dependencies):
        raise SystemExit("Kotlin distribution must include kotlin-test and kotlinx-coroutines-core-jvm")
    cp = os.pathsep.join(map(str, dependencies))
    tests = sorted((repo / "shared/src/commonTest/kotlin/com/choplab/sampler/midi").glob("*Test.kt"))
    tests += sorted((repo / "app/src/test/java/com/choplab/sampler/midi").glob("*Test.kt"))
    production = [repo / "shared/src/commonMain/kotlin/com/choplab/sampler/midi" / (name + ".kt")
                  for name in ("Ddj200", "DdjMixer", "DdjLedFeedback")]
    production.append(repo / "app/src/main/java/com/choplab/sampler/midi/AndroidDdjPerformance.kt")
    with tempfile.TemporaryDirectory(prefix="ddj-host-") as directory:
        temp = Path(directory)
        sources = []
        runner = ["package com.choplab.sampler.midi", "fun main() {", "var total = 0"]
        count = 0
        for path in tests:
            source = path.read_text(encoding="utf-8")
            methods = re.findall(r"@Test\s+fun\s+(\w+)\(", source)
            count += len(methods)
            source = source.replace("import kotlin.test.Test\n", "").replace("import org.junit.Test\n", "")
            source = source.replace("@Test ", "").replace("import org.junit.Assert.assert", "import kotlin.test.assert")
            target = temp / path.name
            target.write_text(source, encoding="utf-8")
            sources.append(target)
            for method in methods:
                runner.append(f'{path.stem}().{method}(); println("PASS {path.stem}.{method}"); total++')
        if count != 74:
            raise SystemExit(f"Expected 74 distinct test bodies; discovered {count}. Review the harness.")
        runner += ['println("TOTAL $total PASS")', "}"]
        runner_file = temp / "Runner.kt"
        runner_file.write_text("\n".join(runner), encoding="utf-8")
        jar = temp / "tests.jar"
        build = subprocess.run([compiler, *map(str, production + sources + [runner_file]),
                                "-cp", cp, "-include-runtime", "-d", str(jar)],
                               capture_output=True, text=True, timeout=180)
        if args.output:
            args.output.mkdir(parents=True, exist_ok=True)
            (args.output / "compile.log").write_text(build.stdout + build.stderr, encoding="utf-8")
        print(build.stdout + build.stderr, end="")
        if build.returncode:
            return build.returncode
        result = subprocess.run(["java", "-cp", str(jar) + os.pathsep + cp,
                                 "com.choplab.sampler.midi.RunnerKt"],
                                capture_output=True, text=True, timeout=120)
        if args.output:
            (args.output / "results.txt").write_text(result.stdout + result.stderr, encoding="utf-8")
        print(result.stdout + result.stderr, end="")
        return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
