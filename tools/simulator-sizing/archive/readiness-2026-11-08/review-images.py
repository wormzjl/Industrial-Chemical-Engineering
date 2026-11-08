from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
base=Path('tmp/equipment-drawings/implementation-readiness-050cd109');out=base/'inspection';out.mkdir(exist_ok=True)
font=ImageFont.truetype('C:/Windows/Fonts/arial.ttf',14)
def contacts(paths,prefix,per=16,w=400,h=310,cols=4):
 for start in range(0,len(paths),per):
  group=paths[start:start+per];canvas=Image.new('RGB',(cols*w,((len(group)+cols-1)//cols)*h),'#e5eaed');d=ImageDraw.Draw(canvas)
  for i,p in enumerate(group):
   im=Image.open(p).convert('RGB');im.thumbnail((w-14,h-35));x=(i%cols)*w;y=(i//cols)*h;canvas.paste(im,(x+(w-im.width)//2,y+24));d.text((x+7,y+5),p.name,font=font,fill='#182e3b')
  canvas.save(out/f'{prefix}-{start//per+1}.png')
ps=sorted((base/'sheets/pages').glob('*.png'));contacts(ps,'pages');ss=sorted((base/'sprite-renders').glob('*.png'));contacts(ss,'sprites',per=20,w=360,h=360,cols=5)
print('Raster pages',len(ps),'sprite proofs',len(ss),'contact images',len(list(out.glob('*.png'))))
