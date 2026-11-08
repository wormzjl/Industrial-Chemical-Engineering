from pathlib import Path
p=Path('src/test/resources/compat/control/control-2.snbt')
s=p.read_text(encoding='utf-8').replace('format:2','format:3').replace('lane:0,joints:','lane:0,medium:"POWER",joints:').replace('kind:"SENSOR",host:10L','kind:"POWER_OUTPUT",host:0L').replace('kind:"CONTROLLER"','kind:"POWER_INPUT"')
s=s[:s.index(',panels:')]+',panels:[],sensorIds:[],nextSensorId:1L}\n'
(p.parent/'control-3.snbt').write_text(s,encoding='utf-8')
# Match the immediate connector renderer's orange rim in the software equipment render.
p=Path('tmp/power-conduit/PowerEquipmentRender.java');s=p.read_text(encoding='utf-8');s=s.replace('        return out;\n    }\n\n    private static void add(', '        out.put(Mesh.Material.ACTUATOR,new Sprite(1,1,new int[]{0xffc68239}));\n        return out;\n    }\n\n    private static void add(');p.write_text(s,encoding='utf-8')
