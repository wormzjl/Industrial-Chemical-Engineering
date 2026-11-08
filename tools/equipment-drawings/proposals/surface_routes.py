"""One explicit surface baseline, orthogonal branches and one cabinet connection.

The mesh supplies support and obstacle checks, never independent trunk choices.
Coordinates on curved skins are projected only after routing intent is fixed.
"""
import heapq,itertools,math
import numpy as np
import revised_layouts
CONTACT=.022

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
 if head or kind=='SPHERE':axis=max([0,2],key=lambda a:np.ptp(points[:,a]))
 elif kind in ['DRUM','EXCHANGER']:axis=2
 elif kind=='TANK':axis=0;anchor[1]=target[1]
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
 # A saddle-mounted cabinet gets a drop at the actual support station, rather
 # than at the end of the probe cluster followed by an axial return detour.
 if kind in ['DRUM','EXCHANGER'] and not head or kind in ['SPHERE','TANK']:
  if lo[axis]<target[axis]<hi[axis]:exit_station=float(target[axis])
  low=min(low,exit_station);high=max(high,exit_station)
 if head:
  # The controller drop passes beside, never through, the future service pads.
  sign=1 if target[axis]>anchor[axis] else -1
  exits=[high if sign>0 else low]+[zone['max' if sign>0 else 'min'][axis]+sign*.10 for zone in graph.zones]
  exit_station=max(exits) if sign>0 else min(exits);low=min(low,exit_station);high=max(high,exit_station)
 profile_cache={}
 def profile(value):
  key=float(value)
  if key not in profile_cache:
   wanted=anchor.copy();wanted[axis]=value
   if head or kind=='SPHERE':q=revised_layouts.surface(native,1,wanted,1,allowed)
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
 return dict(axis=axis,points=skin,nodes=nodes,stations=values,joins={s['id']:indices[float(v)] for s,v in zip(sources,stations)},exit=indices[exit_station],head=head or kind=='SPHERE',projectionAxis=1 if head or kind=='SPHERE' else 2 if kind=='TANK' else None)

def section_connection(graph,start,end,axis,blocked):
 """Shortest supported connection in one real mounting cross-section.

 Slice native faces first, split at physical intersections, then route. The
 section cannot wander axially to another head/saddle or jump between skins.
 """
 station=end[axis];free=[i for i in range(3) if i!=axis];segments=[]
 for triangle in graph.triangles:
  values=triangle[:,axis]-station
  if values.min()>1e-7 or values.max()<-1e-7:continue
  hits=[]
  for a,b in zip(triangle,np.roll(triangle,-1,axis=0)):
   da=a[axis]-station;db=b[axis]-station
   if abs(da)<1e-7:hits.append(a)
   if da*db<-1e-16:hits.append(a+(b-a)*(-da/(db-da)))
  unique={tuple(np.round(v,8)):v for v in hits};hits=list(unique.values())
  for i,a in enumerate(hits):
   for b in hits[i+1:]:
    if np.linalg.norm(b-a)>1e-8:segments.append((a,b))
 if not segments:raise ValueError('No native controller mounting section')
 nodes=[];indices={};links=[]
 def node(point):
  point=np.array(point);key=tuple(np.round(point,7))
  if key not in indices:indices[key]=len(nodes);nodes.append(point);links.append({})
  return indices[key]
 splits=[[0.,1.] for _ in segments];welds=[]
 def cross(a,b):return a[0]*b[1]-a[1]*b[0]
 for i,(a,b) in enumerate(segments):
  r=(b-a)[free]
  for j in range(i):
   c,d=segments[j];s=(d-c)[free];den=cross(r,s);offset=(c-a)[free]
   if abs(den)>1e-12:
    t=cross(offset,s)/den;u=cross(offset,r)/den
    if -1e-7<=t<=1+1e-7 and -1e-7<=u<=1+1e-7:splits[i].append(float(np.clip(t,0,1)));splits[j].append(float(np.clip(u,0,1)))
   elif abs(cross(offset,r))<1e-8:
    for point in [c,d]:
     t=(point-a)@(b-a)/np.dot(b-a,b-a)
     if 0<t<1:splits[i].append(float(t))
    for point in [a,b]:
     u=(point-c)@(d-c)/np.dot(d-c,d-c)
     if 0<u<1:splits[j].append(float(u))
   # Same small joined-face tolerance as the native surface graph. Such a
   # transition remains inside the joint; it is never drawn as a floating lead.
   for left,right in [(i,j),(j,i)]:
    c,d=segments[right]
    for point in segments[left]:
     amount=float(np.clip((point-c)@(d-c)/np.dot(d-c,d-c),0,1));q=c+(d-c)*amount;gap=np.linalg.norm(q-point)
     if 1e-7<gap<=CONTACT and not blocked(point,q,graph.zones):splits[right].append(amount);welds.append((point,q))
 ends=[]
 for point in [start,end]:
  candidates=[]
  for i,(a,b) in enumerate(segments):
   t=float(np.clip((point-a)@(b-a)/np.dot(b-a,b-a),0,1));q=a+(b-a)*t;candidates.append((np.linalg.norm(point-q),i,t,q))
  distance,i,t,q=min(candidates,key=lambda row:row[0])
  if distance>.002:raise ValueError('Controller section misses its entry by '+str(distance))
  splits[i].append(t);ends.append(node(q))
 for (a,b),values in zip(segments,splits):
  chain=[node(a+(b-a)*v) for v in sorted(set(values))]
  for i,j in zip(chain,chain[1:]):
   if i==j or blocked(nodes[i],nodes[j],graph.zones):continue
   length=np.linalg.norm(nodes[j]-nodes[i]);links[i][j]=length;links[j][i]=length
 joined=set()
 for a,b in welds:
  i,j=node(a),node(b)
  if i==j:continue
  length=np.linalg.norm(b-a);links[i][j]=length;links[j][i]=length;joined.add(tuple(sorted([i,j])))
 distances={ends[0]:0.};parents={};queue=[(0.,ends[0])]
 while queue:
  cost,at=heapq.heappop(queue)
  if cost!=distances[at]:continue
  if at==ends[1]:break
  for other,length in links[at].items():
   trial=cost+length
   if trial<distances.get(other,float('inf')):distances[other]=trial;parents[other]=at;heapq.heappush(queue,(trial,other))
 if ends[1] not in distances:
  other={ends[1]};queue=[ends[1]]
  while queue:
   for point in links[queue.pop()]:
    if point not in other:other.add(point);queue.append(point)
  nearest=min((np.linalg.norm(nodes[a]-nodes[b]),nodes[a].tolist(),nodes[b].tolist()) for a in distances for b in other)
  raise ValueError('Disconnected native controller mounting section '+str(nearest))
 route=[ends[1]]
 while route[-1]!=ends[0]:route.append(parents[route[-1]])
 result=[nodes[i] for i in route[::-1]]
 hidden=[dict(a=nodes[a].tolist(),b=nodes[b].tolist(),length=float(np.linalg.norm(nodes[b]-nodes[a])),carrier='native joined surfaces',reason='embedded in joined support') for a,b in zip(route[::-1],route[-2::-1]) if tuple(sorted([a,b])) in joined]
 # A native curve may change two world coordinates; its design is still one
 # section drop (Y) plus the approach (X or Z), never a diagonal design line.
 corner=start.copy();corner[1]=end[1];intent=[]
 for a,b in [(start,corner),(corner,end)]:
  if np.linalg.norm(b-a)>1e-8:intent.append((a.tolist(),b.tolist()))
 return result,intent,hidden

def controller(graph,d,native,shared,target,blocked):
 start=shared['points'][shared['exit']]
 if abs(start[shared['axis']]-target[shared['axis']])>1e-6 and not (d['kind']=='DRUM' and d['parameters']['orientation']=='VERTICAL'):return None
 if d['kind']=='DRUM' and d['parameters']['orientation']=='VERTICAL':
  # Front row -> clear side aisle across the roof -> one vertical shell/skirt
  # section to the cabinet. Both roof plan legs are axis-aligned.
  axis=2 if shared['axis']==0 else 0;wanted=start.copy();wanted[axis]=target[axis]
  roof=revised_layouts.surface(native,1,wanted,1,{'HEAD','SHELL'})
  points=[]
  for t in np.linspace(0,1,max(2,int(abs(target[axis]-start[axis])/.025)+2)):
   q=start+(wanted-start)*t;q=revised_layouts.surface(native,1,q,1,{'HEAD','SHELL'})
   if points and blocked(points[-1],q,graph.zones):raise ValueError('Controller roof aisle enters reserved space')
   points.append(q)
  drop,intent,hidden=section_connection(graph,roof,target,axis,blocked)
  leg=start.copy();leg[axis]=target[axis]
  return points+drop[1:],[(start.tolist(),leg.tolist())]+intent,hidden
 return section_connection(graph,start,target,shared['axis'],blocked)

def native_projection(points,graph,hidden):
 """Resolve facet transitions locally after H/V routing intent is fixed.

 A sampled closest-surface projection can jump across a flange or intersecting
 support. Follow the actual joined faces at that transition, without giving the
 mesh permission to change the trunk or choose a new equipment-wide route.
 """
 invisible={tuple(sorted([tuple(np.round(v['a'],7)),tuple(np.round(v['b'],7))])) for v in hidden}
 def gap(a,b):return max(np.linalg.norm((q:=a+(b-a)*t)-graph.nearest(q)[0]) for t in [.2,.4,.6,.8])
 result=[points[0]]
 for a,b in zip(points,points[1:]):
  edge=tuple(sorted([tuple(np.round(a,7)),tuple(np.round(b,7))]))
  if edge in invisible or np.linalg.norm(b-a)<1e-8 or gap(a,b)<.001:
   result.append(b);continue
  start,_=graph.attach(a);end,_=graph.attach(b);local=route_between(graph,start,end)
  routed=[graph.points[node] for node in local];length=sum(np.linalg.norm(y-x) for x,y in zip(routed,routed[1:]))
  if length>max(.5,4*np.linalg.norm(b-a)):raise ValueError('Facet projection needs a new routing design, not a local transition')
  for x,y in zip(routed,routed[1:]):
   if gap(x,y)>.001:
    distance=np.linalg.norm(y-x)
    if distance>CONTACT+1e-7:raise ValueError('Native facet graph contains an unsupported span')
    hidden.append(dict(a=x.tolist(),b=y.tolist(),length=float(distance),carrier='native joined surfaces',reason='embedded in joined support'))
  result.extend(routed[1:])
 return result

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
