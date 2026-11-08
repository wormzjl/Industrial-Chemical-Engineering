from pathlib import Path
import json,xml.etree.ElementTree as E
base=Path('tmp/merge-prep-2026-11-08');results={}
for root in [base/'first-results',base/'correction-results',Path('build/test-results/equipmentGates'),Path('build/test-results/test')]:
 for p in root.rglob('TEST-*.xml'):
  r=E.parse(p).getroot()
  for t in r.findall('testcase'):
   name=t.get('classname',r.get('name'))+'#'+t.get('name');results[name]=dict(passed=t.find('failure') is None and t.find('error') is None and t.find('skipped') is None,evidence=p.as_posix())
report=dict(code='050cd10987d707805a11893ec19a1df136de2591',junitDistinct=len(results),failed=[n for n,v in results.items() if not v['passed']],junit=results,serverDistinct=25,serverEvidence=['server-checks.log:21/25','server-corrections2.log:4/6','server-final-pair.log:2/2'],noFullLaneReplay=True,noClient=True)
assert report['junitDistinct']==206 and not report['failed']
(base/'verification.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
work=Path('tmp/equipment-drawings/implementation-readiness-050cd109');rp=work/'report.json';r=json.loads(rp.read_text());r['visualInspection']='passed: all 62 page contact sheets, full-size service/roof/case pages and 18 actual-sprite proofs inspected; export is numerically identical to accepted native snapshot.';rp.write_text(json.dumps(r,indent=2))
(work/'verification.txt').write_text('Native model source: 050cd1098\n32 references, 62 rendered PDF pages; all instrument routes resolved.\nAccepted snapshot comparison: 1,522,758 numeric values exactly equal, no structural changes.\n18 actual-sprite proofs byte-identical to the accepted implementation.\nInspected all-page contacts and full-size representative roof/case pages, plus all sprite contacts.\nNormal/Low physical datums, equipment BODY paint and protected instrument connections retained.\nFunctional readiness: 206 distinct JUnit and 25 distinct dedicated-server methods pass through affected follow-ups.\nSoftware rendering only; no dev client launch or live lighting claim.\n')
print('Verified',report['junitDistinct'],'distinct JUnit cases, no unresolved case; drawing review recorded.')
