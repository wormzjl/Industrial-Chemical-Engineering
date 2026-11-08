from pathlib import Path
import xml.etree.ElementTree as E
for p in Path('build/test-results').rglob('TEST-*.xml'):
 r=E.parse(p).getroot()
 if int(r.get('failures',0)):
  print(p)
  for t in r.findall('testcase'):
   for f in t.findall('failure'):print(t.get('name'),f.get('message'),(f.text or '')[:900])
