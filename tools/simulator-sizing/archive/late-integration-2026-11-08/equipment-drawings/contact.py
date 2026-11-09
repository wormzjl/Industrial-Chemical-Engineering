from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import math
root=Path('tmp/equipment-drawings/implementation-616fe7a1')
views=root/'sheets/views'
paths=sorted(views.glob('*-mount-detail.png'))
cols=5;w=320;h=320
out=Image.new('RGB',(cols*w,math.ceil(len(paths)/cols)*h),'#e4ebee');draw=ImageDraw.Draw(out)
font=ImageFont.truetype('C:/Windows/Fonts/arial.ttf',13)
for i,p in enumerate(paths):
 im=Image.open(p).convert('RGB');im.thumbnail((w-14,h-38));x=(i%cols)*w;y=(i//cols)*h
 out.paste(im,(x+(w-im.width)//2,y+30));draw.text((x+8,y+7),p.name.replace('-mount-detail.png',''),font=font,fill='#233b49')
out.save(root/'mount-contact.png')
print(len(paths),'native mounted interface views')

