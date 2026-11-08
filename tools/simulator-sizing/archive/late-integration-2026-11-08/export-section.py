import sys,subprocess
sys.path.insert(0,'tools/equipment-drawings')
from update import gradle_wrapper
subprocess.run(gradle_wrapper()+['--offline','--max-workers=2','--no-configuration-cache','--init-script','tools/equipment-drawings/export.init.gradle','exportEquipmentDrawings','-PequipmentDrawingWork=tmp/equipment-drawings/implementation-geometry-a','-PequipmentDrawingSnapshot=implementation','-PequipmentDrawingHeading=NORTH','-PequipmentDrawingOnly=SPHERE,INDUCED_DRAFT_COOLING_TOWER,NATURAL_DRAFT_COOLING_TOWER'],check=True)
