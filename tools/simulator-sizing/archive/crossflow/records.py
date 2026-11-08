from pathlib import Path
import re

root=Path.cwd()
def record(path,name,field,default):
    p=root/path
    s=p.read_text(encoding='utf-8')
    marker='public record '+name+'('
    start=s.index(marker)+len(marker)
    end=s.index(') {',start)
    signature=s[start:end]
    names=[x.strip().split()[-1] for x in signature.split(',')]
    indent='    ' if name=='ColumnDoor' else '        '
    constructor='\n'+indent+'public '+name+'('+signature+') {\n'+indent+'    this('+','.join(names)+','+default+');\n'+indent+'}\n'
    s=s[:end]+', '+field+s[end:end+3]+constructor+s[end+3:]
    p.write_text(s,encoding='utf-8',newline='\r\n')

record('src/main/java/com/wormzjl/icheme/science/flowsheet/unit/ColumnDoor.java','ColumnDoor','ColumnComponents.Exchangers exchangers','ColumnComponents.Exchangers.STANDARD')
record('src/main/java/com/wormzjl/icheme/world/level/block/entity/EquipmentConstructorBlockEntity.java','Design','com.wormzjl.icheme.science.equipment.ExchangerType exchangerType','com.wormzjl.icheme.science.equipment.ExchangerType.STANDARD')
