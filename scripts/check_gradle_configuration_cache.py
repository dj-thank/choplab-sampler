#!/usr/bin/env python3
"""Exercise the real dependency guard across configuration-cache reuse.

Only temporary copies of the pins and a dependency JAR are modified. The
checkout's pins and Gradle's dependency cache remain untouched.
"""
import argparse
import copy
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time


def groovy_string(path):
    return "'" + str(path).replace('\\', '\\\\').replace("'", "\\'") + "'"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--offline', action='store_true')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    work = root / 'work'
    work.mkdir(exist_ok=True)
    wrapper = root / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
    command = [str(wrapper), '--daemon', '--build-cache', '--console=plain',
               ':jvm-core:verifyNewPipeDependencies']
    if args.offline:
        command.append('--offline')
    results = []

    def run(label, init, cached=True, failure=None, reused=False):
        log = work / ('configuration-cache-' + label + '.log')
        started = time.monotonic()
        with log.open('w', encoding='utf-8') as stream:
            result = subprocess.run(command + ['--init-script', str(init),
                                    '--configuration-cache' if cached else '--no-configuration-cache'],
                                    cwd=root, stdout=stream, stderr=subprocess.STDOUT)
        text = log.read_text(encoding='utf-8', errors='replace')
        task_line = '> Task :jvm-core:verifyNewPipeDependencies'
        guard_ran = task_line in text and not any(task_line + ' ' + state in text
                                               for state in ['UP-TO-DATE', 'FROM-CACHE', 'SKIPPED'])
        cache_reused = 'Configuration cache entry reused.' in text
        expected_result = (result.returncode != 0 and failure in text) if failure else result.returncode == 0
        passed = expected_result and guard_ran and (not reused or cache_reused)
        row = {'case': label, 'seconds': round(time.monotonic() - started, 3),
               'exit_code': result.returncode, 'guard_executed': guard_ran,
               'configuration_cache_reused': cache_reused, 'passed': passed}
        results.append(row)
        (work / 'configuration-cache-results.json').write_text(json.dumps(results, indent=2) + '\n')
        print(json.dumps(row), flush=True)
        if not passed:
            raise RuntimeError(f'{label} failed; see {log}')

    with tempfile.TemporaryDirectory(prefix='configuration-cache-', dir=work) as directory:
        fixture = Path(directory)
        exported = fixture / 'artifacts.json'
        export_init = fixture / 'export.gradle'
        export_init.write_text(
            'gradle.projectsEvaluated {\n'
            "  gradle.rootProject.project(':jvm-core').tasks.named('verifyNewPipeDependencies') { guard ->\n"
            '    guard.doLast {\n'
            f'      new File({groovy_string(exported)}).text = groovy.json.JsonOutput.toJson(guard.artifactPaths.get())\n'
            '    }\n'
            '  }\n'
            '}\n')
        run('export', export_init, cached=False)

        original = json.loads((root / 'config/newpipe-dependencies.json').read_text())
        rows = [row for row in original['artifacts'] if row.get('scope') == 'extractor-runtime']
        transitive = next(row for row in rows
                          if not row['coordinate'].startswith('com.github.TeamNewPipe:NewPipeExtractor:'))
        coordinate = transitive['coordinate']
        source_jar = Path(json.loads(exported.read_text())[coordinate])
        jar = fixture / 'dependency.jar'
        shutil.copyfile(source_jar, jar)
        pins = fixture / 'pins.json'
        pins.write_text(json.dumps(original))
        init = fixture / 'fixture.gradle'
        init.write_text(
            'gradle.projectsEvaluated {\n'
            "  gradle.rootProject.project(':jvm-core').tasks.named('verifyNewPipeDependencies') { guard ->\n"
            f'    guard.pins.set(new File({groovy_string(pins)}))\n'
            f'    guard.artifactPaths.put({groovy_string(coordinate)}, {groovy_string(jar)})\n'
            '  }\n}\n')
        run('store', init)
        run('reuse', init, reused=True)

        changed = copy.deepcopy(original)
        next(row for row in changed['artifacts'] if row['coordinate'] == coordinate)['binary']['sha256'] = '0' * 64
        pins.write_text(json.dumps(changed))
        run('wrong-hash', init, failure='does not match its reviewed pin', reused=True)

        changed = copy.deepcopy(original)
        changed['artifacts'] = [row for row in changed['artifacts'] if row['coordinate'] != coordinate]
        pins.write_text(json.dumps(changed))
        run('missing-transitive-pin', init, failure='runtime dependency graph changed', reused=True)

        pins.write_text(json.dumps(original))
        with jar.open('ab') as stream:
            stream.write(b'\x00')
        run('changed-artifact-bytes', init, failure='does not match its reviewed pin', reused=True)

        shutil.copyfile(source_jar, jar)
        run('restored', init, reused=True)
    print('PASS: configuration reuse retains fresh graph and artifact verification')


if __name__ == '__main__':
    main()
