from pathlib import Path
import json, sys
sys.path.insert(0,'tmp/process-control')
from PIL import Image,ImageDraw
assets=Path('src/main/resources/assets/icheme')
body=Image.new('RGB',(32,32));d=ImageDraw.Draw(body)
for y in range(32):
    c=147+(y%5==0)*3-(y%7==0)*2
    d.line((0,y,31,y),fill=(c-3,c+3,c+6))
d.line((0,0,31,0),fill=(202,207,207));d.line((0,31,31,31),fill=(88,97,102))
for x in (3,27):
    for y in (4,26):d.rectangle((x,y,x+1,y+1),fill=(82,93,99))
body.save(assets/'textures/block/sensor_case.png')
glyphs={
    'p':['11110','10001','10001','11110','10000','10000','10000'],
    't':['11111','00100','00100','00100','00100','00100','00100'],
    'f':['11111','10000','10000','11110','10000','10000','10000'],
    'l':['10000','10000','10000','10000','10000','10000','11111'],
    'n':['10001','11001','11001','10101','10011','10011','10001']}
for letter,glyph in glyphs.items():
    im=Image.new('RGB',(32,32),(157,165,169));d=ImageDraw.Draw(im)
    d.rectangle((3,3,28,25),fill=(39,51,57));d.rectangle((5,5,26,23),fill=(168,186,177))
    d.line((5,5,26,5),fill=(102,122,117))
    for y,row in enumerate(glyph):
        for x,on in enumerate(row):
            if on=='1':d.rectangle((11+2*x,7+2*y,12+2*x,8+2*y),fill=(32,53,51))
    d.rectangle((9,27,22,29),fill=(181,157,85))
    for x in (1,29):
        for y in (1,29):d.rectangle((x,y,x+1,y+1),fill=(76,88,94))
    im.save(assets/f'textures/block/sensor_{letter}.png')

def box(a,b,texture):
    return {'from':a,'to':b,'faces':{face:{'texture':'#'+texture,'uv':[0,0,16,16]} for face in ('north','south','east','west','up','down')}}
def housing(a,b):
    front=box([a[0]+.35,a[1]+.35,a[2]-.25],[b[0]-.35,b[1]-.35,a[2]],'body')
    front['faces']['north']['texture']='#face'
    return [box(a,b,'body'),front]
styles={
    'p':housing([3.5,7,4.5],[12.5,14,11])+[
        box([7,1,7],[9,7,9],'metal'),box([6,4,6],[10,6,10],'metal')],
    't':housing([3.5,8,4.5],[12.5,15,11])+[
        box([7.4,0,7.4],[8.6,8,8.6],'metal'),box([6.5,5.5,6.5],[9.5,7,9.5],'metal')],
    'f':housing([4,8,4.5],[12,14.5,10.5])+[
        box([7,5,7],[9,8,9],'metal'),box([0,2.5,6.5],[16,5.5,9.5],'metal'),
        box([3,1.5,5.5],[5,6.5,10.5],'body'),box([11,1.5,5.5],[13,6.5,10.5],'body')],
    'l':housing([2,7,5],[14,14,11])+[
        box([6.75,2,7],[9.25,7,9],'metal'),box([6,3,6],[10,5,10],'metal')],
    'n':housing([5,9,5],[11,15,11])+[
        box([7,1,7],[9,9,9],'metal'),box([6.5,6,6.5],[9.5,8,9.5],'metal')]+
        [box([6.8,y,6.8],[9.2,y+.3,9.2],'metal') for y in (2,3,4,5)]}
models=assets/'models/item'
parent=json.loads((models/'sensor_probe.json').read_text(encoding='utf-8'))
parent['textures']['body']='icheme:block/sensor_case';parent['elements']=styles['p']
(models/'sensor_probe.json').write_text(json.dumps(parent,indent=2)+'\n',encoding='utf-8')
for name,letter in [('pressure_probe','p'),('temperature_probe','t'),('flowmeter','f'),('digital_level_meter','l'),('speed_sensor','n')]:
    path=models/(name+'.json');model=json.loads(path.read_text(encoding='utf-8'))
    model['textures'].pop('body',None)
    if letter!='p':model['elements']=styles[letter]
    path.write_text(json.dumps(model,indent=2)+'\n',encoding='utf-8')
    (assets/f'textures/block/sensor_{letter}_case.png').unlink()
