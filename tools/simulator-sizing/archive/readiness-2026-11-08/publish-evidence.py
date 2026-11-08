from pathlib import Path
import json,shutil,tarfile,hashlib
root=Path.cwd();batch=Path('D:/Minecraft/Modding/1.21/CreateChemE/research/2026-11-06-simulator-sizing');out=batch/'evidence/readiness-2026-11-08'
if out.exists():raise SystemExit('Readiness evidence already exists; preserve it and choose another directory')
out.mkdir(parents=True)
source=root/'tmp/merge-prep-2026-11-08';drawing=root/'tmp/equipment-drawings/implementation-readiness-050cd109'
shutil.copytree(drawing,out/'native-drawings')
shutil.copy2(root/'build/libs/icheme-0.22.0.jar',out/'icheme-0.22.0.jar')
shutil.copytree(source/'first-results',out/'junit/first-results');shutil.copytree(source/'correction-results',out/'junit/corrections')
shutil.copytree(root/'build/test-results',out/'junit/current',ignore=shutil.ignore_patterns('binary'))
for p in source.glob('*'):
 if p.is_file() and p.suffix in {'.json','.txt','.md'}:shutil.copy2(p,out/p.name)
folders=['line-parity','dcs-console','pipe-guide-audit','operator-gui','equipment-control-boxes','line-consolidation'];figures=0
for folder in folders:
 for p in (root/'tmp'/folder).rglob('*'):
  if not p.is_file() or any(x in p.parts for x in ['classes','__pycache__','.cache']):continue
  if p.suffix.lower() in {'.png','.pdf','.json','.csv','.tsv'} or p.name in {'REQUEST.md','OWNERSHIP.md'}:
   d=out/'historical-ui-line-work'/folder/p.relative_to(root/'tmp'/folder);d.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,d);figures+=1
logdir=Path('D:/Minecraft/Modding/1.21/CreateChemE/tools/simulator-sizing/logs');logdir.mkdir(parents=True,exist_ok=True);archive=logdir/'simulator-sizing-readiness-logs-2026-11-08.tar.gz'
if archive.exists():raise SystemExit('Log archive already exists; do not overwrite')
logs=[]
for folder in [source,drawing]+[root/'tmp'/f for f in folders]:
 logs.extend(p for p in folder.rglob('*') if p.is_file() and (p.suffix=='.log' or p.name.endswith('.log.gz')))
with tarfile.open(archive,'w:gz') as tar:
 for p in sorted(set(logs)):tar.add(p,arcname=p.relative_to(root).as_posix(),recursive=False)
manifest={'code':'050cd10987d707805a11893ec19a1df136de2591','version':'0.22.0','junitDistinct':206,'serverDistinct':25,'nativeReferences':32,'nativePages':62,'spriteProofs':18,'historicalFiles':figures,'compressedLogs':len(set(logs)),'logArchive':str(archive),'logArchiveSha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'noMerge':True,'noClient':True}
(out/'readiness-manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
(out/'README.md').write_text('''# 0.22.0 readiness evidence

Current source: `050cd109`; locales: `429fe26b`. The jar's bytes/hash/asset audit are in `package-verification.json`. `verification.json` merges 206 distinct JUnit results; 25 dedicated-server methods resolve through the preserved selected runs. Earlier failures remain visible in the source log archive.

`native-drawings/` is the implemented current model: 32 references, 62 inspected raster pages and 18 actual-sprite proofs. `native-comparison.json` records exact equality of 1,522,758 numeric values and all 18 PNGs to the accepted native implementation. `historical-ui-line-work/` retains earlier proposals, audit and GUI/line captures; its old pending/UNIMPLEMENTED labels are historical. Current ownership and acceptance are the branch code guide and batch MERGE_REVIEW.

Logs are compressed in the canonical local tools folder named by `readiness-manifest.json`. No player world was used. No new full lane, campaign, timed stress run or dev client was launched. Nothing merged or pushed; live visual acceptance stays with the owner.
''',encoding='utf-8')
index=batch.parent/'INDEX.md';s=index.read_text(encoding='utf-8');lines=s.splitlines()
for i,line in enumerate(lines):
 if line.startswith('- [2026-11-06-simulator-sizing]'):lines[i]='- [2026-11-06-simulator-sizing](2026-11-06-simulator-sizing/README.md): sizing, shared lines/control/FE, native interface drawings and 0.22.0 readiness evidence at `050cd109` (206 JUnit / 25 server methods); In progress since 2026-11-06.'
index.write_text('\n'.join(lines)+'\n',encoding='utf-8')
readme=batch/'README.md';s=readme.read_text(encoding='utf-8');s+='\n- 2026-11-08 readiness: source `050cd109`, four-locale `429fe26b`, 0.22.0 package audit, 206 focused JUnit / 25 dedicated-server methods, 32-reference/62-page native book and 18 sprite proofs. [Evidence](evidence/readiness-2026-11-08/README.md). Earlier lane/campaign passes stand; no second full lane, merge or client launch. Historical late UI/line proposals and all execution logs are retained separately from current native outputs.\n';readme.write_text(s,encoding='utf-8')
(source/'evidence-location.json').write_text(json.dumps({'evidence':str(out),'archive':str(archive),**manifest},indent=2),encoding='utf-8');print(json.dumps(manifest,indent=2))
