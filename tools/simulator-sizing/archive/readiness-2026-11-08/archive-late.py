from pathlib import Path
import shutil,hashlib,csv
root=Path.cwd();scratch=(root/'tmp').resolve();base=root/'tools/simulator-sizing/archive/late-integration-2026-11-08';base.mkdir(parents=True,exist_ok=True)
existing=set()
with (root/'tools/simulator-sizing/source-inventory.tsv').open() as f:
 for row in csv.DictReader(f,delimiter='\t'):existing.add(row['origin_in_tmp'])
folders=['line-parity','dcs-console','pipe-guide-audit','operator-gui','equipment-control-boxes','equipment-drawings','line-consolidation']
source_ext={'.py','.ps1','.java','.gradle','.sh','.js','.patch'}
candidates=list(scratch.glob('*'))+[p for name in folders for p in (scratch/name).rglob('*')]
rows=[]
for p in sorted(set(candidates)):
 if not p.is_file() or p.suffix not in source_ext or any(x in p.relative_to(scratch).parts for x in ['classes','src','__pycache__','.gradle']):continue
 rel=p.relative_to(scratch).as_posix()
 if rel in existing:continue
 assert p.resolve().is_relative_to(scratch)
 dest=base/rel;dest.parent.mkdir(parents=True,exist_ok=True)
 if dest.exists():assert dest.read_bytes()==p.read_bytes()
 else:shutil.copy2(p,dest)
 rows.append((rel,dest.relative_to(root).as_posix(),p.stat().st_size,hashlib.sha256(p.read_bytes()).hexdigest()))
with (base/'source-inventory.tsv').open('w',newline='') as f:
 w=csv.writer(f,delimiter='\t');w.writerow(['origin_in_tmp','retained_path','bytes','sha256']);w.writerows(rows)
(base/'README.md').write_text('''# Late integration investigation archive

Batch: `2026-11-06-simulator-sizing`, retained 2026-11-08. The source inventory records each original scratch path, retained path, size and SHA-256.

This archive preserves the line ownership audit/consolidation, DCS/GUI follow-ups and native equipment-interface implementation probes. Product code and permanent gates remain in their source sets. Current native drawing generation is `tools/equipment-drawings/update.py`; current server selections use `tools/simulator-sizing/focused-gametests.init.gradle` and invoke permanent bodies. Neither workflow loads this historical archive.

## Reuse

- Read a source before running it. Find/replace implementation scripts describe historical edits, not migrations or safe commands for the current branch.
- Geometry/render probes may be compiled against their recorded historical revision using JDK 21 and that revision's development runtime classpath. Put compiled classes and figures under a new indexed `tmp/<name>/` output. Sources that assumed their old scratch path need that path supplied or restored locally.
- `pipe-guide-audit/` holds the original audit readers/verdict scripts; the 838-entry matrix and ownership notes are local evidence, not an alternate framework.
- `operator-gui/` contains offline prototypes/implementation steps; shipped screen/builders are authoritative.
- `equipment-control-boxes/` and root scripts retain proposal and integration experiments. Accepted native owners are `EquipmentServices`, `EquipmentInstruments` and `InstrumentWiring`.
- There is no detached production class or removed production method to reattach. No Gradle gate loads this folder. Timing probes must run alone; renders are not in-game qualification.

History: archived during the final 0.22.0 preparation after code `fd5603249`; generated meshes, screenshots, logs, worlds and bytecode remain local. Original files already retained by the earlier 174-entry archive are not duplicated here.
''',encoding='utf-8')
print(f'Retained {len(rows)} late investigation sources; {sum(r[2] for r in rows)} bytes.')
