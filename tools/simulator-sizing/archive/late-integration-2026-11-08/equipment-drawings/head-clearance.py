from pathlib import Path
import json,math
p=Path('tmp/equipment-drawings/implementation-final-d/geometry/LENGTHWISE_BOOT.json')
if p.exists():
 d=json.loads(p.read_text());q=d['instrumentPlan']['probes']
 for v in q:print(v['id'],v['centre'])
 for i,a in enumerate(q):
  for b in q[i+1:]:
   distance=math.dist(a['centre'],b['centre'])
   if distance<.45:print('close heads',a['id'],b['id'],distance)
