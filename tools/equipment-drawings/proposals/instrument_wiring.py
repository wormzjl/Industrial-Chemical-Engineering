"""Fixed metallic instrument conduits: native pipe sweeps, supported routes and sealed entries.

These are equipment-owned visual parts. They add no layable cable, network node,
world block, process state or collision rule. Shared native meshes own the carrier.
"""
import heapq,json,math,hashlib,gzip,inspect,pickle
from functools import lru_cache
from pathlib import Path
import numpy as np
from PIL import Image
import sensor_layouts
import revised_layouts
import surface_routes

CONTACT=.022
TEXTURES={};_plans={}
EXCLUDED={'BOLT','BOLT_FACE','OPENING','GRATING','HANDRAIL','ACCESS','SHAFT','FAN','BLADE','ROTOR','GUARD','DATA','FE','CABLE_DATA','CABLE_FE','DISPLAY','FLUID','GLASS','MARKING','ARROW','LABEL'}

def texture(path):
 """Metal and union-band atlas, with a ribbed metallic-flex swatch."""
 a=np.full((256,64,3),151,np.uint8)
 for y in range(256):
  v=113 if y<5 else 176 if y<8 else 151
  a[y,:,:]=[v-4,v,v+4]
  a[y,48:,:]=int(151+9*math.cos(y*2*math.pi/8))
 Image.fromarray(a).save(path);return a


def unit(v):
 v=np.asarray(v,float);return v/max(np.linalg.norm(v),1e-15)

def blocked(a,b,zones):
 for z in zones:
  lo=np.array(z['min'])-[.02,.4,.02];hi=np.array(z['max'])+[.02,.02,.02];d=b-a;t0,t1=0.,1.
  for axis in range(3):
   if abs(d[axis])<1e-12:
    if a[axis]<lo[axis] or a[axis]>hi[axis]:t0,t1=1.,0.;break
   else:
    p,q=sorted([(lo[axis]-a[axis])/d[axis],(hi[axis]-a[axis])/d[axis]]);t0=max(t0,p);t1=min(t1,q)
  if t0<=t1:return True
 return False

class SurfaceGraph:
 def __init__(self,polys,zones=()):
  self.zones=zones;self.points=[];self.nodes={};self.links=[];self.edges={};self.face_nodes=[]
  tris=[]
  for v,m in polys:
   if m in EXCLUDED or m.startswith('ARGB_') or any(word in m for word in ['BOLT','SHAFT','FAN_','MARKING']):continue
   for i in range(1,len(v)-1):
    t=v[[0,i,i+1]]
    if np.linalg.norm(np.cross(t[1]-t[0],t[2]-t[0]))>1e-8:tris.append(t)
  self.triangles=np.array(tris);self.lo=self.triangles.min(1);self.hi=self.triangles.max(1);self.normals=np.cross(self.triangles[:,1]-self.triangles[:,0],self.triangles[:,2]-self.triangles[:,0]);self.normals/=np.linalg.norm(self.normals,axis=1)[:,None]
  span=np.ptp(self.triangles.reshape(-1,3),0);self.step=min(1.,max(.2,np.max(span)/100))
  edge_faces={}
  for f,t in enumerate(self.triangles):
   ids=[self.node(v) for v in t];members=set(ids)
   for a,b in [(0,1),(1,2),(2,0)]:
    key=tuple(sorted([ids[a],ids[b]]));edge_faces.setdefault(key,[]).append(f)
    count=max(1,int(math.ceil(np.linalg.norm(t[b]-t[a])/self.step)));chain=[self.node(t[a]+(t[b]-t[a])*s/count) for s in range(count+1)];members.update(chain)
    for u,v in zip(chain,chain[1:]):self.link(u,v,f)
   mid=self.node(t.mean(0));members.add(mid)
   for v in list(members):self.link(mid,v,f)
   self.face_nodes.append(members)
  # Components meet at native surface intersections, not an assumed outer envelope.
  for (ia,ib),owners in edge_faces.items():
   a=self.points[ia];b=self.points[ib];direction=b-a
   candidates=np.flatnonzero(np.all(self.lo<=np.maximum(a,b)+1e-8,axis=1)&np.all(self.hi>=np.minimum(a,b)-1e-8,axis=1))
   t=self.triangles[candidates];e1=t[:,1]-t[:,0];e2=t[:,2]-t[:,0];h=np.cross(direction,e2);det=np.einsum('ij,ij->i',e1,h);valid=abs(det)>1e-10
   candidates=candidates[valid];e1=e1[valid];e2=e2[valid];h=h[valid];det=det[valid]
   if not len(candidates):continue
   s=a-self.triangles[candidates,0];u=np.einsum('ij,ij->i',s,h)/det;q=np.cross(s,e1);v=(q@direction)/det;along=np.einsum('ij,ij->i',e2,q)/det
   good=(u>=-1e-8)&(v>=-1e-8)&(u+v<=1+1e-8)&(along>1e-6)&(along<1-1e-6)
   for face,amount in zip(candidates[good],along[good]):
    at=self.node(a+amount*direction)
    for owner in owners:self.link(at,ia,owner);self.link(at,ib,owner);self.face_nodes[owner].add(at)
    for node in list(self.face_nodes[face]):self.link(at,node,int(face))
    self.face_nodes[face].add(at)
  # Welded/bolted surfaces can touch in the middle of a coplanar face.
  original=len(self.points)
  for node in range(original):
   p=self.points[node];candidates=np.flatnonzero(np.all(self.lo<=p+CONTACT,axis=1)&np.all(self.hi>=p-CONTACT,axis=1));t=self.triangles[candidates]
   if not len(candidates):continue
   n=self.normals[candidates];distance=np.einsum('ij,ij->i',p-t[:,0],n);good=abs(distance)<CONTACT
   for face,dist in zip(candidates[good],distance[good]):
    if node in self.face_nodes[face]:continue
    q=p-dist*self.normals[face]
    if self.inside(q,int(face)):
     attach=self.node(q);self.link(node,attach,int(face))
     for other in list(self.face_nodes[face]):self.link(attach,other,int(face))
     self.face_nodes[face].add(attach)

 def node(self,p):
  p=np.array(p,float);key=tuple(np.round(p,7))
  if key not in self.nodes:self.nodes[key]=len(self.points);self.points.append(p);self.links.append({})
  return self.nodes[key]

 def link(self,a,b,face):
  if a==b:return
  p,q=self.points[a],self.points[b]
  if blocked(p,q,self.zones):return
  length=np.linalg.norm(q-p)
  if length<1e-9:return
  direction=abs((q-p)/length);penalty=1.+.65*(1.-max(direction))
  weight=length*penalty
  if weight<self.links[a].get(b,float('inf')):
   self.links[a][b]=weight;self.links[b][a]=weight;self.edges[tuple(sorted([a,b]))]=int(face)

 def inside(self,q,face):
  a,b,c=self.triangles[face];u=b-a;v=c-a;w=q-a;aa=u@u;bb=u@v;cc=v@v;det=aa*cc-bb*bb
  if det<1e-18:return False
  x=(cc*(w@u)-bb*(w@v))/det;y=(aa*(w@v)-bb*(w@u))/det
  return min(x,y)>-1e-7 and x+y<1.+1e-7

 def nearest(self,p):
  t=self.triangles;a=t[:,0];u=t[:,1]-a;v=t[:,2]-a;n=self.normals
  q=np.array(p)-n*np.einsum('ij,ij->i',np.array(p)-a,n)[:,None];w=q-a
  dot=lambda x,y:np.einsum('ij,ij->i',x,y)
  aa=dot(u,u);bb=dot(u,v);cc=dot(v,v);det=aa*cc-bb*bb;alpha=(cc*dot(w,u)-bb*dot(w,v))/det;beta=(aa*dot(w,v)-bb*dot(w,u))/det
  candidates=[q];dist=[np.where((alpha>=0)&(beta>=0)&(alpha+beta<=1),dot(q-p,q-p),np.inf)]
  for i in range(3):
   start=t[:,i];edge=t[:,(i+1)%3]-start;amount=np.clip(dot(p-start,edge)/dot(edge,edge),0,1);r=start+amount[:,None]*edge;candidates.append(r);dist.append(dot(r-p,r-p))
  which,face=np.unravel_index(np.argmin(np.array(dist)),(4,len(t)));return candidates[which][face],int(face)

 def attach(self,p):
  q,face=self.nearest(np.array(p));at=self.node(q)
  for node in list(self.face_nodes[face]):self.link(at,node,face)
  self.face_nodes[face].add(at);return at,face

 def tree(self,target):
  distances={target:0.};parents={};queue=[(0.,target)]
  while queue:
   cost,node=heapq.heappop(queue)
   if cost!=distances[node]:continue
   for nxt,weight in self.links[node].items():
    trial=cost+weight
    if trial<distances.get(nxt,float('inf')):distances[nxt]=trial;parents[nxt]=node;heapq.heappush(queue,(trial,nxt))
  return parents,distances

 def outward(self,face,mid):
  normal=self.normals[face];spans=[]
  for sign in [1.,-1.]:
   n=normal*sign;p=mid+n*.006;t=self.triangles;e1=t[:,1]-t[:,0];e2=t[:,2]-t[:,0];h=np.cross(n,e2);det=np.einsum('ij,ij->i',e1,h);ids=np.flatnonzero(abs(det)>1e-9);s=p-t[ids,0];u=np.einsum('ij,ij->i',s,h[ids])/det[ids];q=np.cross(s,e1[ids]);v=(q@n)/det[ids];length=np.einsum('ij,ij->i',e2[ids],q)/det[ids];valid=(u>=-1e-7)&(v>=-1e-7)&(u+v<=1+1e-7)&(length>1e-6);spans.append(min(length[valid],default=float('inf')))
  return normal if spans[0]>=spans[1] else -normal

def surface_graph(carriers,zones,k):
 """Cache only this run's trusted generated graph, never world or imported input."""
 stamp=hashlib.sha256((inspect.getsource(SurfaceGraph)+str(CONTACT)+str(sorted(EXCLUDED))).encode())
 for v,m in carriers:stamp.update(v.tobytes());stamp.update(m.encode())
 stamp.update(json.dumps(zones,sort_keys=True).encode());cache=k.ROOT/'surface-cache';cache.mkdir(exist_ok=True);file=cache/(stamp.hexdigest()+'.pkl.gz')
 if file.exists():
  try:
   with gzip.open(file,'rb') as stream:return pickle.load(stream)
  except (EOFError,OSError,pickle.UnpicklingError):file.rename(file.with_suffix('.incomplete'))
 graph=SurfaceGraph(carriers,zones)
 pending=file.with_suffix('.pending')
 with gzip.open(pending,'wb',compresslevel=1) as stream:pickle.dump(graph,stream,protocol=5)
 pending.replace(file)
 return graph


def wire_sources(d,cat,p,native,k):
 sources=[]
 for probe in cat['points']+cat['factory']:
  if probe.get('existing'):continue
  centre=np.array(probe['centre']);tap=np.array(probe['tap']);n=np.array(probe['normal'])
  if sensor_layouts.head_model(probe):
   gland,entry_axis=sensor_layouts.instrument_entry(probe);start=gland+entry_axis*.03;axis=int(np.argmax(abs(entry_axis)));sign=-int(entry_axis[axis])
   foot=revised_layouts.surface(native,axis,start,sign,{'SHELL','HEAD','PIPE','TOWER_PANEL','CONCRETE'})
   tail=[start,foot]
  else:
   start=centre+[0,-.04,0];tail=[start,tap+n*.035+[0,-.04,0]]
  sources.append(dict(id=probe['mount'],start=start,tail=tail,desired=tail[-1],normal=n,physical=True))
 # Built-in pickups are wired inside their equipment casing. Hollow channel
 # markers are not exterior probes and must not generate a deck of fake leads.
 return sources

@lru_cache(maxsize=1)
def native_meshes(k):
 file=k.ROOT/'instrument-conduits-meshes.json'
 return json.loads(file.read_text()) if file.exists() else {}

def support_normal(graph,face):
 # Evaluate in a face interior. A boundary point can lie on a neighbouring face
 # and falsely make the escape-ray test point into the pressure body.
 return graph.outward(face,graph.triangles[face].mean(0))

def build(d,native,p,cat,k):
 key=d['id']
 if key in _plans:return _plans[key]
 if d['kind'] in ['SENSOR_HOUSING','LEVEL_SENSOR_HOUSING']:return dict(polys=[],paths=[],sources=0)
 sources=wire_sources(d,cat,p,native,k)
 if not sources:return dict(polys=[],paths=[],sources=0)
 carriers=native+p['extra'];stamp=hashlib.sha256(Path(__file__).read_bytes()+Path(sensor_layouts.__file__).read_bytes()+Path(surface_routes.__file__).read_bytes())
 for v,m in carriers:stamp.update(v.tobytes());stamp.update(m.encode())
 stamp.update(json.dumps(cat,sort_keys=True).encode());signature=stamp.hexdigest();cache=k.ROOT/'metal-conduit-cache';cache.mkdir(exist_ok=True);file=cache/(key+'.json')
 if file.exists() and (data:=json.loads(file.read_text())).get('signature')==signature:
  report=data['report'];hardware=[(np.array(v),m) for v,m in data['hardware']]
 else:
  graph=surface_graph(carriers,cat.get('reserved',[]),k)
  materials={}
  for vertices,material in carriers:
   for i in range(1,len(vertices)-1):materials[vertices[[0,i,i+1]].tobytes()]=material
  graph.carrier_materials=[materials.get(triangle.tobytes(),'') for triangle in graph.triangles]
  _,port,normal=next(v for v in p['ports'] if v[0]=='DATA');normal=np.array(normal);low=np.array(p['low']);high=np.array(p['high']);axis=int(np.argmax(abs(normal)));side=2 if axis==0 else 0;target=(low+high)/2
  approach=np.mean([s['desired'] for s in sources],axis=0);entry_normal=np.zeros(3);entry_normal[side]=1. if approach[side]>=target[side] else -1.
  target[1]=low[1]+min(.07,(high[1]-low[1])*.25);target[side]=(high[side] if entry_normal[side]>0 else low[side])+.003*entry_normal[side]
  shared=surface_routes.baseline(graph,d,native,sources,target,blocked)
  end,end_face=graph.attach(target);ends=[(source,*graph.attach(source['desired'])) for source in sources]
  baseline_nodes=shared['nodes'];exit_node=baseline_nodes[shared['exit']]
  cabinet=surface_routes.route_between(graph,exit_node,end,set(baseline_nodes)-{exit_node})
  paths=[];hardware=[];failures=[];runs=[];surface=[];branches=[];concealed=[]
  def run(points,diameter):
   points=[np.array(v) for i,v in enumerate(points) if i==0 or np.linalg.norm(np.array(v)-points[i-1])>1e-6]
   if len(points)>1:runs.append(dict(id=key+'/'+str(len(runs)),points=[v.tolist() for v in points],outside=diameter,flex=False))
  emitted=set()
  def emit(points,role,source=None,hidden=()):
   phase=0.
   concealed_edges={tuple(sorted([tuple(np.round(v['a'],7)),tuple(np.round(v['b'],7))])) for v in hidden}
   for a,b in zip(points,points[1:]):
    length=np.linalg.norm(b-a)
    if length<1e-8:continue
    edge=tuple(sorted([tuple(np.round(a,7)),tuple(np.round(b,7))]))
    if edge in concealed_edges:phase+=length;continue
    if edge not in emitted:
     _,face=graph.nearest((a+b)/2);surface.append(dict(a=a.tolist(),b=b.tolist(),normal=support_normal(graph,face).tolist(),width=.025,phase=phase,role=role,source=source));emitted.add(edge)
    phase+=length
  # The baseline has one geometric definition and is emitted exactly once.
  emit(shared['points'],'baseline')
  for src,start,face in ends:
   join_index=shared['joins'][src['id']];join_node=baseline_nodes[join_index]
   branch=surface_routes.route_between(graph,start,join_node,set(baseline_nodes)-{join_node})
   branch_points=[graph.points[node] for node in branch];axis=shared['axis'];station=shared['points'][join_index][axis]
   constant=axis if abs(branch_points[0][axis]-station)<1e-5 else None
   if constant is not None:
    constrained=[]
    for point in branch_points:
     wanted=point.copy();wanted[axis]=station;constrained.append(surface_routes.section_nearest(graph.triangles,wanted,axis))
    branch_points=constrained
   head_project=(1,lambda wanted:revised_layouts.surface(native,1,wanted,1,{'HEAD','SHELL'})) if shared['head'] else None
   hidden=[]
   try:rendered,intent=surface_routes.orthogonal(branch_points,graph,blocked,constant,head_project,hidden)
   except ValueError:
    # A nozzle may meet its host outside the station plane. Keep its one pinned
    # join and H/V legs, but allow the required perpendicular approach around it.
    constant=None;branch_points=[graph.points[node] for node in branch];hidden=[]
    try:rendered,intent=surface_routes.orthogonal(branch_points,graph,blocked,concealed=hidden)
    except ValueError as error:raise ValueError(d['id']+' '+src['id']+': '+str(error)) from error
   rendered[0]=graph.points[start];rendered[-1]=shared['points'][join_index]
   emit(rendered,'branch',src['id'],hidden);concealed.extend(hidden);branches.append(dict(source=src['id'],joinIndex=join_index,join=shared['points'][join_index].tolist(),points=[v.tolist() for v in rendered],intent=intent,constantAxis=constant,concealed=hidden))
   direction=1 if join_index<=shared['exit'] else -1;trunk=[baseline_nodes[i] for i in range(join_index,shared['exit']+direction,direction)]
   nodes=branch+trunk[1:]+cabinet[1:]
   support=graph.points[start];n=support_normal(graph,face);entry=support+n*.001
   run(src['tail']+[entry],.020)
   paths.append(dict(source=src['id'],points=[graph.points[v].tolist() for v in nodes],tail=[np.array(v).tolist() for v in src['tail']]+[entry.tolist()],surfaceLength=sum(np.linalg.norm(graph.points[a]-graph.points[b]) for a,b in zip(nodes,nodes[1:])),joinIndex=join_index))
  hidden=[];cabinet_points,cabinet_intent=surface_routes.orthogonal([graph.points[node] for node in cabinet],graph,blocked,concealed=hidden)
  emit(cabinet_points,'controller',hidden=hidden);concealed.extend(hidden)
  baseline=dict(axis=shared['axis'],points=[v.tolist() for v in shared['points']],parameters=shared['stations'],exitIndex=shared['exit'],projectionAxis=shared['projectionAxis'],definitionCount=1)
  report=dict(paths=paths,sources=len(sources),connected=len(paths),failed=failures,concealedTransitions=concealed,gland=target.tolist(),glandNormal=entry_normal.tolist(),branchDiameter=.020,trunkDiameter=.025,uniqueSurfaceSegments=len(surface),conduitRuns=runs,surfaceRuns=surface,baseline=baseline,branches=branches,controllerIntent=cabinet_intent,texture='instrument-conduit.png')
  report['meshFingerprint']=hashlib.sha256(json.dumps(runs,sort_keys=True).encode()).hexdigest()
  file.write_text(json.dumps(dict(signature=signature,report=report,hardware=[(v.tolist(),m) for v,m in hardware])))
 polys=list(hardware);meshes=native_meshes(k).get(key)
 for i,row in enumerate(report['surfaceRuns']):
  a=np.array(row['a']);b=np.array(row['b']);n=np.array(row['normal']);tangent=unit(b-a);side=unit(np.cross(n,tangent));width=row['width'];name='SURFACE_CONDUIT_'+key+'_'+str(i)
  TEXTURES[name]=dict(origin=a,along=tangent,across=side,width=width,type='surface',phase=row['phase'])
  a=a+n*.001;b=b+n*.001;polys.append((np.array([a-side*width/2,a+side*width/2,b+side*width/2,b-side*width/2]),name))
 if meshes and meshes.get('fingerprint')==report['meshFingerprint']:
  for row in meshes['runs']:
   material='METAL_CONDUIT_'+row['id'];TEXTURES[material]=dict(points=np.array(row['points']),flex=row['flex'],type='mesh')
   polys.extend((np.array(q['v']).reshape(-1,3),material) for q in row['polygons'])
 result=dict(report,polys=polys,meshed=bool(meshes and meshes.get('fingerprint')==report['meshFingerprint']));_plans[key]=result;return result

def geometry(d,native,p,cat,k):
 result=build(d,native,p,cat,k)
 if result.get('failed'):raise ValueError('Unconnected fixed instrument conduits: '+d['id']+' '+str(result['failed']))
 if result.get('sources') and not result['meshed']:raise ValueError('Run --prepare-wiring then --mesh-export before rendering '+d['id'])
 return result['polys']

def prepare(k,only=None):
 texture(k.ROOT/'instrument-conduit.png')
 file=k.ROOT/'instrument-conduits.json';records=json.loads(file.read_text()) if only and file.exists() else {}
 for entry in sensor_layouts.entries(k):
  if only and entry['id'] not in only:continue
  d=json.loads((k.ROOT/'geometry'/entry['file']).read_text());native=k.raw(d);p=k.proposal(d,native);cat=sensor_layouts.plan(d,native,k);report=build(d,native,p,cat,k)
  if report.get('failed'):raise ValueError((d['id'],report['failed']))
  if report.get('sources'):records[d['id']]=dict(fingerprint=report['meshFingerprint'],runs=report['conduitRuns'])
  print(d['id'],str(report.get('connected',0))+'/'+str(report.get('sources',0)),flush=True)
 (k.ROOT/'instrument-conduits.json').write_text(json.dumps(records));(k.ROOT/'wiring-report.json').write_text(json.dumps(reports(),indent=2))

def transform(polys,move,label):
 result=[]
 for vertices,m in polys:
  name=m
  if m in TEXTURES:
   name=m+'_'+label
   if name not in TEXTURES:
    meta=TEXTURES[m]
    if meta['type']=='mesh':TEXTURES[name]=dict(points=move(meta['points']),flex=meta['flex'],type='mesh')
    else:
     origin=move(meta['origin']);TEXTURES[name]=dict(origin=origin,along=unit(move(meta['origin']+meta['along'])-origin),across=unit(move(meta['origin']+meta['across'])-origin),width=meta['width'],type='surface',phase=meta['phase'])
  result.append((move(vertices),name))
 return result

def reports():return {key:{k:v for k,v in p.items() if k!='polys'} for key,p in _plans.items()}

def shaded(material,vertices,barycentric,tri_indices,shade=1.):
 meta=TEXTURES[material]
 if meta['type']=='surface':
  along=(vertices-meta['origin'])@meta['along'];across=(vertices-meta['origin'])@meta['across'];u=sum(barycentric[i]*along[tri_indices[i]] for i in range(3));v=sum(barycentric[i]*across[tri_indices[i]] for i in range(3));edge=np.abs(v)/(meta['width']/2)
  # A round metal highlight and narrow rim shadow, not a flat cable ribbon.
  base=84+91*np.sqrt(np.clip(1-edge*edge,0,1));collar=np.mod(u+meta['phase'],1.0)<.028;base=np.where(collar,base*.78,base)
  return np.clip(np.stack([base-4,base,base+4],-1)*shade,0,255).astype(np.uint8)
 path=meta['points'];lengths=np.linalg.norm(np.diff(path,axis=0),axis=1);distances=np.r_[0,np.cumsum(lengths)];along=[]
 for p in vertices:
  choices=[]
  for i,(a,b) in enumerate(zip(path,path[1:])):
   delta=b-a;t=np.clip((p-a)@delta/(delta@delta),0,1);choices.append((np.linalg.norm(p-(a+delta*t)),distances[i]+t*lengths[i]))
  along.append(min(choices)[1])
 u=sum(barycentric[i]*along[tri_indices[i]] for i in range(3));base=np.full_like(u,151.)
 if meta['flex']:base+=9*np.cos(u*2*np.pi/.008)
 else:
  collar=np.mod(u,1.0);base=np.where(collar<.018,113,np.where(collar<.03,176,base))
 return np.clip(np.stack([base-4,base,base+4],-1)*shade,0,255).astype(np.uint8)
