"""Bounded source guard for owned Java, including nested and inherited implementations.

Generated Java is outside this guard. Interfaces, records, enums, abstract classes,
conventional test classes, and the reviewed authentic configurations retain their names.
Pure DTO/entity exceptions are explicit and cannot acquire handwritten method bodies.
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
NEW = re.compile(r'\bnew\s+[\w.$]+(?:\s*<[\w\s.$?,<>\[\]]*>)?\s*\(')


def code_only(source):
    return LITERALS.sub(lambda m: re.sub(r'[^\n]', ' ', m[0]), source)


def anonymous_classes(code):
    for match in NEW.finditer(code):
        depth = 1
        end = match.end()
        while end < len(code) and depth:
            depth += (code[end] == '(') - (code[end] == ')')
            end += 1
        if not depth and code[end:].lstrip().startswith('{'):
            yield match.start()


def violations(path, source, data_classes):
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
            if re.search(r'\)\s*(?:throws\s+[\w.,\s]+)?\{', code):
                failures.append('pure data exemption acquired a handwritten method or constructor')
            continue
        failures.append(f'behavior class {name} must end Impl')
    for offset in anonymous_classes(code):
        failures.append(f'anonymous implementation at line {code.count(chr(10), 0, offset) + 1} needs a named Impl class')
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
        errors.extend(f'{path}: {message}' for message in violations(path, source, manifest['pure_data_classes']))
    for path, bean in manifest['preserved_spring_beans'].items():
        source = sources.get(path, '')
        if not re.search(r'@(?:RestController|Component)\(\s*"' + re.escape(bean) + r'"\s*\)', source):
            errors.append(f'{path}: missing preserved Spring bean ID {bean}')
    if errors:
        raise SystemExit('\n'.join(errors))
    print(f'Owned Java naming guard passed: {len(sources)} sources, {len(manifest["preserved_spring_beans"])} preserved Spring bean IDs; generated Java excluded.')


if __name__ == '__main__':
    main()
