"""Preview cropped directly from the reviewed native-geometry drawing sheets."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

root=Path(__file__).resolve().parent/'drawings-g'
canvas=Image.new('RGB',(1200,1070),'#e6edef')
d=ImageDraw.Draw(canvas)
title=ImageFont.truetype('C:/Windows/Fonts/arialbd.ttf',25)
font=ImageFont.truetype('C:/Windows/Fonts/arial.ttf',19)
d.rectangle((0,0,1200,74),fill='#233b49')
d.text((24,21),'PROPOSED INSTRUMENT PLACEMENT | REV G',font=title,fill='white')
cards=[(35,'Vertical drum: P / T / L on top'),(36,'Horizontal drum: P / T / L on top'),
       (37,'Large sphere: P / T at top platform'),(38,'Small sphere: P / T at top platform'),
       (39,'Induced tower: built-in Ti / Tb; manual L')]
for i,(page,label) in enumerate(cards):
 x=20+590*(i%2);y=94+315*(i//2)
 d.rectangle((x,y,x+570,y+295),fill='white')
 d.text((x+14,y+12),label,font=font,fill='#233b49')
 im=Image.open(root/'pages'/f'sheet-{page:02d}.png').convert('RGB')
 im=im.crop((44,200,1110,635));im.thumbnail((550,245))
 canvas.paste(im,(x+(570-im.width)//2,y+48))
x,y=610,724
for line in ['P = pressure; T = temperature; L = level',
             'Solid leaders: manual instruments',
             'Dotted gold leaders: built-in instruments',
             'Top T uses an immersion assembly.',
             'Tower level mount stays unchanged.',
             'Exact dimensions and choices in the PDF.',
             'Drawing proposal only; game code unchanged.']:
 d.text((x+12,y),line,font=font,fill='#233b49');y+=34
canvas.save(root/'sensor-placement-summary.png')
