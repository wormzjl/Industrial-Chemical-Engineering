"""One explicit surface baseline, orthogonal branches and one cabinet connection.

The mesh supplies support and obstacle checks, never independent trunk choices.
Coordinates on curved skins are projected only after routing intent is fixed.
"""
import heapq,itertools,math
import numpy as np
import revised_layouts

def section_nearest(triangles,wanted,axis):
 """Closest actual skin point in one fixed station plane."""
 station=wanted[axis];values=triangles[:,:,axis]-station
 candidates=triangles[(values.min(1)<=1e-8)&(values.max(1)>=-1e-8)]
 best=(float('inf'),None)
 for triangle in candidates:
  hits=[]
  for a,b in zip(triangle,np.roll(triangle,-1,axis=0)):
   da=a[axis]-station;db=b[axis]-station
   if abs(da)<1e-8:hits.append(a)
   if da*db<-1e-16:hits.append(a+(b-a)*(-da/(db-da)))
  for i,a in enumerate(hits):
   b=hits[(i+1)%len(hits)];delta=b-a;length=delta@delta
   q=a+delta*np.clip((wanted-a)@delta/length,0,1) if length>1e-16 else a
   distance=np.linalg.norm(q-wanted)
   if distance<best[0]:best=(distance,q)
 if best[1] is None:raise ValueError('No carrier surface at baseline station '+str(station))
 q=best[1].copy();q[axis]=station;return q

def route_between(graph,start,end,forbidden=()):
 if start==end:return [start]
 forbidden=set(forbidden)-{start,end};costs={start:0.};parents={};queue=[(0.,start)]
 while queue:
  _,node=heapq.heappop(queue)
  if node==end:
   path=[end]
   while path[-1]!=start:path.append(parents[path[-1]])
   return path[::-1]
  for other,weight in graph.links[node].items():
   if other in forbidden:continue
   trial=costs[node]+weight
   if trial<costs.get(other,float('inf')):
    costs[other]=trial;parents[other]=node
    heapq.heappush(queue,(trial+np.linalg.norm(graph.points[other]-graph.points[end]),other))
 raise ValueError('No supported connection to the common baseline')

def baseline(graph,d,native,sources,target,blocked):
 points=np.array([s['desired'] for s in sources]);anchor=np.median(points,axis=0);kind=d['kind'];head=kind=='DRUM' and d['parameters']['orientation']=='VERTICAL'
 if head:axis=max([0,2],key=lambda a:np.ptp(points[:,a]))
 elif kind in ['DRUM','EXCHANGER']:axis=2
 elif kind=='TANK':axis=0;anchor[1]=target[1]
 elif kind=='SPHERE':axis=1
 else:axis=2;anchor[0]=target[0];anchor[1]=target[1]
 allowed={'SHELL','HEAD'} if kind in ['DRUM','TANK','SPHERE','EXCHANGER'] else {'TOWER_PANEL','CONCRETE'}
 if kind in ['DRUM','EXCHANGER']:allowed.add('FLANGE')
 triangles=[]
 for vertices,material in native:
  if material not in allowed:continue
  if 'COOLING_TOWER' in kind:
   normal=np.cross(vertices[1]-vertices[0],vertices[2]-vertices[0]);length=np.linalg.norm(normal)
   if length<1e-10 or abs(normal[1]/length)>.5 or vertices[:,1].min()>anchor[1]+.05 or vertices[:,1].max()<anchor[1]-.05:continue
  for i in range(1,len(vertices)-1):
   triangle=vertices[[0,i,i+1]]
   if np.linalg.norm(np.cross(triangle[1]-triangle[0],triangle[2]-triangle[0]))>1e-10:triangles.append(triangle)
 triangles=np.array(triangles)
 if not len(triangles):raise ValueError('No native baseline carrier: '+d['id'])
 lo,hi=triangles.reshape(-1,3).min(0),triangles.reshape(-1,3).max(0)
 stations=np.clip(points[:,axis],lo[axis]+1e-6,hi[axis]-1e-6)
 low,high=stations.min(),stations.max();exit_station=float(np.clip(target[axis],low,high))
 profile_cache={}
 def profile(value):
  key=float(value)
  if key not in profile_cache:
   wanted=anchor.copy();wanted[axis]=value
   if head:q=revised_layouts.surface(native,1,wanted,1,allowed)
   elif kind=='TANK':q=revised_layouts.surface(native,2,wanted,1 if anchor[2]>(lo[2]+hi[2])/2 else -1,allowed)
   else:q=section_nearest(triangles,wanted,axis)
   profile_cache[key]=q
  return profile_cache[key]
 values=sorted(set([float(v) for v in stations]+[exit_station]+list(np.linspace(low,high,max(2,min(257,int((high-low)/.15)+2))))))
 def refine(a,b,depth=0):
  middle=(a+b)/2;q=profile(middle)
  if depth<12 and np.linalg.norm(q-(profile(a)+profile(b))/2)>.0004:return refine(a,middle,depth+1)[:-1]+refine(middle,b,depth+1)
  return [a,b]
 refined=[values[0]]
 for a,b in zip(values,values[1:]):refined.extend(refine(a,b)[1:])
 values=refined;skin=[profile(v) for v in values]
 if any(blocked(a,b,graph.zones) for a,b in zip(skin,skin[1:])):raise ValueError('Common baseline enters a reserved service envelope: '+d['id'])
 nodes=[graph.attach(q)[0] for q in skin]
 for a,b in zip(nodes,nodes[1:]):
  _,face=graph.nearest((graph.points[a]+graph.points[b])/2);graph.link(a,b,face)
 indices={value:i for i,value in enumerate(values)}
 return dict(axis=axis,points=skin,nodes=nodes,stations=values,joins={s['id']:indices[float(v)] for s,v in zip(sources,stations)},exit=indices[exit_station],head=head,projectionAxis=1 if head else 2 if kind=='TANK' else None)

def orthogonal(points,graph,blocked,constant_axis=None,head_project=None,concealed=None):
 """Return only H/V design legs; curves arise from the carrier projection."""
 points=[np.array(v) for v in points];a,b=points[0],points[-1]
 if np.linalg.norm(b-a)<1e-8:return [a],[]
 if head_project:
  projection_axis,project=head_project;free=[i for i in range(3) if i!=projection_axis];waypoints=[a.copy()];p=a.copy()
  for axis in free:
   if abs(p[axis]-b[axis])>1e-7:p=p.copy();p[axis]=b[axis];waypoints.append(p)
  if len(waypoints)==1:return [a,b],[]
  result=[];legs=[]
  for p,q in zip(waypoints,waypoints[1:]):
   legs.append((p.tolist(),q.tolist()))
   for t in np.linspace(0,1,max(2,int(np.linalg.norm(q-p)/.08)+2)):
    at=project(p+(q-p)*t)
    if result and blocked(result[-1],at,graph.zones):raise ValueError('Head branch enters reserved service space')
    result.append(at)
  result[0]=a;result[-1]=b;return result,legs
 def projection(wanted):
  return section_nearest(graph.triangles,wanted,constant_axis) if constant_axis is not None else graph.nearest(wanted)[0]
 original=sum(np.linalg.norm(q-p) for p,q in zip(points,points[1:]));choices=[]
 active=[axis for axis in range(3) if abs(a[axis]-b[axis])>1e-7]
 for order in itertools.permutations(active):
  if constant_axis is not None and abs(a[constant_axis]-b[constant_axis])>1e-5:continue
  waypoints=[a.copy()];p=a.copy()
  for axis in order:
   if abs(p[axis]-b[axis])>1e-7:p=p.copy();p[axis]=b[axis];waypoints.append(p)
  result=[];legs=[];safe=True
  for p,q in zip(waypoints,waypoints[1:]):
   length=np.linalg.norm(q-p);count=max(2,min(129,int(length/.1)+2));leg=[projection(p+(q-p)*t) for t in np.linspace(0,1,count)]
   if max(np.linalg.norm(np.diff(leg,axis=0),axis=1),default=0)>max(.12,3*length/(count-1)):safe=False;break
   if any(blocked(x,y,graph.zones) for x,y in zip(leg,leg[1:])):safe=False;break
   result.extend(leg if not result else leg[1:]);legs.append((p.tolist(),q.tolist()))
  if not safe or not result:continue
  length=sum(np.linalg.norm(q-p) for p,q in zip(result,result[1:]))
  if length<=original*2+.15:choices.append((len(legs),length,result,legs))
 if choices:
  _,_,result,legs=min(choices,key=lambda row:row[:2]);result[0]=a;result[-1]=b;return result,legs
 if len(points)<3:
  # Embedded wiring can pass inside a structural member. Never represent the
  # inclined member as an exterior diagonal conduit or invent a floating span.
  carriers=[]
  for t in [.25,.5,.75]:
   wanted=a+(b-a)*t;q,face=graph.nearest(wanted)
   if np.linalg.norm(q-wanted)>.025:break
   material=graph.carrier_materials[face]
   if material not in {'SUPPORT','STEELWORK','CONCRETE','TOWER_PANEL','FIREPROOF','BRACKET'}:break
   carriers.append(material)
  if len(carriers)==3 and concealed is not None:
   concealed.append(dict(a=a.tolist(),b=b.tolist(),length=float(np.linalg.norm(b-a)),carrier=carriers[1],reason='embedded in supporting structure'));return [a,b],[]
  raise ValueError('No supported horizontal/vertical surface connection')
 middle=len(points)//2;left,l=orthogonal(points[:middle+1],graph,blocked,constant_axis,concealed=concealed);right,r=orthogonal(points[middle:],graph,blocked,constant_axis,concealed=concealed)
 return left[:-1]+right,l+r
