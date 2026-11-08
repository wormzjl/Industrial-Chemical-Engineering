from pathlib import Path
import json,subprocess,struct
root=Path('src/main/resources');assets=root/'assets/icheme'
paths=subprocess.check_output(['git','ls-files','--modified','--others','--exclude-standard','src/main/resources'],encoding='utf-8').splitlines()
files=sorted(set(Path(p) for p in paths if p.endswith('.json')))
def no_duplicates(pairs):
 d={}
 for k,v in pairs:
  if k in d:raise ValueError('Duplicate key '+k)
  d[k]=v
 return d
for p in files:json.loads(p.read_text(encoding='utf-8'),object_pairs_hook=no_duplicates)
models=[p for p in files if '/models/' in p.as_posix()]
for p in models:
 data=json.loads(p.read_text(encoding='utf-8'));parent=data.get('parent','')
 if parent.startswith('icheme:'):assert (assets/('models/'+parent.split(':')[1]+'.json')).is_file(),(p,parent)
 for texture in data.get('textures',{}).values():
  if texture.startswith('icheme:'):assert (assets/('textures/'+texture.split(':')[1]+'.png')).is_file(),(p,texture)
for p in paths:
 if p.endswith('.png'):
  b=Path(p).read_bytes();assert b[:8]==b'\x89PNG\r\n\x1a\n';w,h=struct.unpack('>II',b[16:24]);assert w>0 and h>0
print(f'Validated {len(files)} changed JSON files, model texture/parent references, and new PNG headers.')
