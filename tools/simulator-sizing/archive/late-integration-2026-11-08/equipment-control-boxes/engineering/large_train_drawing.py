"""Native large-frame opposed pump/motor drawing; DATA connects along one side."""
import json,numpy as np
from train_interfaces import opposed
import sensor_layouts

def build(k):
 def read(id):return json.loads((k.ROOT/'geometry'/f'{id}.json').read_text())
 pd=read('PUMP_BB2');md=read('MOTOR_LARGE');pp=k.proposal(pd,k.raw(pd));mp=k.proposal(md,k.raw(md))
 polys=k.raw(pd)+pp['extra']+sensor_layouts.geometry(sensor_layouts.plan(pd,k.raw(pd),k),k)+[(opposed(v,3,9),m) for v,m in k.raw(md)+mp['extra']]
 for q in json.loads((k.ROOT/'shaft-block.json').read_text()):polys.append((np.array(q['v']).reshape(-1,3)+[1,1,4],q['m']))
 p=next(q for m,q,n in pp['ports'] if m=='DATA');m=opposed(next(q for medium,q,n in mp['ports'] if medium=='DATA'),3,9);x=3.1
 points=[(p+[.025,0,0]).tolist(),[x,p[1],p[2]],[x,m[1],p[2]],[x,m[1],m[2]],(m+[.025,0,0]).tolist()]
 routes=[dict(medium='DATA',label='DATA',points=points)]
 labels=[('BB2 pump',np.array([1.5,2.9,1.5])),('Large motor',np.array([1.5,3.0,7.0])),('Shared DATA side',np.array([3.2,1.65,5.2]))]
 return polys,routes,labels
