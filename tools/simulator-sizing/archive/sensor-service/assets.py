from pathlib import Path
import json, re, sys
sys.path.insert(0, 'tmp/process-control')
from PIL import Image, ImageDraw

assets = Path('src/main/resources/assets/icheme')
source = Path('src/main/java/com/wormzjl/icheme/runtime/instrument/Sensor.java').read_text(encoding='utf-8')
colours = {symbol.lower(): tuple(int(colour[i:i+2],16) for i in (2,4,6))
           for symbol,colour in re.findall(r'\("([PTFLN])",0x([a-f0-9]{8})\)',source)}
glyphs = {
    'p':['11110','11011','11011','11110','11000','11000','11000'],
    't':['11111','11111','00100','00100','00100','00100','00100'],
    'f':['11111','11000','11000','11110','11000','11000','11000'],
    'l':['11000','11000','11000','11000','11000','11000','11111'],
    'n':['11001','11001','11101','11111','10111','10011','10011']}

for letter, colour in colours.items():
    body=Image.new('RGB',(32,32),(132,140,143));d=ImageDraw.Draw(body)
    d.rectangle((0,0,31,8),fill=colour)
    d.rectangle((0,25,31,31),fill=colour)
    d.line((0,9,31,9),fill=(204,210,209),width=1)
    d.line((0,24,31,24),fill=(70,79,83),width=1)
    for x in (3,28):
        for y in (12,21): d.rectangle((x,y,x+1,y+1),fill=(68,76,79))
    body.save(assets/f'textures/block/sensor_{letter}_case.png')
    face=Image.new('RGB',(32,32),(47,57,63));d=ImageDraw.Draw(face)
    d.rectangle((1,1,30,30),fill=colour)
    d.rectangle((5,4,26,27),fill=(226,231,222))
    for y,row in enumerate(glyphs[letter]):
        for x,on in enumerate(row):
            if on=='1':d.rectangle((9+3*x,6+3*y,11+3*x,8+3*y),fill=(31,44,49))
    for x in (2,28):
        for y in (2,28):d.rectangle((x,y,x+1,y+1),fill=(194,202,201))
    face.save(assets/f'textures/block/sensor_{letter}.png')

parent_path=assets/'models/item/sensor_probe.json'
parent=json.loads(parent_path.read_text(encoding='utf-8'))
parent['textures']['body']='icheme:block/sensor_p_case'
parent['elements'][2]['from']=[3,6,3.7]
parent['elements'][2]['to']=[13,14,4]
parent_path.write_text(json.dumps(parent,indent=2)+'\n',encoding='utf-8')
for name,letter in [('pressure_probe','p'),('temperature_probe','t'),('flowmeter','f'),('digital_level_meter','l'),('speed_sensor','n')]:
    path=assets/f'models/item/{name}.json';model=json.loads(path.read_text(encoding='utf-8'))
    model['textures']['body']=f'icheme:block/sensor_{letter}_case'
    path.write_text(json.dumps(model,indent=2)+'\n',encoding='utf-8')

path=assets/'lang/en_us.json';text=path.read_text(encoding='utf-8')
entries={
    'tooltip.icheme.sensor.pressure':'Fits: straight pipes, control valves and equipment pressure taps.',
    'tooltip.icheme.sensor.temperature':'Fits: straight pipes and equipment temperature taps.',
    'tooltip.icheme.sensor.flow':'Fits: straight pipes and control valves. Use a nozzle pipe to meter equipment flow.',
    'tooltip.icheme.sensor.level':'Fits: drums, floating-roof tanks, storage spheres and cooling-tower basins.',
    'tooltip.icheme.sensor.speed':'Fits: the steam turbine output coupling.',
    'tooltip.icheme.sensor.install':'Right-click a supported host or its preview. Installs at the next process update.',
    'message.icheme.sensor.unsupported':'No compatible sensor mount here. Check the item tooltip.',
    'message.icheme.sensor.reach':'Move within reach of the sensor preview on the outside of the equipment.',
    'message.icheme.sensor.occupied':'This sensor mount or pipe accessory slot is occupied.',
    'message.icheme.sensor.blocked':'The sensor mount is obstructed. Clear the space around its preview.',
    'message.icheme.sensor.queued':'Sensor change queued for the next process update.',
    'message.icheme.sensor.changed':'Sensor change cancelled: the item or equipment changed.',
    'message.icheme.sensor.installed':'Sensor installed.',
    'message.icheme.sensor.removed':'Sensor removed.'}
data=json.loads(text)
for key,value in entries.items():
    assert key not in data,key
    text=text.rstrip()[:-1].rstrip()+',\n  '+json.dumps(key)+': '+json.dumps(value)+'\n}\n'
path.write_text(text,encoding='utf-8')
