from pathlib import Path
import json,html,sys
from PIL import Image,ImageOps,ImageDraw,ImageFont
from pypdf import PdfReader
root=Path(__file__).resolve().parent/(sys.argv[1] if len(sys.argv)>1 else 'drawings')
manifest=json.loads((root/'measurements.json').read_text());records=manifest['sheets']
reader=PdfReader(root/'equipment-control-interfaces.pdf')
assert len(reader.pages)==len(records)+1
for rec,page in zip(records,reader.pages[1:]):
 text=page.extract_text()
 assert rec['title'] in text,rec['id']
 expected=rec.get('section',{'INTERCONNECT':'CONNECTIONS','INSTRUMENTS':'INSTRUMENT MOUNTS','REGISTER':'MANUAL ITEMS','RESEARCH':'RESEARCH','HARDWARE':'INSTRUMENT HARDWARE','SIGNALS':'PROCESS CHANNEL DEFINITIONS'}.get(rec.get('mode'),'PORT FACE CENTRES'));assert expected in text,(rec['id'],expected)
 assert 'PROPOSAL ONLY' in text
 assert rec['nativeFacets']>0 or rec.get('mode') in ['REGISTER','RESEARCH','SIGNALS']
font=ImageFont.truetype('C:/Windows/Fonts/arial.ttf',18)
for batch,start in enumerate(range(0,len(reader.pages),16),1):
 tilew,tileh=440,336;canvas=Image.new('RGB',(tilew*4,tileh*4),'#dfe7eb');draw=ImageDraw.Draw(canvas)
 for k,page in enumerate(range(start,min(start+16,len(reader.pages)))):
  im=Image.open(root/'pages'/f'sheet-{page+1:02d}.png').convert('RGB');im.thumbnail((tilew-12,tileh-35))
  x=(k%4)*tilew;y=(k//4)*tileh;canvas.paste(im,(x+(tilew-im.width)//2,y+5))
  caption='Cover' if page==0 else f'{page:02d}  '+records[page-1]['title']
  while draw.textlength(caption,font=font)>tilew-16:caption=caption[:-4]+'...'
  draw.text((x+8,y+tileh-27),caption,font=font,fill='#233b49')
 canvas.save(root/f'overview-{batch}.jpg',quality=94)
cards=[]
for rec in records:
 src=f'pages/sheet-{rec["sheet"]+1:02d}.png';title=html.escape(rec['title'])
 cards.append(f'<a class="card" href="{src}"><img loading="lazy" src="{src}" alt="{title}"><span>{rec["sheet"]:02d} / {title}</span></a>')
page='''<!doctype html><html lang="en"><meta charset="utf-8"><title>IChemE engineering drawings</title>
<style>*{box-sizing:border-box}body{margin:0;background:#e7edef;color:#233b49;font:16px/1.5 Segoe UI,Arial,sans-serif}header{background:#233b49;color:white;padding:36px max(28px,5vw)}h1{font-size:32px;margin:4px 0 12px}header p{max-width:1000px;color:#d2e1e7}a{color:#0875a8}header a{display:inline-block;background:#bfe4f2;color:#183b4c;padding:10px 16px;margin-right:12px;text-decoration:none;border-radius:3px}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(430px,1fr));gap:22px;padding:30px 4vw}.card{display:block;background:white;text-decoration:none;box-shadow:0 2px 8px #203b4c15}.card:hover{outline:2px solid #1689bc}.card img{display:block;width:100%;height:auto}.card span{display:block;padding:12px 16px;color:#233b49;font-weight:600}.note{margin:0 4vw 30px;max-width:1100px;font-size:14px}footer{padding:20px 4vw;background:#d8e1e6}</style>
<header><small>IChemE / DESIGN REVIEW / __SNAPSHOT__</small><h1>Equipment control interfaces</h1>
<p>Revision L joins exterior probes to one shared baseline, with horizontal/vertical branches and a single controller connection. Only short probe-to-surface metallic links are modelled; the rest is surface texture, following native head curves where needed. Includes four enlarged conduit/probe-mount sheets. 80 mm P/T and 100 mm level necks with substantial process flanges replace fragile-looking stems. All preceding instrument positions, channel schedules, enclosures and network socket datums are retained. This remains a design proposal.</p>
<a href="equipment-control-interfaces.pdf">Open bookmarked drawing book</a><a href="measurements.json">Coordinates and reference sizes</a></header>
<div class="grid">'''+''.join(cards)+'''</div><p class="note">Grey: native equipment. Blue: proposed or reused hardware/castings/supports. Cyan: DATA. Orange: FE. Metal grey: fixed instrument conduit. Amber outlines: ladder/player keep-clear guide. Main orthographic views share the labelled scale; enlarged mounting and cable views have separate scales. D and P mark conduit continuation to the data network or FE supply. Cable bends use the native conduit/pipe mesher; spines are proposed reference layouts. Functional placement, occupied cells and world routing remain pending implementation.</p><footer>Preview only. No product code changed. No client launch, game tests or merge preparation.</footer></html>'''
(root/'index.html').write_text(page.replace('__SNAPSHOT__',manifest['snapshot']),encoding='utf-8')
print(f'Packaged {len(records)} sheets + cover; all titles, port schedules and proposal labels present.')
