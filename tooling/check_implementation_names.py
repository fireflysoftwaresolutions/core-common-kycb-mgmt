"""Guard named technical implementations, including nested and inherited SPI classes.

Conventional HTTP entries, data/entity classes, authentic configurations/bootstrap,
interfaces, records, enums, abstract classes and test suites retain semantic names.
Anonymous implementations and generated Java are outside this naming convention.
"""
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
CONFIGURATIONS = {
    ('core-common-kycb-mgmt-web/src/main/java/io/fireflysoftware/core/kycb/web/KycbApplication.java', 'KycbApplication'),
    ('core-common-kycb-mgmt-web/src/main/java/io/fireflysoftware/core/kycb/web/config/CodecLimitsConfig.java', 'CodecLimitsConfig'),
    ('core-common-kycb-mgmt-web/src/test/java/io/fireflysoftware/core/kycb/web/openapi/OpenApiGenApplication.java', 'OpenApiGenApplication'),
    ('core-common-kycb-mgmt-web/src/test/java/io/fireflysoftware/core/kycb/web/reproofing/ReproofingExistingSecurityTest.java', 'ProductConfiguration'),
    ('core-common-kycb-mgmt-web/src/test/java/io/fireflysoftware/core/kycb/web/reproofing/ReproofingExistingSecurityTest.java', 'ExplicitExistingPolicy'),
}
LITERALS = re.compile(r'//[^\n]*|/\*.*?\*/|""".*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'', re.S)
DECLARATIONS = re.compile(r'\b((?:(?:public|private|protected|static|final|abstract|sealed|non-sealed)\s+)*)class\s+(\w+)')


def code_only(source):
    return LITERALS.sub(lambda m: re.sub(r'[^\n]', ' ', m[0]), source)


def violations(path, source, data_classes, conventional_entries=None):
    code = code_only(source)
    failures = []
    for match in DECLARATIONS.finditer(code):
        modifiers, name = match.groups()
        if 'abstract' in modifiers.split() or name.endswith('Impl'):
            continue
        if name == Path(path).stem and '/src/test/java/' in path and name.endswith('Test'):
            continue
        if (path, name) in CONFIGURATIONS:
            continue
        if data_classes.get(path) == name:
            continue
        if name in (conventional_entries or {}).get(path, []):
            continue
        failures.append(f'named technical implementation class {name} must end Impl')
    return failures


def main():
    manifest = json.loads((ROOT / 'tooling/implementation-names.json').read_text())
    tracked = subprocess.check_output(['git', 'ls-files', '*.java'], cwd=ROOT, text=True).splitlines()
    sources = {p: (ROOT / p).read_text() for p in tracked
               if ('/src/main/java/' in p or '/src/test/java/' in p) and '/generatedJava/' not in p
               and (ROOT / p).exists()}
    # Newly renamed sources may not be staged during a local preflight.
    for path in manifest['preserved_spring_beans']:
        if (ROOT / path).exists():
            sources[path] = (ROOT / path).read_text()
    errors = []
    for path, source in sources.items():
        errors.extend(f'{path}: {message}' for message in violations(path, source, manifest['data_classes'], manifest['conventional_entry_classes']))
    for path, bean in manifest['preserved_spring_beans'].items():
        source = sources.get(path, '')
        if not re.search(r'@(?:RestController|Component)\(\s*"' + re.escape(bean) + r'"\s*\)', source):
            errors.append(f'{path}: missing preserved Spring bean ID {bean}')
    if errors:
        raise SystemExit('\n'.join(errors))
    print(f'Owned Java naming guard passed: {len(sources)} sources, {len(manifest["preserved_spring_beans"])} preserved Spring bean IDs; generated Java excluded.')


if __name__ == '__main__':
    main()
