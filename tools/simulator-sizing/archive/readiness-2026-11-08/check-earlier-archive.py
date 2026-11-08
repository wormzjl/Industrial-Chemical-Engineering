from pathlib import Path
import csv,hashlib,json
base=Path.cwd();rows=[];issues=[]
with Path('tools/simulator-sizing/source-inventory.tsv').open() as f:
 for r in csv.DictReader(f,delimiter='\t'):
  p=base/'tmp'/r['origin_in_tmp'];d=base/'tools/simulator-sizing/archive'/r['origin_in_tmp']
  if p.exists():
   if not d.exists() or d.read_bytes()!=p.read_bytes():issues.append(r['origin_in_tmp'])
   else:rows.append(dict(origin_in_tmp=r['origin_in_tmp'],retained_path=d.relative_to(base).as_posix(),sha256=hashlib.sha256(p.read_bytes()).hexdigest()))
Path('tmp/merge-prep-2026-11-08/earlier-move-manifest.json').write_text(json.dumps(rows,indent=2))
print('Earlier archive originals eligible to move:',len(rows),'differing/missing retained copies:',issues)
