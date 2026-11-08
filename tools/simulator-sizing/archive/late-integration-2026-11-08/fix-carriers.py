from pathlib import Path
p=Path('src/main/java/com/wormzjl/icheme/science/equipment/EquipmentServices.java');s=p.read_text(encoding='utf-8').replace('entry[other]+=.09;','double[] centre=surface.bounds(MOUNT);entry[other]+=(centre[other]+centre[other+3]>=2*anchor[other]?1:-1)*.09;');p.write_text(s,encoding='utf-8')
p=Path('src/main/java/com/wormzjl/icheme/science/equipment/InstrumentWiring.java');s=p.read_text(encoding='utf-8').replace('Material.TOWER_PANEL,Material.GRATING','Material.TOWER_PANEL,Material.TOWER_LOUVER,Material.TOWER_CONCRETE,Material.GRATING');p.write_text(s,encoding='utf-8')
