"""Enlarged actual equipment contexts for the fixed instrument-wiring proposal."""
import json,re,numpy as np
import sensor_layouts,instrument_wiring

def scene(k,name):
 d=json.loads((k.ROOT/'geometry'/f'{name}.json').read_text());native=k.raw(d);p=k.proposal(d,native);cat=sensor_layouts.plan(d,native,k)
 wire=instrument_wiring.build(d,native,p,cat,k)
 return d,p,cat,wire,native+p['extra']+sensor_layouts.geometry(cat,k)+wire['polys']

def detail(c,k,scene_data,centre,extent,rect,label,direction=(1,1.0,-1.3)):
 d,p,cat,wire,polys=scene_data;centre=np.array(centre);extent=np.array(extent)
 if 'PROBE' in label or 'PROCESS ROOT' in label or 'LEVEL HEAD' in label:
  probe=min(cat['points']+cat['factory'],key=lambda v:np.linalg.norm(np.array(v['centre'])-centre));n=np.array(probe['normal']);direction=n*1.3+sensor_layouts.basis(n)[:,0]*.8+[0,.65,0]
  # A cropped neighbouring head without its carrier reads as a floating part.
  # Show this instrument's hardware; keep the shared surface routes as context.
  index=next(i for i,path in enumerate(wire['paths']) if path['source']==probe['mount']);material='METAL_CONDUIT_'+d['id']+'/'+str(index)
  polys=k.raw(d)+p['extra']+sensor_layouts.hardware_point(probe,k)+[(v,m) for v,m in wire['polys'] if not m.startswith('METAL_CONDUIT_') or m==material]
 cut=k.clipped(polys,centre-extent/2,centre+extent/2);basis=k.camera_basis(direction);vertices=np.concatenate([v for v,m in cut])@basis.T;span=np.ptp(vertices[:,:2],axis=0)
 scale=min((rect[2]-20)/span[0],(rect[3]-35)/span[1]);slug=re.sub(r'[^A-Za-z0-9_-]+','-',label);k.drawview(c,cut,basis,rect,scale,k.VIEWS/f'{k.ACTIVE}-{slug}.png',label+f' | 1:{2834.6457/scale:.1f}',False)

def pages(c,k,start):
 records=[]
 drum=scene(k,'VERTICAL');hx=scene(k,'EXCHANGER_STANDARD');pump=scene(k,'PUMP_OH2');tower=scene(k,'NATURAL_DRAFT_COOLING_TOWER')
 specs=[
 ('FIXED_WIRING','Instrument wiring - metallic conduit and sealed entries',[
  (drum,drum[2]['points'][0]['centre'],[1.5,1.2,1.5],'PROBE / SHORT FIXED LEAD'),
  (drum,(np.array(drum[1]['low'])+drum[1]['high'])/2,[1.1,1.0,1.1],'C1 / SMALL SIDE GLAND')],
  ['20 mm branch / 25 mm trunk metallic conduit. Only probe-to-surface sections are modelled; longer routes are surface texture.',
   'The short metallic probe connections use the native pipe sweep. Metallic highlights, collars, clips and junctions on equipment use texture.',
   'Textured metallic conduit takes straight axial routes on barrels and supports, following the curved surface on vessel heads. No exposed electrical tails.',
   'Fixed wiring belongs to the equipment visual model. It adds no player-laid cable, network node, collision or placement rule.']),
 ('THERMAL_WIRING','Instrument wiring - exchanger and small rotating machine',[
  (hx,hx[2]['factory'][0]['centre'],[1.5,1.5,1.5],'EXCHANGER / FACTORY PROBE'),
  (pump,(np.array(pump[1]['low'])+pump[1]['high'])/2,[1.4,1.0,1.4],'SMALL PUMP / INTERNAL PICKUPS')],
  ['Four exchanger connection groups retain their separate passage identity. Surface branches join the same C1 enclosure.',
   'Machine pickups are wired inside their casing; there are no exterior pseudo-probes or deck leads. The small pump enclosure stays tucked on its drive-end deck.',
   'Long conduit runs are texture on the actual surface. Connections crossing an assembly joint are concealed inside the equipment; no floating textured span is added.',
   'Process connections, shafts, controller channels and external DATA/FE cable datums retain the preceding revision.']),
 ('TOWER_WIRING','Instrument wiring - large equipment details',[
  (tower,next(p for p in tower[2]['factory'] if p['kind']=='L')['centre'],[1.6,1.5,1.6],'TOWER / FACTORY LEVEL HEAD'),
  (tower,(np.array(tower[1]['low'])+tower[1]['high'])/2,[1.6,1.3,1.6],'TOWER / C1 TRUNK ENTRY')],
  ['Large equipment uses the same metallic-conduit texture, sealed short probe connections and reinforced process mounts as small equipment.',
   'The factory basin level instrument retains its existing head and tap location. Inlet/outlet P/T channels remain built in.',
   'Raceways follow actual equipment facets and supports. Future drum valve reservations remain clear.',
   'Reference geometry only. Full-size and heading sweeps, occupied cells and runtime implementation follow drawing acceptance.'])]
 for offset,(id,title,views,notes) in enumerate(specs):
  index=start+offset;k.ACTIVE=id;k.titleblock(c,index,title,'Revision K | Fixed equipment-owned metallic conduits | Exact native context, proposed metallic conduits and reinforced process mounts')
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
 return records
