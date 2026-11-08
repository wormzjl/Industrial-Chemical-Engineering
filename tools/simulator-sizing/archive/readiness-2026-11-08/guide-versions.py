from pathlib import Path
p=Path('documentation/reference/code-guide/README.md');s=p.read_text(encoding='utf-8')
s=s.replace('process simulator\'s packets `"2"`','process simulator\'s packets `"3"`').replace('state format 35 and facts schema 12','state format 39 and facts schema 14').replace('fluid menu protocol `fluid-18`','fluid menu protocol `fluid-19`').replace('the pipe tools on `pipeline-tool-2`','tool settings on `pipeline-tool-5`, shared line commands on `lines-1`, controller/DCS on `control-3`').replace('machine pages `machine-2`','machine pages `machine-3`').replace('equipment protocol `equipment-18` and shape fields 8','equipment protocol `equipment-21` and shape fields 10').replace('`icheme_accessories` facts schema 3','`icheme_accessories` facts schema 4')
p.write_text(s,encoding='utf-8')
# Record every baseline result before a filtered follow-up replaces XMLs.
import xml.etree.ElementTree as E,json
runs={}
for run in ['first-results']:
 rows=[]
 for p in (Path('tmp/merge-prep-2026-11-08')/run).rglob('TEST-*.xml'):
  r=E.parse(p).getroot();rows.append(dict(name=r.get('name'),tests=int(r.get('tests')),failures=int(r.get('failures')),errors=int(r.get('errors')),skipped=int(r.get('skipped'))))
 runs[run]=rows
Path('tmp/merge-prep-2026-11-08/junit-first-summary.json').write_text(json.dumps(runs,indent=2))
print('First filtered run:',sum(x['tests'] for x in rows),'tests;',sum(x['failures'] for x in rows),'initial failures retained.')
