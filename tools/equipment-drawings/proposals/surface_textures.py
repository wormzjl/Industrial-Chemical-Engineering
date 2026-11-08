"""Fixed conduit finish painted on native facets, never chord/ribbon geometry.

All full views, clipped details and transformed assemblies use the same carrier
materials. Texture evaluation changes colour only; native positions/depth and
occlusion stay authoritative, including at facet boundaries and curved heads.
"""
import numpy as np

MATERIALS={}

def paint(carriers,runs,key):
 if not runs:return carriers
 starts=np.array([r['a'] for r in runs]);ends=np.array([r['b'] for r in runs]);radii=np.array([r['width']/2 for r in runs])
 low=np.minimum(starts,ends)-radii[:,None];high=np.maximum(starts,ends)+radii[:,None]
 result=[]
 for i,(vertices,material) in enumerate(carriers):
  ids=np.flatnonzero(np.all(high>=vertices.min(0),axis=1)&np.all(low<=vertices.max(0),axis=1))
  if len(ids):
   name='SURFACE_FINISH_'+key+'_'+str(i)
   MATERIALS[name]=dict(base=material,runs=[runs[j] for j in ids])
   result.append((vertices,name))
  else:result.append((vertices,material))
 return result

def base(material):return MATERIALS[material]['base'] if material in MATERIALS else material

def distance(point,a,b):
 delta=b-a;t=np.clip(np.sum((point-a)*delta,axis=-1)/np.maximum(np.sum(delta*delta,axis=-1),1e-30),0,1)
 return np.linalg.norm(point-a-t[...,None]*delta,axis=-1),t

def shaded(material,vertices,barycentric,indices,colour,shade):
 meta=MATERIALS[material];triangle=vertices[indices]
 points=sum(barycentric[i][...,None]*triangle[i] for i in range(3))
 nearest=np.full(points.shape[:-1],np.inf);phase=np.zeros_like(nearest)
 for row in meta['runs']:
  a=np.array(row['a']);b=np.array(row['b']);dist,t=distance(points,a,b);relative=dist/(row['width']/2);closer=relative<nearest
  nearest=np.minimum(nearest,relative);phase=np.where(closer,row['phase']+t*np.linalg.norm(b-a),phase)
 metal=84+91*np.sqrt(np.clip(1-nearest*nearest,0,1));metal=np.where(np.mod(phase,1.)<.028,metal*.78,metal)
 paint=np.clip(np.stack([metal-4,metal,metal+4],-1)*shade,0,255).astype(np.uint8)
 return np.where((nearest<=1)[...,None],paint,colour)

def transform(material,move,label):
 if material not in MATERIALS:return material
 name=material+'_'+label
 if name not in MATERIALS:
  meta=MATERIALS[material];MATERIALS[name]=dict(base=meta['base'],runs=[dict(row,a=move(np.array(row['a'])).tolist(),b=move(np.array(row['b'])).tolist()) for row in meta['runs']])
 return name

def nearest(points,triangles):
 """Point-to-native-triangle distances, including edges; independent of view."""
 a=triangles[:,0];u=triangles[:,1]-a;v=triangles[:,2]-a;n=np.cross(u,v);nn=np.sum(n*n,axis=1)
 good=nn>1e-18;a=a[good];u=u[good];v=v[good];n=n[good];nn=nn[good];triangles=triangles[good]
 answer=[]
 for point in points:
  q=point-n*(np.sum((point-a)*n,axis=1)/nn)[:,None];w=q-a
  aa=np.sum(u*u,axis=1);bb=np.sum(u*v,axis=1);cc=np.sum(v*v,axis=1);wu=np.sum(w*u,axis=1);wv=np.sum(w*v,axis=1);det=aa*cc-bb*bb
  alpha=(cc*wu-bb*wv)/det;beta=(aa*wv-bb*wu)/det
  minimum=np.min(np.where((alpha>=-1e-8)&(beta>=-1e-8)&(alpha+beta<=1+1e-8),np.linalg.norm(q-point,axis=1),np.inf),initial=np.inf)
  for i in range(3):minimum=min(minimum,np.min(distance(point,triangles[:,i],triangles[:,(i+1)%3])[0],initial=np.inf))
  answer.append(minimum)
 return np.array(answer)

def coverage(carriers,runs):
 """Prove the centreline has paintable native skin at sub-width spacing."""
 triangles=np.array([v[[0,i,i+1]] for v,m in carriers for i in range(1,len(v)-1)])
 low=triangles.min(1);high=triangles.max(1);worst=0.;failures=[];samples=0
 for index,row in enumerate(runs):
  a=np.array(row['a']);b=np.array(row['b']);radius=row['width']/2
  candidates=triangles[np.all(high>=np.minimum(a,b)-radius,axis=1)&np.all(low<=np.maximum(a,b)+radius,axis=1)]
  points=np.array([a+(b-a)*t for t in np.linspace(0,1,max(3,int(np.linalg.norm(b-a)/.006)+2))])
  gaps=nearest(points,candidates) if len(candidates) else np.full(len(points),np.inf);gap=float(gaps.max());worst=max(worst,gap);samples+=len(points)
  if gap>radius*.5:failures.append(dict(segment=index,role=row['role'],source=row.get('source'),gap=gap,a=row['a'],b=row['b']))
 return dict(method='native-facet colour mask; no surface overlay geometry',samples=samples,maxCentrelineGap=worst,uncovered=failures)
