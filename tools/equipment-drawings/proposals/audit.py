"""Offline drawing checks; never launches the game or a Gradle test task."""
import argparse,json
from pathlib import Path
import numpy as np

def main():
 p=argparse.ArgumentParser();p.add_argument('work',type=Path);p.add_argument('--previous',type=Path);args=p.parse_args()
 drawing=args.work/'drawings-m';data=json.loads((drawing/'measurements.json').read_text());records=data['sheets'];wires=json.loads((args.work/'wiring-report.json').read_text());sources=segments=bridges=0
 for id,row in wires.items():
  if not row.get('sources'):continue
  assert row['sources']==row['connected'] and not row['failed'],id
  assert row['branchDiameter']==.020 and row['trunkDiameter']==.025,id
  assert row.get('meshed'),id
  assert row['surfaceTexture']['samples']>0 and not row['surfaceTexture']['uncovered'],(id,'surface paint leaves native skin',row['surfaceTexture']['uncovered'])
  assert row['surfaceTexture']['method']=='native-facet colour mask; no surface overlay geometry',id
  if id in ['LENGTHWISE','LENGTHWISE_BOOT','EXCHANGER_STANDARD','EXCHANGER_CROSSFLOW','SPHERE','SPHERE_SMALL']:
   assert row['controllerSection'],id
   axis=row['baseline']['axis'];points=np.array(row['controllerPoints'])
   assert np.ptp(points[:,axis])<1e-6,(id,'controller detours outside its mounting section')
  if id=='VERTICAL':
   points=np.array(row['controllerPoints']);turns=row['controllerIntent']
   assert row['controllerSection'] and len(turns)<=3,(id,'indirect roof approach')
  baseline=row['baseline'];points=np.array(baseline['points']);axis=baseline['axis']
  assert baseline['definitionCount']==1,id
  assert np.max(abs(points[:,axis]-baseline['parameters']))<1e-5,(id,'baseline station drift')
  assert sum(s['role']=='baseline' for s in row['surfaceRuns'])==len(points)-1,(id,'trunk not emitted once')
  assert len({tuple(sorted([tuple(np.round(s['a'],7)),tuple(np.round(s['b'],7))])) for s in row['surfaceRuns']})==len(row['surfaceRuns']),(id,'duplicate surface line')
  assert len(row['branches'])==row['sources'],id
  for branch in row['branches']:
   assert np.linalg.norm(np.array(branch['points'][-1])-points[branch['joinIndex']])<1e-7,(id,'independent branch baseline')
  for a,b in row['controllerIntent']+[leg for branch in row['branches'] for leg in branch['intent']]:
   assert np.count_nonzero(abs(np.array(b)-a)>1e-6)<=1,(id,'diagonal design leg')
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
  for bridge in row['concealedTransitions']:
   assert bridge['length']>0 and bridge['reason'] in ['embedded in supporting structure','embedded in joined support'],id
   if bridge['reason']=='embedded in joined support':assert bridge['length']<=.02200001,id
   edge=tuple(sorted([tuple(np.round(bridge['a'],7)),tuple(np.round(bridge['b'],7))]))
   assert not any(edge==tuple(sorted([tuple(np.round(s['a'],7)),tuple(np.round(s['b'],7))])) for s in row['surfaceRuns']),(id,'concealed span drawn outside')
  sources+=row['sources'];segments+=row['uniqueSurfaceSegments'];bridges+=len(row['concealedTransitions'])
 assert len([v for v in records if v.get('mode')=='WIRING'])==5
 for reference in records:
  if 'instruments' not in reference or reference['id'].endswith('_INSTRUMENTS'):continue
  cat=reference['instruments'];physical=[p for p in cat['points']+cat['factory'] if not p.get('existing')]
  assert reference.get('fixedWiring',{}).get('sources',0)==len(physical),(reference['id'],'external probe scope')
  if reference['id']=='VERTICAL':
   assert np.ptp([p['tap'][2] for p in cat['points']])<1e-7,'P/T/L must share the front service row'
   assert {r['tag'] for r in cat['reserved']}=={'F1','F2'}
  if reference['id'] in ['SPHERE','SPHERE_SMALL']:
   assert {p['kind'] for p in cat['points']}=={'P','T','L'}
   assert all(p['entry']=='top' and p.get('platformBracket') for p in cat['points']),reference['id']
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
     # Only the requested vertical L row and two sphere top-level entries move.
     clean=lambda rows:[{k:v for k,v in row.items() if k!='processRole'} for row in rows]
     if key=='points' and id.removesuffix('_INSTRUMENTS') in ['VERTICAL','SPHERE','SPHERE_SMALL']:
      old_points={p['tag']:p for p in clean(left)};new_points={p['tag']:p for p in clean(right)}
      assert old_points.keys()==new_points.keys(),(id,'instrument identity')
      for tag,before_point in old_points.items():
       if tag!='L1':assert before_point==new_points[tag],(id,tag)
       else:
        for field in ['tag','kind','mount','manual','allowed','connectorKey']:assert before_point[field]==new_points[tag][field],(id,tag,field)
     else:assert clean(left)==clean(right),(id,key)
   preserved.append(id)
 report=dict(snapshot=data['snapshot'],referenceSources=sources,surfaceSegments=segments,shortJointBridges=bridges,wiredReferences=sum(v.get('sources',0)>0 for v in wires.values()),commonBaseline=True,orthogonalDesignLegs=True,nativeFacetTextureCoverage=True,controllerMountingSections=True,requestedTopLevelEntries=True,preservedSheets=preserved,scope='Proposal/reference checks only; no world placement, collision, all-size or runtime qualification.')
 (args.work/'drawing-m-audit.json').write_text(json.dumps(report,indent=2));print(json.dumps(report,indent=2))

if __name__=='__main__':main()
