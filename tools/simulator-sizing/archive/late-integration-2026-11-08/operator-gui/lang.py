from pathlib import Path
import json
p=Path('src/main/resources/assets/icheme/lang/en_us.json');s=p.read_text(encoding='utf-8');new={
'gui.icheme.control.sensor_location':'Sensor block: %s, %s, %s\nDimension: %s',
'gui.icheme.control.sensor_host_location':'Equipment block: %s, %s, %s\nDimension: %s',
'gui.icheme.control.location_unknown':'Location unavailable',
'gui.icheme.equipment_constructor.rated_speed':'Rated speed',
'gui.icheme.equipment_constructor.rated_speed.hint':'Shaft speed at the rated design point, in rpm. The connected driver determines the running speed.'}
s=s[:s.rfind('}')].rstrip()+',\n'+',\n'.join('  '+json.dumps(k)+': '+json.dumps(v,ensure_ascii=False) for k,v in new.items())+'\n}\n';json.loads(s);p.write_text(s,encoding='utf-8')
