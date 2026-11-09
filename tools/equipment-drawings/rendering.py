"""Shared orthographic drawing helpers promoted from the equipment review renderer.

Native meshes are supplied by ShapeRasterizer. This module changes no geometry.
Materials use neutral engineering colours; game textures and lighting need their
separate asset/client checks.
"""
import math,textwrap
import numpy as np
from PIL import Image
from reportlab.lib.colors import HexColor
from reportlab.pdfbase import pdfmetrics
INK='#233b49'; MUTE='#637783'; BLUE='#137baf'; DATA='#1499b3'; POWER='#c96b24'
def bbox(polys):
 v=np.concatenate([q[0] for q in polys]);return v.min(0),v.max(0)

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
 elif material=='INSTRUMENT_CONDUIT':c=np.array([108,119,125])
 elif material=='SERVICE_COVER':c=np.array([170,180,185])
 elif material=='SERVICE_CASE':c=np.array([145,157,165])
 elif material=='INSTRUMENT_LEAD':c=np.array([92,102,109])
 elif material in ['COPPER','COPPER_LINE']:c=np.array([196,122,82])
 elif material in ['CARBON_STEEL','CARBON_STEEL_LINE']:c=np.array([104,108,114])
 elif material in ['STAINLESS','STAINLESS_LINE']:c=np.array([176,181,186])
 elif material=='BRICK':c=np.array([150,84,66])
 elif material=='IRON':c=np.array([78,80,84])
 elif material in ['SUPPORT','STEELWORK','CONCRETE','FIREPROOF']:c=np.array([163,178,185])
 elif 'GUARD' in material or 'ACCESS' in material:c=np.array([153,168,173])
 else:c=np.array([198,208,214])
 return np.clip(c*shade,0,255).astype(np.uint8)

def render(polys,basis,bounds,filename,pixels=1100):
 filename.parent.mkdir(parents=True,exist_ok=True)
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

def camera_basis(direction):
 n=np.array(direction,float);n/=np.linalg.norm(n);r=np.cross([0,1,0],n);r/=np.linalg.norm(r);u=np.cross(n,r);return np.array([r,u,n])

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
