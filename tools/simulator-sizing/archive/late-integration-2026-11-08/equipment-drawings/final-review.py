from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
from pypdf import PdfReader
import subprocess,json,math
root=Path('tmp/equipment-drawings/implementation-616fe7a1');p=root/'sheets/equipment-drawings.pdf'
pages=sorted((root/'sheets/pages').glob('*.png'));cols=5;w=300;h=225
im=Image.new('RGB',(cols*w,math.ceil(len(pages)/cols)*h),'#dfe7eb');draw=ImageDraw.Draw(im);font=ImageFont.truetype('C:/Windows/Fonts/arial.ttf',12)
for i,png in enumerate(pages):
 q=Image.open(png).convert('RGB');q.thumbnail((w-8,h-23));x=i%cols*w;y=i//cols*h;im.paste(q,(x+(w-q.width)//2,y+20));draw.text((x+8,y+3),str(i+1),font=font,fill='#233b49')
im.save(root/'page-contact.png')
reader=PdfReader(p)
for i,page in enumerate(reader.pages):
 if i in [0,1,9,10,15,16,29,30,45,46,57,58,60,61]:print(i+1, page.extract_text().splitlines()[:3])
path='src/test/resources/compat/layouts/layouts.txt';before=subprocess.check_output(['git','show','616fe7a1^:'+path],text=True).splitlines();after=Path(path).read_text().splitlines();n=0
for old,new in zip(before,after):
 if old==new:continue
 n+=1
 for key in ['box','pipes','terminal']:
  left=next((v.strip() for v in old.split('|') if v.strip().startswith(key)),None);right=next((v.strip() for v in new.split('|') if v.strip().startswith(key)),None)
  assert left==right,(key,left,right)
assert len(before)==len(after)==96
print('96 layout entries;',n,'cell/stamp changes; boxes, pipe cells, shaft terminals unchanged')

