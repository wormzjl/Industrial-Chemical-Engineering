from pathlib import Path
import json
from PIL import Image, ImageDraw
root=Path('src/main/resources')
a=root/'assets/icheme'; data=root/'data/icheme'
def write(path,obj):
 path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(obj,indent=2)+'\n',encoding='utf-8')
def box(fr,to,tex,front=None):
 faces={s:{'texture':'#'+tex,'uv':[0,0,16,16]} for s in ['north','south','east','west','up','down']}
 if front:faces['north']['texture']='#'+front
 return {'from':fr,'to':to,'faces':faces}
# The port label is static; process readings only appear in subscribed GUIs.
for name,base in [('control_case',(123,132,135)),('data_cable',(55,103,122))]:
 im=Image.new('RGB',(32,32));p=im.load()
 for y in range(32):
  for x in range(32):
   v=((x*13+y*5)%7-3);p[x,y]=tuple(max(0,min(255,c+v)) for c in base)
 im.save(a/f'textures/block/{name}.png')
letters={'P':['11110','10001','10001','11110','10000','10000','10000'],'T':['11111','00100','00100','00100','00100','00100','00100'],'F':['11111','10000','10000','11110','10000','10000','10000'],'L':['10000','10000','10000','10000','10000','10000','11111'],'N':['10001','11001','11001','10101','10011','10011','10001']}
for name,letter in [('pressure_probe','P'),('temperature_probe','T'),('flowmeter','F'),('digital_level_meter','L'),('speed_sensor','N')]:
 im=Image.new('RGB',(32,32),(131,141,145));d=ImageDraw.Draw(im);d.rectangle((2,2,29,29),outline=(62,70,74),width=2);d.rectangle((5,5,26,25),fill=(20,35,40));d.rectangle((7,7,24,23),fill=(123,158,155))
 for y,row in enumerate(letters[letter]):
  for x,p in enumerate(row):
   if p=='1':d.rectangle((11+2*x,9+2*y,12+2*x,10+2*y),fill=(24,43,44))
 d.rectangle((11,27,20,28),fill=(48,105,125));im.save(a/f'textures/block/sensor_{letter.lower()}.png')
 old=a/f'models/item/{name}.json';existing=json.loads(old.read_text()) if old.exists() else {}
 model={'textures':{'metal':'icheme:block/equipment_pipe','body':'icheme:block/control_case','face':f'icheme:block/sensor_{letter.lower()}'},'elements':[box([6.5,1,7],[9.5,7,10],'metal'),box([3,6,4],[13,14,11],'body'),box([4,7,3.7],[12,13,4],'body','face')]}
 if 'display' in existing:model['display']=existing['display']
 write(old,model)
# Cabinet and console front faces, using the same cool metal and blue signal accents as the connectors.
for name,dcs in [('controller_front',False),('dcs_front',True)]:
 im=Image.new('RGB',(32,32),(122,132,136));d=ImageDraw.Draw(im);d.rectangle((1,1,30,30),outline=(59,67,71),width=2);d.rectangle((5,5,26,19 if dcs else 17),fill=(19,35,39));d.rectangle((7,7,24,17 if dcs else 15),outline=(80,137,145))
 if dcs:
  d.line((9,13,13,13,13,9,19,9,19,14,23,14),fill=(105,173,160),width=1);d.line((9,16,23,16),fill=(51,90,96))
 else:
  d.rectangle((9,9,13,13),outline=(125,170,160));d.line((16,10,23,10),fill=(102,153,150));d.line((16,13,23,13),fill=(102,153,150))
 d.rectangle((7,23,11,26),fill=(70,123,102));d.rectangle((15,23,19,26),fill=(55,65,69));d.rectangle((23,22,24,28),fill=(49,58,63));im.save(a/f'textures/block/{name}.png')
for name,dcs in [('local_controller',False),('dcs',True)]:
 model={'parent':'minecraft:block/block','textures':{'particle':'icheme:block/control_case','case':'icheme:block/control_case','metal':'icheme:block/equipment_structure','front':'icheme:block/'+('dcs_front' if dcs else 'controller_front')},'elements':[]}
 if dcs:model['elements']=[box([1,0,2],[15,2,14],'metal'),box([2,2,7],[14,10,14],'case'),box([1,9,3],[15,16,13],'case','front')]
 else:model['elements']=[box([3,0,5],[13,2,12],'metal'),box([2,2,3],[14,15,13],'case','front')]
 write(a/f'models/block/{name}.json',model)
 write(a/f'blockstates/{name}.json',{'variants':{f'facing={f}':{'model':f'icheme:block/{name}','y':r} for f,r in [('north',0),('east',90),('south',180),('west',270)]}})
 write(a/f'models/item/{name}.json',{'parent':f'icheme:block/{name}'})
 for n in [name]:write(data/f'loot_table/blocks/{n}.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':f'icheme:{n}'}],'conditions':[{'condition':'minecraft:survives_explosion'}]}]})
write(a/'models/block/data_cable.json',{'loader':'icheme:data_cable','textures':{'cable':'icheme:block/data_cable','particle':'icheme:block/data_cable'}})
write(a/'blockstates/data_cable.json',{'variants':{'':{'model':'icheme:block/data_cable'}}})
write(data/'loot_table/blocks/data_cable.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':'icheme:data_cable'}],'conditions':[{'condition':'minecraft:survives_explosion'}]}]})
im=Image.new('RGBA',(32,32));d=ImageDraw.Draw(im);d.ellipse((3,4,28,27),fill=(86,99,105),outline=(35,43,48),width=2);d.ellipse((6,6,25,25),fill=(49,97,118));
for i in [0,3,6]:d.ellipse((7+i,7+i,24-i,24-i),outline=(81,135,153),width=1)
d.ellipse((13,13,18,18),fill=(20,32,38));d.line((24,22,29,28,31,28),fill=(66,120,139),width=3);im.save(a/'textures/item/data_cable.png')
write(a/'models/item/data_cable.json',{'parent':'minecraft:item/generated','textures':{'layer0':'icheme:item/data_cable'}})
for name,pattern,keys,count in [
 ('data_cable',['CCC','RRR','CCC'],{'C':'minecraft:copper_ingot','R':'minecraft:redstone'},16),
 ('local_controller',['IRI','RCR','ILI'],{'I':'minecraft:iron_ingot','R':'minecraft:redstone','C':'minecraft:comparator','L':'minecraft:lever'},1),
 ('dcs',['GGG','RCR','III'],{'G':'minecraft:glass_pane','R':'minecraft:redstone','C':'icheme:local_controller','I':'minecraft:iron_ingot'},1)]:
 write(data/f'recipe/{name}.json',{'type':'minecraft:crafting_shaped','category':'redstone','pattern':pattern,'key':{k:{'item':v} for k,v in keys.items()},'result':{'id':'icheme:'+name,'count':count}})
p=a/'lang/en_us.json';lang=json.loads(p.read_text(encoding='utf-8'))
lang.update({'item.icheme.data_cable':'Data cable','block.icheme.data_cable':'Data cable','block.icheme.local_controller':'Controller box','block.icheme.dcs':'Control station',
'gui.icheme.data.cancelled':'Cable route cancelled','gui.icheme.data.no_route':'No clear cable route. Select a data port, cable end or free cell.','gui.icheme.data.started':'Cable start selected. Select the other end.','gui.icheme.data.laid':'Data cable laid'})
labels={'no_controllers':'No controller boxes connected.','controllers':'Controllers','no_devices':'No connected devices. Check the data cable.','back':'Back','settings':'Control','name':'Tag','off':'Off','on':'On','target':'Set point','input':'Input sensor','reading':'Reading','output':'Output','choose_output':'Choose output','action':'Action','inlet':'Inlet','outlet':'Outlet','status':'Status','waiting':'Waiting for the next engine update','none':'None selected','pending':'Waiting for application','close':'Close','apply':'Apply','invalid':'Enter a valid set point and select an input and output.'}
lang.update({'gui.icheme.control.'+k:v for k,v in labels.items()});write(p,lang)
