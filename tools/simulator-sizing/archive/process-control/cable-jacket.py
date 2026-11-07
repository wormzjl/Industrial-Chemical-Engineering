from pathlib import Path
from PIL import Image,ImageDraw
assets=Path('src/main/resources/assets/icheme')
im=Image.new('RGB',(32,32));pixels=im.load()
for y in range(32):
 for x in range(32):
  grain=((x*11+y*7)%5)-2
  base=(194,159,65)
  if x in [3,28]:base=(175,140,51)
  elif x in [4,29]:base=(204,171,80)
  if y==0:base=(125,108,57)
  elif y==1:base=(166,141,64)
  pixels[x,y]=tuple(max(0,min(255,c+grain)) for c in base)
im.save(assets/'textures/block/data_cable.png')
im=Image.new('RGBA',(32,32));d=ImageDraw.Draw(im);d.ellipse((3,4,28,27),fill=(78,87,91),outline=(30,37,42),width=2);d.ellipse((6,6,25,25),fill=(194,159,65));
for i in [0,3,6]:d.ellipse((7+i,7+i,24-i,24-i),outline=(139,112,46),width=1)
d.line((8,10,13,6),fill=(224,194,108),width=2);d.line((23,12,26,16),fill=(224,194,108),width=2);d.ellipse((13,13,18,18),fill=(35,34,28));d.line((24,22,29,28,31,28),fill=(194,159,65),width=3);d.line((28,26,29,27),fill=(125,108,57),width=2);im.save(assets/'textures/item/data_cable.png')
