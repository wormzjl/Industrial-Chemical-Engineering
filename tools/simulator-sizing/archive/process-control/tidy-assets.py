from pathlib import Path
import json, subprocess
p=Path('src/main/resources/assets/icheme/lang/en_us.json')
new=json.loads(p.read_text(encoding='utf-8'));raw=subprocess.check_output(['git','show','HEAD:src/main/resources/assets/icheme/lang/en_us.json']).decode('utf-8');old=json.loads(raw)
extra={k:v for k,v in new.items() if k not in old}
p.write_text(raw[:raw.rfind('}')].rstrip()+',\n'+',\n'.join('  '+json.dumps(k,ensure_ascii=False)+': '+json.dumps(v,ensure_ascii=False) for k,v in extra.items())+'\n}\n',encoding='utf-8')
for name in ['local_controller','dcs']:
 p=Path('src/main/resources/assets/icheme/models/block')/(name+'.json');p.write_text(p.read_text().replace('equipment_structure','equipment_steelwork'))
