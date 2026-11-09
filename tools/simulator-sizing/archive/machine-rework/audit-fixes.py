from pathlib import Path
b=Path('src/main/java/com/wormzjl/icheme')
def edit(path,old,new):
 p=b/path;s=p.read_text(encoding='utf-8');assert old in s,(path,old[:55]);p.write_text(s.replace(old,new),encoding='utf-8')
edit('client/gui/screens/EquipmentConstructorScreen.java','case "flow", "head", "power", "exhaust"','case "flow", "head", "power", "exhaust", "base_speed"')
edit('runtime/fluid/ShaftTrains.java','                    if (old != null) {\n                        topology.removeShaft(old.id());','                    if (old != null) {\n                        host.removed(old.id());topology.removeShaft(old.id());')
edit('compat/create/CreateBridgeBlockEntity.java','double demand=generated!=0?','double demand=generated!=0&&rpm>0?')
# The common reduced equipment lane now loads the motor laws used by its design-speed calculation.
p=Path('build.gradle');s=p.read_text(encoding='utf-8');old="def equipmentGates = gateGroup('equipment', 'science/equipment',\n        ['science/material', 'science/math',";assert old in s;s=s.replace(old,old+" 'science/machine',");p.write_text(s,encoding='utf-8')
