# Minimal deterministic pixel writer for this native asset generator; no third-party runtime required.
import math, struct, zlib
class Bitmap:
 def __init__(self,mode,size,colour=None):
  self.w,self.h=size;self.channels=4 if mode=='RGBA' else 3;self.p=[tuple(colour or (0,)*self.channels)]*(self.w*self.h)
 def load(self):return self
 def __setitem__(self,p,c):
  x,y=p
  if 0<=x<self.w and 0<=y<self.h:self.p[y*self.w+x]=tuple(c)
 def save(self,path):
  def chunk(t,d):return struct.pack('>I',len(d))+t+d+struct.pack('>I',zlib.crc32(t+d)&0xffffffff)
  raw=b''.join(b'\0'+bytes(c for p in self.p[y*self.w:(y+1)*self.w] for c in p) for y in range(self.h))
  path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',self.w,self.h,8,6 if self.channels==4 else 2,0,0,0))+chunk(b'IDAT',zlib.compress(raw,9))+chunk(b'IEND',b''))
class Image:
 @staticmethod
 def new(mode,size,colour=None):return Bitmap(mode,size,colour)
class Painter:
 def __init__(self,im):self.im=im
 def colour(self,c):return tuple(c)+(255,) if len(c)==3 and self.im.channels==4 else c
 def rectangle(self,box,fill=None,outline=None,width=1):
  x0,y0,x1,y1=box
  for y in range(math.floor(y0),math.ceil(y1)+1):
   for x in range(math.floor(x0),math.ceil(x1)+1):
    c=outline if outline and min(x-x0,x1-x,y-y0,y1-y)<width else fill
    if c is not None:self.im[x,y]=self.colour(c)
 def ellipse(self,box,fill=None,outline=None,width=1):
  x0,y0,x1,y1=box;cx=(x0+x1)/2;cy=(y0+y1)/2;rx=(x1-x0)/2;ry=(y1-y0)/2
  for y in range(math.floor(y0),math.ceil(y1)+1):
   for x in range(math.floor(x0),math.ceil(x1)+1):
    r=((x-cx)/rx)**2+((y-cy)/ry)**2
    if r>1:continue
    edge=rx<=width or ry<=width or ((x-cx)/max(.01,rx-width))**2+((y-cy)/max(.01,ry-width))**2>=1
    c=outline if outline and edge else fill
    if c is not None:self.im[x,y]=self.colour(c)
 def line(self,points,fill,width=1):
  for i in range(0,len(points)-2,2):
   x0,y0,x1,y1=points[i:i+4];n=max(1,math.ceil(max(abs(x1-x0),abs(y1-y0))))
   for k in range(n+1):
    x=round(x0+(x1-x0)*k/n);y=round(y0+(y1-y0)*k/n);self.rectangle((x-width//2,y-width//2,x+(width-1)//2,y+(width-1)//2),fill=fill)
class ImageDraw:
 @staticmethod
 def Draw(im):return Painter(im)
