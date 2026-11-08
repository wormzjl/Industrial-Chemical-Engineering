import re
from pathlib import Path
pat=re.compile(r'(?<![A-Za-z0-9_$])(?:StrictMath|Math)\s*\.\s*(sin|cos|tan|asin|acos|atan2|atan|sinh|cosh|tanh|exp|expm1|log|log10|log1p|pow|cbrt|hypot|random)\s*\(')
base=Path('src/main/java/com/wormzjl/icheme')
for area in ['science','runtime']:
 for p in sorted((base/area).rglob('*.java')):
  if p.name=='DeterministicMath.java':continue
  hits=[(i,m.group(1)) for i,line in enumerate(p.read_text(encoding='utf-8').splitlines(),1) for m in pat.finditer(line)]
  if hits:print(p.relative_to(base),hits)
