"""Drawing-only shaft-train service frame; shared by port placement and assembled views."""
import numpy as np

def opposed(points,xsum,zsum):
 p=np.array(points).copy();p[...,0]=xsum-p[...,0];p[...,2]=zsum-p[...,2];return p

def frame(d):
 ports=d.get('shaftPorts',[])
 if not ports:return None
 port=ports[0];out=np.array([port['dx'],port['dy'],port['dz']],float)
 flow=out if port['role']=='DRIVER_OUTPUT' else -out
 data=np.cross(flow,[0.,1.,0.]);data/=np.linalg.norm(data)
 return dict(role=port['role'],shaftOutward=out.tolist(),powerFlow=flow.tolist(),dataOutward=data.tolist(),feOutward=(-data).tolist())

def port(low,high,normal):
 low=np.array(low);high=np.array(high);normal=np.array(normal);axis=int(np.argmax(abs(normal)));q=(low+high)/2
 q[axis]=(high[axis] if normal[axis]>0 else low[axis])+normal[axis]*.04
 return q,normal

def case(k,low,high,normal,cover=True):
 low=np.array(low);high=np.array(high);normal=np.array(normal);axis=int(np.argmax(abs(normal)));sign=normal[axis]
 result=k.box(low,high)
 if cover:
  a=low+.025;b=high-.025;at=high[axis] if sign>0 else low[axis];a[axis]=min(at,at+sign*.012);b[axis]=max(at,at+sign*.012);result+=k.box(a,b,'COVER')
 return result

def large_terminals(d,polys,k,surface):
 """Identical compact housings on the opposed solid strips; medium only selects service side."""
 c=np.array(d['terminal']);f=frame(d);enclosures=[];extra=[];ports=[]
 for medium,normal in [('DATA',f['dataOutward']),('FE',f['feOutward'])]:
  n=np.array(normal);sign=n[0];point=surface(polys,0,[0,c[1],c[2]],sign,{'MOTOR_PLAIN','MOTOR_PANEL','GENERATOR_PANEL'})
  lo=np.array([point[0]+(.015 if sign>0 else -.195),c[1]-.15,c[2]-.16]);hi=lo+[.18,.30,.32]
  extra+=case(k,lo,hi,n);q,n=port(lo,hi,n);ports.append((medium,q,n))
  enclosures.append(dict(medium=medium,low=lo.tolist(),high=hi.tolist(),normal=n.tolist(),anchor=point.tolist()))
  back=lo[0] if sign>0 else hi[0]
  extra+=k.box([min(back,point[0]-.002*sign),c[1]-.12,c[2]-.13],[max(back,point[0]-.002*sign),c[1]+.12,c[2]+.13],'BRACKET')
 data=enclosures[0]
 return np.array(data['low']),np.array(data['high']),extra,ports,enclosures
