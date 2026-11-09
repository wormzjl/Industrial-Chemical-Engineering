from pathlib import Path
from collections import Counter
roots=['line-parity','dcs-console','pipe-guide-audit','operator-gui','equipment-control-boxes','equipment-drawings','line-consolidation','merge-prep-2026-11-08']
for r in roots:
 ps=[p for p in (Path('tmp')/r).rglob('*') if p.is_file() and p.suffix in {'.py','.ps1','.java','.gradle','.sh','.js','.patch'} and not any(x in p.parts for x in ['classes','src','__pycache__','.gradle'])]
 print(r,len(ps),sum(p.stat().st_size for p in ps))
print('root',len([p for p in Path('tmp').glob('*') if p.suffix in {'.py','.ps1','.java','.gradle','.sh','.js','.patch'}]))
