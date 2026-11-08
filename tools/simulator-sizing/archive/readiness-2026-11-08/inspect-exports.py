from pathlib import Path
import json
base=Path('tmp/equipment-drawings/implementation-readiness-050cd109')
d=json.loads((base/'geometry/SPHERE.json').read_text());print(list(d));print('polygons',len(d['polygons']));print('instrument keys',d.get('instrumentPlan',{}).keys());print('wiring',d.get('instrumentWiring',{}).keys())
old=Path('tmp/equipment-drawings/implementation-616fe7a1/sprite-renders');new=base/'sprite-renders'
ps=list(new.glob('*.png'));same=sum(p.read_bytes()==(old/p.name).read_bytes() for p in ps if (old/p.name).exists());print('Pixel/PNG byte-identical sprite proofs:',same,'/',len(ps))
