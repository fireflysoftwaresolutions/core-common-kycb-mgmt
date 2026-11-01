"""Qualification-only source parent; never install or republish released parent 6."""
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PARENT = ROOT / '.qualification/firefly-parent'
PIN = '6442bd51733ade4d5f4583e1afe812dd2c520b40'
NAMESPACE = {'m': 'http://maven.apache.org/POM/4.0.0'}

head = subprocess.check_output(['git', '-C', str(PARENT), 'rev-parse', 'HEAD'], text=True).strip()
if head != PIN:
    raise SystemExit('Source parent does not match the qualification pin')
parent_model = ET.parse(PARENT / 'pom.xml').getroot()
properties = parent_model.find('m:properties', NAMESPACE)
framework_parent = parent_model.find('m:parent/m:version', NAMESPACE)
framework_bom = properties.find('m:fireflyframework.bom.version', NAMESPACE)
if framework_parent is None or framework_bom is None or any(
        element.text != '26.10.23' for element in (framework_parent, framework_bom)):
    raise SystemExit('Source fixture must use one exact framework parent/BOM train 26.10.23')
project = ROOT / 'pom.xml'
original = project.read_text()
if original.count('<relativePath/>') != 1:
    raise SystemExit('Unexpected owning parent reference; refusing an ambiguous overlay')
updated = original.replace('<relativePath/>', '<relativePath>.qualification/firefly-parent/pom.xml</relativePath>', 1)
project.write_text(updated)
summary = {
    'scope': 'Exact source-parent qualification only; no published parent or service artifact adoption',
    'source_parent': PIN, 'framework_train': '26.10.23', 'published_parent_adoption': False,
    'service_source': subprocess.check_output(['git', '-C', str(ROOT), 'rev-parse', 'HEAD'], text=True).strip(),
    'original_pom_sha256': hashlib.sha256(original.encode()).hexdigest(),
    'fixture_pom_sha256': hashlib.sha256(updated.encode()).hexdigest(),
    'overlay': 'One parent relativePath only; no versions, SDKs, or specifications edited',
}
(ROOT / '.qualification/source-summary.json').write_text(json.dumps(summary, indent=2) + '\n')
print(summary['scope'])
