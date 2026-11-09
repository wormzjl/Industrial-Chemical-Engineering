import sys,subprocess
sys.path.insert(0,'tools/equipment-drawings')
from update import gradle_wrapper
subprocess.run(gradle_wrapper()+['--offline','--max-workers=2','--no-configuration-cache','--init-script','tools/equipment-drawings/export.init.gradle','exportEquipmentDrawings','-PequipmentDrawingWork=tmp/equipment-drawings/implementation-geometry-a','-PequipmentDrawingSnapshot=implementation','-PequipmentDrawingHeading=NORTH','-PequipmentDrawingOnly=DRUM,COLUMN,TANK,SPHERE,EXCHANGER,KETTLE,AIR_COOLER,PUMP,COMPRESSOR,CENTRIFUGAL_COMPRESSOR,VERTICAL_HEATER,CABIN_HEATER,STEAM_TURBINE,INDUCED_DRAFT_COOLING_TOWER,NATURAL_DRAFT_COOLING_TOWER,MOTOR,GENERATOR,INLINE_PUMP,INLINE_COMPRESSOR,CONTROL_VALVE'],check=True)
