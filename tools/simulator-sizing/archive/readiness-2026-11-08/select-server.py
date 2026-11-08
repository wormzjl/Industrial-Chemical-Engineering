from pathlib import Path
import re,json,shutil
base=Path('tmp/merge-prep-2026-11-08');dest=base/'correction-results';dest.mkdir(exist_ok=True)
for task in ['test','equipmentGates']:shutil.copytree(Path('build/test-results')/task,dest/task,dirs_exist_ok=True)
classes=['ConduitLayingGameTests','DcsConsoleGameTests','ProcessControlGameTests','CreativePowerGameTests','PipeLayingGameTests']
selections=[]
for name in classes:
 s=(Path('src/fluidGameTest/java/com/wormzjl/icheme/fluid/gametest')/(name+'.java')).read_text()
 selections += [name+'#'+x for x in re.findall(r'public static void (\w+)\(GameTestHelper helper\)',s)]
(base/'server-selection.txt').write_text(','.join(selections))
print(len(selections),'dedicated-server methods. Changed LinePlacement/LinePieces affect the shared adapters, lead sweep, collision and stubs; InstrumentHosts.clear has its first-cell regression. GaugeAccessoriesTest loads InstrumentHosts; only that quick class remains to follow up.')
