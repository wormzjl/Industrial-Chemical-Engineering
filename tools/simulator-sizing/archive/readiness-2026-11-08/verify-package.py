from pathlib import Path
import zipfile,hashlib,json,xml.etree.ElementTree as E,subprocess
root=Path.cwd();jar=root/'build/libs/icheme-0.22.0.jar';report={'code':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),'version':'0.22.0','jar':str(jar),'bytes':jar.stat().st_size,'sha256':hashlib.sha256(jar.read_bytes()).hexdigest()}
with zipfile.ZipFile(jar) as z:
 names=z.namelist();unknown=[]
 for name in names:
  if name.startswith('com/wormzjl/icheme/') and name.endswith('.class'):
   outer=name.split('$')[0].removesuffix('.class')+'.java'
   if not (root/'src/main/java'/outer).exists():unknown.append(name)
 assets=[p for p in (root/'src/main/resources/assets/icheme').rglob('*') if p.is_file()]
 mismatch=[str(p.relative_to(root/'src/main/resources')) for p in assets if str(p.relative_to(root/'src/main/resources')).replace('\\','/') not in names or z.read(p.relative_to(root/'src/main/resources').as_posix())!=p.read_bytes()]
 meta=z.read('META-INF/neoforge.mods.toml').decode();assert '0.22.0' in meta
 report.update(unknownOwnClasses=unknown,assetFiles=len(assets),mismatchedAssets=mismatch,duplicateEntries=len(names)-len(set(names)),natives=[n for n in names if n.startswith('natives/') and n.endswith(('.dll','.so','.dylib'))])
 assert not unknown and not mismatch and not report['duplicateEntries'] and report['natives']
report['counts']={label:len(list((root/path).rglob('*.java'))) for label,path in [('production','src/main/java'),('tests','src/test/java'),('serverFixtures','src/fluidGameTest/java')]}
Path('tmp/merge-prep-2026-11-08/package-verification.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps(report,indent=2))
