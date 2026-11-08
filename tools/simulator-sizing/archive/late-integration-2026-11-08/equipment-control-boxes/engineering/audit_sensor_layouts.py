"""Focused static instrument drawing checks. No Minecraft/runtime or test-suite execution."""
import json,numpy as np
import drawings as k,sensor_layouts,revised_layouts
import instrument_policy
import signal_catalog

issues=[];manual=0;factory=0;top=0;unchanged=0;catalog=[];stems=0;relocations=[]
reservations=[];thermal=[];bootChannels=[];rotating=[];channelCoverage=[];towerLevels=[]
for e in sensor_layouts.entries(k):
 d=json.loads((k.ROOT/'geometry'/e['file']).read_text());native=k.raw(d);cat=sensor_layouts.plan(d,native,k);manual+=len(cat['points']);factory+=len(cat['factory']);catalog.append(dict(id=d['id'],**cat))
 if d['id'] in ['VERTICAL','LENGTHWISE','LENGTHWISE_BOOT']:
  for i,p in enumerate(cat['points']):
   for other in cat['points'][i+1:]:assert np.linalg.norm(np.array(p['centre'])-other['centre'])>.5,(d['id'],p['tag'],other['tag'],'heads too close')
   for nozzle in d['processPorts']:
    base=np.array(nozzle['base']);delta=np.array(nozzle['face'])-base;head=np.array(p['centre']);t=np.clip(np.dot(head-base,delta)/np.dot(delta,delta),0,1);clear=np.linalg.norm(head-base-t*delta)-nozzle['outsideDiameter']/2
    assert clear>.20,(d['id'],p['tag'],nozzle['role'],'inside nozzle envelope')
 for p in sensor_layouts.head_points(cat):
  centre=np.array(p['centre']);head=[(v@sensor_layouts.basis(p['normal']).T+centre,m) for v,m in sensor_layouts.native_assets(k,'digital_level' if p['kind']=='L' else 'digital')];lo,hi=k.bbox(head)
  contact=k.clipped(native,lo+1e-5,hi-1e-5)
  if contact:issues.append(dict(id=d['id'],tag=p['tag'],headIntersections=sorted(set(m for v,m in contact))))
  if p['entry']=='top':
   top+=1;assert centre[1]>p['tap'][1]
   actual=revised_layouts.surface(native,1,p['tap'],1,{'SHELL','HEAD'});assert np.allclose(actual,p['tap'])
   if p.get('probeTip'):assert p['probeTip'][1]<p['tap'][1]
   if d['kind']=='SPHERE':
    pc=np.array(d['topPlatform']['centre']);assert abs(centre[1]-pc[1]-.85)<1e-8
    assert np.linalg.norm((centre-pc)[[0,2]])>d['topPlatform']['radius']
    assert np.dot(np.array(p['normal']),centre-pc)<0
    bracket=revised_layouts.surface(native,1,p['platformBracket'],1,{'GRATING'});assert np.allclose(bracket,p['platformBracket'])
  else:
   source=next(s for s in d['sensorMounts'] if s['id']==p['mount'])
   if p.get('previousCentre'):assert np.array_equal(p['previousCentre'],[source['x'],source['y'],source['z']])
   else:assert np.array_equal(centre,[source['x'],source['y'],source['z']]);unchanged+=1
 if d['id'] in ['VERTICAL','LENGTHWISE','LENGTHWISE_BOOT']:assert {p['kind'] for p in cat['points']}=={'P','T','L'} and all(p['entry']=='top' for p in cat['points'])
 for r in cat.get('reserved',[]):
  lo=np.array(r['min']);hi=np.array(r['max']);assert np.allclose(hi-lo,[1.,1.5,1.])
  forbidden=[(v,m) for v,m in native if m not in {'HEAD','SHELL'}]
  assert not k.clipped(forbidden,lo,hi),(d['id'],r['tag'],'native obstruction')
  for p in cat['points']:
   poly=[(v@sensor_layouts.basis(p['normal']).T+p['centre'],m) for v,m in sensor_layouts.native_assets(k,'digital_level' if p['kind']=='L' else 'digital')]
   assert not k.clipped(poly,lo,hi),(r['tag'],p['tag'])
  assert not sensor_layouts.overlaps(lo,hi,d.get('accessClearance',[]))
  for dx in [-.5,.5]:
   for dz in [-.5,.5]:revised_layouts.surface(native,1,np.array(r['tap'])+[dx,0,dz],1,{'HEAD','SHELL'})
  reservations.append(dict(id=d['id'],**r))
 if d.get('boot'):
  assert {p['mount'] for p in cat['points'] if p['kind']=='L'}=={'level_body','level_boot'}
  p=next(p for p in cat['points'] if p['mount']=='level_boot');assert np.allclose(np.array(p['tap'])[[0,2]],np.array(d['boot']['axis'])[[0,2]])
  assert d['boot']['bottom'][1]<p['guidedTip'][1]<d['boot']['top'][1]
  bootChannels.append(dict(id=d['id'],mount=p['mount'],tap=p['tap'],tip=p['guidedTip']))
 if d['kind']=='EXCHANGER':
  assert len(cat['factory'])==4 and not cat['points']
  assert {p['processRole'] for p in cat['factory']}=={p['role'] for p in d['processPorts']}
  for p in cat['factory']:
   port=next(s for s in d['processPorts'] if s['role']==p['processRole']);axis=np.array(port['face'])-.28*np.array(port['normal']);assert np.allclose(axis,p['immersionTip'])
   assert np.allclose(revised_layouts.surface(native,0,axis,1,{'PIPE'}),p['tap']);assert abs(p['tap'][0]-axis[0]-port['outsideDiameter']/2)<1e-6
   head=[(v,m) for v,m in sensor_layouts.hardware_point(p,k) if m=='FACTORY_SENSOR'];lo,hi=k.bbox(head);assert not k.clipped(native,lo+1e-5,hi-1e-5),(d['id'],p['tag'],'cap obstruction')
   thermal.append(dict(id=d['id'],role=p['processRole'],tap=p['tap'],tip=p['immersionTip']))
 if d['kind'] in signal_catalog.TOWER_KINDS:
  assert not cat['points'];assert {p['mount'] for p in cat['factory']}=={'builtin_inlet_T','builtin_basin_T','level_body'}
  level=next(p for p in cat['factory'] if p['kind']=='L');source=next(s for s in d['sensorMounts'] if s['id']=='level_body');assert not level['manual'] and level['integralHead'] and level['centre']==[source['x'],source['y'],source['z']]
  assert 'previousCentre' not in level;towerLevels.append(dict(id=d['id'],centre=level['centre'],builtIn=True))
 if instrument_policy.built_in(d):assert not cat['points']
 if d['kind'] in signal_catalog.THERMAL_KINDS or d['kind']=='COLUMN':
  keys=[s['key'] for s in cat['controllerSignals']];assert len(keys)==len(set(keys))
  quantities=['pressure','temperature','flow_rate'] if d['kind'] in ['EXCHANGER','KETTLE','COLUMN'] else ['pressure','temperature']
  for prefix,port in signal_catalog.ports(d):assert all(prefix+'_'+q in keys for q in quantities),(d['id'],prefix)
  assert all(s['interface']=='C1' and s['probe']=='internal' for s in cat['controllerSignals'])
  if d['kind'] in signal_catalog.TOWER_KINDS:assert 'basin_level' in keys
  if d['kind']=='COLUMN':
   assert {'top_pressure','top_temperature','bottom_pressure','bottom_temperature','reflux_pressure','reflux_temperature','reflux_flow_rate'}<=set(keys)
   assert cat['streamFamilies']==[dict(f,quantities=['pressure','temperature','flow'],interface='C1',identity='column equipment ID + member slot/unit + port role/ordinal; no coordinates') for f in signal_catalog.COLUMN_FAMILIES]
  channelCoverage.append(dict(id=d['id'],signals=keys,streamFamilies=cat.get('streamFamilies',[])))
 if instrument_policy.rotating(d):
  assert not cat['points'] and not cat['factory'] and not sensor_layouts.geometry(cat,k)
  keys={s['key'] for s in cat['controllerSignals']};assert 'shaft_speed' in keys
  if instrument_policy.fluid_rotating(d):assert keys=={'inlet_pressure','inlet_temperature','outlet_pressure','outlet_temperature','flow_rate','shaft_speed'}
  else:assert keys=={'shaft_speed'}
  assert all(s['probe']=='internal' and s['interface']=='C1' for s in cat['controllerSignals'])
  assert instrument_policy.description(d)==k.proposal(d,native)['policy']
  rotating.append(dict(id=d['id'],signals=sorted(keys),manualMounts=0,externalSensorFacets=0))
 triangles=np.array([v[[0,i,i+1]] for v,m in native for i in range(1,len(v)-1)])
 for p in cat['points']+cat['factory']:
  if p.get('existing'):continue
  tap=np.array(p['tap']);centre=np.array(p['centre']);end=centre+[0,-.15,0] if p['entry']=='top' and sensor_layouts.head_model(p) else centre-.055*np.array(p['normal']) if sensor_layouts.head_model(p) else centre
  direction=end-tap;start=tap+.002*direction/np.linalg.norm(direction);stems+=1
  if not sensor_layouts.segment_clear(triangles,start,end):issues.append(dict(id=d['id'],tag=p['tag'],exteriorStemCrossing=True))
  if p.get('previousCentre'):relocations.append(dict(id=d['id'],tag=p['tag'],old=p['previousCentre'],new=p['centre']))
report=dict(manualMounts=manual,factoryInstrumentPoints=factory,newTopEntries=top,retainedMountCentres=unchanged,exteriorStemChecks=stems,clearanceRelocations=relocations,futureReservations=reservations,bootChannels=bootChannels,exchangerProbes=thermal,rotatingEquipment=rotating,thermalColumnChannels=channelCoverage,towerLevels=towerLevels,issues=issues,scope='Reference meshes only; readings/phase models unchanged. World placement, occupied cells, clipping/reach, all sizes and controller runtime exposure are pending implementation.')
(k.ROOT/'drawing-j-audit.json').write_text(json.dumps(report,indent=2));(k.ROOT/'instrument-register.json').write_text(json.dumps(catalog,indent=2));print(json.dumps(report))
assert not issues,issues
