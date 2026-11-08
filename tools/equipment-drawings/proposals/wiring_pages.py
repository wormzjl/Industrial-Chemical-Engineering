"""Enlarged actual equipment contexts for the fixed instrument-wiring proposal."""
import json,re,numpy as np
import sensor_layouts,instrument_wiring

def scene(k,name):
 d=json.loads((k.ROOT/'geometry'/f'{name}.json').read_text());native=k.raw(d);p=k.proposal(d,native);cat=sensor_layouts.plan(d,native,k)
 wire=instrument_wiring.build(d,native,p,cat,k)
 return d,p,cat,wire,sensor_layouts.equipment_scene(d,native,p,cat,k)

def detail(c,k,scene_data,centre,extent,rect,label,direction=(1,1.0,-1.3)):
 d,p,cat,wire,polys=scene_data;centre=np.array(centre);extent=np.array(extent)
 if 'PROBE' in label or 'PROCESS ROOT' in label or 'LEVEL HEAD' in label:
  probe=min(cat['points']+cat['factory'],key=lambda v:np.linalg.norm(np.array(v['centre'])-centre));n=np.array(probe['normal']);direction=n*1.3+sensor_layouts.basis(n)[:,0]*.8+[0,.65,0]
  # A cropped neighbouring head without its carrier reads as a floating part.
  # Show this instrument's hardware; keep the shared surface routes as context.
  index=next(i for i,path in enumerate(wire['paths']) if path['source']==probe['mount']);material='METAL_CONDUIT_'+d['id']+'/'+str(index)
  polys=sensor_layouts.equipment_scene(d,k.raw(d),p,cat,k,sensor_layouts.hardware_point(probe,k))
  polys=[(v,m) for v,m in polys if not m.startswith('METAL_CONDUIT_') or m==material]
 cut=k.clipped(polys,centre-extent/2,centre+extent/2);basis=k.camera_basis(direction);vertices=np.concatenate([v for v,m in cut])@basis.T;span=np.ptp(vertices[:,:2],axis=0)
 scale=min((rect[2]-20)/span[0],(rect[3]-35)/span[1]);slug=re.sub(r'[^A-Za-z0-9_-]+','-',label);k.drawview(c,cut,basis,rect,scale,k.VIEWS/f'{k.ACTIVE}-{slug}.png',label+f' | 1:{2834.6457/scale:.1f}',False)

def pages(c,k,start):
 records=[]
 drum=scene(k,'VERTICAL');hx=scene(k,'EXCHANGER_STANDARD');pump=scene(k,'PUMP_OH2');tower=scene(k,'NATURAL_DRAFT_COOLING_TOWER')
 specs=[
 ('FIXED_WIRING','Instrument wiring - metallic conduit and sealed entries',[
  (drum,drum[2]['points'][0]['centre'],[1.5,1.2,1.5],'PROBE / SHORT FIXED LEAD'),
  (drum,(np.array(drum[1]['low'])+drum[1]['high'])/2,[1.1,1.0,1.1],'C1 / SUPPORTED REAR ENTRY')],
  ['20 mm branch / 25 mm trunk metallic conduit. Only probe-to-surface sections are modelled; longer routes are surface texture.',
   'The short metallic probe connections use the native pipe sweep. Metallic highlights, collars, clips and junctions on equipment use texture.',
   'Conduit markings are painted on the native facets, including curved heads. The controller drop follows its mounting section into the supported rear pad.',
   'Fixed wiring belongs to the equipment visual model. It adds no player-laid cable, network node, collision or placement rule.']),
 ('THERMAL_WIRING','Instrument wiring - exchanger and small rotating machine',[
  (hx,hx[2]['factory'][0]['centre'],[1.5,1.5,1.5],'EXCHANGER / FACTORY PROBE'),
  (pump,(np.array(pump[1]['low'])+pump[1]['high'])/2,[1.4,1.0,1.4],'SMALL PUMP / INTERNAL PICKUPS')],
  ['Four exchanger connection groups retain their separate passage identity. Surface branches join the same C1 enclosure.',
   'Machine pickups are wired inside their casing; there are no exterior pseudo-probes or deck leads. The small pump enclosure stays tucked on its drive-end deck.',
   'Long runs share one surface-textured baseline. Wiring follows horizontal/vertical routes; where only an inclined structural carrier exists, it remains concealed inside that support.',
   'Process connections, shafts, controller channels and external DATA/FE cable datums retain the preceding revision.']),
 ('TOWER_WIRING','Instrument wiring - large equipment details',[
  (tower,next(p for p in tower[2]['factory'] if p['kind']=='L')['centre'],[1.6,1.5,1.6],'TOWER / FACTORY LEVEL HEAD'),
  (tower,(np.array(tower[1]['low'])+tower[1]['high'])/2,[1.6,1.3,1.6],'TOWER / C1 TRUNK ENTRY')],
  ['Large equipment uses the same metallic-conduit texture, sealed short probe connections and reinforced process mounts as small equipment.',
   'The factory basin level instrument retains its existing head and tap location. Inlet/outlet P/T channels remain built in.',
   'Raceways follow actual equipment facets and supports. Future drum valve reservations remain clear.',
   'Reference geometry only. Full-size and heading sweeps, occupied cells and runtime implementation follow drawing acceptance.'])]
 for offset,(id,title,views,notes) in enumerate(specs):
  index=start+offset;k.ACTIVE=id;k.titleblock(c,index,title,'Revision M | Native-facet conduit finish | Exact native context, proposed metallic conduits and reinforced process mounts')
  for rect,(data,centre,extent,label) in zip([(40,300,530,400),(620,300,530,400)],views):detail(c,k,data,centre,extent,rect,label)
  k.txt(c,40,255,'FIXED METALLIC INSTRUMENT CONDUIT',11,k.BLUE,'Helvetica-Bold');y=229
  for note in notes:y=k.lines(c,40,y,note,153,10,15)-12
  assert y>60,y
  c.bookmarkPage(id);c.addOutlineEntry(title,id,level=0);c.showPage();records.append(dict(id=id,title=title,sheet=index,mode='WIRING',section='FIXED METALLIC INSTRUMENT CONDUIT',nativeFacets=sum(len(v[0][4]) for v in views),ports=[],notes=notes))
 # The actual same drum provides all three root connections at equal scale.
 index=start+len(specs);id='PROBE_PROCESS_MOUNTS';title='Probe mounting - pressure, temperature and level';k.ACTIVE=id
 k.titleblock(c,index,title,'Thicker load-bearing necks | Process bosses and hex fittings | Existing head, tap and insertion datums retained')
 for i,probe in enumerate(drum[2]['points']):
  detail(c,k,drum,np.array(probe['centre'])+[0,-.24,0],[.70,1.15,.70],(40+i*375,315,355,390),probe['kind']+' / PROCESS ROOT',direction=(1,.65,-1.3))
 k.txt(c,40,272,'REINFORCED PROCESS CONNECTIONS',11,k.BLUE,'Helvetica-Bold');y=246
 for note in ['P: 80 mm supported neck, 170 mm root flange and 110 mm hex process fitting. No thin unsupported mast.',
              'T: 80 mm extension/thermowell neck, 170 mm root flange and 110 mm process fitting; the immersion tip remains in its original passage.',
              'L: 100 mm supported neck, 220 mm mounting flange and 140 mm process fitting. The body/boot identities and guided insertion remain separate.',
              'Built-in small T heads use a 60 mm neck and 90 mm mounting collar. All dimensions above are game-model choices, not selected pressure ratings.']:
  y=k.lines(c,40,y,note,152,10,15)-12
 for x,label,url in [(40,'ABB Kopex-Ex conduit and sealed fittings','https://new.abb.com/low-voltage/products/conduit-fittings/kopex-ex/ex-metallic-conduit-systems/ex-metallic-fittings/exs-fittings'),(430,'Emerson / direct pressure installation','https://www.emerson.com/en/measurement-instrumentation/products/rosemount-3051-pressure-transmitter-family'),(800,'WIKA / heavy-duty flanged thermowell','https://www.wika.com/en-us/tw10-2.WIKA')]:
  k.txt(c,x,68,label,8.3,k.BLUE);c.linkURL(url,(x,65,x+330,80),relative=0,thickness=0)
 c.bookmarkPage(id);c.addOutlineEntry(title,id,level=0);c.showPage();records.append(dict(id=id,title=title,sheet=index,mode='WIRING',section='REINFORCED PROCESS CONNECTIONS',nativeFacets=len(drum[4]),ports=[]))
 records.append(baseline_page(c,k,start+len(records)))
 return records

def baseline_page(c,k,index):
 k.ACTIVE='SHARED_BASELINE';title='Instrument conduit - one common baseline';data=scene(k,'LENGTHWISE');d,p,cat,wire,polys=data
 k.titleblock(c,index,title,'One defined trunk | Horizontal/vertical joins | Native horizontal-drum reference | Probe positions retained')
 heads=cat['points'];centres=np.array([p['centre'] for p in heads]);low=centres.min(0)-[.5,.75,.45];high=centres.max(0)+[.5,.20,.45];cut=k.clipped(polys,low,high)
 for basis,rect,name,label in [(k.camera_basis([1,1,-.7]),(40,285,550,400),'baseline-roof-iso','ROOF / SHARED TRUNK'),(k.TOP,(635,285,510,400),'baseline-roof-plan','TOP / ONE BASELINE')]:
  projected=np.concatenate([v for v,m in cut])@basis.T;span=np.ptp(projected[:,:2],axis=0);scale=min((rect[2]-35)/span[0],(rect[3]-35)/span[1]);origin,scale=k.drawview(c,cut,basis,rect,scale,k.VIEWS/(name+'.png'),label+f' | 1:{2834.6457/scale:.1f}',False)
  if name.endswith('plan'):
   for probe in heads:
    at=origin+(np.array(probe['tap'])@basis.T)[:2]*scale;colour={'P':'#80589b','T':'#a65b39','L':'#25776d'}[probe['kind']]
    k.line(c,at,[rect[0]+65,at[1]],colour,.8);c.setFillColorRGB(1,1,1);c.setStrokeColor(k.HexColor(colour));c.circle(*at,5,stroke=1,fill=1);k.txt(c,rect[0]+12,at[1]-3,probe['tag'],11,colour,'Helvetica-Bold')
 k.txt(c,40,252,'ONE BASELINE / HORIZONTAL AND VERTICAL RUNS',11,k.BLUE,'Helvetica-Bold');y=226
 for note in ['The grey surface conduit has one baseline definition. Every probe joins that same centreline at its own station; it is drawn once.',
              'A single controller leg leaves the shared trunk. Branches and flat-surface legs use horizontal/vertical turns; there are no diagonal shortcuts.',
              'Curved heads follow the native surface along the chosen horizontal or vertical surface coordinate. The mesh does not select a separate baseline for each probe.',
              'Only the short protected probe-to-surface drops are modelled. The common trunk, couplings and branch routes are surface textures. Coloured leaders identify probes.']:
  y=k.lines(c,40,y,note,150,10,15)-12
 assert y>60,y
 c.bookmarkPage(k.ACTIVE);c.addOutlineEntry(title,k.ACTIVE,level=0);c.showPage()
 return dict(id=k.ACTIVE,title=title,sheet=index,mode='WIRING',section='ONE BASELINE / HORIZONTAL AND VERTICAL RUNS',nativeFacets=len(polys),ports=[],reference=d['id'],baseline=wire['baseline'],branches=wire['branches'])

def baseline_detail(k):
 """Native top-view close-up, with labels pinned to the actual shared joins."""
 from PIL import Image,ImageDraw,ImageFont
 d,p,cat,wire,polys=scene(k,'LENGTHWISE');basis=k.TOP;joins=np.array([b['join'] for b in wire['branches']])@basis.T
 low=joins[:,:2].min(0)-[.35,.32];high=joins[:,:2].max(0)+[.35,.32];temporary=k.OUT/'common-baseline-native.png'
 k.render(polys,basis,(low,high),temporary,1100)
 im=Image.open(temporary).convert('RGB');canvas=Image.new('RGB',(im.width+130,im.height+100),'#f2f5f6');canvas.paste(im,(90,55));draw=ImageDraw.Draw(canvas)
 font=ImageFont.truetype('C:/Windows/Fonts/arial.ttf',22);bold=ImageFont.truetype('C:/Windows/Fonts/arialbd.ttf',22)
 draw.text((15,14),'One common baseline',fill='#233b49',font=bold);scale=1100/max(high-low)
 for branch,head in zip(wire['branches'],cat['points']):
  q=np.array(branch['join'])@basis.T;x=90+(q[0]-low[0])*scale;y=55+im.height-(q[1]-low[1])*scale;colour={'P':'#80589b','T':'#a65b39','L':'#25776d'}[head['kind']]
  draw.line([(52,y),(x-14,y)],fill=colour,width=3);draw.ellipse((x-14,y-14,x+14,y+14),fill='white',outline=colour,width=3);draw.text((12,y-13),head['tag'],fill=colour,font=bold)
 draw.text((15,canvas.height-32),'Shared P / T / L trunk',fill='#233b49',font=font);canvas.save(k.OUT/'common-baseline-detail.png')

def revision_previews(k):
 """Readable roof reviews from the same native-facet scene as the book."""
 from PIL import Image,ImageDraw,ImageFont
 font=ImageFont.truetype('C:/Windows/Fonts/arial.ttf',24);bold=ImageFont.truetype('C:/Windows/Fonts/arialbd.ttf',26)
 for id in ['VERTICAL','SPHERE','SPHERE_SMALL']:
  d,p,cat,wire,polys=scene(k,id);k.ACTIVE=id+'_INSTRUMENTS'
  if id=='VERTICAL':
   tops=[np.array(p['centre']) for p in cat['points']];lo=np.array([.25,min(p[1] for p in tops)-1.4,.25]);hi=np.array([6.75,max(p[1] for p in tops)+.3,6.75])
  else:
   centre=np.array(d['topPlatform']['centre']);radius=d['topPlatform']['radius'];lo=centre-[radius+.5,2.0,radius+.5];hi=centre+[radius+.5,1.15,radius+.5]
  cut=k.clipped(polys,lo,hi);basis=k.TOP;vertices=np.concatenate([v for v,m in cut])@basis.T;low=vertices[:,:2].min(0)-.2;high=vertices[:,:2].max(0)+.2
  path=k.OUT/(id.lower()+'-roof-native.png');k.render(cut,basis,(low,high),path,1100);im=Image.open(path).convert('RGB');canvas=Image.new('RGB',(im.width+80,im.height+130),'white');canvas.paste(im,(40,75));draw=ImageDraw.Draw(canvas)
  draw.text((35,20),k.TITLES[id]+' / top service arrangement',font=bold,fill=k.INK);scale=1100/max(high-low)
  def position(point):
   q=np.array(point)@basis.T;return np.array([40+(q[0]-low[0])*scale,75+im.height-(q[1]-low[1])*scale])
  for head in cat['points']:
   at=position(head['centre']);colour={'P':'#80589b','T':'#a65b39','L':'#25776d'}[head['kind']];target=at+np.array([0,-55])
   draw.line([tuple(at),tuple(target+[0,20])],fill=colour,width=2);draw.ellipse((*tuple(at-7),*tuple(at+7)),fill='white',outline=colour,width=3);draw.text(tuple(target),head['tag'],font=bold,fill=colour,anchor='mt')
  for reserve in cat.get('reserved',[]):
   a=position(reserve['min']);b=position(reserve['max']);box=[min(a[0],b[0]),min(a[1],b[1]),max(a[0],b[0]),max(a[1],b[1])];draw.rectangle(box,outline='#b37d2c',width=3);draw.text((box[0]+8,box[1]+8),reserve['tag']+' reserved',font=font,fill='#9a681f')
  draw.text((35,canvas.height-40),'PROPOSAL ONLY / native geometry / rear reservations retained' if id=='VERTICAL' else 'PROPOSAL ONLY / P, T and L1 serviced from the top platform',font=font,fill=k.MUTE)
  canvas.save(k.OUT/(id.lower()+'-top-arrangement.png'))
