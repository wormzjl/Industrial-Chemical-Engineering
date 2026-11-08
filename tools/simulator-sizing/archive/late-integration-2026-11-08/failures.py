import xml.etree.ElementTree as E
from pathlib import Path
for p in Path('build/test-results/equipmentGates').glob('TEST-*.xml'):
 r=E.parse(p).getroot()
 if int(r.get('failures','0')):
  print(p.name)
  for tc in r.findall('testcase'):
   f=tc.find('failure')
   if f is not None:print(tc.get('name'), f.get('message','')[:180])
