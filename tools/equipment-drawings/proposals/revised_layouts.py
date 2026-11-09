"""Drawing proposals. Mounts must name a native surface; no outer-bounds fallback."""
import numpy as np
from proposed_castings import material as source_material
from access_clearance import candidate_clear
import train_interfaces
import instrument_policy

def surface(polys,axis,point,sign,materials):
 plane=[i for i in range(3) if i!=axis];hits=[]
 for v,m in polys:
  if source_material(m) not in materials:continue
  for j in range(1,len(v)-1):
   t=v[[0,j,j+1]];a=t[:,plane];A=np.column_stack((a[1]-a[0],a[2]-a[0]))
   if abs(np.linalg.det(A))<1e-11:continue
   u,w=np.linalg.solve(A,np.array(point)[plane]-a[0])
   if min(u,w)>=-1e-8 and u+w<=1+1e-8:hits.append(t[0,axis]+u*(t[1,axis]-t[0,axis])+w*(t[2,axis]-t[0,axis]))
 if not hits:raise ValueError(f'No native mounting surface at {point}, materials {materials}')
 q=np.array(point,float);q[axis]=max(hits) if sign>0 else min(hits);return q

def mount_back(polys,point,k):
 lo=point-np.array([.40,.20,.25]);hi=point+np.array([.40,.20,.25])
 faces=k.clipped([(v,m) for v,m in polys if source_material(m) in ['SUPPORT','STEELWORK','CONCRETE']],lo,hi)
 return max(point[0],max((v[:,0].max() for v,m in faces),default=point[0]))

def support(polys,y,d,k,prefer_front=False,contact_footprint=None):
 candidates=[]
 for v,m in polys:
  lo=v.min(0);hi=v.max(0)
  normal=np.cross(v[1]-v[0],v[2]-v[0])
  if source_material(m) in ['SUPPORT','STEELWORK','CONCRETE'] and lo[1]<=y-.2 and hi[1]>=y+.2 and hi[2]-lo[2]>.07 and abs(normal[0])>1e-9:
   z=(hi[2]+lo[2])/2;point=surface(polys,0,[0,y,z],1,{'SUPPORT','STEELWORK','CONCRETE'});candidates.append((point[0],z))
 if not candidates:raise ValueError('No support face at required service height')
 order=sorted(set(candidates),key=lambda p:(-p[0],p[1])) if prefer_front else sorted(set(candidates),reverse=True)
 for x,z in order:
  point=np.array([x,y,z]);outer=surface(polys,0,point,1,{'SUPPORT','STEELWORK','CONCRETE'})
  if abs(outer[0]-x)>1e-7:continue
  if contact_footprint:
   # A pad needs a supporting face across its rear footprint, not only at its centre.
   height,width=contact_footprint;contacts=[]
   for dy in [-height/2,height/2]:
    for dz in [-width/2,width/2]:
     try:contacts.append(surface(polys,0,[0,y+dy,z+dz],1,{'SUPPORT','STEELWORK','CONCRETE'}))
     except ValueError:pass
   if len(contacts)!=4 or any(abs(q[0]-x)>1e-7 for q in contacts):continue
  outer=point.copy();outer[0]=mount_back(polys,point,k)
  if candidate_clear(outer,d.get('accessClearance',[])):return point
 raise ValueError('Every support face obstructs native ladder/player clearance: '+d['id'])

def build(d,polys,k):
 id=d['id'];kind=d['kind'];par=d['parameters'];lo,hi=k.bbox(polys)
 power=d['power'];front=5;mode='NEW';mount='';notes=[];dims=np.array([.30,.40,.50]);extra=[];anchor=None;clamp=None
 low=high=None;contact='native support';special_ports=None;deck_mount=False;enclosures=None
 def mount_case(point,face,width=.50,height=.40,depth=.30):
  axis=0 if face in [4,5] else 2;sign=1 if face in [3,5] else -1;other=2 if axis==0 else 0
  lower=np.array(point,float);upper=lower.copy();lower[1]-=height/2;upper[1]+=height/2;lower[other]-=width/2;upper[other]+=width/2
  a=(mount_back(polys,point,k) if face==5 else point[axis])+sign*.03;b=a+sign*depth;lower[axis]=min(a,b);upper[axis]=max(a,b);return lower,upper
 def deck_case(x,z,width,height,depth,foot):
  point=surface(polys,1,[x,0,z],1,{'PUMP_BASE'})
  lower=np.array([x-depth/2,point[1]+foot,z-width/2]);return point,lower,lower+[depth,height,width]
 if id in ['VERTICAL','COLUMN']:
  a=d['axis'];anchor=surface(polys,0,[0,1.5,a+max(.6,.16*par['size'])],1,{'SUPPORT'});low,high=mount_case(anchor,5)
  mount='Welded pad on the solid skirt between access openings.'
 elif id in ['LENGTHWISE','LENGTHWISE_BOOT']:
  anchor=support(polys,1.3,d,k,prefer_front=True,contact_footprint=(.28,.28));front=5;low,high=mount_case(anchor,front)
  mount='Direct pad on the outward side rib of the front saddle. Cable entry faces laterally away from the vessel.';contact='outer saddle rib'
  notes=['Side access for cable connection; no separate post. Existing pressure body, saddles and process nozzles retained.']
 elif kind=='TANK':
  a=d['planAxis'];r=par['size']/2;offset=min(1.7,.65*r);x=a+offset;y=1.5
  anchor=surface(polys,2,[x,y,0],-1,{'SHELL'});front=2;low,high=mount_case(anchor,front)
  # A flat cabinet must stand ahead of the entire curved footprint, not just its centre.
  zsafe=min(surface(polys,2,[xx,y,0],-1,{'SHELL'})[2] for xx in [x-.25,x,x+.25])-.03
  high[2]=zsafe;low[2]=zsafe-.30
  mount='Front service-area shell pad, beside the level gauge; clear of the central nozzle.'
  notes=['Native optional sight column fitted in this reference to show the service clearance. Roof at 50% fill.'];contact='front shell pad'
 elif kind=='SPHERE':
  foot=np.array(d['supportLegs'][0]);anchor=surface(polys,2,[foot[0],1.5,foot[2]],-1,{'SUPPORT'});front=2;low,high=mount_case(anchor,front)
  clamp=(foot,d['legRadius']);mount='Two split clamps and a backing plate fixed directly to the front service leg.';contact='leg clamps'
 elif kind in ['EXCHANGER','KETTLE','AIR_COOLER']:
  anchor=support(polys,1.5,d,k);low,high=mount_case(anchor,5);mount='Pad directly on the existing saddle/frame leg.'
 elif kind=='PUMP':
  # One pump-family rule: shrink with nominal bore, with a floor set by the existing 220 mm gland.
  scale=min(1.,par['size']/.4);width=max(.28,.50*scale);height=max(.26,.40*scale);depth=max(.18,.30*scale)
  plate=np.concatenate([v for v,m in polys if source_material(m)=='PUMP_BASE'])
  x=plate[:,0].max()-.025-depth/2;z=plate[:,2].max()-.05-width/2
  anchor,low,high=deck_case(x,z,width,height,depth,.015);deck_mount=True
  mount='Compact terminal on the drive-end base deck beside the coupling guard, tucked inside the plinth outline.';contact='15 mm deck mounting foot'
  notes=['Box scales with nominal bore, with a 280 x 260 x 180 mm minimum around the unchanged 220 mm gland. DATA faces the motor-side corridor; shaft and guard remain clear.']
 elif id=='COMPRESSOR':
  anchor=support(polys,1.5,d,k);low,high=mount_case(anchor,5);mount='Pad fixed to the existing outer foundation/support face.'
 elif id=='CENTRIFUGAL_COMPRESSOR':
  plate=np.concatenate([v for v,m in polys if source_material(m)=='PUMP_BASE']);x=plate[:,0].max()-.30;z=plate[:,2].max()-.5
  anchor,low,high=deck_case(x,z,.35,.30,.25,.025);deck_mount=True
  mount='Compact terminal on the drive-end base deck beside the bearing, inside the plinth outline.'
  contact='25 mm deck mounting foot';b=d['compactBase'];notes=[f'Proposed baseplate: {b["oldPlateWidth"]:.2f} -> {b["newPlateWidth"]:.2f} m wide; plinth: {b["oldPlinthWidth"]:.2f} -> {b["newPlinthWidth"]:.2f} m. Shaft and gas nozzles retain native datums.']
 elif kind in ['VERTICAL_HEATER','CABIN_HEATER']:
  anchor=support(polys,1.5,d,k);low,high=mount_case(anchor,5);mount='Pad on a clear foundation/support face at grade, outside the ladder/player envelope and burner aisle.'
 elif id=='INDUCED_DRAFT_COOLING_TOWER':
  anchor=surface(polys,2,[par['size']-.5,1.,0],-1,{'TOWER_PANEL'});front=2;low,high=mount_case(anchor,front)
  mount='Front basin wall pad below the louvers; clear of the access ladder and central water connection.';contact='basin wall'
 elif id=='NATURAL_DRAFT_COOLING_TOWER':
  a=par['size']/2+.5;anchor=surface(polys,0,[0,1.,a+1.5],1,{'CONCRETE'});low,high=mount_case(anchor,5)
  mount='Direct pad on the outside basin rim, below the air-inlet struts.';contact='basin rim'
  notes=['Mount and cable route enlarged below. Passive draft: no artificial FE port.']
 elif id.endswith('_SMALL') and kind in ['MOTOR','GENERATOR']:
  c=np.array(d['terminal']);front=2;mode='ENLARGE';base=d['nativeTerminalBounds']['min'][1]
  low=np.array([c[0]-.20,base,c[2]-.15]);high=low+[.4,.3,.3]
  mount='Compact terminal replaces the original housing at its native base; no stacked box or extra pedestal.'
 elif id.endswith('_LARGE') and kind in ['MOTOR','GENERATOR']:
  mode='SPLIT';low,high,extra,special_ports,enclosures=train_interfaces.large_terminals(d,polys,k,surface);front=4 if special_ports[0][2][0]<0 else 5
  mount='Matched compact DATA and FE housings on opposite solid casing strips. Identical enclosure and gland dimensions.'
  notes=['Original side terminal and fixed downlead replaced by this pair. Shaft, casing and cooler geometry retained.']
 elif id=='STEAM_TURBINE':
  front=4;mode='REUSE';low=np.array([1.33,2.52,.4502]);high=np.array([1.67,2.826,.7102])
  mount='Reuse the governor housing with a lateral DATA entry. Depth is 260 mm to fit the unchanged 220 mm gland.'
 elif id in ['INLINE_PUMP','INLINE_COMPRESSOR']:
  mode='INTEGRAL';front=3;feature=d['integralMotor'];centre=np.array(feature['axis']);yc=centre[1];portlist=[]
  for medium,sgn in [('DATA',-1),('FE',1)]:
   n=np.array([0,0,sgn]);q=centre+n*(feature['radius']+.025)
   extra+=k.frustum(q,n,min(.14,feature['radius']*.94),.095,-.12,-.04,'PROPOSED',16);portlist.append((medium,q,n))
  special_ports=portlist;low,high=k.bbox(extra)
  mount='Matched tapered necks grow from a wider motor casting. DATA/FE are exact mirrors about its true axis.'
  notes=['Original asymmetric terminal nub removed in the proposal. Native wet-end and process ports retained.']
 elif id=='CONTROL_VALVE':
  mode='CAST_POSITIONER';front=3;yc=d['positionerSeat']+.18;centre=np.array([.5,yc,.63]);n=np.array([0,0,1]);q=np.array([.5,yc,.745])
  extra+=k.contoured_case([.5,yc],.24,.26,.53,.73,.035,'PROPOSED')
  extra+=k.contoured_case([.5,yc],.218,.238,.728,.739,.028,'COVER')
  extra+=k.frustum(q,n,.105,.095,-.12,-.04,'PROPOSED',16)
  special_ports=[('DATA',q,n)];low,high=k.bbox(extra)
  mount='Contoured positioner seated against the extended yoke, below the diaphragm. Cable entry faces outward, perpendicular to the process pipe.'
  notes=['Reference arrangement follows the owner actuator photo. Yoke extended 140 mm; globe body and pipe connections retained. No instrument-air simulation added.']
 elif id.endswith('SENSOR_HOUSING'):
  front=3;mode='HOUSING';low=np.array([-.15,-.15,-.188]);high=low+[.3,.3,.26]
  mount='Transmitter housing with rear data gland; existing front readout and process mount retained.'
 else:raise ValueError('No explicit mount for '+id)
 dims=high-low;centre=(low+high)/2
 if special_ports is not None:ports=special_ports
 elif train_interfaces.frame(d):
  f=train_interfaces.frame(d);media=[('DATA',f['dataOutward'])]+([('FE',f['feOutward'])] if power else [])
  ports=[(medium,*train_interfaces.port(low,high,n)) for medium,n in media]
 elif mode=='HOUSING':ports=[('DATA',np.array([centre[0],centre[1],low[2]-.04]),np.array([0,0,-1]))]
 elif power:
  axis=2 if front==5 else 0;ports=[]
  for medium,sign in [('DATA',-1),('FE',1)]:
   q=centre.copy();q[axis]=(low[axis]-.04 if sign<0 else high[axis]+.04);n=np.zeros(3);n[axis]=sign;ports.append((medium,q,n))
 else:
  axis=0 if front==5 else 2;sign=1 if front in [3,5] else -1;q=centre.copy();q[axis]=(high[axis]+.04 if sign>0 else low[axis]-.04);n=np.zeros(3);n[axis]=sign;ports=[('DATA',q,n)]
 if mode not in ['INTEGRAL','CAST_POSITIONER','SPLIT']:
  normal=np.array([1,0,0]) if front==5 else np.array([-1,0,0]) if front==4 else np.array([0,0,-1 if front==2 else 1])
  extra+=train_interfaces.case(k,low,high,normal,cover=mode!='REUSE')
  if mode=='HOUSING':
   extra+=k.box([low[0]+.04,low[1]+.10,high[2]+.001],[high[0]-.04,high[1]-.05,high[2]+.002],'DISPLAY')
 compact=mode in ['INTEGRAL','CAST_POSITIONER']
 for medium,q,n in ports:extra+=k.gland(q,n,medium,compact)
 if anchor is not None and deck_mount:
  extra+=k.box([low[0]+.025,anchor[1]-.004,low[2]+.03],[high[0]-.025,low[1],high[2]-.03],'BRACKET')
 elif anchor is not None:
  axis=0 if front==5 else 2;other=2 if axis==0 else 0;sign=1 if front in [3,5] else -1
  target=centre.copy();target[axis]=low[axis] if sign>0 else high[axis]
  # Two real continuous steel brackets from native surface to the back of the enclosure.
  for shift in [-.09,.09]:
   a=anchor.copy();a[other]+=shift;b=target.copy();b[other]+=shift
   # Each foot seats on its own native facet. A tangent pad copied sideways
   # from the centre floats off curved tank shells and round support legs.
   a=surface(polys,axis,a,sign,{'SUPPORT','STEELWORK','CONCRETE','SHELL','TOWER_PANEL'})
   elbow=a.copy();elbow[axis]=b[axis];end=b.copy()
   extra+=k.box(np.minimum(a,elbow)-.018,np.maximum(a,elbow)+.018,'BRACKET')
   extra+=k.box(np.minimum(elbow,end)-.018,np.maximum(elbow,end)+.018,'BRACKET')
  extra+=k.box(np.minimum(target,centre)-[.018,.14,.14] if axis==0 else np.minimum(target,centre)-[.14,.14,.018],np.maximum(target,centre)+([.018,.14,.14] if axis==0 else [.14,.14,.018]),'BRACKET')
 if clamp:
  foot,r=clamp
  for y in [1.36,1.60]:extra+=k.cylinder([foot[0],y,foot[2]],[0,1,0],r+.018,0,.04,'BRACKET',12)
 policy=instrument_policy.description(d)
 if mode=='HOUSING':policy='Existing sensor ID, readout and process mount retained.'
 if mode in ['INTEGRAL','CAST_POSITIONER'] and not instrument_policy.rotating(d):policy='Native actuator feedback and settings; integrated terminal casting.'
 result=dict(low=low,high=high,dims=dims,front=front,mode=mode,mount=mount,notes=notes,ports=ports,extra=extra,policy=policy,anchor=anchor,contact=contact,glandOD=.19 if compact else .22)
 if enclosures:result['enclosures']=enclosures
 if train_interfaces.frame(d):result['trainFrame']=train_interfaces.frame(d)
 bases=[(v,m) for v,m in polys if source_material(m)=='CONCRETE']
 if bases:result['basePlan']=[a.tolist() for a in k.bbox(bases)]
 return result

def cable_routes(d,p):
 routes=[];floor=-.55 if p['mode']=='HOUSING' else .25
 for medium,q,n in p['ports']:
  a=q+.025*n;b=q+.70*n
  # The common route owner clears the physical plinth before any ground-level drop.
  if p.get('basePlan'):
   axis=int(np.argmax(abs(n)));low,high=p['basePlan'];edge=high[axis]+.125 if n[axis]>0 else low[axis]-.125
   b[axis]=max(b[axis],edge) if n[axis]>0 else min(b[axis],edge)
  c=b.copy();c[1]=floor;end=c+.8*n
  points=[a,b,c,end]
  routes.append(dict(medium=medium,points=[v.tolist() for v in points],end=end.tolist(),destination='DATA network / controller / DCS' if medium=='DATA' else 'standard FE supply',label='D' if medium=='DATA' else 'P'))
 return routes
