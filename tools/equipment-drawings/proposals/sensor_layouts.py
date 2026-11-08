"""Drawing-only instrument catalog: permissions, mount coordinates and hardware share one owner."""
import json,math,numpy as np
from functools import lru_cache
import revised_layouts
import instrument_policy
import signal_catalog
from access_clearance import overlaps

CHANGED=['VERTICAL','LENGTHWISE','LENGTHWISE_BOOT','SPHERE','SPHERE_SMALL','INDUCED_DRAFT_COOLING_TOWER','EXCHANGER_STANDARD','EXCHANGER_CROSSFLOW','NATURAL_DRAFT_COOLING_TOWER']
ALLOWED={'P':['pressure gauge','digital pressure transmitter'],'T':['thermometer','digital temperature transmitter'],'L':['digital level transmitter'],'LG':['mechanical sight column']}
_plans={}

def entries(k):return k.MAN['entries']+[dict(id='LENGTHWISE_BOOT',file='LENGTHWISE_BOOT.json')]

def rotating_channels(d):
 channels=[];markers=[]
 if instrument_policy.fluid_rotating(d):
  ports=d.get('processPorts',[])
  for side,roles in [('inlet',{'SUCTION','INLET','STEAM_INLET','STEAM'}),('outlet',{'DISCHARGE','OUTLET','EXHAUST','STEAM_OUTLET'})]:
   port=next((p for p in ports if p['role'] in roles),None)
   if port is not None:centre=port['base']
   elif d['kind'].startswith('INLINE_'):centre=[.18 if side=='inlet' else .82,.5,.5]
   else:raise ValueError('Missing process role for '+d['id']+' '+side)
   markers.append(dict(tag=('IN' if side=='inlet' else 'OUT')+' P/T',centre=list(centre)))
   channels.extend(signal_catalog.stream(side,side,False))
  channels.append(signal_catalog.signal('flow_rate','flow','process passage',readout='mass flow; volumetric flow when available'))
 shafts=d.get('shaftPorts',[])
 if shafts:
  s=shafts[0];centre=np.array([s['cell'][a]+.5 for a in ['x','y','z']])-.35*np.array([s['dx'],s['dy'],s['dz']])
 elif d['kind'].startswith('INLINE_'):centre=np.array(d['integralMotor']['axis'])
 else:raise ValueError('Missing rotating axis for '+d['id'])
 markers.append(dict(tag='N',centre=centre.tolist()));channels.append(signal_catalog.signal('shaft_speed','speed','internal shaft pickup'))
 return channels,markers

def head_model(p):return ('digital_level' if p['kind']=='L' else 'digital') if p['manual'] or p.get('integralHead') else None
def head_points(cat):return [p for p in cat['points']+cat['factory'] if head_model(p) and not p.get('existing')]

def basis(normal):
 n=np.array(normal,float);n/=np.linalg.norm(n);return np.column_stack((np.cross([0,1,0],n),[0,1,0],n))

@lru_cache(maxsize=12)
def native_assets(k,name):
 data=json.loads((k.ROOT/'geometry/instrument-assets.json').read_text())[name]
 return [(np.array(p['v']).reshape(-1,3),'ARGB_'+format(p['colour']&0xffffff,'06x')) for p in data]

def nearest_surface(polys,target,materials):
 triangles=np.array([v[[0,i,i+1]] for v,m in polys if m in materials for i in range(1,len(v)-1)])
 if len(triangles)==0:raise ValueError('No process surface for instrument')
 a=triangles[:,0];u=triangles[:,1]-a;v=triangles[:,2]-a;n=np.cross(u,v);nn=np.einsum('ij,ij->i',n,n);valid=nn>1e-16;triangles=triangles[valid];a=a[valid];u=u[valid];v=v[valid];n=n[valid];nn=nn[valid]
 q=np.array(target);projected=q-n*(np.einsum('ij,ij->i',q-a,n)/nn)[:,None]
 dot=lambda x,y:np.einsum('ij,ij->i',x,y)
 aa=dot(u,u);bb=dot(u,v);cc=dot(v,v);w=projected-a;d=aa*cc-bb*bb;alpha=(cc*dot(w,u)-bb*dot(w,v))/d;beta=(aa*dot(w,v)-bb*dot(w,u))/d
 candidates=[projected[(alpha>=-1e-9)&(beta>=-1e-9)&(alpha+beta<=1+1e-9)]]
 for i in range(3):
  start=triangles[:,i];edge=triangles[:,(i+1)%3]-start;length=dot(edge,edge);t=np.clip(dot(q-start,edge)/np.maximum(length,1e-30),0,1);candidates.append(start+t[:,None]*edge)
 points=np.concatenate(candidates);return points[np.argmin(np.sum((points-q)**2,axis=1))]

def point(tag,kind,mount,centre,normal,tap,entry='back',manual=True,note='',**extras):
 result=dict(tag=tag,kind=kind,mount=mount,centre=np.array(centre).tolist(),normal=np.array(normal).tolist(),tap=np.array(tap).tolist(),entry=entry,manual=manual,allowed=ALLOWED.get(kind,[]) if manual else [],note=note)
 result.update(extras);return result

def head_clear(native,centre,normal,kind,k,zones=()):
 body=[(v@basis(normal).T+centre,m) for v,m in native_assets(k,'digital_level' if kind=='L' else 'digital')];lo,hi=k.bbox(body)
 return not k.clipped(native,lo-.02,hi+.02) and not overlaps(lo-.02,hi+.02,zones)

def segment_clear(triangles,a,b):
 e1=triangles[:,1]-triangles[:,0];e2=triangles[:,2]-triangles[:,0];direction=b-a;h=np.cross(direction,e2);det=np.einsum('ij,ij->i',e1,h);ids=np.flatnonzero(abs(det)>1e-10)
 f=1/det[ids];s=a-triangles[ids,0];u=f*np.einsum('ij,ij->i',s,h[ids]);q=np.cross(s,e1[ids]);v=f*(q@direction);t=f*np.einsum('ij,ij->i',e2[ids],q)
 return not np.any((u>=-1e-8)&(v>=-1e-8)&(u+v<=1+1e-8)&(t>1e-5)&(t<1-1e-5))

def retained_point(d,native,k,tag,typ,s,n):
 original=np.array([s['x'],s['y'],s['z']]);centre=original.copy();note='Existing location retained';changed=False
 materials={'SHELL','HEAD'} if d['kind'] in ['TANK','SPHERE','DRUM'] else {'PIPE'} if typ!='L' else {'TOWER_PANEL','CONCRETE'}
 triangles=np.array([v[[0,i,i+1]] for v,m in native for i in range(1,len(v)-1)])
 def valid(c,n,zones=()):
  if not head_clear(native,c,n,typ,k,zones):return False
  tap=nearest_surface(native,c-.10*n,materials);end=c-.055*n;delta=end-tap
  return segment_clear(triangles,tap+.002*delta/np.linalg.norm(delta),end)
 if not valid(centre,n):
  if d['id']=='INDUCED_DRAFT_COOLING_TOWER':raise ValueError('The requested unchanged tower level pad is obstructed')
  if d['kind']=='SPHERE' and typ=='L':
   verts=np.concatenate([v for v,m in native if m=='HEAD']);axis=(verts.min(0)+verts.max(0))/2;offset=centre-axis
   for angle in [15,-15,30,-30,45,-45,60,-60,90,-90]:
    t=math.radians(angle);R=np.array([[math.cos(t),0,math.sin(t)],[0,1,0],[-math.sin(t),0,math.cos(t)]]);candidate=axis+R@offset;normal=R@n
    if valid(candidate,normal,d.get('accessClearance',[])):centre=candidate;n=normal;changed=True;break
  else:
   ports=sorted(d.get('processPorts',[]),key=lambda p:np.linalg.norm(np.array([p['cell'][a]+.5 for a in ['x','y','z']])-centre))
   directions=[np.array(p['normal'],float) for p in ports]+[np.array([0.,1.,0.]),n]
   for distance in np.arange(.05,.651,.05):
    for direction in directions:
     candidate=original+distance*direction
     if valid(candidate,n,d.get('accessClearance',[])):centre=candidate;changed=True;break
    if changed:break
  if not changed:raise ValueError('No clear instrument relocation for '+d['id']+' '+tag)
  note='Shifted to clear existing access/casting; same logical instrument slot'
 tap=nearest_surface(native,centre-.10*n,materials)
 result=point(tag,typ,s['id'],centre,n,tap,note=note,probeDepth=min(.12,2*d['parameters']['size']/3))
 if changed:result['previousCentre']=original.tolist()
 return result

def top_point(d,native,k,tag,kind,mount,x,z,normal):
 tap=revised_layouts.surface(native,1,[x,0,z],1,{'SHELL','HEAD'});centre=tap+[0,.55,0]
 result=point(tag,kind,mount,centre,normal,tap,'top',note='Top-entry vapour pressure' if kind=='P' else 'Top-entry immersion assembly; unchanged bulk-T reading' if kind=='T' else 'Vertical radar entry; liquid + solid height')
 if kind=='T':
  bottom=revised_layouts.surface(native,1,[x,0,z],-1,{'SHELL','HEAD'});result['probeTip']=(bottom+[0,.20,0]).tolist()
 return result

def sphere_points(d,native,k):
 platform=d['topPlatform'];c=np.array(platform['centre']);radius=platform['radius'];rho=radius+.085;chosen=[]
 obstacles=[(v,m) for v,m in native if m not in ['HEAD','GRATING']]
 for tag,kind,mount,preferred in [('P1','P','pressure_top',-math.pi/4),('T1','T','temperature_top',-3*math.pi/4),('L1','L','level_body',-math.pi/2)]:
  angles=sorted(np.arange(72)*2*math.pi/72,key=lambda a:abs(math.atan2(math.sin(a-preferred),math.cos(a-preferred))))
  for angle in angles:
   n=np.array([math.cos(angle),0,math.sin(angle)]);tapxz=c+n*rho;centre=tapxz.copy();centre[1]=c[1]+.85
   if any(np.linalg.norm(centre-np.array(p['centre']))<.7 for p in chosen):continue
   if k.clipped(obstacles,centre-[.17,.15,.17],centre+[.17,.15,.17]):continue
   if overlaps(centre-[.17,.15,.17],centre+[.17,.15,.17],d.get('accessClearance',[])):continue
   edge=c+n*(radius-.06)
   try:
    bracket=revised_layouts.surface(native,1,edge,1,{'GRATING'});tap=revised_layouts.surface(native,1,tapxz,1,{'HEAD'})
   except ValueError:continue
   if abs(bracket[1]-c[1])>1e-7:continue
   p=point(tag,kind,mount,centre,-n,tap,'top',note='Accessible from top platform; walkway kept clear',platformBracket=bracket.tolist(),platformRadius=radius)
   if kind=='T':p['probeTip']=(revised_layouts.surface(native,1,tapxz,-1,{'HEAD'})+[0,.20,0]).tolist()
   chosen.append(p);break
  else:raise ValueError('No clear native top-platform instrument position: '+d['id']+' '+kind)
 return chosen

def plan(d,native,k):
 key=(d['id'],json.dumps(d['parameters'],sort_keys=True))
 if key in _plans:return _plans[key]
 id=d['id'];kind=d['kind'];points=[];factory=[];reserved=[];note='';channels=[];internal=[];families=[]
 if kind in ['SENSOR_HOUSING','LEVEL_SENSOR_HOUSING']:
  result=dict(points=[],factory=[],policy='Hardware reference only; host applicability is listed in the instrument register.');_plans[key]=result;return result
 if instrument_policy.rotating(d):
  channels,internal=rotating_channels(d);note=instrument_policy.description(d)
 elif id=='VERTICAL':
  shell=[(v,m) for v,m in native if m in ['SHELL','HEAD']];lo,hi=k.bbox(shell);cx,cz=((lo+hi)/2)[[0,2]];r=min(.3*d['parameters']['size']/2,1.05)
  for tag,typ,mount,x,z in [('P1','P','pressure_top',cx-r,cz-r),('T1','T','temperature_top',cx+r,cz-r),('L1','L','level_body',cx,cz-r)]:points.append(top_point(d,native,k,tag,typ,mount,x,z,[0,0,-1]))
  for tag,name,x in [('F1','Gas blanketing valve',cx-r),('F2','PSV',cx+r)]:
   tap=revised_layouts.surface(native,1,[x,0,cz+1.2*r],1,{'HEAD','SHELL'});lo=tap+[-.5,.08,-.5];hi=lo+[1.,1.5,1.]
   reserved.append(dict(tag=tag,name=name,tap=tap.tolist(),min=lo.tolist(),max=hi.tolist(),note='1.0 x 1.0 m service footprint; 1.5 m clear height. Provisional game-layout reserve, not device sizing.'))
  note='P/T/L aligned in one front roof service row; rear F1 blanketing and F2 PSV zones reserved.'
 elif id in ['LENGTHWISE','LENGTHWISE_BOOT']:
  shell=[(v,m) for v,m in native if m in ['SHELL','HEAD']];lo,hi=k.bbox(shell);cx=(lo[0]+hi[0])/2;middle=(lo[2]+hi[2])/2;length=d['parameters']['length']
  for tag,typ,mount,z in [('P1','P','pressure_top',middle+(.03 if d.get('boot') else -.20)*length),('T1','T','temperature_top',middle+(.16 if d.get('boot') else 0)*length),('L1','L','level_body',middle+(.28 if d.get('boot') else .20)*length)]:points.append(top_point(d,native,k,tag,typ,mount,cx,z,[1,0,0]))
  note='All three instrument entries are on the crown. Level beam follows the vertical centre plane.'
  if d.get('boot'):
   b=d['boot'];p=top_point(d,native,k,'L2','L','level_boot',b['axis'][0],b['axis'][2],[1,0,0]);p['guidedTip']=(np.array(b['bottom'])+[0,.20,0]).tolist();p['note']='Top-mounted guided probe aligned with boot; separate boot heavy-liquid/allocated-solids level channel.';points.append(p)
   note='P/T/L1 on crown. L1 reads body non-gas height; L2 aligns with the boot and reads its distinct boot-level channel.'
 elif kind=='SPHERE':
  points=sphere_points(d,native,k);note='P/T/L serviced from the top platform; manway, relief and access kept clear.'
 elif kind in signal_catalog.TOWER_KINDS:
  hot=next(p for p in d['processPorts'] if p['role']=='HOT_WATER_INLET');v=np.array([hot['cell'][a] for a in ['x','y','z']],float)+.5-.35*np.array(hot['normal']);tap=revised_layouts.surface(native,1,v,1,{'PIPE'})
  factory.append(point('Ti','T','builtin_inlet_T',tap+[0,.25,0],[1,0,0],tap,'top',False,'Factory hot-water inlet temperature'))
  level=next(p for p in d['sensorMounts'] if p['id']=='level_body');tap=revised_layouts.surface(native,2,[level['x']-.8,.60,0],1,{'TOWER_PANEL'} if kind=='INDUCED_DRAFT_COOLING_TOWER' else {'CONCRETE'})
  factory.append(point('Tb','T','builtin_basin_T',tap+[0,0,.20],[0,0,1],tap,'back',False,'Factory immersed basin temperature'))
  p=retained_point(d,native,k,'L1','L',level,np.array([0,0,1]));assert 'previousCentre' not in p;p.update(manual=False,allowed=[],integralHead=True,note='Factory basin-level gauge, unchanged position; liquid plus solids, excluding gas.');factory.append(p)
 elif kind=='EXCHANGER':
  tags={'TUBE_INLET':'Tti','TUBE_OUTLET':'Tto','SHELL_INLET':'Tsi','SHELL_OUTLET':'Tso'}
  for port in d['processPorts']:
   axis=np.array(port['face'])-.28*np.array(port['normal']);tap=revised_layouts.surface(native,0,axis,1,{'PIPE'});centre=tap+[.18,0,0]
   factory.append(point(tags[port['role']],'T','builtin_'+port['role'].lower()+'_T',centre,[1,0,0],tap,manual=False,note=port['role'].replace('_',' ').title()+' mixed-stream temperature',processRole=port['role'],immersionTip=axis.tolist(),capAxis=[1,0,0]))
  note='Built-in tube inlet/outlet and shell inlet/outlet T. No manual equipment-body sensor mounts.'
 elif instrument_policy.built_in(d):
  note='Built-in process/drive instruments; no manual equipment-body mounts.'
 elif not d.get('sensorMounts'):
  note='No equipment-body mounts; use P/T/F instruments on the connected process pipe.'
 else:note='Existing equipment-body instrument mounts retained.'
 if kind in signal_catalog.THERMAL_KINDS:
  channels,internal=signal_catalog.thermal(d);note=instrument_policy.description(d)
 elif kind=='COLUMN':
  channels,internal,families=signal_catalog.column(d);lo,hi=k.bbox([(v,m) for v,m in native if m=='CLADDING']);a=d['axis'];internal=[dict(tag='TOP P/T',centre=[a,float(hi[1]-.35),a]),dict(tag='BOTTOM P/T',centre=[a,float(lo[1]+.35),a])]+internal;note=instrument_policy.description(d)
 if instrument_policy.manual_host(d):
  counts={'P':0,'T':0,'L':0}
  for s in d.get('sensorMounts',[]):
   typ='L' if s['id'].startswith('level_') else 'P' if s['pressure'] else 'T'
   if id in ['VERTICAL','LENGTHWISE','LENGTHWISE_BOOT'] or kind=='SPHERE' or id=='INDUCED_DRAFT_COOLING_TOWER' and typ!='L':continue
   counts[typ]+=1;face=s['face'];n=np.array([1,0,0]) if face==5 else np.array([-1,0,0]) if face==4 else np.array([0,0,1]) if face==3 or face<0 and typ=='L' else np.array([0,0,-1])
   points.append(retained_point(d,native,k,typ+str(counts[typ]),typ,s,n))
  if 'mechanicalLevelBounds' in d:
   b=d['mechanicalLevelBounds'];centre=[(b[0]+b[3])/2,min(1.,b[4]-.2),b[2]];points.append(point('LG','LG','tank_level',centre,[0,0,-1],centre,note='Existing optional mechanical sight column',existing=True))
 for p in head_points(dict(points=points,factory=factory)):p['connectorKey']=id+'/'+p['tag']
 if any('previousCentre' in p for p in points):
  note='P/T at the top platform; body-level slot moved clear of rear access.' if kind=='SPHERE' else 'Existing instrument slots retained; obstructed heads shifted clear of the casting.'
 result=dict(points=points,factory=factory,reserved=reserved,policy=note,controllerSignals=channels,internal=internal,streamFamilies=families);_plans[key]=result;return result

def instrument_entry(p):
 """One fixed electrical entry datum for both its gland and conduit route."""
 R=basis(p['normal']);centre=np.array(p['centre']);offset=.080 if p['kind']=='L' else .060
 if p['entry']=='top':return centre+R@np.array([offset,-.115,-.025]),np.array([0.,-1.,0.])
 return centre+R@np.array([offset,0,-.065]),-np.array(p['normal'],float)

def hardware_point(p,k):
 if p.get('existing'):return []
 centre=np.array(p['centre']);n=np.array(p['normal']);tap=np.array(p['tap']);R=basis(n);out=[]
 if head_model(p):
  out=[(v@R.T+centre,m) for v,m in native_assets(k,head_model(p))]
  out += [(np.array(q['v']).reshape(-1,3)@R.T+centre,'ARGB_'+format(q['colour']&0xffffff,'06x')) for q in connector_meshes(k)[p['connectorKey']]]
  if p['entry']=='top':
   out+=k.cylinder(centre,[0,1,0],.014,-.152,-.105,'INSTRUMENT_STEEL',12)
  # Sealed metallic conduit entry. There is no exposed electrical tail.
  q,entry_axis=instrument_entry(p);out+=k.cylinder(q,entry_axis,.018,0,.03,'INSTRUMENT_STEEL',8)
  # Process support is distinct from the electrical conduit. Retain the native
  # insertion and head datums; sleeve the fragile-looking 28 mm extension.
  top=p['entry']=='top';end=centre+np.array([0,-.115,0]) if top else centre-n*.055
  elbow=np.array([centre[0],tap[1],centre[2]]) if top else tap
  root_axis=unit_vector(elbow-tap) if np.linalg.norm(elbow-tap)>.001 else unit_vector(end-tap)
  neck=.050 if p['kind']=='L' else .040
  for a,b in [(tap,elbow),(elbow,end)]:
   if np.linalg.norm(b-a)>.001:out+=k.cylinder(a,unit_vector(b-a),neck,0,np.linalg.norm(b-a),'INSTRUMENT_STEEL',8)
  flange=.110 if p['kind']=='L' else .085
  out+=k.cylinder(tap,root_axis,flange,-.012,.018,'INSTRUMENT_STEEL',12)
  out+=k.cylinder(tap,root_axis,.070 if p['kind']=='L' else .055,.018,.085,'INSTRUMENT_STEEL',6)
  head_axis=unit_vector(end-elbow) if np.linalg.norm(end-elbow)>.001 else root_axis
  out+=k.cylinder(end,head_axis,neck*1.35,-.035,.005,'INSTRUMENT_STEEL',8)
 else:
  direction=centre-tap;length=np.linalg.norm(direction);out+=k.cylinder(tap,direction/length,.030,0,length,'INSTRUMENT_STEEL',8);out+=k.cylinder(tap,direction/length,.045,-.008,.03,'INSTRUMENT_STEEL',6);out+=k.cylinder(centre,p.get('capAxis',[0,1,0]),.055,-.04,.04,'FACTORY_SENSOR',12)
 for key in ['guidedTip','immersionTip']:
  if p.get(key):
   direction=np.array(p[key])-tap;length=np.linalg.norm(direction);out+=k.cylinder(tap,direction/length,.009,0,length,'INSTRUMENT_STEEL',10)
 if p.get('platformBracket'):
  a=np.array(p['platformBracket'])+[0,-.025,0];b=tap.copy();b[1]=a[1];direction=b-a;length=np.linalg.norm(direction);out+=k.cylinder(a,direction/length,.018,0,length,'BRACKET',6)
 return out

def unit_vector(value):return value/np.linalg.norm(value)

def geometry(catalog,k):return [poly for p in catalog['points']+catalog['factory'] for poly in hardware_point(p,k)]

def equipment_scene(d,native,proposal,catalog,k,hardware=None):
 """One composition owner for every full, cropped and assembly drawing."""
 import instrument_wiring
 return instrument_wiring.scene(d,native,proposal,catalog,k,geometry(catalog,k) if hardware is None else hardware)

@lru_cache(maxsize=1)
def connector_meshes(k):return json.loads((k.ROOT/'geometry/instrument-connector-meshes.json').read_text())

def prepare(k):
 inputs={}
 for e in entries(k):
  d=json.loads((k.ROOT/'geometry'/e['file']).read_text());cat=plan(d,k.raw(d),k)
  for p in head_points(cat):
   key=d['id']+'/'+p['tag'];p['connectorKey']=key;centre=np.array(p['centre']);tap=np.array(p['tap']);depth=p['tap'][1]-p['probeTip'][1] if p.get('probeTip') else p.get('probeDepth',.12)
   inputs[key]=dict(tap=(basis(p['normal']).T@(tap-centre)).tolist(),top=p['entry']=='top',temperature=p['kind']=='T',depth=depth)
 (k.ROOT/'instrument-connectors.json').write_text(json.dumps(inputs));print('Prepared native instrument connectors:',len(inputs),flush=True)

def permitted(catalog):
 kinds=[]
 for p in catalog['points']:
  if p['kind'] not in kinds:kinds.append(p['kind'])
 return ', '.join(kinds) if kinds else 'None - built in' if catalog['policy'].startswith('Built-in') else 'None on body'
