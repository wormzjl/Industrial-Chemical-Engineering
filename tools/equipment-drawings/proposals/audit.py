"""Offline drawing checks; never launches the game or a Gradle test task."""
import argparse,json
from pathlib import Path
import numpy as np

def main():
 p=argparse.ArgumentParser();p.add_argument('work',type=Path);p.add_argument('--previous',type=Path);args=p.parse_args()
 drawing=args.work/'drawings-k';data=json.loads((drawing/'measurements.json').read_text());records=data['sheets'];wires=json.loads((args.work/'wiring-report.json').read_text());sources=segments=bridges=0
 for id,row in wires.items():
  if not row.get('sources'):continue
  assert row['sources']==row['connected'] and not row['failed'],id
  assert row['branchDiameter']==.020 and row['trunkDiameter']==.025,id
  assert row.get('meshed'),id
  assert len(row['paths'])==len({v['source'] for v in row['paths']}),id
  for path in row['paths']:
   assert np.isfinite(np.array(path['points'])).all(),id
   assert np.linalg.norm(np.array(path['points'][-1])-row['gland'])<=.004,id
  reference=next((r for r in records if r['id']==id),None)
  if reference and 'instruments' in reference:
   instruments=reference['instruments'];heads=[p for p in instruments['points']+instruments['factory'] if (p['manual'] or p.get('integralHead')) and not p.get('existing')]
   for head in heads:
    path=next(p for p in row['paths'] if p['source']==head['mount']);tail=np.array(path['tail']);direction=tail[1]-tail[0];direction/=np.linalg.norm(direction)
    assert max(np.linalg.norm(np.cross(tail-tail[0],direction),axis=1))<.0012,(id,head['tag'],'unnecessary lead bend')
  for bridge in row['concealedTransitions']:assert 0<bridge['length']<=.30,id
  sources+=row['sources'];segments+=row['uniqueSurfaceSegments'];bridges+=len(row['concealedTransitions'])
 assert len([v for v in records if v.get('mode')=='WIRING'])==4
 for reference in records:
  if 'instruments' not in reference or reference['id'].endswith('_INSTRUMENTS'):continue
  cat=reference['instruments'];physical=[p for p in cat['points']+cat['factory'] if not p.get('existing')]
  assert reference.get('fixedWiring',{}).get('sources',0)==len(physical),(reference['id'],'external probe scope')
 preserved=[]
 if args.previous:
  previous=json.loads(args.previous.read_text());old={r['id']:r for r in previous['sheets']};new={r['id']:r for r in records}
  for id,before in old.items():
   after=new[id]
   for key in ['ports','routes','nativeEnvelope','proposedEquipmentEnvelope','componentOrigins','streamFamilies','controllerSignals']:
    if key in before:assert before[key]==after[key],(id,key)
   if 'instruments' in before:
    for key in ['points','factory','reserved']:
     left=before['instruments'].get(key,[]);right=after['instruments'].get(key,[])
     # processRole links the existing cap to its channel; no position changes.
     clean=lambda rows:[{k:v for k,v in row.items() if k!='processRole'} for row in rows]
     assert clean(left)==clean(right),(id,key)
   preserved.append(id)
 report=dict(snapshot=data['snapshot'],referenceSources=sources,surfaceSegments=segments,shortJointBridges=bridges,wiredReferences=sum(v.get('sources',0)>0 for v in wires.values()),preservedSheets=preserved,scope='Proposal/reference checks only; no world placement, collision, all-size or runtime qualification.')
 (args.work/'drawing-k-audit.json').write_text(json.dumps(report,indent=2));print(json.dumps(report,indent=2))

if __name__=='__main__':main()
