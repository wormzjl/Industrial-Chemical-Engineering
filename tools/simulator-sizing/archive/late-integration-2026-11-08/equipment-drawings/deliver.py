from pathlib import Path
import json,shutil,subprocess
root=Path('tmp/equipment-drawings/implementation-616fe7a1')
dst=Path('C:/Users/wormz/.codex/visualizations/2026/11/06/01a1aed4-f4ee-7a61-8d39-dbce92e7a546/equipment-control-interfaces/implementation-616fe7a1')
changed=subprocess.check_output(['git','diff','--name-only','616fe7a1..HEAD'],text=True).splitlines()
assert set(changed)=={'src/main/java/com/wormzjl/icheme/runtime/fluid/ColumnOperation.java','src/main/java/com/wormzjl/icheme/runtime/fluid/ColumnUnit.java','src/test/java/com/wormzjl/icheme/runtime/fluid/ColumnProbeTest.java'},changed
report=json.loads((root/'report.json').read_text());assert report['pages']==report['rasterPages']==62 and report['equipment']==32
report.update(visualInspection='Reviewed all-page contact sheet, native enclosure and probe details, representative full sheets, and light/dark normal/low actual-sprite proofs. In-game validation remains pending.',runtimeFollowup='fd560324',modelUnchangedAfterSnapshot=True)
(root/'report.json').write_text(json.dumps(report,indent=2))
review='''Equipment interfaces implementation - verification
Model/layout commit: 616fe7a1c
Telemetry basis follow-up: fd560324
Native drawing book: 32 references, 62 rasterized pages; no unresolved instrument routes.
Reviewed all-page and enclosure contact sheets, representative full sheets, and actual-sprite proofs.
Service boxes and covers use equipment body paint; data/power ring colours stay distinct.
Layout table: 96 entries, 14 cell/stamp changes; boxes, nozzle cells and shaft terminals unchanged.
Constructor size/detail routing sweep: 48 profiles passed; route math unchanged since that pass.
Curved-facet full-width ribbon and continuous UV checks: passed on final finish code.
Constructor factory caps and built-in level display geometry check: passed, normal and low detail.
ColumnRunTest: 32 passed; FluidFactsCompatTest: 13 passed before the reporting-basis follow-up.
After that follow-up: total-feed/water mass-reading test and saved-probe reload test passed.
Earlier affected gauge, conduit, shape-area, layout, control-input and column-solve checks passed.
compileJava and compileFluidGameTestJava passed. No full-suite or merge-lane invocation.
No client launched; no live server GameTest or player-world checks performed.
Unavailable quantities remain unavailable: V3 has no circulating pumparound stream, and some air properties have no accepted source.
No new facts/shape fields; accepted column probe readings are rebuildable state.
No general documentation, translation, version or merge preparation. Nothing merged.
Existing AGENTS.md change remains unstaged.
'''
(root/'verification.txt').write_text(review)
dst.mkdir(parents=True,exist_ok=True)
for folder in ['sheets','sprite-renders']:shutil.copytree(root/folder,dst/folder,dirs_exist_ok=True)
for name in ['report.json','verification.txt','page-contact.png','mount-contact.png']:shutil.copy2(root/name,dst/name)
print('Delivered:',dst)
print('PDF bytes:',(dst/'sheets/equipment-drawings.pdf').stat().st_size)
print('Sprite proofs:',len(list((dst/'sprite-renders').glob('*.png'))))
