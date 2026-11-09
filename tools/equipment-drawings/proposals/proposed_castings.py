"""Explicit revision-C geometry changes for review, without changing game source or port datums."""
import numpy as np

def material(m):return m.removeprefix('PROPOSED_')

def inline_motor_feature(polys):
 # Identify the native square junction nub as a complete six-face component, not as the motor envelope.
 candidates=[]
 for i,(v,m) in enumerate(polys):
  delta=np.ptp(v,0);nonzero=delta[delta>1e-8]
  if m=='MOTOR' and len(v)==4 and len(nonzero)==2 and .05<min(nonzero)<.2 and abs(nonzero[0]-nonzero[1])<1e-8:candidates.append(i)
 if len(candidates)!=6:raise ValueError(f'Expected complete native terminal nub, found {len(candidates)} faces')
 vertices=np.concatenate([polys[i][0] for i in candidates]);low,high=vertices.min(0),vertices.max(0)
 return set(candidates),(low+high)/2

def geometry(d,polys,k):
 id=d['id']
 if id in ['MOTOR_LARGE','GENERATOR_LARGE']:
  c=np.array(d['terminal']);indices={i for i,(v,m) in enumerate(polys) if m=='MOTOR_PLAIN' and np.max(abs(v-c))<.6 and np.count_nonzero(np.ptp(v,0)<1e-7)==1 and np.count_nonzero(np.ptp(v,0)>.08)>=2}
  assert len(indices)==6
  vertices=np.concatenate([polys[i][0] for i in indices]);lo,hi=vertices.min(0),vertices.max(0)
  removed=indices|{i for i,(v,m) in enumerate(polys) if m=='PIPE'}
  d['replacedTerminalBounds']=dict(min=lo.tolist(),max=hi.tolist());d['removedNativeTerminalFacets']=sorted(removed)
  return [(v,m) for i,(v,m) in enumerate(polys) if i not in removed]
 if id=='CENTRIFUGAL_COMPRESSOR':
  cap=(1.9*.5+.1)*d['parameters']['size']+.03;old=np.concatenate([v for v,m in polys if m=='PUMP_CHANNEL']);ax=(old[:,0].min()+old[:,0].max())/2
  oldhalf=np.ptp(old[:,0])/2;newhalf=cap+.20;shift=oldhalf-newhalf;result=[]
  for v,m in polys:
   q=v.copy()
   if m in ['PUMP_BASE','PUMP_CHANNEL','CONCRETE']:
    q[:,0]-=np.sign(q[:,0]-ax)*shift;m='PROPOSED_'+m
   result.append((q,m))
  d['compactBase']=dict(oldPlateWidth=2*oldhalf,newPlateWidth=2*newhalf,oldPlinthWidth=2*(oldhalf+.08),newPlinthWidth=2*(newhalf+.08))
  return result
 if id in ['INLINE_PUMP','INLINE_COMPRESSOR']:
  removed,nub=inline_motor_feature(polys);body=np.concatenate([v for i,(v,m) in enumerate(polys) if m=='MOTOR' and i not in removed]);rad=max(np.max(abs(body[:,0]-.5)),np.max(abs(body[:,2]-.5)))
  target=.145 if id=='INLINE_PUMP' else .155;ratio=target/rad;out=[]
  for i,(v,m) in enumerate(polys):
   if i in removed:continue
   q=v.copy()
   if m=='MOTOR':q[:,[0,2]]=.5+(q[:,[0,2]]-.5)*ratio;m='PROPOSED_MOTOR'
   out.append((q,m))
  base=body[:,1].min();out+=k.frustum([.5,0,.5],[0,1,0],rad*.87,target,base-.03,base+.025,'PROPOSED_MOTOR',20)
  d['integralMotor']=dict(axis=[.5,float(nub[1]),.5],radius=target,base=float(base))
  return out
 if id=='CONTROL_VALVE':
  machine=np.concatenate([v for v,m in polys if m=='MACHINE']);seat=machine[:,1].max();act=np.concatenate([v for v,m in polys if m=='ACTUATOR']);oldtop=act[:,1].min();rise=.14;ratio=(oldtop-seat+rise)/(oldtop-seat);out=[]
  for v,m in polys:
   q=v.copy();changed=q[:,1]>seat+1e-8
   q[:,1]=np.where(q[:,1]>=oldtop,q[:,1]+rise,np.where(changed,seat+(q[:,1]-seat)*ratio,q[:,1]))
   if changed.any():m='PROPOSED_'+m
   out.append((q,m))
  d['positionerSeat']=float(seat);d['positionerTop']=float(oldtop+rise)
  return out
 return polys
