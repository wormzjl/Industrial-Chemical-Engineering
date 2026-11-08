"""Native large-frame opposed pump/motor drawing; DATA connects along one side."""
import json,numpy as np
from train_interfaces import opposed
import sensor_layouts
import instrument_wiring

def build(k,instruments=True):
 def read(id):return json.loads((k.ROOT/'geometry'/f'{id}.json').read_text())
 pd=read('PUMP_BB2');md=read('MOTOR_LARGE');pp=k.proposal(pd,k.raw(pd));mp=k.proposal(md,k.raw(md))
 pump=k.raw(pd)+pp['extra']+(sensor_layouts.equipment_visuals(pd,k.raw(pd),pp,sensor_layouts.plan(pd,k.raw(pd),k),k) if instruments else [])
 motor=k.raw(md)+mp['extra']+(sensor_layouts.equipment_visuals(md,k.raw(md),mp,sensor_layouts.plan(md,k.raw(md),k),k) if instruments else [])
 polys=pump+instrument_wiring.transform(motor,lambda v:opposed(v,3,9),'large-train')
 if (k.ROOT/'shaft-block.json').exists():
  for q in json.loads((k.ROOT/'shaft-block.json').read_text()):polys.append((np.array(q['v']).reshape(-1,3)+[1,1,4],q['m']))
 p=next(q for m,q,n in pp['ports'] if m=='DATA');m=opposed(next(q for medium,q,n in mp['ports'] if medium=='DATA'),3,9);x=3.1
 points=[(p+[.025,0,0]).tolist(),[x,p[1],p[2]],[x,m[1],p[2]],[x,m[1],m[2]],(m+[.025,0,0]).tolist()]
 routes=[dict(medium='DATA',label='DATA',points=points)]
 labels=[('BB2 pump',np.array([1.5,2.9,1.5])),('Large motor',np.array([1.5,3.0,7.0])),('Shared DATA side',np.array([3.2,1.65,5.2]))]
 return polys,routes,labels
