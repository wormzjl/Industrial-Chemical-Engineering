"""Native sheet crops for revision H review; all dimensions stay in the PDF."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

root=Path(__file__).resolve().parent/'drawings-h'
canvas=Image.new('RGB',(1440,1130),'#e6edef');d=ImageDraw.Draw(canvas)
title=ImageFont.truetype('C:/Windows/Fonts/arialbd.ttf',27)
font=ImageFont.truetype('C:/Windows/Fonts/arial.ttf',21)
d.rectangle((0,0,1440,76),fill='#233b49');d.text((24,22),'REV H | ROOF RESERVES, BOOT LEVELS AND EXCHANGER T',font=title,fill='white')
cards=[(35,'Vertical drum: two future service zones',(44,190,1110,635)),
       (37,'Booted drum: separate body L1 and boot L2',(44,200,1110,1040)),
       (41,'Standard: four built-in temperature points',(44,200,1110,1040)),
       (42,'Crossflow: four built-in temperature points',(44,200,1110,1040))]
for i,(page,label,crop) in enumerate(cards):
 x=20+710*(i%2);y=96+482*(i//2);d.rectangle((x,y,x+690,y+460),fill='white');d.text((x+14,y+12),label,font=font,fill='#233b49')
 im=Image.open(root/'pages'/f'sheet-{page:02d}.png').convert('RGB').crop(crop);im.thumbnail((665,395));canvas.paste(im,(x+(690-im.width)//2,y+53+(395-im.height)//2))
d.text((25,1080),'Proposal only. Dashed boxes are future space reservations. Exact sizes and channels are listed in the PDF.',font=font,fill='#233b49')
canvas.save(root/'sensor-placement-summary.png')
