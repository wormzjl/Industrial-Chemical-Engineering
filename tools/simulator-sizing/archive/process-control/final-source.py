from pathlib import Path
import json
p=Path('src/main/resources/assets/icheme/models/item/sensor_probe.json');obj=json.loads(p.read_text(encoding='utf-8'));p.write_text(json.dumps({'parent':'minecraft:block/block',**obj},indent=2)+'\n',encoding='utf-8')
p=Path('src/main/resources/assets/icheme/lang/en_us.json');s=p.read_text(encoding='utf-8').rstrip();s=s[:-1].rstrip()+',\n  "gui.icheme.control.choose_input": "Select sensor ID"\n}\n';p.write_text(s,encoding='utf-8')
p=Path('src/main/java/com/wormzjl/icheme/client/gui/screens/inventory/ControlPanelScreen.java');s=p.read_text(encoding='utf-8').replace('Component.literal(f.label()),ControlPanelScreen::skin','Component.literal(f.key().equals("name")?tr("control.name"):f.label()),ControlPanelScreen::skin');p.write_text(s,encoding='utf-8')
