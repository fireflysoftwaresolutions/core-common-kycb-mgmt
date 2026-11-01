"""Hosted source formatting artifact only; never modifies qualified source."""
from pathlib import Path
import os,shutil,subprocess,sys
root=Path(__file__).resolve().parents[1]
if os.environ.get('GITHUB_ACTIONS')!='true':raise SystemExit('Hosted JVM only')
output=root/'.qualification/formatted'
files=[]
for source in root.glob('*/src/**/*.java'):
 if '/reproofing/' not in str(source):continue
 target=output/source.relative_to(root);target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,target);files.append(str(target))
subprocess.run(['java','-jar',sys.argv[1],'--aosp','--cleanup=false','--replace',*files],check=True)
print('Prepared candidate source formatting artifact; original qualification source unchanged.')
