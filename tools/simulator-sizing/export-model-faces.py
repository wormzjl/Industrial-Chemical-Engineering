# Export the current cabinet/sensor model faces for the retained SensorAssetRender software rasterizer.
# Usage: python tools/simulator-sizing/export-model-faces.py tmp/<task>/models.tsv
from pathlib import Path
import json, sys
assets=Path('src/main/resources/assets/icheme');models=[(n,'block') for n in ['local_controller','dcs']]+[(n,'item') for n in ['pressure_probe','temperature_probe','flowmeter','digital_level_meter','speed_sensor']]
rows=[]
for name,folder in models:
 model=json.loads((assets/f'models/{folder}/{name}.json').read_text(encoding='utf-8'))
 if model.get('parent','').startswith('icheme:'):
  parent=json.loads((assets/('models/'+model['parent'].split(':')[1]+'.json')).read_text(encoding='utf-8'));parent['textures'].update(model.get('textures',{}));model={**parent,**model,'textures':parent['textures']}
 textures=model['textures']
 for element in model['elements']:
  x,y,z=element['from'];X,Y,Z=element['to']
  for face,defn in element['faces'].items():
   p,n={'north':([(X,Y,z),(X,y,z),(x,y,z),(x,Y,z)],(0,0,-1)),'south':([(x,Y,Z),(x,y,Z),(X,y,Z),(X,Y,Z)],(0,0,1)),'east':([(X,Y,Z),(X,y,Z),(X,y,z),(X,Y,z)],(1,0,0)),'west':([(x,Y,z),(x,y,z),(x,y,Z),(x,Y,Z)],(-1,0,0)),'up':([(x,Y,z),(x,Y,Z),(X,Y,Z),(X,Y,z)],(0,1,0)),'down':([(x,y,Z),(x,y,z),(X,y,z),(X,y,Z)],(0,-1,0))}[face]
   tex=defn['texture']
   while tex.startswith('#'):tex=textures[tex[1:]]
   tex=assets/'textures'/f'{tex.split(":")[1]}.png';uv=defn.get('uv',[0,0,16,16]);uvs=[uv[0]/16,uv[1]/16,uv[0]/16,uv[3]/16,uv[2]/16,uv[3]/16,uv[2]/16,uv[1]/16]
   rows.append('\t'.join(map(str,[name,tex.as_posix(),*[v for point in p for v in point],*uvs,*n])))
output=Path(sys.argv[1]).resolve()
if not output.is_relative_to(Path('tmp').resolve()): raise ValueError('Output must stay in this worktree tmp/')
output.write_text('\n'.join(rows),encoding='utf-8')

