"""Drawing-only rejection against exported native climbing/player volumes."""
import numpy as np
def overlaps(low,high,zones):
 return any(np.all(np.array(high)>np.array(z['min'])+1e-8) and np.all(np.array(low)<np.array(z['max'])-1e-8) for z in zones)
def polygons_hit(polys,zones):
 for v,m in polys:
  if overlaps(v.min(0),v.max(0)+1e-9,zones):return True
 return False
def route_hit(points,zones,radius=.0625):
 p=np.array(points)
 if any(overlaps(np.minimum(a,b)-radius,np.maximum(a,b)+radius,zones) for a,b in zip(p,p[1:])):return True
 # Rounded elbows cut inside the spine corner: include their full corner/sweep box as well.
 bend=3*radius
 for a,b,c in zip(p,p[1:],p[2:]):
  incoming=b-a;outgoing=c-b;incoming/=np.linalg.norm(incoming);outgoing/=np.linalg.norm(outgoing)
  if abs(np.dot(incoming,outgoing))<.99:
   q=np.array([b-bend*incoming,b,b+bend*outgoing])
   if overlaps(q.min(0)-radius,q.max(0)+radius,zones):return True
 return False
def minimum_gap(polys,routes,zones):
 bounds=[(np.concatenate([v for v,m in polys]).min(0),np.concatenate([v for v,m in polys]).max(0))]
 for r in routes:
  for a,b in zip(np.array(r['points']),np.array(r['points'])[1:]):bounds.append((np.minimum(a,b)-.0625,np.maximum(a,b)+.0625))
 return min((np.linalg.norm(np.maximum(np.maximum(np.array(z['min'])-hi,lo-np.array(z['max'])),0)) for lo,hi in bounds for z in zones),default=float('inf'))
def candidate_clear(point,zones):
 x,y,z=point
 if overlaps([x-.02,y-.22,z-.35],[x+.43,y+.22,z+.35],zones):return False
 points=np.array([[x+.395,y,z],[x+1.07,y,z],[x+1.07,.25,z],[x+1.87,.25,z]])
 return not route_hit(points,zones)
