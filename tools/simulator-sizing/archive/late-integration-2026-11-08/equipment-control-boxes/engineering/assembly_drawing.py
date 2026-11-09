"""One model-true pump/motor reference arrangement with DATA and FE connection routes."""
import json,numpy as np
from train_interfaces import opposed
import sensor_layouts
def build(k):
 def read(id):return json.loads((k.ROOT/'geometry'/f'{id}.json').read_text())
 pd=read('PUMP_OH2');md=read('MOTOR_SMALL');pp=k.proposal(pd,k.raw(pd));mp=k.proposal(md,k.raw(md))
 pump=k.raw(pd)+pp['extra']+sensor_layouts.geometry(sensor_layouts.plan(pd,k.raw(pd),k),k);motor=[]
 # One shaft block between opposed equipment faces at z=3 and z=4. Origins stay on the block grid.
 def move(v):
  return opposed(v,3,7)
 for v,m in k.raw(md)+mp['extra']:motor.append((move(v),m))
 polys=pump+motor
 if (k.ROOT/'shaft-block.json').exists():
  for q in json.loads((k.ROOT/'shaft-block.json').read_text()):polys.append((np.array(q['v']).reshape(-1,3)+[1,1,3],q['m']))
 asset=k.ROOT.parents[2]/'src/main/resources/assets/icheme/models/block'
 def block(name,at):
  data=json.loads((asset/(name+'.json')).read_text());out=[];offset=np.array(at)
  if 'elements' not in data:return k.box(offset,offset+1,'EXTERNAL')
  for elem in data['elements']:
   material='EXTERNAL'
   if 'display' in elem.get('name','') or 'monitor bezel' in elem.get('name',''):material='DISPLAY'
   out+=k.box(np.array(elem['from'])/16+offset,np.array(elem['to'])/16+offset,material)
  return out
 polys+=block('local_controller',[4,0,5])+block('dcs_complete',[6,0,5])+block('creative_power',[-2,0,5])
 pumpdata=pp['ports'][0][1];mdat=next(q for m,q,n in mp['ports'] if m=='DATA').copy();mpow=next(q for m,q,n in mp['ports'] if m=='FE').copy()
 mdat=move(mdat);mpow=move(mpow)
 lc=np.array([4.5,.5,5.8175]);dcs=np.array([6.5,.5,5.88]);rf=np.array([-.98,.5,5.5])
 for point,n,medium in [(lc,[0,0,1],'DATA'),(dcs,[0,0,1],'DATA'),(rf,[1,0,0],'FE')]:polys+=k.gland(point,np.array(n),medium)
 routes=[
  dict(medium='DATA',label='D1',points=[(pumpdata+[.025,0,0]).tolist(),[3.5,pumpdata[1],pumpdata[2]],[3.5,2.5,pumpdata[2]],[3.5,2.5,7],[3.5,.5,7],[6.5,.5,7],(dcs+[0,0,.025]).tolist()]),
  dict(medium='DATA',label='D2',points=[[3.5,2.5,mdat[2]],[3.5,mdat[1],mdat[2]],(mdat+[.025,0,0]).tolist()]),
  dict(medium='DATA',label='D3',points=[[4.5,.5,7],(lc+[0,0,.025]).tolist()]),
  dict(medium='FE',label='P1',points=[(rf+[.025,0,0]).tolist(),[-.25,.5,5.5],[-.25,mpow[1],5.5],[-.25,mpow[1],mpow[2]],(mpow+[-.025,0,0]).tolist()])]
 labels=[('Pump / DATA demand',np.array([2.0,2.7,1.7])),('Motor / 3000 rpm max',np.array([1.5,2.8,6.])),('RF source / all 6 faces',np.array([-1.5,1.3,5.5])),('Local controller',np.array([4.5,1.3,5.5])),('DCS',np.array([6.5,2.3,5.5]))]
 return polys,routes,labels
