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
