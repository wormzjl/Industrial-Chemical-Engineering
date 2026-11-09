from pathlib import Path
import xml.etree.ElementTree as E
for run in ['first-results','correction-results']:
 rs=[E.parse(p).getroot() for p in (Path('tmp/merge-prep-2026-11-08')/run).rglob('TEST-*.xml')]
 print(run,'tests',sum(int(r.get('tests')) for r in rs),'failed',sum(int(r.get('failures')) for r in rs))
