from pathlib import Path
import json
base=Path('src/main/resources/assets/icheme/models/item')
prototype=json.loads((base/'pressure_probe.json').read_text(encoding='utf-8'))
prototype.pop('display',None);prototype['textures']['face']='icheme:block/sensor_p'
(base/'sensor_probe.json').write_text(json.dumps(prototype,indent=2)+'\n',encoding='utf-8')
for name,letter in [('pressure_probe','p'),('temperature_probe','t'),('flowmeter','f'),('digital_level_meter','l'),('speed_sensor','n')]:
 p=base/(name+'.json');old=json.loads(p.read_text(encoding='utf-8'));child={'parent':'icheme:item/sensor_probe','textures':{'face':'icheme:block/sensor_'+letter}}
 if 'display' in old:child['display']=old['display']
 p.write_text(json.dumps(child,indent=2)+'\n',encoding='utf-8')
