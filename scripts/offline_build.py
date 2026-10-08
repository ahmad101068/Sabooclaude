"""Offline build for the pure-Kotlin modules when Gradle cannot download dependencies.

Each module is compiled with ONLY its declared dependencies on the classpath, so an undeclared
cross-module import fails here exactly as it would in Gradle. Then all tests run with JUnit 4.
CI uses Gradle; this script is a fallback for restricted environments.

Usage: python3 scripts/offline_build.py <dir-with-kotlin-compiler-and-junit-jars> [--from <module>]
(--from reuses already-built jars of earlier modules; for local iteration only.)
"""
import os, subprocess, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODULES = {  # module -> direct dependencies (must mirror build.gradle.kts)
    'kernel': [],
    'platform': ['kernel'],
    'ledger': ['platform'],
    'treasury': ['ledger'],
    'inventory': ['ledger'],
    'purchasing': ['inventory', 'treasury'],
    'sales': ['inventory', 'treasury'],
    'payroll': ['treasury'],
    'backup': ['kernel'],
    'persistence': ['sales', 'purchasing', 'payroll'],
    'core': ['persistence'],
}
TEST_ONLY = ('sqlite-jdbc',)  # testImplementation jars: never visible to main code

def closure(m):
    seen = []
    for d in MODULES[m]:
        for x in closure(d) + [d]:
            if x not in seen: seen.append(x)
    return seen

def main():
    jars = sorted(str(p) for p in Path(sys.argv[1]).glob('*.jar'))
    tool_cp = ':'.join(jars)
    out = ROOT / 'build' / 'offline'; out.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ, JAVA_TOOL_OPTIONS='')
    kotlinc = ['java', '-Xmx3g', '-cp', tool_cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '17']
    test_classes = []
    start = sys.argv[sys.argv.index('--from') + 1] if '--from' in sys.argv else None
    skipping = start is not None
    for m in MODULES:
        if skipping and m != start:
            continue
        skipping = False
        deps = [str(out / f'{d}.jar') for d in closure(m)]
        cp = ':'.join(deps + [j for j in jars if not any(t in j for t in TEST_ONLY)])
        src = sorted(str(p) for p in (ROOT / 'modules' / m / 'src/main/kotlin').rglob('*.kt'))
        r = subprocess.run(kotlinc + ['-classpath', cp, '-d', str(out / f'{m}.jar')] + src, capture_output=True, text=True, env=env)
        print(f'[compile] {m}: exit={r.returncode}'); print(r.stdout + r.stderr, end='')
        if r.returncode: sys.exit(r.returncode)
        tests = sorted(str(p) for p in (ROOT / 'modules' / m / 'src/test/kotlin').rglob('*.kt'))
        if tests:
            r = subprocess.run(kotlinc + ['-classpath', ':'.join([str(out / f'{m}.jar')] + deps + jars), '-d', str(out / f'{m}-test.jar')] + tests,
                               capture_output=True, text=True, env=env)
            print(f'[compile-test] {m}: exit={r.returncode}'); print(r.stdout + r.stderr, end='')
            if r.returncode: sys.exit(r.returncode)
            base = ROOT / 'modules' / m / 'src/test/kotlin'
            test_classes += [str(Path(t).relative_to(base)).removesuffix('.kt').replace('/', '.') for t in tests if t.endswith('Test.kt')]
    cp = ':'.join([str(p) for p in out.glob('*.jar')] + jars)
    r = subprocess.run(['java', '-cp', cp, 'org.junit.runner.JUnitCore'] + test_classes, capture_output=True, text=True, env=env)
    print(r.stdout[-6000:] + r.stderr[-3000:])
    sys.exit(r.returncode)

if __name__ == '__main__':
    main()
