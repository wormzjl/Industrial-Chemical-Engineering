import json
from pathlib import Path
p=Path('tmp/equipment-drawings/proposal-k/wiring-report.json')
d=json.loads(p.read_text())
for name in ['SPHERE','SPHERE_SMALL','INDUCED_DRAFT_COOLING_TOWER','NATURAL_DRAFT_COOLING_TOWER']:
 v=d.get(name,{})
 print(name,'baseline',v.get('baseline'),'controller',v.get('controllerIntent'))
 print('branches',v.get('branches'))
