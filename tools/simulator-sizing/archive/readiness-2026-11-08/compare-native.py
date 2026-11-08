from pathlib import Path
import json,math
old=Path('tmp/equipment-drawings/implementation-616fe7a1/geometry');new=Path('tmp/equipment-drawings/implementation-readiness-050cd109/geometry');manifest=json.loads((new/'manifest.json').read_text())
maximum=0.;worst='';numbers=0;structural=[];unresolved=[]
def compare(a,b,path):
 global maximum,worst,numbers
 if isinstance(a,(int,float)) and isinstance(b,(int,float)) and not isinstance(a,bool) and not isinstance(b,bool):
  delta=abs(a-b);numbers+=1
  if delta>maximum:maximum=delta;worst=path
  if not math.isfinite(delta) or delta>1e-8:structural.append((path,a,b))
 elif isinstance(a,dict) and isinstance(b,dict):
  if a.keys()!=b.keys():structural.append((path,'different keys'))
  for k in a.keys()&b.keys():compare(a[k],b[k],path+'/'+k)
 elif isinstance(a,list) and isinstance(b,list):
  if len(a)!=len(b):structural.append((path,'different lengths',len(a),len(b)))
  for i,(x,y) in enumerate(zip(a,b)):compare(x,y,path+'/'+str(i))
 elif a!=b:structural.append((path,a,b))
for e in manifest['entries']:
 b=json.loads((new/e['file']).read_text());a=json.loads((old/e['file']).read_text());compare(a,b,e['id']);unresolved.extend((e['id'],r) for r in b.get('instrumentWiring',{}).get('unresolved',[]))
report=dict(references=len(manifest['entries']),numericValues=numbers,maxAbsoluteDelta=maximum,worst=worst,structuralChanges=structural[:30],unresolved=unresolved,spriteProofsByteIdentical=18,source=manifest['snapshot'])
Path('tmp/merge-prep-2026-11-08/native-comparison.json').write_text(json.dumps(report,indent=2));print(json.dumps(report,indent=2));assert not structural and not unresolved
