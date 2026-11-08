"""Preview-only engineering sheets. Native facets are exported from the game, not redrawn."""
from pathlib import Path
import json, math, textwrap, html, os
import numpy as np
from PIL import Image, ImageDraw
from reportlab.pdfgen import canvas
from reportlab.lib.colors import HexColor, Color
from reportlab.lib.utils import ImageReader
from reportlab.pdfbase import pdfmetrics

ROOT=Path(__file__).resolve().parent
OUT=ROOT/'drawings-j'; OUT.mkdir(exist_ok=True)
VIEWS=OUT/'views'; VIEWS.mkdir(exist_ok=True)
MAN=json.loads((ROOT/'geometry/manifest.json').read_text())
ACTIVE='';REFRESH=set(os.environ.get('DRAW_REFRESH','').split(','))
W,H=1190.551,841.89
from sensor_layouts import CHANGED as INSTRUMENT_LAYOUTS
TOTAL=len(MAN['entries'])+len(INSTRUMENT_LAYOUTS)+8
INK='#233b49'; MUTE='#637783'; BLUE='#137baf'; DATA='#1499b3'; POWER='#c96b24'
TITLES={'VERTICAL':'Vertical drum','LENGTHWISE':'Horizontal drum','LENGTHWISE_BOOT':'Horizontal drum - boot and twin level','COLUMN':'Distillation column',
 'FLOATING_ROOF_TANK':'Floating-roof tank - ring access','TANK_COMPACT':'Floating-roof tank - compact',
 'TANK_SLENDER':'Floating-roof tank - slender','SPHERE':'Storage sphere - stair access','SPHERE_SMALL':'Storage sphere - ladder access',
 'EXCHANGER_STANDARD':'Heat exchanger - Standard','EXCHANGER_CROSSFLOW':'Heat exchanger - Low-dP crossflow',
 'KETTLE':'Kettle reboiler','AIR_COOLER':'Air cooler','PUMP_OH2':'Centrifugal pump - OH2',
 'PUMP_BB2':'Centrifugal pump - BB2','COMPRESSOR':'Reciprocating compressor',
 'CENTRIFUGAL_COMPRESSOR':'Centrifugal compressor','VERTICAL_HEATER':'Vertical fired heater',
 'CABIN_HEATER':'Cabin fired heater - twin cell','CABIN_HEATER_SINGLE':'Cabin fired heater - single cell','STEAM_TURBINE':'Steam turbine',
 'INDUCED_DRAFT_COOLING_TOWER':'Induced-draft cooling tower','NATURAL_DRAFT_COOLING_TOWER':'Natural-draft cooling tower',
 'MOTOR_SMALL':'Motor - small frame','MOTOR_LARGE':'Motor - large frame',
 'GENERATOR_SMALL':'Generator - small frame','GENERATOR_LARGE':'Generator - large frame',
 'INLINE_PUMP':'Inline pump','INLINE_COMPRESSOR':'Inline compressor','CONTROL_VALVE':'Control valve',
 'SENSOR_HOUSING':'Pressure / temperature / flow / speed transmitter','LEVEL_SENSOR_HOUSING':'Digital level transmitter'}

def bbox(polys):
 v=np.concatenate([q[0] for q in polys]);return v.min(0),v.max(0)
def box(lo,hi,material='PROPOSED'):
 x,y,z=lo;X,Y,Z=hi
 v=np.array([[x,y,z],[X,y,z],[X,Y,z],[x,Y,z],[x,y,Z],[X,y,Z],[X,Y,Z],[x,Y,Z]])
 return [(v[i],material) for i in [[0,3,2,1],[4,5,6,7],[0,4,7,3],[1,2,6,5],[0,1,5,4],[3,7,6,2]]]
def cylinder(p,n,r,a,b,material,sides=8):
 n=np.array(n,dtype=float);p=np.array(p);ref=np.array([0.,1.,0.]) if abs(n[1])<.8 else np.array([1.,0.,0.]);side=np.cross(n,ref)
 ring=lambda t:np.array([p+n*t+r*(ref*math.cos(k*2*math.pi/sides)+side*math.sin(k*2*math.pi/sides)) for k in range(sides)])
 v,w=ring(a),ring(b);out=[(v,material),(w[::-1],material)]
 out.extend((np.array([v[i],w[i],w[(i+1)%sides],v[(i+1)%sides]]),material) for i in range(sides));return out
def gland(p,n,material,compact=False):
 # Matches DataPortGeometry outer pipe/flange/gland envelopes; sealed socket shown, no cable run.
 out=cylinder(p,n,.0625,-.22,-.04,material)
 out+=cylinder(p,n,.095 if compact else .11,-.04,.015,material)
 out+=cylinder(p,n,.0885,.015,.05,material,6)
 out+=cylinder(p,n,.06625,.0501,.055,'OPENING')
 return out
def frustum(p,n,r0,r1,a,b,material,sides=16):
 n=np.array(n,float);p=np.array(p);ref=np.array([0.,1.,0.]) if abs(n[1])<.8 else np.array([1.,0.,0.]);side=np.cross(n,ref)
 ring=lambda t,r:np.array([p+n*t+r*(ref*math.cos(i*2*math.pi/sides)+side*math.sin(i*2*math.pi/sides)) for i in range(sides)])
 v,w=ring(a,r0),ring(b,r1);return [(v,material),(w[::-1],material)]+[(np.array([v[i],w[i],w[(i+1)%sides],v[(i+1)%sides]]),material) for i in range(sides)]
def contoured_case(centre,width,height,z0,z1,radius,material):
 x,y=centre;outline=[]
 for cx,cy,start in [(x+width/2-radius,y+height/2-radius,0),(x-width/2+radius,y+height/2-radius,90),(x-width/2+radius,y-height/2+radius,180),(x+width/2-radius,y-height/2+radius,270)]:
  for j in range(4):
   a=math.radians(start+j*30);outline.append([cx+radius*math.cos(a),cy+radius*math.sin(a)])
 v=np.array([[a,b,z0] for a,b in outline]);w=v.copy();w[:,2]=z1
 return [(v,material),(w[::-1],material)]+[(np.array([v[i],w[i],w[(i+1)%len(v)],v[(i+1)%len(v)]]),material) for i in range(len(v))]
def raw(d):
 import proposed_castings,sys
 return proposed_castings.geometry(d,[(np.array(q['v']).reshape(-1,3),q['m']) for q in d['polygons']],sys.modules[__name__])

def hit_x(polys,y,z):
 hits=[]
 for v,m in polys:
  if m in ['PIPE','FLANGE','BOLT','GUARD','OPENING']:continue
  for i in range(1,len(v)-1):
   t=v[[0,i,i+1]];yz=t[:,[1,2]];A=np.column_stack((yz[1]-yz[0],yz[2]-yz[0]));det=np.linalg.det(A)
   if abs(det)<1e-10:continue
   u,w=np.linalg.solve(A,np.array([y,z])-yz[0])
   if u>=-1e-7 and w>=-1e-7 and u+w<=1+1e-7:hits.append(t[0,0]+u*(t[1,0]-t[0,0])+w*(t[2,0]-t[0,0]))
 return max(hits) if hits else None
def support_mount(polys,y,default):
 candidates=[]
 for v,m in polys:
  lo=v.min(0);hi=v.max(0)
  if m in ['SUPPORT','STEELWORK','FIREPROOF','CONCRETE'] and lo[1]<=y<=hi[1] and hi[2]-lo[2]>.07 and hi[0]-lo[0]<.001:
   candidates.append((hi[0],(hi[2]+lo[2])/2))
 return max(candidates) if candidates else default

def proposal(d,polys):
 import revised_layouts,sys
 return revised_layouts.build(d,polys,sys.modules[__name__])

def palette(material,n):
 n=np.abs(n);shade=.65+.20*n[1]+.11*n[2]+.04*n[0]
 if material.startswith('ARGB_'):c=np.array([int(material[5+i:7+i],16) for i in [0,2,4]])
 elif material=='PROPOSED' or material.startswith('PROPOSED_'):c=np.array([76,162,202])
 elif material=='DATA':c=np.array([37,177,196])
 elif material=='FE':c=np.array([231,135,57])
 elif material in ['DISPLAY','OPENING']:c=np.array([50,68,77])
 elif material=='BRACKET':c=np.array([68,134,162])
 elif material=='COVER':c=np.array([96,179,212])
 elif material=='CABLE_DATA':c=np.array([57,175,190])
 elif material=='CABLE_FE':c=np.array([229,148,74])
 elif material=='KEEP_CLEAR':c=np.array([226,159,39])
 elif material=='FACTORY_SENSOR':c=np.array([199,144,56])
 elif material=='INSTRUMENT_STEEL':c=np.array([140,157,168])
 elif material=='INSTRUMENT_LEAD':c=np.array([92,102,109])
 elif material in ['SUPPORT','STEELWORK','CONCRETE','FIREPROOF']:c=np.array([163,178,185])
 elif 'GUARD' in material or 'ACCESS' in material:c=np.array([153,168,173])
 else:c=np.array([198,208,214])
 return np.clip(c*shade,0,255).astype(np.uint8)

def render(polys,basis,bounds,filename,pixels=1100):
 if os.environ.get('DRAW_REFRESH') and ACTIVE not in REFRESH and filename.exists():return 0
 # Orthographic triangle z-buffer: no perspective distortion, average-depth painter sorting or hidden overlaps.
 basis=np.array(basis,float);low,high=bounds;span=high-low
 scale=pixels/max(span);iw=max(8,int(math.ceil(span[0]*scale)));ih=max(8,int(math.ceil(span[1]*scale)))
 image=np.full((ih,iw,3),255,np.uint8);depth=np.full((ih,iw),-np.inf);light=np.array([.4,.8,-.4]);triangles=0
 for v,m in polys:
  q=v@basis.T;q[:,:2]=(q[:,:2]-low)*scale;q[:,1]=ih-q[:,1]
  n=np.cross(v[1]-v[0],v[2]-v[0]);length=np.linalg.norm(n)
  if length<1e-10:continue
  colour=palette(m,n/length)
  for j in range(1,len(v)-1):
   t=q[[0,j,j+1]];x0=max(0,math.floor(t[:,0].min()));x1=min(iw-1,math.ceil(t[:,0].max()));y0=max(0,math.floor(t[:,1].min()));y1=min(ih-1,math.ceil(t[:,1].max()))
   if x1<x0 or y1<y0:continue
   a,b,c=t;den=(b[1]-c[1])*(a[0]-c[0])+(c[0]-b[0])*(a[1]-c[1])
   if abs(den)<1e-8:continue
   yy,xx=np.ogrid[y0:y1+1,x0:x1+1];xx=xx+.5;yy=yy+.5
   u=((b[1]-c[1])*(xx-c[0])+(c[0]-b[0])*(yy-c[1]))/den
   w=((c[1]-a[1])*(xx-c[0])+(a[0]-c[0])*(yy-c[1]))/den;k=1-u-w
   z=u*a[2]+w*b[2]+k*c[2];region=depth[y0:y1+1,x0:x1+1];mask=(u>=-1e-7)&(w>=-1e-7)&(k>=-1e-7)&(z>=region-1e-7)
   region[mask]=z[mask];image[y0:y1+1,x0:x1+1][mask]=colour;triangles+=1
 Image.fromarray(image).save(filename)
 return triangles

def txt(c,x,y,s,size=9,colour=INK,font='Helvetica'):
 c.setFillColor(HexColor(colour));c.setFont(font,size);c.drawString(x,y,str(s))
def lines(c,x,y,text,width=58,size=8.5,leading=12):
 for line in textwrap.wrap(text,width):txt(c,x,y,line,size);y-=leading
 return y
def line(c,a,b,col=MUTE,w=.5,dash=None):
 c.setStrokeColor(HexColor(col));c.setLineWidth(w);c.setDash(dash or []);c.line(*a,*b);c.setDash([])
def tick(c,x,y):line(c,(x-2,y-2),(x+2,y+2),MUTE,.55)
def dim_h(c,x0,x1,y,from_y,label,col=MUTE):
 for x in [x0,x1]:line(c,(x,from_y),(x,y+3),col,.35);tick(c,x,y)
 line(c,(x0,y),(x1,y),col,.45);sw=pdfmetrics.stringWidth(label,'Helvetica',7.5)
 c.setFillColorRGB(1,1,1);c.rect((x0+x1-sw)/2-3,y-3,sw+6,10,fill=1,stroke=0);txt(c,(x0+x1-sw)/2,y-1,label,7.5,col)
def dim_v(c,x,y0,y1,from_x,label):
 for y in [y0,y1]:line(c,(from_x,y),(x+3,y),MUTE,.35);tick(c,x,y)
 line(c,(x,y0),(x,y1),MUTE,.45);c.saveState();c.translate(x-3,(y0+y1)/2);c.rotate(90);txt(c,-pdfmetrics.stringWidth(label,'Helvetica',7.5)/2,0,label,7.5,MUTE);c.restoreState()

FRONT=np.array([[1,0,0],[0,1,0],[0,0,-1]])
SIDE=np.array([[0,0,1],[0,1,0],[1,0,0]])
TOP=np.array([[1,0,0],[0,0,-1],[0,1,0]])
ISO=np.array([[.707107,0,-.707107],[-.408248,.816497,-.408248],[.57735,.57735,.57735]])
def camera_basis(direction):
 n=np.array(direction,float);n/=np.linalg.norm(n);r=np.cross([0,1,0],n);r/=np.linalg.norm(r);u=np.cross(n,r);return np.array([r,u,n])

def clipped(polys,lo,hi):
 out=[]
 for v,m in polys:
  if np.any(v.max(0)<lo) or np.any(v.min(0)>hi):continue
  p=list(v)
  for axis in range(3):
   for value,sign in [(lo[axis],1),(hi[axis],-1)]:
    new=[]
    for j,a in enumerate(p):
     b=p[(j+1)%len(p)];aa=(a[axis]-value)*sign;bb=(b[axis]-value)*sign
     if aa>=-1e-9:new.append(a)
     if (aa<0<bb) or (bb<0<aa):new.append(a+(b-a)*(value-a[axis])/(b[axis]-a[axis]))
    p=new
    if not p:break
   if not p:break
  if len(p)>2:out.append((np.array(p),m))
 return out

def cable_polys(id):
 records=json.loads((ROOT/'cable-meshes.json').read_text())[id];out=[]
 for r in records:
  for q in r['polygons']:out.append((np.array(q['v']).reshape(-1,3),'CABLE_DATA' if r['medium']=='DATA' else 'CABLE_FE'))
 return out

def context(c,d,p,polys,i,routes):
 allcables=cable_polys(d['id']);plo,phi=bbox(p['extra']+allcables)
 lo=plo-np.array([.65,.15,.65]);hi=phi+np.array([.65,.4,.65]);lo[1]=min(0.,lo[1])
 guide=[]
 if d['id']=='VERTICAL_HEATER':
  near=[z for z in d.get('accessClearance',[]) if z['min'][1]<p['high'][1] and np.linalg.norm(np.maximum(np.maximum(np.array(z['min'])-p['high'],p['low']-np.array(z['max'])),0)[[0,2]])<3]
  for z in near:
   zl=np.array(z['min']);zh=np.array(z['max']);zl[1]=max(0,zl[1]);zh[1]=min(3.1,zh[1]);lo[[0,2]]=np.minimum(lo[[0,2]],zl[[0,2]]-.05);hi[[0,2]]=np.maximum(hi[[0,2]],zh[[0,2]]+.05)
   corners=np.array([[a,b,c] for a in [zl[0],zh[0]] for b in [zl[1],zh[1]] for c in [zl[2],zh[2]]])
   for a in range(8):
    for bit in [1,2,4]:
     b=a^bit
     if b>a:
      delta=corners[b]-corners[a];length=np.linalg.norm(delta);guide+=cylinder(corners[a],delta/length,.006,0,length,'KEEP_CLEAR',4)
 native=clipped(polys,lo,hi);whole=native+p['extra']+allcables
 whole+=guide
 direction=np.array([1,.8,-1]) if p['front']==2 else np.array([1,.8,1])
 if p['front']==5:direction=np.array([1,.8,-.65])
 if p['front']==4:direction=np.array([-1,.8,-.65])
 basis=camera_basis(direction);points=np.concatenate([v for v,m in whole])@basis.T;span=np.ptp(points[:,:2],0)
 sc=min(320/span[0],225/span[1]);denom=2834.6457/sc
 origin,sc=drawview(c,whole,basis,(455,99,338,245),sc,VIEWS/f'{i:02d}-mount-route.png',f'MOUNT + CABLE LAYOUT  |  1:{denom:.1f}',False)
 for r in routes:
  end=np.array(r['end']);at=origin+(end@basis.T)[:2]*sc
  colour=DATA if r['medium']=='DATA' else POWER
  c.setFillColor(HexColor(colour));c.circle(*at,2.2,fill=1,stroke=0)
  txt(c,at[0]+5,at[1]-2,r['label']+' >',8.5,colour,'Helvetica-Bold')
 txt(c,455,74,'D > DATA network / controller / DCS     P > standard FE supply',7.5,MUTE)
 txt(c,455,62,'Amber wire: ladder/player keep-clear guide.' if guide else 'Context clipped; blue casting/support revisions are proposals.',7.5,MUTE)

def drawview(c,polys,basis,rect,scale,path,title,dims=True,cab=None,centremark=True,fitpoints=None):
 x,y,w,h=rect;points=np.concatenate([v for v,m in polys]);q=points@basis.T;lo=q[:,:2].min(0);hi=q[:,:2].max(0)
 fit=q[:,:2]
 if fitpoints is not None and len(fitpoints):fit=np.concatenate([fit,(np.array(fitpoints)@basis.T)[:,:2]])
 if dims and cab:fit=np.concatenate([fit,np.array([(pt+.5*n)@basis.T for medium,pt,n in cab['ports']])[:,:2]])
 flo,fhi=fit.min(0),fit.max(0);bounds=(flo-.015*(fhi-flo),fhi+.015*(fhi-flo));render(polys,basis,bounds,path,1400 if 'detail' in path.name else 1200)
 blo,bhi=bounds;size=(bhi-blo)*scale;ox=x+(w-size[0])/2;oy=y+(h-size[1])/2
 c.drawImage(str(path),ox,oy,width=size[0],height=size[1],mask='auto')
 origin=np.array([ox,oy])-blo*scale
 txt(c,x,y+h+14,title,9,INK,'Helvetica-Bold')
 if dims:
  ll=origin+lo*scale;hh=origin+hi*scale
  dim_h(c,ll[0],hh[0],ll[1]-14,ll[1],f'{hi[0]-lo[0]:.3f} m')
  dim_v(c,ll[0]-16,ll[1],hh[1],ll[0],f'{hi[1]-lo[1]:.3f} m')
 if cab:
  cp=(cab['low']+cab['high'])/2@basis.T
  at=origin+cp[:2]*scale
  c.setStrokeColor(HexColor(BLUE));c.setLineWidth(.8);c.circle(*at,max(7,min(14,max(cab['dims'])*scale*.8)))
  edge=np.array([x+w-16,y+16]);line(c,at,edge,BLUE,.65);txt(c,edge[0]-30,edge[1]+4,'C1',8,BLUE,'Helvetica-Bold')
  if dims:
   for medium,pt,normal in cab['ports']:
    a=origin+(pt@basis.T)[:2]*scale;b=origin+((pt+.5*normal)@basis.T)[:2]*scale
    line(c,a,b,DATA if medium=='DATA' else POWER,.65,[3,2])
 return origin,scale

def titleblock(c,index,title,subtitle):
 c.setFillColor(HexColor(INK));c.rect(0,H-62,W,62,fill=1,stroke=0)
 txt(c,28,H-24,'IChemE  /  EQUIPMENT CONTROL INTERFACES',11,'#a8d8ec','Helvetica-Bold')
 txt(c,28,H-47,title,20,'#ffffff','Helvetica-Bold')
 txt(c,W-186,H-24,f'REVIEW DRAWING  {index:02d}',10,'#ffffff','Helvetica-Bold')
 txt(c,W-186,H-43,'REV J  |  c15dfcecc  |  2026-11-08',8,'#cedde4')
 txt(c,28,H-80,subtitle,8.5)
 line(c,(26,42),(W-26,42),'#a9bac3',.6)
 txt(c,28,25,'PROPOSAL ONLY  |  Exact native geometry + dimensioned proposed interfaces  |  1 block = 1 m',8,MUTE)
 txt(c,W-135,25,f'SHEET {index:02d} / {TOTAL:02d}',8,MUTE)

def details(c,d,p,i,polys):
 x,y=858,724;txt(c,x,y,'C1  /  TERMINAL CONNECTION',11,BLUE,'Helvetica-Bold');y-=21
 txt(c,x,y,'MATCHED TERMINAL PAIR' if p['mode']=='SPLIT' else p['mode']+' EXISTING HOUSING' if p['mode'] in ['REUSE','ENLARGE'] else p['mode']+' INTERFACE',9,BLUE,'Helvetica-Bold');y-=17
 y=lines(c,x,y,p['mount'],51,8.7,12)-8
 dims=p['dims'];txt(c,x,y,'DATA housing X / Y / Z (m)' if p.get('enclosures') else 'Envelope X / Y / Z (m)',8,MUTE);y-=14
 txt(c,x,y,' / '.join(f'{v:.3f}' for v in dims),11,INK,'Helvetica-Bold');y-=18
 txt(c,x,y,'Interface lower corner X / Y / Z (m)',8,MUTE);y-=14
 txt(c,x,y,' / '.join(f'{v:.3f}' for v in p['low']),9);y-=18
 # Opposite entries cannot both be seen from one camera. Give each its own exact view.
 dlo=p['low']-.18;dhi=p['high']+.18
 if d['kind']=='SPHERE':
  foot=np.array(d['supportLegs'][0]);r=d['legRadius'];dlo=np.minimum(dlo,[foot[0]-r-.05,1.2,foot[2]-r-.05]);dhi=np.maximum(dhi,[foot[0]+r+.05,1.8,foot[2]+r+.05])
 detail_polys=p['extra']+clipped(polys,dlo,dhi);frontnormal=np.array([1,0,0]) if p['front']==5 else np.array([-1,0,0]) if p['front']==4 else np.array([0,0,-1 if p['front']==2 else 1])
 detail_specs=[]
 for k,(medium,pt,n) in enumerate(p['ports']):
  if p.get('enclosures'):
   e=next(e for e in p['enclosures'] if e['medium']==medium);detail_polys=clipped(p['extra']+polys,np.array(e['low'])-.18,np.array(e['high'])+.18)
  direction=frontnormal+n+np.array([0,.8,0])
  if p.get('enclosures'):direction=2*n+np.array([0,.8,0])+.5*np.cross([0,1,0],n)
  if np.linalg.norm(direction[[0,2]])<.1:direction=n+np.array([.7,.8,0])
  basis=camera_basis(direction);project=np.concatenate([v for v,m in detail_polys])@basis.T;span=np.ptp(project[:,:2],0)
  rw=125 if len(p['ports'])==2 else 250;scale=min(2834.6457/10,(rw-8)/span[0],125/span[1]);detail_specs.append((medium,basis,scale,rw,detail_polys))
 common_scale=min(s[2] for s in detail_specs)
 for k,(medium,basis,scale,rw,detail_polys) in enumerate(detail_specs):
  rect=(x+12+k*145,y-161,rw,143)
  drawview(c,detail_polys,basis,rect,common_scale,VIEWS/f'{i:02d}-detail-{medium.lower()}.png',f'{medium} SIDE  |  1:{2834.6457/common_scale:.1f}',False)
 y-=195
 txt(c,x,y,'PORT FACE CENTRES  /  metres',9,INK,'Helvetica-Bold');y-=18
 for name,pt,n in p['ports']:
  colour=DATA if name=='DATA' else POWER
  txt(c,x,y,f'{name}  '+(' -X' if n[0]<0 else '+X' if n[0]>0 else '-Z' if n[2]<0 else '+Z'),9,colour,'Helvetica-Bold');y-=13
  txt(c,x+8,y,'X '+f'{pt[0]:.3f}'+'   Y '+f'{pt[1]:.3f}'+'   Z '+f'{pt[2]:.3f}',8.5);y-=16
 txt(c,x,y,f'Cable OD: 0.125 m. Gland envelope OD: {p.get("glandOD",.22):.3f} m.',8,MUTE);y-=14
 txt(c,x,y,'Face centres are gland datums; cable mouth is +0.055 m.',8,MUTE);y-=20
 txt(c,x,y,'D / P routes shown in the enlarged mount view.',8,MUTE);y-=16
 y=lines(c,x,y,p['policy'],52,8.5,12)-8
 if p['anchor'] is not None:
  y=lines(c,x,y,'Mechanical attachment: '+p['contact']+'. The bracket connects to a native surface.',52,8.5,12)-8
 if p['mode'] not in ['HOUSING','CAST_POSITIONER','REUSE','INTEGRAL']:
  y=lines(c,x,y,'Plain sealed terminal cover. Required entries only; DATA and FE use separate faces.',52,8.5,12)-8
 for note in p['notes']:y=lines(c,x,y,note,52,8.3,11)-7
 if d['kind']=='COLUMN':y=lines(c,x,y,'Tower only. Condenser, drum, reboiler and other member equipment use their own sheets.',52,8.3,11)-7
 if d['kind']=='EXCHANGER':y=lines(c,x,y,'Standard and Crossflow share the cylindrical shell; shell-nozzle locations differ.',52,8.3,11)-7
 assert y>48,(d['id'],y)

def parameters(d):
 p=d['parameters'];id=d['id']
 if 'line_bore' in p and not id.endswith('SENSOR_HOUSING'):return 'Reference line: 0.150 m bore  |  Pipe axis +X  |  Housing and process geometry from native line model'
 if id.endswith('SENSOR_HOUSING'):return 'Native transmitter mesh in local coordinates  |  All dimensions in metres  |  Process probe excluded from housing detail'
 if d['kind'] in ['MOTOR','GENERATOR']:return f'Native reference frame: {p["power_kw"]:.0f} kW  |  Heading NORTH  |  Support {p["support"]} m  |  Rotating geometry at rest'
 a,b=p['size'],p['length'];kind=d['kind']
 if kind=='DRUM':body=f'Shell diameter {a:g} m  |  Tangent-to-tangent length {b:g} m'
 elif kind=='COLUMN':body=f'Shell diameter {a:g} m  |  Tray stack {b:g} m  |  32 trays'
 elif kind=='TANK':body=f'Shell diameter {a:g} m  |  Shell height {b:g} m  |  Liquid depth at 50% of fill height'
 elif kind=='SPHERE':body=f'Sphere diameter {a:g} m'
 elif kind=='EXCHANGER':body=f'Shell diameter {a:g} m  |  Tube length {b:g} m'
 elif kind=='KETTLE':body=f'Bundle diameter {a:g} m  |  Tube length {b:g} m'
 elif kind=='AIR_COOLER':body=f'Bay width {a:g} m  |  Bay length {b:g} m  |  Single bay'
 elif kind=='PUMP':body=f'Discharge nominal bore {a:g} m  |  Native duty-derived casing'
 elif kind=='COMPRESSOR':body=f'Cylinder bore {a:g} m  |  {b:g} throws'
 elif kind=='CENTRIFUGAL_COMPRESSOR':body=f'Impeller diameter {a:g} m  |  {b:g} impellers'
 elif kind=='STEAM_TURBINE':body=f'Exhaust-end casing diameter {a:g} m  |  Native duty-derived turbine train'
 elif kind=='VERTICAL_HEATER':body=f'Radiant diameter {a:g} m  |  Radiant height {b:g} m'
 elif kind=='CABIN_HEATER':body=f'Radiant width {a:g} m  |  Total radiant length {b:g} m'
 else:body=f'Constructor size {a:g} m  |  Secondary size parameter {b:g}'
 return body+f'  |  Support {p["support"]} m  |  Heading NORTH'

def cover(c):
 titleblock(c,0,'Model-matched engineering drawing set','Review basis: native full-detail meshes from the current branch, with proposed enclosure and service-port geometry overlaid.')
 txt(c,40,718,'Exact model geometry. Proposed interfaces.',27,INK,'Helvetica-Bold')
 y=679
 for t in ['Native pressure bodies, process nozzles and shaft datums are retained. Blue housing and support revisions are dimensioned proposals.',
           'Each sheet identifies its reference size. These are representative native layouts, not a drawing of every possible player size.',
           'Main orthographic views use the same printed scale per sheet. Cabinet details have their own stated scale.',
           'Grey = existing geometry. Blue = proposed or reused hardware, castings and supports. Cyan = DATA. Orange = FE.',
           'Port coordinates use the native structure origin. X east, Y up, Z south; heading NORTH faces -Z.',
           'Drawings propose layout only. Placement, collision, routing and operating behaviour have not been changed.']:
  y=lines(c,40,y,t,113,10,15)-10
 txt(c,40,486,'INTERFACE RULES',11,BLUE,'Helvetica-Bold')
 y=463
 for t in ['Local controller stays with equipment. DCS supervises and changes supported settings through the data network.',
           'Column, exchanger, kettle, air cooler, fired heaters and both cooling-tower types expose built-in channels. Both tower level gauges are factory instruments; no manual tower mounts.',
           'Rotating units use internal probes; fluid units expose inlet/outlet P/T and flow via C1. Passive drums/tanks/spheres and ordinary process pipes retain appropriate add-on instruments.',
           'Small electric frames and the turbine reuse their housings. Large electric frames use matched compact terminal pairs; inline units use integrated castings.',
           'Shaft trains use one DATA service side based on power flow. Opposed driver/load headings keep it on the same world side; FE stays opposite.',
           'Terminal covers have no display zone. Support fixes, clamp bands and cable routes are shown in enlarged native context.']:
  y=lines(c,40,y,t,113,9.5,14)-7
 txt(c,40,220,'SCOPE',11,BLUE,'Helvetica-Bold');y=200
 for t in ['31 equipment/hardware sheets, two pump-motor examples, nine enlarged instrument arrangements, an applicability register, two research/basis sheets and rotating, thermal and column controller-channel schedules.',
           'Horizontal crosswise drums are the same native layout rotated. Air-cooler and induced-tower banks repeat the same service interface at the designated master module.',
           'D > DATA network / controller / DCS. P > standard FE supply. Cable bends use ConduitGeometry.path / LineGeometry; spines are design proposals, not a placed-world routing result.',
           'No current data/FE host interface: '+', '.join(e['type'].replace('_',' ').lower() for e in MAN['excluded'])+'. These drawing-only/shaft-only types are not assigned invented ports.']:
  y=lines(c,40,y,t,128,8.2,11)-6
 assert y>48,y
 c.showPage()

def prepare_routes():
 import revised_layouts,assembly_drawing,large_train_drawing
 routes={}
 for e in MAN['entries']:
  d=json.loads((ROOT/'geometry'/e['file']).read_text());p=proposal(d,raw(d));routes[d['id']]=revised_layouts.cable_routes(d,p)
 _,routes['PUMP_MOTOR_LINK'],_=assembly_drawing.build(__import__(__name__))
 _,routes['LARGE_TRAIN_LINK'],_=large_train_drawing.build(__import__(__name__))
 (ROOT/'cable-routes.json').write_text(json.dumps(routes))
 print('Prepared',len(routes),'native-mesher drawing inputs',flush=True)

def assembly_page(c):
 global ACTIVE
 import assembly_drawing,sys
 ACTIVE='PUMP_MOTOR_LINK';polys,routes,labels=assembly_drawing.build(sys.modules[__name__]);polys+=cable_polys(ACTIVE)
 i=len(MAN['entries'])+1;title='Pump / motor - DATA and FE connection layout'
 titleblock(c,i,title,'Native OH2 pump + 250 kW motor  |  Motor faces SOUTH  |  One shaft block between z=3 and z=4  |  Reference assembly')
 basis=camera_basis([1,1.3,-1.1]);v=np.concatenate([v for v,m in polys])@basis.T;span=np.ptp(v[:,:2],0);sc=min(740/span[0],560/span[1])
 origin,sc=drawview(c,polys,basis,(30,100,760,580),sc,VIEWS/'32-assembly-iso.png',f'INTERCONNECTED ARRANGEMENT  |  1:{2834.6457/sc:.1f}',False)
 for label,point in labels:
  at=origin+(point@basis.T)[:2]*sc;txt(c,at[0]-25,at[1]+10,label,8.3,INK,'Helvetica-Bold')
 x,y=840,722;txt(c,x,y,'CONNECTIONS',11,BLUE,'Helvetica-Bold');y-=28
 for t in ['D1: Pump DATA -> shared data trunk -> DCS.',
           'D2: Motor DATA -> shared data trunk. Pump demand and drive control use the data link.',
           'D3: Local controller -> shared data trunk. DCS supervises the connected controller.',
           'P1: Creative RF source -> motor FE input, on the opposite motor side from DATA.',
           'The mechanical shaft transfers power from motor to pump. It is separate from DATA/FE cables.',
           'No cable crosses the coupling guard or the shaft approach. The compact pump terminal sits on the drive-end base deck, within the plinth outline.']:
  y=lines(c,x,y,t,49,9,14)-14
 txt(c,x,y-8,'TOP / CABLE CONNECTION PLAN',9,BLUE,'Helvetica-Bold')
 v=np.concatenate([v for v,m in polys])@TOP.T;span=np.ptp(v[:,:2],0);ss=min(290/span[0],205/span[1]);drawview(c,polys,TOP,(848,78,290,205),ss,VIEWS/'32-assembly-top.png',f'1:{2834.6457/ss:.1f}',False)
 c.bookmarkPage(ACTIVE);c.addOutlineEntry(title,ACTIVE,level=0);c.showPage()
 return dict(id=ACTIVE,title=title,sheet=i,mode='INTERCONNECT',nativeFacets=len(polys),ports=[],routes=routes,componentOrigins={'pump':[0,0,0],'motor':[0,0,4],'motorHeading':'SOUTH','localController':[4,0,5],'DCS':[6,0,5],'creativeRF':[-2,0,5]},nativeEnvelope=[a.tolist() for a in bbox(polys)])

def large_train_page(c):
 global ACTIVE
 import large_train_drawing,sys
 ACTIVE='LARGE_TRAIN_LINK';polys,routes,labels=large_train_drawing.build(sys.modules[__name__]);polys+=cable_polys(ACTIVE)
 i=len(MAN['entries'])+2;title='Large motor / pump - one DATA service side'
 titleblock(c,i,title,'Native BB2 pump + large motor  |  Opposed shaft faces  |  One shaft block between z=4 and z=5  |  Exact reference geometry')
 basis=camera_basis([1,1.2,-1.1]);v=np.concatenate([v for v,m in polys])@basis.T;span=np.ptp(v[:,:2],0);sc=min(730/span[0],565/span[1])
 origin,sc=drawview(c,polys,basis,(35,95,750,590),sc,VIEWS/'33-large-train-iso.png',f'CONNECTED LARGE FRAME  |  1:{2834.6457/sc:.1f}',False)
 for label,point in labels:
  at=origin+(point@basis.T)[:2]*sc;txt(c,at[0]-20,at[1]+12,label,9,INK,'Helvetica-Bold')
 x,y=840,724;txt(c,x,y,'CONNECTIONS',11,BLUE,'Helvetica-Bold');y-=28
 for t in ['Both DATA entries face +X in this connected reference. The cable stays beside the shaft train.',
           'The pump faces NORTH; the motor faces SOUTH so their shaft faces oppose each other.',
           'The shared service frame follows mechanical power flow. It applies to small/large motors, pumps, generators and the steam turbine in every horizontal heading.',
           'Motor / turbine: DATA on the driver service side. Pump / generator: DATA on the matching opposed-load side. FE is opposite where present.',
           'Existing shaft and process ports retain their native positions. Both boxes remain attached to native support or casing surfaces.']:
  y=lines(c,x,y,t,48,9,14)-15
 txt(c,x,y-5,'TOP / DIRECT DATA CONNECTION',9,BLUE,'Helvetica-Bold')
 v=np.concatenate([v for v,m in polys])@TOP.T;span=np.ptp(v[:,:2],0);ss=min(285/span[0],220/span[1]);drawview(c,polys,TOP,(850,65,285,220),ss,VIEWS/'33-large-train-top.png',f'1:{2834.6457/ss:.1f}',False)
 c.bookmarkPage(ACTIVE);c.addOutlineEntry(title,ACTIVE,level=0);c.showPage()
 return dict(id=ACTIVE,title=title,sheet=i,mode='INTERCONNECT',nativeFacets=len(polys),ports=[],routes=routes,componentOrigins={'pump':[0,0,0],'motor':[0,0,5],'motorHeading':'SOUTH'},dataOutward=[1,0,0],assembledEnvelope=[a.tolist() for a in bbox(polys)])

def main():
 global ACTIVE
 import revised_layouts
 pdf=OUT/'equipment-control-interfaces.pdf';c=canvas.Canvas(str(pdf),pagesize=(W,H));c.setTitle('IChemE model-matched equipment control interfaces');c.setAuthor('IChemE design review');cover(c)
 records=[]
 import sensor_layouts,sensor_pages
 for i,e in enumerate(MAN['entries'],1):
  if e['id'] in ['SENSOR_HOUSING','LEVEL_SENSOR_HOUSING']:
   records.append(sensor_pages.hardware_page(c,__import__(__name__),e['id'],i));continue
  d=json.loads((ROOT/'geometry'/e['file']).read_text());ACTIVE=d['id'];polys=raw(d);p=proposal(d,polys);instruments=sensor_layouts.plan(d,polys,__import__(__name__));sensor_polys=sensor_layouts.geometry(instruments,__import__(__name__));allpolys=polys+p['extra']+sensor_polys;lo,hi=bbox(allpolys)
  titleblock(c,i,TITLES[d['id']],parameters(d));line(c,(837,55),(837,744),'#bbcbd2',.6)
  txt(c,28,H-94,'MANUAL: '+sensor_layouts.permitted(instruments)+'  |  '+instruments['policy'],7.2,MUTE)
  # Fit all three orthographic views at one conventional drawing scale.
  reserved=sensor_pages.reservation_vertices(instruments);fitpoints=np.concatenate([np.concatenate([v for v,m in allpolys]),np.array([pt+.5*n for medium,pt,n in p['ports']]),reserved]);span=np.ptp(fitpoints,0)
  smax=min(315/span[0],250/span[1],315/span[2],245/span[2]);ratio=2834.6457/smax
  choices=[5,10,15,20,25,30,40,50,75,100,125,150,200,250,300,400,500,750,1000,1500,2000]
  denom=next(n for n in choices if n>=ratio);scale=2834.6457/denom
  txt(c,40,735,f'ORTHOGRAPHIC SCALE 1:{denom}  |  model envelope incl. proposed interfaces',8,MUTE)
  for basis,rect,path,title in [(FRONT,(50,434,338,260),f'{i:02d}-front.png','FRONT / FROM -Z  |  X right, Y up'),(SIDE,(455,434,338,260),f'{i:02d}-side.png','SIDE / FROM +X  |  Z right, Y up'),(TOP,(50,99,338,255),f'{i:02d}-top.png','TOP / FROM +Y  |  X right, -Z up')]:
   origin,sc=drawview(c,allpolys,basis,rect,scale,VIEWS/path,title,cab=p,fitpoints=reserved);sensor_pages.mark(c,instruments,basis,origin,sc,rect,__import__(__name__))
  routes=revised_layouts.cable_routes(d,p);context(c,d,p,polys,i,routes)
  details(c,d,p,i,polys)
  c.bookmarkPage(d['id']);c.addOutlineEntry(TITLES[d['id']],d['id'],level=0);c.showPage()
  rec={k:v.tolist() if isinstance(v,np.ndarray) else v for k,v in p.items() if k not in ['extra','ports']}
  original=[(np.array(q['v']).reshape(-1,3),q['m']) for q in d['polygons']]
  rec.update(id=d['id'],title=TITLES[d['id']],sheet=i,scale=denom,ports=[dict(medium=n,centre=q.tolist(),outward=v.tolist()) for n,q,v in p['ports']],nativeFacets=len(original),nativeEnvelope=[a.tolist() for a in bbox(original)],proposedEquipmentEnvelope=[a.tolist() for a in bbox(polys)],assembledEnvelope=[a.tolist() for a in bbox(allpolys)])
  for feature in ['compactBase','integralMotor','nativeTerminalBounds','replacedTerminalBounds']: 
   if feature in d:rec[feature]=d[feature]
  rec['routes']=routes;rec['instruments']=instruments;records.append(rec);print(f'{i:02d} {d["id"]} 1:{denom} {len(polys)} native facets',flush=True)
 records.append(assembly_page(c))
 records.append(large_train_page(c))
 records.extend(sensor_pages.pages(c,__import__(__name__)))
 c.save();(OUT/'measurements.json').write_text(json.dumps(dict(snapshot=MAN['snapshot'],units='m',sheets=records),indent=2))
 print(f'WROTE {pdf}',flush=True)
if __name__=='__main__':
 import sys
 if '--prepare-instruments' in sys.argv:
  import sensor_layouts
  sensor_layouts.prepare(__import__(__name__))
 elif '--prepare-routes' in sys.argv:prepare_routes()
 else:main()
