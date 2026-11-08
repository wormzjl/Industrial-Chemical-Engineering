"""Instrument annotations, enlarged layouts and applicability register from sensor_layouts."""
import json,math,numpy as np
import sensor_layouts
import instrument_policy
import signal_catalog

COLOURS={'P':'#80589b','T':'#a65b39','L':'#25776d','LG':'#536d78'}
SOURCES=[
 ('R1 / Emerson 3051, hardware installation','Gas-service taps and transmitter orientation.','https://www.emerson.com/is/content/emerson/en/measurement-instrumentation/technical/products/pressure/documents/doc-rosemount-3051-00809-0105-4007.pdf'),
 ('R2 / Endress+Hauser, multipoint thermometers','Vertical/flexible immersion assemblies for vessels and pressure service.','https://www.endress.com/en/field-instruments-overview/temperature-measurement-thermometers-transmitters/multipoint-thermometers'),
 ('R3 / Emerson Rosemount 765','Immersed elements determine liquid temperature; a roof head alone does not.','https://www.emerson.com/en/measurement-instrumentation/products/rosemount-765-multiple-spot-temperature-and-water-level-sensor'),
 ('R4 / Emerson 5408, product data sheet','Separate nozzle-mounted radar; liquid/solid service and obstruction considerations.','https://www.emerson.com/is/content/emerson/en/measurement-instrumentation/technical/products/level/documents/doc-rosemount-00813-0100-4408.pdf'),
 ('R5 / BAC Series 1500 O&M manual','Cold-basin temperature/probe controls and optional level instrumentation.','https://baltimoreaircoil.com/es/download_api_endpoint/438/1500_om_20150909.pdf'),
 ('R6 / Endress+Hauser tank-gauging guide','Spherical-tank instrumentation example, printed p.58. P/T top access here follows the owner requirement.','https://www.jp.endress.com/_storage/asset/6121588/storage/master/file/55798090/download/GL01027FEN0118.pdf')]

def hardware_page(c,k,id,index):
 k.ACTIVE=id;level=id=='LEVEL_SENSOR_HOUSING';polys=sensor_layouts.native_assets(k,'digital_level' if level else 'digital');title='Digital level instrument head' if level else 'Digital pressure / temperature instrument head'
 k.titleblock(c,index,title,'Native instrument casing geometry | Process connection and fixed wiring are separate from the equipment DATA conduit')
 span=np.ptp(np.concatenate([v for v,m in polys]),0);scale=min(300/span[0],235/span[1],300/span[2]);ratio=2834.6457/scale;denom=next(v for v in [1,2,2.5,3,5,10] if v>=ratio);scale=2834.6457/denom
 for basis,rect,suffix,label in [(np.eye(3),(50,434,338,260),'front','DISPLAY FACE / FROM +Z'),(k.SIDE,(455,434,338,260),'side','SIDE'),(k.TOP,(50,99,338,255),'top','TOP')]:k.drawview(c,polys,basis,rect,scale,k.VIEWS/f'{index:02d}-hardware-{suffix}.png',f'{label} | 1:{denom:g}')
 x,y=858,724;k.txt(c,x,y,'INSTRUMENT HARDWARE',11,k.BLUE,'Helvetica-Bold');y-=25
 for text in ['Head width / height / depth: '+('280 / 230 / 137 mm' if level else '230 / 230 / 137 mm')+' including the raised ID trim.',
              'This is the native closed metal instrument body, with its display and ID trim. It is not an added equipment controller box.',
              'Equipment-mounted heads report through the host DATA interface at C1. Fixed instrument leads use small sealed glands.',
              'The 125 mm placeable DATA conduit joins C1, not the process entry shown on this head.',
              'Permitted items and top/back process-entry arrangements depend on the host and mount. See the applicability register.',
              'P/T dial and digital instruments share the same process slot; they cannot both occupy it. Level uses its separate level slot.',
              'A common housing appearance does not permit vessel-mounted flowmeters or extra manual sensors on automatically instrumented equipment.']:
  y=k.lines(c,x,y,text,51,9,13)-16
 c.bookmarkPage(id);c.addOutlineEntry(title,id,level=0);c.showPage();return dict(id=id,title=title,sheet=index,mode='HARDWARE',nativeFacets=len(polys),ports=[],bodyDimensions=span.tolist())

def mark(c,catalog,basis,origin,scale,rect,k):
 x,y,w,h=rect;items=catalog['points']+catalog['factory']
 for i,p in enumerate(items):
  colour=COLOURS.get(p['kind'],k.BLUE) if p['manual'] else '#a87b26';at=origin+(np.array(p['centre'])@basis.T)[:2]*scale
  end=np.array([x+7,y+h-15-15*i]);k.line(c,at,end,colour,.55,[2,2] if not p['manual'] else None)
  c.setFillColorRGB(1,1,1);c.setStrokeColor(k.HexColor(colour));c.circle(*at,2.5,fill=1,stroke=1)
  k.txt(c,end[0]+3,end[1]+3,p['tag']+(' built-in' if not p['manual'] else ''),7.5,colour,'Helvetica-Bold')
 for i,p in enumerate(sorted(catalog.get('internal',[]),key=lambda p:-(np.array(p['centre'])@basis.T)[1])):
  colour='#597583';at=origin+(np.array(p['centre'])@basis.T)[:2]*scale;end=np.array([x+7,y+h-15-15*(len(items)+i)])
  k.line(c,at,end,colour,.55,[2,2]);c.setFillColorRGB(1,1,1);c.setStrokeColor(k.HexColor(colour));c.circle(*at,2.2,fill=1,stroke=1);k.txt(c,end[0]+3,end[1]+3,p['tag']+' internal',7.2,colour,'Helvetica-Bold')
 for i,r in enumerate(catalog.get('reserved',[])):
  lo=np.array(r['min']);hi=np.array(r['max']);verts=np.array([[lo[0] if not a else hi[0],lo[1] if not b else hi[1],lo[2] if not z else hi[2]] for a in range(2) for b in range(2) for z in range(2)]);q=origin+(verts@basis.T)[:,:2]*scale
  for a in range(8):
   for bit in [1,2,4]:
    b=a^bit
    if a<b:k.line(c,q[a],q[b],'#b0783b',.7,[3,2])
  at=origin+(np.array(r['tap'])@basis.T)[:2]*scale;end=np.array([x+7,y+h-15-15*(len(items)+i)]);k.line(c,at,end,'#b0783b',.6,[3,2]);k.txt(c,end[0]+3,end[1]+3,r['tag']+' reserved',7.5,'#b0783b','Helvetica-Bold')

def reservation_vertices(catalog):
 return np.array([[x,y,z] for r in catalog.get('reserved',[]) for x in [r['min'][0],r['max'][0]] for y in [r['min'][1],r['max'][1]] for z in [r['min'][2],r['max'][2]]]).reshape(-1,3)

def view(c,polys,catalog,basis,rect,name,title,k,hidden=False):
 reserved=reservation_vertices(catalog);projected=np.concatenate([np.concatenate([v for v,m in polys]),reserved])@basis.T;span=np.ptp(projected[:,:2],0);sc=min((rect[2]-30)/span[0],(rect[3]-20)/span[1]);origin,sc=k.drawview(c,polys,basis,rect,sc,k.VIEWS/name,title+f' | 1:{2834.6457/sc:.1f}',False,fitpoints=reserved)
 mark(c,catalog,basis,origin,sc,rect,k)
 if hidden:
  for p in catalog['points']:
   for tip,colour in [('probeTip',COLOURS['T']),('guidedTip',COLOURS['L'])]:
    if p.get(tip):
     a=origin+(np.array(p['tap'])@basis.T)[:2]*sc;b=origin+(np.array(p[tip])@basis.T)[:2]*sc;k.line(c,a,b,colour,1.,[4,3])
 return origin,sc

def table(c,d,cat,x,y,k):
 k.txt(c,x,y,'INSTRUMENT MOUNTS',11,k.BLUE,'Helvetica-Bold');y-=22
 for p in cat['points']+cat['factory']:
  colour=COLOURS.get(p['kind'],k.BLUE) if p['manual'] else '#a87b26';kind='MANUAL' if p['manual'] else 'BUILT IN'
  k.txt(c,x,y,p['tag']+' / '+kind+' / '+p['mount'],8.8,colour,'Helvetica-Bold');y-=14
  k.txt(c,x,y,'Head X / Y / Z: '+' / '.join(f'{v:.3f}' for v in p['centre']),8.4);y-=13
  k.txt(c,x,y,'Tap  X / Y / Z: '+' / '.join(f'{v:.3f}' for v in p['tap']),8.4);y-=14
  choices=' or '.join(p['allowed']) if p['manual'] else p['note'];y=k.lines(c,x,y,choices,52,8.2,11)-6
  if p.get('probeTip'):
   y=k.lines(c,x,y,f'Top-entry immersion path: {p["tap"][1]-p["probeTip"][1]:.3f} m to the depicted tip. R2/R3.',52,8.1,11)-7
  if p.get('guidedTip'):y=k.lines(c,x,y,f'Guided probe through body into boot: {p["tap"][1]-p["guidedTip"][1]:.3f} m. Distinct level_boot channel.',52,8.1,11)-7
  k.line(c,(x,y+3),(x+316,y+3),'#d4dfe4',.5);y-=10
 for r in cat.get('reserved',[]):
  k.txt(c,x,y,r['tag']+' / FUTURE RESERVE / '+r['name'],8.5,'#b0783b','Helvetica-Bold');y-=14
  k.txt(c,x,y,'Roof centre X / Y / Z: '+' / '.join(f'{v:.3f}' for v in r['tap']),8.1);y-=13
  y=k.lines(c,x,y,r['note'],53,8.1,11)-6
 y=k.lines(c,x,y,cat['policy'],53,9,13)-13
 notes=[]
 if any(p['manual'] and p['kind'] in ('P','T') for p in cat['points']):notes.append('P/T dial and digital alternatives share one slot; one instrument occupies that slot.')
 notes.append('Equipment sensors feed C1 through fixed metallic conduit. The placeable DATA conduit remains 125 mm at the equipment enclosure.')
 if any(p['kind']=='L' for p in cat['points']+cat['factory']):notes.append('Level means liquid + solid height; gas is excluded.')
 if d.get('boot'):notes.append('Boot L2 retains the current heavy-liquid plus allocated-solids reading over the boot span. It is not a duplicate of body L1.')
 if d['kind']=='EXCHANGER':notes.append('P/T/flow are exposed at all four stream connections. Each channel reads its own bulk stream, not the exchanger wall; probe tips enter their named passages.')
 elif d['kind'] in signal_catalog.TOWER_KINDS:notes.append('Inlet/outlet P/T and basin level reach the controller through C1. Ti and Tb mark the inlet/basin T points. L1 is factory fitted at its existing position.')
 else:notes.append('The temperature reading remains the existing bulk-state value.')
 for text in notes:
  y=k.lines(c,x,y,text,53,8.3,12)-9
 assert y>45,(d['id'],y)

def layout_page(c,k,id,index):
 k.ACTIVE=id+'_INSTRUMENTS';d=json.loads((k.ROOT/'geometry'/f'{id}.json').read_text());native=k.raw(d);p=k.proposal(d,native);cat=sensor_layouts.plan(d,native,k);hardware=sensor_layouts.equipment_visuals(d,native,p,cat,k);allpolys=native+p['extra']+hardware
 title=k.TITLES[id]+' - instrument arrangement';k.titleblock(c,index,title,'Native reference equipment + dimensioned instrument proposal | Coordinates in metres | Installed digital examples shown')
 k.line(c,(818,55),(818,744),'#bbcbd2',.6);table(c,d,cat,840,724,k)
 if d['kind']=='EXCHANGER':
  for i,pnt in enumerate(cat['factory']):
   centre=np.array(pnt['centre']);polys=k.clipped(allpolys,centre-[.55,.65,.55],centre+[.35,.65,.55]);view(c,polys,dict(points=[],factory=[pnt]),k.camera_basis([1,.65,-1]),(40+i*185,402,175,290),f'{index:02d}-thermal-{i}.png',pnt['tag']+' / '+pnt['processRole'].replace('_',' '),k)
 elif d['kind'] in signal_catalog.TOWER_KINDS:
  for i,(items,rect,dir,titlepart) in enumerate([(cat['factory'][:1],(40,402,345,290),[1,.8,-1],'HOT-WATER INLET / FACTORY T'),(cat['points']+cat['factory'][1:],(425,402,345,290),[1,.8,1],'BASIN / EXISTING LEVEL PAD')]):
   centres=np.array([s['centre'] for s in items]);lo=centres.min(0)-[.65,.65,.7];hi=centres.max(0)+[.65,.65,.7];polys=k.clipped(allpolys,lo,hi)
   view(c,polys,dict(points=[s for s in items if s['manual']],factory=[s for s in items if not s['manual']]),k.camera_basis(dir),rect,f'{index:02d}-sensor-detail-{i}.png',titlepart,k)
 else:
  top=[s for s in cat['points'] if s['entry']=='top'];lo=np.min([s['tap'] for s in top],0)-[.65,.5,.65];hi=np.max([s['centre'] for s in top],0)+[.65,.45,.65]
  if d['kind']=='SPHERE':
   pc=np.array(d['topPlatform']['centre']);r=d['topPlatform']['radius'];lo=np.minimum(lo,pc-[r+.6,1.5,r+.6]);hi=np.maximum(hi,pc+[r+.6,2.1,r+.6])
  if cat.get('reserved'):
   lo=np.minimum(lo,np.min([r['min'] for r in cat['reserved']],0)-.2);hi=np.maximum(hi,np.max([r['max'] for r in cat['reserved']],0)+.2)
  roof=k.clipped(allpolys,lo,hi);view(c,roof,dict(points=top,factory=[],reserved=cat.get('reserved',[])),k.camera_basis([1,.85,1] if d.get('boot') else [1,.85,-1]),(40,402,730,290),f'{index:02d}-sensor-roof.png','TOP SERVICE ARRANGEMENT',k)
 frontcat=dict(points=cat['points'],factory=cat['factory'],reserved=cat.get('reserved',[]));view(c,allpolys,frontcat,k.SIDE if d.get('boot') or d['kind']=='EXCHANGER' else k.FRONT,(40,90,330,245),f'{index:02d}-sensor-elevation.png','SIDE / BODY L1 AND BOOT L2' if d.get('boot') else 'FULL EQUIPMENT / HIDDEN T PATH',k,True)
 view(c,allpolys,frontcat,k.TOP,(425,90,345,245),f'{index:02d}-sensor-plan.png','PLAN / PROCESS NOZZLES RETAINED',k)
 k.txt(c,40,60,'Tags: P pressure, T temperature, L level. Solid leader = manual; dotted leader = factory. Brown dashed line = immersion path.',7.5,k.MUTE)
 c.bookmarkPage(k.ACTIVE);c.addOutlineEntry(title,k.ACTIVE,level=0);c.showPage()
 return dict(id=k.ACTIVE,title=title,sheet=index,mode='INSTRUMENTS',nativeFacets=len(native),instruments=cat,ports=[])

def register_page(c,k,index):
 k.ACTIVE='INSTRUMENT_REGISTER';title='Instrument applicability and placement register';k.titleblock(c,index,title,'Equipment-body mounts | P/T dial or digital alternatives share one slot | F belongs on a process pipe')
 x=[40,390,575];y=714
 for at,label in zip(x,['REFERENCE EQUIPMENT','MANUAL ITEMS','PLACEMENT / BUILT-IN CHANNELS']):k.txt(c,at,y,label,10,k.BLUE,'Helvetica-Bold')
 y-=23;rows=[]
 for e in sensor_layouts.entries(k):
  d=json.loads((k.ROOT/'geometry'/e['file']).read_text());cat=sensor_layouts.plan(d,k.raw(d),k);permission=sensor_layouts.permitted(cat);placement=cat['policy']
  if len(placement)>96:placement=placement[:93]+'...'
  k.txt(c,x[0],y,k.TITLES[d['id']],8.5);k.txt(c,x[1],y,permission,8.5);k.txt(c,x[2],y,placement,8.1)
  k.line(c,(40,y-7),(1150,y-7),'#d8e2e7',.4);y-=16;rows.append(dict(id=d['id'],manual=permission,policy=cat['policy']))
 y-=12
 for text in ['P: pressure gauge or digital pressure transmitter. T: thermometer or digital temperature transmitter. L: digital level transmitter.',
              'LG: optional mechanical sight column on floating-roof tanks only. A single mounting slot takes one instrument, not both dial and digital heads.',
              'Pipes: analog P/T and digital P/T/F use the existing pipe mounting rules. A control valve retains its P/F module slot; these are pipe accessories, not added vessel-body nozzles.',
              'Rotating equipment uses internal probes. Both cooling-tower types have built-in inlet/outlet P/T and level; no manual tower sensor mounts. Controller schedules list thermal and column channels.']:
  y=k.lines(c,40,y,text,168,8.4,12)-6
 assert y>50,y
 c.bookmarkPage(k.ACTIVE);c.addOutlineEntry(title,k.ACTIVE,level=0);c.showPage();return dict(id=k.ACTIVE,title=title,sheet=index,mode='REGISTER',nativeFacets=0,ports=[],rows=rows)

def basis_page(c,k,index):
 k.ACTIVE='INSTRUMENT_BASIS';title='Instrument hardware and research basis';k.titleblock(c,index,title,'Manufacturer references reviewed 2026-11-08 | Engineering arrangement proposal, not a new measurement model')
 k.txt(c,40,720,'NATIVE INSTRUMENT HEADS / COMMON SCALE',11,k.BLUE,'Helvetica-Bold')
 poly=[]
 for name,x in [('digital',0),('digital',.4),('digital_level',.85),('dial',1.3)]:poly += [(v+[x,0,0],m) for v,m in sensor_layouts.native_assets(k,name)]
 basis=k.camera_basis([.2,.25,1]);q=np.concatenate([v for v,m in poly])@basis.T;sc=min(690/np.ptp(q[:,0]),160/np.ptp(q[:,1]));k.drawview(c,poly,basis,(40,500,715,170),sc,k.VIEWS/f'{index:02d}-hardware.png',f'P / T / L DIGITAL HEADS AND DIAL ALTERNATIVE | 1:{2834.6457/sc:.1f}',False)
 y=470
 notes=[
 ('Pressure / R1','Use a short top vapour tapping for the revised drums and sphere. The head is accessible for service; its elevation is not a bottom hydrostatic-pressure measurement.'),
 ('Temperature / R2-R3','A top-mounted display needs a probe into the contents. The proposal shows a long top-entry immersion assembly. The existing game still reports one bulk temperature; this drawing does not add a temperature profile or a phase-selection solver.'),
 ('Level / R4','The drum level transmitter uses a separate vertical radar entry. Keep it clear of inlet nozzles and internal obstructions. The game value remains liquid-plus-solid height, excluding gas.'),
 ('Sphere access / R6','P/T heads are served from the actual top platform. Their roof nozzles stand beside the outside rim with short support brackets, preserving the walking path, manway, relief stacks and access opening. The small sphere level pad shifts clear of rear access; its logical slot is retained.'),
 ('Cooling towers / R5; owner revision J','Ti is at the hot-water inlet and Tb senses the cold basin. Both tower types have factory P/T and a factory L1 level gauge at its unchanged position; no manual tower sensor points remain.'),
 ('Network / current host contract','Equipment-mounted instruments share the equipment DATA port at C1. 20/25 mm metallic instrument conduits are fixed equipment parts, not another placeable cable size. Pipe-mounted sensors retain their direct network-adapter arrangement.')]
 for heading,text in notes:
  k.txt(c,40,y,heading,9,k.BLUE,'Helvetica-Bold');y-=15;y=k.lines(c,40,y,text,110,8.4,12)-13
 assert y>47,y
 k.txt(c,820,744,'RESEARCH SOURCES',11,k.BLUE,'Helvetica-Bold');y=720
 for heading,text,url in SOURCES:
  k.txt(c,820,y,heading,8.5,k.INK,'Helvetica-Bold');y-=15;y=k.lines(c,820,y,text,54,8.1,11)-3
  k.txt(c,820,y,'Open manufacturer reference',8,k.BLUE);c.linkURL(url,(820,y-2,960,y+10),relative=0,thickness=0);y-=28
 c.bookmarkPage(k.ACTIVE);c.addOutlineEntry(title,k.ACTIVE,level=0);c.showPage();return dict(id=k.ACTIVE,title=title,sheet=index,mode='RESEARCH',nativeFacets=len(poly),ports=[],sources=[dict(title=a,note=b,url=u) for a,b,u in SOURCES])

def pages(c,k):
 start=len(k.MAN['entries'])+3;result=[]
 for i,id in enumerate(sensor_layouts.CHANGED):result.append(layout_page(c,k,id,start+i))
 end=start+len(sensor_layouts.CHANGED);result.append(register_page(c,k,end));result.append(basis_page(c,k,end+1));result.append(expansion_basis_page(c,k,end+2));result.append(rotating_page(c,k,end+3));result.append(thermal_page(c,k,end+4));result.append(column_page(c,k,end+5));return result

def thermal_page(c,k,index):
 k.ACTIVE='THERMAL_FEEDBACK';title='Thermal equipment - internal controller signals';k.titleblock(c,index,title,'Built-in instrumentation | Both cooling-tower level gauges are factory fitted | Existing DATA interface C1')
 x=[40,430,915];y=718
 for at,label in zip(x,['EQUIPMENT REFERENCE','CONNECTIONS / MEASUREMENTS','MANUAL MOUNTS']):k.txt(c,at,y,label,10,k.BLUE,'Helvetica-Bold')
 y-=28;rows=[]
 for e in k.MAN['entries']:
  d=json.loads((k.ROOT/'geometry'/e['file']).read_text())
  if d['kind'] not in signal_catalog.THERMAL_KINDS:continue
  cat=sensor_layouts.plan(d,k.raw(d),k);flow=d['kind'] in ['EXCHANGER','KETTLE'];level=d['kind'] in signal_catalog.TOWER_KINDS
  desc='P/T'+('/F' if flow else '')+' at all '+str(len(d['processPorts']))+' inlet/outlet roles'+('; basin L' if level else '')
  for at,text in zip(x,[k.TITLES[d['id']],desc,'None']):k.txt(c,at,y,text,9)
  k.line(c,(40,y-10),(1150,y-10),'#d8e2e7',.5);y-=30;rows.append(dict(id=d['id'],signals=cat['controllerSignals']))
 y-=25;k.txt(c,40,y,'CHANNEL MEANING',11,k.BLUE,'Helvetica-Bold');y-=25
 notes=[
  'Furnaces: P/T at every convection/radiant inlet and outlet. The native fuel connection also has its own P/T channel. Twin/single-cell designs retain their actual connection roles.',
  'AC means the air cooler: P/T at PROCESS_INLET and PROCESS_OUTLET. Both water-cooling-tower types expose hot-water-inlet P/T, cold-water-outlet P/T and basin level.',
  'Level on both cooling towers is built in. The existing L1 gauge positions and native instrument housings are retained; the player does not install a level accessory. Ti/Tb show the factory inlet/basin temperature points.',
  'Standard/Crossflow exchangers expose P/T/F independently at TUBE_INLET, TUBE_OUTLET, SHELL_INLET and SHELL_OUTLET. Inlet/outlet volumetric rates can differ with density and phase; do not copy one volume rate across both ends.',
  'The related kettle reference exposes P/T/F at each of its five actual ports; shell feed, vapour and liquid remain distinct from the heating-medium tube inlet/outlet. Its existing pool model remains unchanged.',
  'Every value belongs to its actual accepted port state and carries its source unit/basis. F is fluid flow, not heat duty. Unknown or unmodelled quantities are unavailable, never zero or a borrowed neighbouring state.',
  'Signals are selected by equipment ID plus stable role/channel key and travel through C1 to the connected controller. Geometry, process nozzles, existing cable routes and publication cadence remain unchanged.',
  'This schedule is a drawing/implementation contract. It does not claim that the current game already publishes the new controller channels.']
 for text in notes:y=k.lines(c,40,y,text,160,9,13)-12
 assert y>48,y
 c.bookmarkPage(k.ACTIVE);c.addOutlineEntry(title,k.ACTIVE,level=0);c.showPage();return dict(id=k.ACTIVE,title=title,sheet=index,mode='SIGNALS',section='CHANNEL MEANING',nativeFacets=0,ports=[],rows=rows)

def column_page(c,k,index):
 k.ACTIVE='COLUMN_FEEDBACK';d=json.loads((k.ROOT/'geometry/COLUMN.json').read_text());cat=sensor_layouts.plan(d,k.raw(d),k);title='Column - complete process, utility and reflux signal scope';k.titleblock(c,index,title,'Column C1 supervises its installed assembly members | Dynamic stream roles and ordinals | No new solver')
 k.txt(c,40,718,'COLUMN BODY / REFERENCE NOZZLES',11,k.BLUE,'Helvetica-Bold');y=690
 k.txt(c,40,y,'INTERNAL POINT / PORT',9,k.BLUE,'Helvetica-Bold');k.txt(c,375,y,'CONTROLLER CHANNELS',9,k.BLUE,'Helvetica-Bold');y-=24
 rows=[('Top internal stage / head','P_top, T_top'),('Bottom internal stage / sump','P_bottom, T_bottom')]+[(key,'P, T, F') for key,p in signal_catalog.ports(d)]
 for label,values in rows:
  k.txt(c,40,y,label,9);k.txt(c,375,y,values,9);k.line(c,(40,y-8),(700,y-8),'#d8e2e7',.45);y-=22
 y-=20
 for text in [
  'Reflux is an explicit P/T/F channel group at the column return, downstream of the reflux valve. It is distinct from overhead vapour, distillate and a reflux-ratio setpoint.',
  'Top and bottom P/T refer to the accepted internal stage/sump states. Top T is not the condenser outlet or the reflux temperature.',
  'Every current and future inlet/outlet uses the same role/ordinal expansion. Include all installed member ports, intermediate process connections and utilities; do not freeze this reference nozzle count into the interface.',
  'Identity: column equipment ID + member slot + unit ordinal + port role/ordinal + quantity. Repeated side draws, steam feeds, pumparounds and parallel shells remain separately addressable.',
  'Column boundary streams currently publish mol/s, Pa and K. Member networks can publish mass/volume rates. Keep declared bases; convert only with valid composition/density. Do not relabel mol/s as kg/s.',
  'Use accepted scheduled column/member snapshots; keep provenance and unavailable quality when a source is absent or unmodelled. Reflux/utility coverage is required even where the current OperationView does not yet publish it. No GUI-triggered solve or column-model redesign.',
  'Absent optional members have no active readings. Per-unit channels remain distinct; an aggregate flow is an explicitly named sum, not a replacement for the member streams.']:
  y=k.lines(c,40,y,text,98,8.6,12)-12
 assert y>47,y
 k.txt(c,760,718,'ALL P/T/F STREAM FAMILIES',11,k.BLUE,'Helvetica-Bold');y=690
 for f in cat['streamFamilies']:
  y=k.lines(c,760,y,f['name'],63,9,12)-4;y=k.lines(c,760,y,f['ports'],64,8.3,11)-4;y=k.lines(c,760,y,f['basis'],64,8.1,11)-15
 assert y>47,y
 c.bookmarkPage(k.ACTIVE);c.addOutlineEntry(title,k.ACTIVE,level=0);c.showPage();return dict(id=k.ACTIVE,title=title,sheet=index,mode='SIGNALS',section='ALL P/T/F STREAM FAMILIES',nativeFacets=0,ports=[],controllerSignals=cat['controllerSignals'],streamFamilies=cat['streamFamilies'])

def rotating_page(c,k,index):
 k.ACTIVE='ROTATING_FEEDBACK';title='Rotating equipment - internal probes and controller signals';k.titleblock(c,index,title,'Built-in instrumentation | No manual equipment-body sensor mounts | Signals belong to the equipment ID')
 x=[40,382,757,1025];y=718
 for at,label in zip(x,['EQUIPMENT REFERENCE','PROCESS SIGNALS VIA C1','DRIVE FEEDBACK','MANUAL MOUNTS']):k.txt(c,at,y,label,9.5,k.BLUE,'Helvetica-Bold')
 y-=26;rows=[]
 for e in k.MAN['entries']:
  d=json.loads((k.ROOT/'geometry'/e['file']).read_text())
  if not instrument_policy.rotating(d):continue
  native=k.raw(d);cat=sensor_layouts.plan(d,native,k);fluid=instrument_policy.fluid_rotating(d)
  for at,text in zip(x,[k.TITLES[d['id']],'P_in, T_in, P_out, T_out, F' if fluid else 'No fluid passage','N / internal drive feedback','None']):k.txt(c,at,y,text,9)
  k.line(c,(40,y-9),(1150,y-9),'#d8e2e7',.5);y-=23;rows.append(dict(id=d['id'],signals=cat['controllerSignals']))
 y-=25;k.txt(c,40,y,'PROCESS CHANNEL DEFINITIONS',11,k.BLUE,'Helvetica-Bold');y-=25
 definitions=[('P_in / P_out','Pressure at the respective inlet / outlet process passage.','Pa'),('T_in / T_out','Bulk-stream temperature at the respective inlet / outlet passage.','K'),('F','Flow through the machine; mass flow and volumetric flow when available.','kg/s; m3/s'),('N','Existing internal shaft / rotor speed pickup.','rpm')]
 for name,meaning,unit in definitions:
  k.txt(c,40,y,name,10,k.INK,'Helvetica-Bold');k.txt(c,185,y,meaning,9.2);k.txt(c,970,y,unit,9.2);y-=26
 y-=24
 notes=[
  'Pumps, reciprocating and centrifugal compressors, steam turbines and inline movers have factory inlet/outlet P/T probes and a flow channel. No accessory head, exposed sensor mounting pad or separate flowmeter installation is needed on the machine.',
  'Motor/generator frames retain internal speed and drive feedback. They have no fluid passage and therefore no invented process P/T or flow channels.',
  'All signals reach the linked controller through the existing equipment DATA interface C1. Select equipment ID and stable channel key; position is a drawing reference, not the channel identity.',
  'Small hollow leaders on equipment sheets locate the internal passage/shaft references. Internal pickups are wired inside the casing. Surface-conduit markings connect only visible exterior instrument heads. Both connected pump-motor examples omit the former add-on sensor heads.',
  'Existing process nozzles, shafts, casings, DATA/FE terminal geometry and cable routes remain unchanged. Ordinary pipe-mounted instruments remain available on process pipes.',
  'Drawing contract only: controller/runtime implementation remains pending. Units above identify the underlying quantities; GUI display units follow the existing user settings.']
 for text in notes:y=k.lines(c,40,y,text,160,9,13)-9
 assert y>50,y
 c.bookmarkPage(k.ACTIVE);c.addOutlineEntry(title,k.ACTIVE,level=0);c.showPage();return dict(id=k.ACTIVE,title=title,sheet=index,mode='SIGNALS',nativeFacets=0,ports=[],rows=rows)

def expansion_basis_page(c,k,index):
 k.ACTIVE='EXPANSION_BASIS';title='Roof reservations, boot levels and exchanger temperature points';k.titleblock(c,index,title,'Revision H arrangement basis | Game-layout proposal with native geometry | No new pressure-protection or phase solver')
 blocks=[
  ('VERTICAL DRUM / LEAVE ROOM TO GROW',[
   'P1, T1 and L1 are grouped on the front half of the roof. F1 and F2 are separate rear service envelopes for a future blanketing valve and PSV. Current process nozzle and manway geometry remains native.',
   'Each dashed envelope is 1.0 x 1.0 m in plan and 1.5 m high at this reference size. These are provisional spatial allowances, not selected valve sizes, relief capacities, nozzle bores or a certified safety design. No future valve is depicted as already installed.',
   'Reserve independent process access for protection and blanketing. A blanketing regulator controls vapor-space pressure; it does not replace the required relief protection.']),
  ('HORIZONTAL DRUM / TWO DIFFERENT LEVEL CHANNELS',[
   'The plain drum remains in the book. A separate boot-enabled reference uses the exact native boot, drain, liquid outlet and minimum-height saddles.',
   'L1 is a top body-level entry clear of the process nozzles. L2 is a separate top guided probe directly over the actual boot axis, continuing through the drum into the boot. It shares the host equipment DATA interface but retains a distinct level_boot identity.',
   'The existing game L1 reads non-gas height including solids. L2 retains its existing heavy-liquid/allocated-solids boot-span reading. The physical guided-probe arrangement does not add a new emulsion/interface algorithm or alter phase separation.']),
  ('STANDARD AND CROSSFLOW / FOUR BUILT-IN T POINTS',[
   'Tti/Tto measure tube inlet/outlet; Tsi/Tso measure shell inlet/outlet. The small integrated heads are fitted on the sides of the existing nozzle necks, leaving the pipe/flange connection faces clear.',
   'Each insertion tip reaches its own nozzle passage. Labels use stream side and inlet/outlet roles, rather than hot/cold, so reversing the thermal service does not rename the physical point. Channels refer to the accepted bulk state at the respective connection, not wall-cell temperature.',
   'Both exchanger configurations retain their native process-port coordinates. All four probes are factory instruments; there are no manual sensor mounting choices on these exchangers. The kettle retains its separate existing pool model.'])]
 y=718
 for heading,paragraphs in blocks:
  k.txt(c,40,y,heading,11,k.BLUE,'Helvetica-Bold');y-=23
  for paragraph in paragraphs:y=k.lines(c,40,y,paragraph,126,9.2,13)-12
  y-=13
 assert y>60,y
 sources=[
 ('Blanketing and protection / manufacturer arrangement','https://www.protectoseal.com/resources/understanding-tank-safety/tank-blanketing/'),
 ('Boot separator / Emerson Engineer\'s Guide, section 4.3.3','https://www.emerson.com/is/content/emerson/en/measurement-instrumentation/marketing/products/level/documents/dl-rmt-00805-0100-1034.pdf'),
 ('Exchanger inlet/outlet temperature monitoring / Emerson','https://www.emerson.com/en/measurement-instrumentation/industries/oil-and-gas/heat-exchanger-efficiency-temperature-monitoring')]
 k.txt(c,840,724,'RESEARCH REFERENCES',11,k.BLUE,'Helvetica-Bold');y=695
 for label,url in sources:
  y=k.lines(c,840,y,label,48,9,13)-12;k.txt(c,840,y,'Open manufacturer source',9,k.BLUE);c.linkURL(url,(840,y-2,1000,y+11),relative=0,thickness=0);y-=48
 k.txt(c,840,y,'REFERENCE CHECKS',11,k.BLUE,'Helvetica-Bold');y-=23
 for text in ['Future envelope footprints sit on the native roof and clear the shown hardware and access zones.',
              'Boot L2 is vertically aligned with the real boot and its hidden probe reaches inside it.',
              'Built-in T necks and insertion tips touch the correct native nozzle passage.',
              'Only reference sizes are checked. Placement, occupied cells, world access and runtime operation remain pending implementation.']:
  y=k.lines(c,840,y,text,48,9,13)-16
 c.bookmarkPage(k.ACTIVE);c.addOutlineEntry(title,k.ACTIVE,level=0);c.showPage();return dict(id=k.ACTIVE,title=title,sheet=index,mode='RESEARCH',nativeFacets=0,ports=[],sources=[dict(title=a,url=b) for a,b in sources])
