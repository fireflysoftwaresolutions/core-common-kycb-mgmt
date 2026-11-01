import json, sys
from pathlib import Path
import xml.etree.ElementTree as ET
r=Path(__file__).resolve().parents[1]
s=json.loads((r/'.qualification/source-summary.json').read_text());s['maven_exit']=int(sys.argv[1]);s['reports']=[]
for p in r.glob('*/target/surefire-reports/TEST-*.xml'):
 t=ET.parse(p).getroot();s['reports'].append({k:t.get(k) for k in ('name','tests','failures','errors','skipped')})
(r/'.qualification/source-summary.json').write_text(json.dumps(s,indent=2)+'\n')
print(json.dumps(s,indent=2))
log=(r/'.qualification/maven.log').read_text(errors='replace')
for line in log.splitlines():
 if line.startswith('[ERROR]') and ('Compilation' in line or '.java:[' in line or 'Could not resolve' in line or 'BUILD' in line): print(line[:400])

if s['maven_exit']==0:
 import hashlib,subprocess
 spec=r/'core-common-kycb-mgmt-sdk/src/main/resources/openapi.yml'
 sdk=list((r/'core-common-kycb-mgmt-sdk/target').glob('core-common-kycb-mgmt-sdk-*.jar'))
 if not spec.exists() or not sdk: raise SystemExit('Original producer SDK artifact missing')
 required=['createReproofingCase','inspectReproofingCase','bindReproofingSession','confirmReproofingCase','consumeReproofingAuthorization','verifyReproofingAuthorization','recordReproofingExecution']
 for op in required:
  if 'operationId: '+op not in spec.read_text(): raise SystemExit('Original producer operation missing: '+op)
 database=[t for t in s['reports'] if t['name'].endswith('.ReproofingPostgresTest')]
 if len(database)!=1 or int(database[0]['tests'])<11 or int(database[0]['skipped'])!=0: raise SystemExit('Required actual PostgreSQL suite did not execute')
 s['producer_spec_sha256']=hashlib.sha256(spec.read_bytes()).hexdigest()
 s['sdk_jar_sha256']=hashlib.sha256(sdk[0].read_bytes()).hexdigest()
 s['producer_operations']=required
 jar=r/'core-common-kycb-mgmt-web/target/core-common-kycb-mgmt.jar'
 entries=subprocess.check_output(['jar','tf',str(jar)],text=True)
 if any(v in entries for v in ('ReproofingPostgresTest','ReproofingSecurityTest','SyntheticProvider')):raise SystemExit('Test fixture packaged in production artifact')
 s['test_fixture_in_product_jar']=False
 (r/'.qualification/source-summary.json').write_text(json.dumps(s,indent=2)+'\n')
 print('Original producer operations, generated SDK and production fixture exclusion verified.')
