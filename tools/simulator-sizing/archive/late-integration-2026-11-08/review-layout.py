import xml.etree.ElementTree as E
from pathlib import Path
p=Path('build/test-results/equipmentGates/TEST-com.wormzjl.icheme.science.equipment.ShapeRasterizerTest.xml')
for t in E.parse(p).getroot().findall('testcase'):
 if t.find('failure') is not None:print(t.get('name'),t.find('failure').get('message'))
p=Path('build/compat/layouts/layouts.txt');old=Path('src/test/resources/compat/layouts/layouts.txt')
a=old.read_text().splitlines();b=p.read_text().splitlines()
changes=0
for x,y in zip(a,b):
 if x==y:continue
 changes+=1
 for key in ['box','pipes','terminal']:
  left=next((v.strip() for v in x.split('|') if v.strip().startswith(key)),None);right=next((v.strip() for v in y.split('|') if v.strip().startswith(key)),None)
  if left!=right:print(x.split(',')[0],key,left,'->',right)
print('layout lines changed',changes,'of',len(a),'->',len(b))
