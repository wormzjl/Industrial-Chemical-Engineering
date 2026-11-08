from pathlib import Path
import shutil,xml.etree.ElementTree as ET,json,subprocess
out=Path('tmp/merge-prep-2026-11-08'); dest=out/'prior-results';dest.mkdir(exist_ok=True)
for p in Path('build/test-results').rglob('TEST-*.xml'):
 d=dest/p.parent.name/p.name;d.parent.mkdir(exist_ok=True);shutil.copy2(p,d)
changed=subprocess.check_output(['git','diff','--name-only','68294ece','--','src/main','src/test','src/fluidGameTest'],text=True).splitlines()
(out/'changed-since-previous-readiness.txt').write_text('\n'.join(changed)+'\n')
quick=['LanguageFilesTest','DeterministicMathSourceTest','LineContractTest','LineToolSessionTest','CableConnectionsTest','ControlSystemTest','ControlColumnsTest','ConstructorColumnTest','KitLayoutTest','FluidGuiConsistencyTest','BuiltInInputsTest','GaugeAccessoriesTest']
equipment=['EquipmentServicesTest','CoolingTowerGeometryTest','ConduitGeometryTest','EquipmentLayoutGoldenTest','ShapeRasterizerTest','RenderDetailTest','DcsGeometryTest','UnitTextTest','MachineRpmDesignTest','PipeRouteTest','LineGeometryTest']
(out/'filtered-selection.json').write_text(json.dumps(dict(changed=changed,quick=quick,equipment=equipment,reason='Post-682 line/DCS/operator checks were deferred; final three deterministic geometry edits load services/instrument/valve owners; translations changed at 429fe26b. No unchanged column or solver campaign replay.'),indent=2))
print(f'{len(changed)} changed source/resource paths since prior readiness; selected {len(quick)} quick and {len(equipment)} equipment classes; exact inventory saved.')
