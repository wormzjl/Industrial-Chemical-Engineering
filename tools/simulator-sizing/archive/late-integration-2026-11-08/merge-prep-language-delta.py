import json,subprocess
from pathlib import Path
root=Path('src/main/resources/assets/icheme/lang');en=json.loads((root/'en_us.json').read_text(encoding='utf-8'));old=json.loads(subprocess.check_output(['git','show','f6d02add6:src/main/resources/assets/icheme/lang/en_us.json'],text=True,encoding='utf-8'))
print('English keys',len(en))
for k,v in en.items():
 if k not in old or old[k]!=v:print('NEW' if k not in old else 'EDIT',k,repr(old.get(k)),'=>',repr(v))
for k in old:
 if k not in en:print('DROP',k)
for p in root.glob('*.json'):
 d=json.loads(p.read_text(encoding='utf-8'));print(p.name,'keys',len(d),'missing',[k for k in en if k not in d],'extra',[k for k in d if k not in en])
for root in ['src/main/java','src/test/java','src/fluidGameTest/java']:print(root,len(list(Path(root).rglob('*.java'))))
