from pathlib import Path
import json,re,subprocess
code=subprocess.check_output(['git','rev-parse','--short=9','HEAD'],text=True).strip();report=json.loads(Path('tmp/merge-prep-2026-11-08/package-verification.json').read_text())
for p in Path('documentation/reference/code-guide').glob('*.md'):
 s=p.read_text(encoding='utf-8')
 if '**Snapshot:** fd5603249' not in s:continue
 s=s.replace('**Snapshot:** fd5603249','**Snapshot:** '+code)
 s=s.replace('Main merge pending.','Ported into this 0.22.0 branch; the batch is not merged.')
 if p.name=='pipe-laying.md':s=s.replace('An ordinary validation refusal writes nothing;','Collected Minecraft cell cursors are made immutable before use, so preflight and derived collision visit every crossed cell. An ordinary validation refusal writes nothing;')
 if p.name=='wired-control-power.md':s=s.replace('The DCS is a two-block console','`DcsGeometry.localParts` supplies the local cabinet model-authoring/collision bounds; its recessed rear entry is not blocked by a full-cube collision. Both cabinets union derived lead geometry into their actual shape. DATA/POWER item IDs now register BlockItems and direct placement enters `LineLaying.handPlaced`.\n\nThe DCS is a two-block console')
 p.write_text(s,encoding='utf-8')
for name in ['README.md','MERGE_REVIEW.md']:
 p=Path('documentation/2026-11-06-simulator-sizing')/name;s=p.read_text(encoding='utf-8').replace('@CODE@',code).replace('@BYTES@',f"{report['bytes']:,}").replace('@SHA@',report['sha256']);p.write_text(s,encoding='utf-8')
p=Path('CHANGELOG.md');s=p.read_text(encoding='utf-8').replace('final translations `429fe26b1`; batch','final translations `429fe26b1`, readiness fixes `'+code+'`; batch');p.write_text(s,encoding='utf-8')
p=Path('tools/simulator-sizing/README.md');s=p.read_text(encoding='utf-8');s+='''
- 2026-11-08: late integration at `050cd109` closes deferred shared-line/DCS/GUI/native-interface checks. The final full lane at `891c8acf5` was not repeated; only affected filters ran. 206 distinct JUnit checks and 25 dedicated-server methods resolve through preserved follow-ups. Reachability reports no orphan production class; source counts are 669 main, 489 test and 36 GameTest files. Current 0.22.0 package, all native drawings/proofs, 838-entry applicability matrix and verification manifest are under canonical `research/2026-11-06-simulator-sizing/evidence/readiness-2026-11-08/`; logs are `simulator-sizing-readiness-logs-2026-11-08.tar.gz` in the main local logs folder.
- 2026-11-08: moved 177 byte-verified earlier archived sources and 102 late source/scripts out of scratch; preserved generated results and previous jars. The late archive has a SHA-256 inventory, and the readiness archive retains this pass's authoring/audit scripts. Historical patch scripts are not current migrations. Accepted model regeneration is `tools/equipment-drawings/update.py`; `ControlAssets` now shares local cabinet bounds with collision through `DcsGeometry.localParts`.
''';p.write_text(s,encoding='utf-8')
p=Path('tools/pump-sprites/README.md');s=p.read_text(encoding='utf-8');s+='\n- 2026-11-08: `050cd109` makes local-controller model authoring read `DcsGeometry.localParts`, the same bounds used for physical collision. Existing model coordinates/textures remain unchanged; isolated cabinet-resource and all-heading socket checks qualify the shared table. Compile `DcsGeometry.java` with the authoring sources or include `build/classes/java/main` on javac/java classpaths.\n';p.write_text(s,encoding='utf-8')
p=Path('documentation/reference/code-guide/pipe-laying.md');s=p.read_text(encoding='utf-8').replace("Whether a host takes its new joint is `LineAnchors` and `LineLayout.mergeHosts`' to check.",'`LineAnchors` and `LineLayout.mergeHosts` check whether a retained host takes its new joint.');p.write_text(s,encoding='utf-8')
print('Guide snapshots and package review now name',code)
