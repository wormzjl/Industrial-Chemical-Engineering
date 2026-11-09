"""Drawing contract for built-in channels; stream keys own P/T/F expansion."""
from collections import Counter,defaultdict

THERMAL_KINDS=frozenset(['VERTICAL_HEATER','CABIN_HEATER','AIR_COOLER','INDUCED_DRAFT_COOLING_TOWER','NATURAL_DRAFT_COOLING_TOWER','EXCHANGER','KETTLE'])
TOWER_KINDS=frozenset(['INDUCED_DRAFT_COOLING_TOWER','NATURAL_DRAFT_COOLING_TOWER'])

def signal(key,quantity,location,**extra):
 result=dict(key=key,quantity=quantity,location=location,probe='internal',interface='C1',availability='accepted published state; unavailable if not supplied');result.update(extra);return result

def stream(prefix,location,flow=True,**extra):
 values=[signal(prefix+'_'+q,q,location,**extra) for q in ['pressure','temperature']]
 if flow:values.append(signal(prefix+'_flow_rate','flow',location,flowBasis='declared source basis; convert only with valid composition/density',**extra))
 return values

def ports(d):
 counts=Counter(p['role'] for p in d['processPorts']);seen=defaultdict(int);result=[]
 for p in d['processPorts']:
  role=p['role'];seen[role]+=1;key=role.lower()+('_'+str(seen[role]) if counts[role]>1 or role in ['DRAW','STEAM','PUMPAROUND_DRAW','RETURN'] else '')
  result.append((key,p))
 return result

def thermal(d):
 values=[];markers=[];flow=d['kind'] in ['EXCHANGER','KETTLE']
 short={'CONVECTION_INLET':'CI','CONVECTION_OUTLET':'CO','RADIANT_INLET':'RI','RADIANT_OUTLET':'RO','PROCESS_INLET':'IN','PROCESS_OUTLET':'OUT','HOT_WATER_INLET':'IN','COLD_WATER_OUTLET':'OUT','TUBE_INLET':'TI','TUBE_OUTLET':'TO','SHELL_INLET':'SI','SHELL_OUTLET':'SO'}
 for key,p in ports(d):
  values.extend(stream(key,p['role'].replace('_',' ').lower(),flow,processRole=p['role']));markers.append(dict(tag=short.get(p['role'],p['role'])+(' P/T/F' if flow else ' P/T'),centre=p['base'],processRole=p['role']))
 if d['kind'] in TOWER_KINDS:values.append(signal('basin_level','level','basin liquid + solid height; gas excluded',units='m; % of span'))
 return values,markers

COLUMN_FAMILIES=[
 dict(key='boundary.{role}.{index}',name='Every external column boundary',ports='feed, steam[i], off_gas, sour_water, distillate, draw[i], bottoms',basis='OperationView.Stream: native mol/s, Pa, K; mass/volume conversions require valid basis'),
 dict(key='member.{slot}.{unit}.port.{role}.{index}',name='Every port of every installed member',ports='all inlet/outlet roles, including intermediate process and utility streams',basis='Accepted member port state; unit index identifies each parallel physical unit'),
 dict(key='condenser.{unit}.tube_{inlet|outlet}',name='Condenser cooling-water supply / return',ports='each water condenser unit; shell/process passages also included',basis='Conditional on installed water condenser'),
 dict(key='trim_condenser.{unit}.tube_{inlet|outlet}',name='Water-trim cooling-water supply / return',ports='each installed trim unit after an air condenser',basis='Conditional on AIR_THEN_WATER design'),
 dict(key='reboiler.{unit}.tube_{inlet|outlet}',name='Reboiler heating utility supply / return',ports='steam/condensate or other connected heating medium; feed/vapour/liquid also included',basis='Conditional on installed kettle; retain native pool model'),
 dict(key='pa_cooler.{loop}.{unit}.tube_{inlet|outlet}',name='Pumparound cooler utilities',ports='cooling utility supply/return, plus process draw/return, every loop and unit',basis='Conditional on actual pumparound members'),
 dict(key='ejector.{unit}.{suction|motive|discharge}',name='Ejector process and motive utility',ports='suction, motive steam and discharge mixture',basis='Conditional on installed ejector'),
 dict(key='air_utility.{member}.{unit}.{inlet|outlet}',name='Cooling-air utility where present',ports='inlet/outlet P/T/F for air-cooled members',basis='Expose unavailable when the existing air model does not publish a quantity; never substitute water/process pressure')]

def column(d):
 values=[];markers=[]
 short={'VAPOUR_OUTLET':'VAP','REFLUX':'REFLUX','FEED':'FEED','PUMPAROUND_DRAW':'PA DRAW','RETURN':'PA RETURN','VAPOUR_RETURN':'VAP RET','DRAW':'DRAW','STEAM':'STEAM','BOTTOMS':'BOTTOMS'}
 for end in ['top','bottom']:values.extend(stream(end,'column '+end+' internal state',False,source='accepted column stage/sump state, not condenser or reflux setpoint'))
 for key,p in ports(d):
  suffix=key.rsplit('_',1)[-1];label=short[p['role']]+(' '+suffix if suffix.isdigit() else '')
  values.extend(stream(key,p['role'].replace('_',' ').lower(),True,processRole=p['role']));markers.append(dict(tag=label+' P/T/F',centre=p['base']))
 return values,markers,[dict(f,quantities=['pressure','temperature','flow'],interface='C1',identity='column equipment ID + member slot/unit + port role/ordinal; no coordinates') for f in COLUMN_FAMILIES]
