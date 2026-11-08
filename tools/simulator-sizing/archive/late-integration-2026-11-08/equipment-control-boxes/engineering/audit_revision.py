"""Reference drawing checks only; does not exercise Minecraft/world placement."""
import json,numpy as np
import drawings as k
import revised_layouts,assembly_drawing
from access_clearance import overlaps,polygons_hit,route_hit,minimum_gap
def crossings(polys,points):
 tris=np.array([v[[0,i,i+1]] for v,m in polys for i in range(1,len(v)-1)])
 e1=tris[:,1]-tris[:,0];e2=tris[:,2]-tris[:,0];count=0
 for a,b in zip(points,points[1:]):
  origin=np.array(a);direction=np.array(b)-origin;h=np.cross(direction,e2);det=np.einsum('ij,ij->i',e1,h);ids=np.flatnonzero(abs(det)>1e-10)
  f=1/det[ids];s=origin-tris[ids,0];u=f*np.einsum('ij,ij->i',s,h[ids]);q=np.cross(s,e1[ids]);v=f*(q@direction);t=f*np.einsum('ij,ij->i',e2[ids],q)
  count+=int(((u>=-1e-8)&(v>=-1e-8)&(u+v<=1+1e-8)&(t>1e-6)&(t<1-1e-6)).sum())
 return count
issues=[];contacts=0;routes=0;accessChecked=0;heaterGap=None
for entry in k.MAN['entries']:
 d=json.loads((k.ROOT/'geometry'/entry['file']).read_text());native=k.raw(d);p=k.proposal(d,native)
 if p['anchor'] is not None:contacts+=1
 assert not any(m=='DISPLAY' for v,m in p['extra']) or p['mode']=='HOUSING',d['id']
 if p['mode']=='NEW':assert p['anchor'] is not None,d['id']
 rs=revised_layouts.cable_routes(d,p);zones=d.get('accessClearance',[])
 if zones:
  accessChecked+=1
  if polygons_hit(p['extra']+k.cable_polys(d['id']),zones) or any(route_hit(r['points'],zones) for r in rs):issues.append(dict(id=d['id'],accessClearanceConflict=True))
 if d['id']=='VERTICAL_HEATER':heaterGap=float(minimum_gap(p['extra'],rs,zones))
 if d['id'] in ['INLINE_PUMP','INLINE_COMPRESSOR']:
  a,b=p['ports'];assert np.allclose(a[1][[0,1]],b[1][[0,1]]) and abs(a[1][2]+b[1][2]-1)<1e-10
  assert np.allclose(a[2],-b[2]);assert p['glandOD']==.19
  v=np.concatenate([v for v,m in p['extra']]+[v for v,m in native if m=='PROPOSED_MOTOR']);mirror=v.copy();mirror[:,2]=1-mirror[:,2]
  key=lambda a:np.array(sorted(map(tuple,np.round(a,8))))
  assert np.array_equal(key(v),key(mirror)),d['id']+' terminal/casing mirror mismatch'
 if d['id'] in ['MOTOR_SMALL','GENERATOR_SMALL']:
  nb=d['nativeTerminalBounds'];assert np.all(p['low']<=np.array(nb['min'])+1e-9) and np.all(p['high']>=np.array(nb['max'])-1e-9)
 if d['id']=='CONTROL_VALVE':assert np.array_equal(p['ports'][0][2],[0,0,1])
 if d['id']=='CENTRIFUGAL_COMPRESSOR':
  for old,(v,m) in zip(d['polygons'],native):
   if old['m'] not in ['PUMP_BASE','PUMP_CHANNEL','CONCRETE']:assert np.array_equal(np.array(old['v']).reshape(-1,3),v)
 for route in rs:
  routes+=1;hits=crossings(native,route['points'])
  if hits:issues.append(dict(id=d['id'],medium=route['medium'],centrelineCrossings=hits))
native,rs,_=assembly_drawing.build(k);native=[(v,m) for v,m in native if m not in ['DATA','FE','OPENING','PROPOSED','COVER','BRACKET']]
for r in rs:
 hits=crossings(native,r['points']);routes+=1
 if hits:issues.append(dict(id='PUMP_MOTOR_LINK',medium=r['medium'],centrelineCrossings=hits))
old=json.loads((k.ROOT/'drawings-b/measurements.json').read_text());previous=next(r for r in old['sheets'] if r['id']=='VERTICAL_HEATER');he=json.loads((k.ROOT/'geometry/VERTICAL_HEATER.json').read_text());oldRejected=overlaps(previous['low'],previous['high'],he['accessClearance']);assert oldRejected
report=dict(nativeSurfaceMounts=contacts,referenceCableRoutes=routes,nativeAccessLayoutsChecked=accessChecked,previousHeaterMountRejected=oldRejected,heaterReservedSpaceGapMetres=heaterGap,inlineMotorAndTerminalsMirrored=True,smallTerminalsReplaceOriginalHousings=True,compressorProcessAndShaftGeometryPreserved=True,valveEntryPerpendicularToPipe=True,issues=issues,scope='Reference proposals only. Player/climb keep-outs include enclosure/brackets, native meshed conduits and corner sweep bounds; world placement and other constructor sizes remain unverified.')
(k.ROOT/'drawing-c-audit.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report))
