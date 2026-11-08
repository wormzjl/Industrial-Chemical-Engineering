"""Focused drawing checks for the shared shaft-frame port proposal; no game/test-suite run."""
import ast,json,numpy as np
import drawings as k,revised_layouts,train_interfaces,assembly_drawing,large_train_drawing

# Reuse the existing reference-audit centreline checker without running its unrelated checks.
tree=ast.parse((k.ROOT/'audit_revision.py').read_text());node=next(n for n in tree.body if isinstance(n,ast.FunctionDef) and n.name=='crossings')
scope={'np':np};exec(compile(ast.Module(body=[node],type_ignores=[]),'existing drawing audit helper','exec'),scope);crossings=scope['crossings']
ids=['PUMP_OH2','PUMP_BB2','MOTOR_SMALL','MOTOR_LARGE','GENERATOR_SMALL','GENERATOR_LARGE','STEAM_TURBINE'];frames={};cases=[]
for id in ids:
 d=json.loads((k.ROOT/'geometry'/f'{id}.json').read_text());native=k.raw(d);p=k.proposal(d,native);frame=train_interfaces.frame(d);frames[id]=frame
 data=next((q,n) for medium,q,n in p['ports'] if medium=='DATA');assert np.array_equal(data[1],frame['dataOutward'])
 shaft=d['shaftPorts'][0]['cell'];centre=np.array([shaft['x']+.5,shaft['y']+.5,shaft['z']+.5])
 assert np.dot(data[0]-centre,data[1])>0 and abs(np.dot(data[1],frame['powerFlow']))<1e-10
 for medium,q,n in p['ports']:
  if medium=='FE':assert np.array_equal(n,-data[1]) and np.dot(q-centre,n)>0
 assert all(crossings(native,r['points'])==0 for r in revised_layouts.cable_routes(d,p)),id+' conduit crosses body'
 if p.get('enclosures'):
  sizes=[]
  for e in p['enclosures']:
   lo=np.array(e['low']);hi=np.array(e['high']);c=(lo+hi)/2;dims=hi-lo;sizes.append(dims)
   assert not k.clipped(native,lo+1e-6,hi-1e-6),id+' added box intersects body'
   for y in [c[1]-.12,c[1]+.12]:
    for z in [c[2]-.13,c[2]+.13]:
     at=revised_layouts.surface(native,0,[0,y,z],int(e['normal'][0]),{'MOTOR_PLAIN','MOTOR_PANEL','GENERATOR_PANEL'});assert abs(at[0]-e['anchor'][0])<1e-8
   assert min(dims[1:])>=p['glandOD']+.04-1e-8
  assert np.allclose(sizes[0],sizes[1])
  keep=[q for i,q in enumerate(d['polygons']) if i not in d['removedNativeTerminalFacets']];assert len(keep)==len(native)
  assert all(q['m']==m and np.array_equal(np.array(q['v']).reshape(-1,3),v) for q,(v,m) in zip(keep,native))
 if id=='STEAM_TURBINE':assert min(p['dims'][1:])>=p['glandOD']+.04-1e-8
 cases.append(dict(id=id,role=frame['role'],data=data[1].tolist(),fe=(-data[1]).tolist() if d['power'] else None,referenceRouteCrossings=0))

rotations=[np.array([[np.cos(t),0,np.sin(t)],[0,1,0],[-np.sin(t),0,np.cos(t)]]) for t in np.arange(4)*np.pi/2];pairs=0
opposed=np.diag([-1,1,-1])
for driver in ['MOTOR_SMALL','MOTOR_LARGE','STEAM_TURBINE']:
 for load in ['PUMP_OH2','PUMP_BB2','GENERATOR_SMALL','GENERATOR_LARGE']:
  for rotation in rotations:
   a=rotation@frames[driver]['dataOutward'];b=rotation@opposed@frames[load]['dataOutward'];assert np.allclose(a,b)
   assert np.allclose(rotation@frames[driver]['powerFlow'],rotation@opposed@frames[load]['powerFlow']);pairs+=1
for build in [assembly_drawing.build,large_train_drawing.build]:
 polys,routes,_=build(k);native=[(v,m) for v,m in polys if m not in ['DATA','FE','OPENING','PROPOSED','COVER','BRACKET']]
 assert all(crossings(native,r['points'])==0 for r in routes)
report=dict(cases=cases,opposedPairHeadingChecks=pairs,referenceAssemblies=2,inlinePolicy='Self-powered inline units retain mirrored integrated entries; no external shaft ports.',scope='Offline reference drawings only; world routing/occupied cells and all constructor sizes remain pending.')
(k.ROOT/'drawing-f-audit.json').write_text(json.dumps(report,indent=2));print(json.dumps(report))
