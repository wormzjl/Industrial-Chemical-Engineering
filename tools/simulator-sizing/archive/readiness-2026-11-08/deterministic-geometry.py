from pathlib import Path
import re
root=Path('src/main/java/com/wormzjl/icheme/science/equipment')
for name in ['EquipmentInstruments.java','EquipmentServices.java','ValveGeometry.java']:
 p=root/name;s=p.read_text(encoding='utf-8')
 s=re.sub(r'\bMath\.(sin|cos|atan2|pow)\(',r'DeterministicMath.\1(',s)
 if name=='EquipmentInstruments.java':s=s.replace('Math.hypot(dx,dz)','Math.sqrt(dx*dx+dz*dz)')
 if 'import com.wormzjl.icheme.science.math.DeterministicMath;' not in s:s=s.replace('package com.wormzjl.icheme.science.equipment;','package com.wormzjl.icheme.science.equipment;\n\nimport com.wormzjl.icheme.science.math.DeterministicMath;',1)
 p.write_text(s,encoding='utf-8')
