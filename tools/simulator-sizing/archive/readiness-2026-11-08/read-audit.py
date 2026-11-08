from pathlib import Path
import json
p=Path('tmp/pipe-guide-audit/entries.json');a=json.loads(p.read_text(encoding='utf-8'));print(type(a).__name__,len(a));print(str(a[0] if isinstance(a,list) else list(a.items())[:1])[:1500])
