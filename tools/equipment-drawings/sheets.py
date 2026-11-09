"""Dimensioned reference sheets from exported current game meshes, without proposal overlays."""
import html
import json
import math
from pathlib import Path

import numpy as np
from pypdf import PdfReader
from reportlab.pdfgen import canvas

import rendering as r
import service_details

W,H=1190.551,841.89  # A3 landscape, PDF points.
FRONT=np.array([[1,0,0],[0,1,0],[0,0,-1]])
SIDE=np.array([[0,0,1],[0,1,0],[1,0,0]])
TOP=np.array([[1,0,0],[0,0,-1],[0,1,0]])

def header(c,title,snapshot,page):
    c.setFillColor(r.HexColor(r.INK));c.rect(0,H-62,W,62,fill=1,stroke=0)
    r.txt(c,28,H-24,'IChemE / CURRENT EQUIPMENT MODEL / NATIVE GEOMETRY',11,'#a8d8ec','Helvetica-Bold')
    r.txt(c,28,H-48,title,19,'#ffffff','Helvetica-Bold')
    r.txt(c,28,H-82,f'Source: {snapshot} | 1 block = 1 m | Reference pose, not a player-world export',8,r.MUTE)
    r.line(c,(28,45),(W-28,45),'#bbcbd2',.6)
    r.txt(c,28,25,'Geometry and interface coordinates come from current game code. Neutral engineering colours; no proposed parts.',8,r.MUTE)
    r.txt(c,W-105,25,f'SHEET {page:02d}',8,r.MUTE)

def interfaces(d):
    rows=[];counts={}
    for p in d.get('processPorts',[]):
        role=p['role'];counts[role]=counts.get(role,0)+1
        rows.append((f'{role} #{counts[role]}',p['face'],'fluid face'))
    for key,label in [('existingData','DATA'),('existingPower','FE')]:
        if d.get(key):rows.append((label,[d[key][a] for a in ['x','y','z']],'socket datum'))
    for i,p in enumerate(d.get('shaftPorts',[]),1):
        rows.append((f'SHAFT #{i} / {p["role"]}',[p['cell'][a]+.5 for a in ['x','y','z']],'cell centre'))
    for p in d.get('instrumentPlan',{}).get('probes',[]):rows.append((p['id'],p['centre'],'manual mount option' if p['manual'] else 'factory probe'))
    for p in d.get('instrumentPlan',{}).get('reserved',[]):rows.append((p['id'],((np.array(p['low'])+p['high'])/2).tolist(),'reserved: '+p['purpose']))
    return rows

def add_gallery(out):
    out=Path(out);paths=sorted((out/'pages').glob('sheet-*.png'))
    cards=''.join(f'<a href="pages/{p.name}"><img loading="lazy" width="360" src="pages/{p.name}" alt="{p.stem}"></a>' for p in paths)
    index=out/'index.html';index.write_text(index.read_text()+'<h2>Rendered sheets</h2><div>'+cards+'</div>')

def generate(work):
    work=Path(work);out=work/'sheets';out.mkdir();views=out/'views';views.mkdir()
    manifest=json.loads((work/'geometry/manifest.json').read_text());pdf=out/'equipment-drawings.pdf'
    c=canvas.Canvas(str(pdf),pagesize=(W,H));c.setTitle('IChemE current equipment model drawings')
    records=[];page=0
    for entry in manifest['entries']:
        d=json.loads((work/'geometry'/entry['file']).read_text());polys=[(np.array(p['v']).reshape(-1,3),p['m']) for p in d['polygons']]
        points=np.concatenate([v for v,m in polys])
        if not np.isfinite(points).all():raise ValueError(f'Non-finite mesh: {d["id"]}')
        lo,hi=r.bbox(polys);span=hi-lo
        if not (span>0).all():raise ValueError(f'Degenerate equipment envelope: {d["id"]}')
        title=d['id'].replace('_',' ').title();page+=1;header(c,title,manifest['snapshot'],page);c.bookmarkPage(d['id']);c.addOutlineEntry(title,d['id'],level=0)
        ratio=2834.6457/min(315/span[0],245/span[1],315/span[2],245/span[2])
        denominators=[1,2,2.5,5,10,15,20,25,30,40,50,75,100,125,150,200,250,300,400,500,750,1000,1500,2000,3000]
        denominator=next((n for n in denominators if n>=ratio),math.ceil(ratio/1000)*1000)
        scale=2834.6457/denominator
        for basis,rect,label in [(FRONT,(50,434,338,245),'FROM -Z'),(SIDE,(455,434,338,245),'FROM +X'),(TOP,(50,99,338,245),'FROM +Y')]:
            r.drawview(c,polys,basis,rect,scale,views/f'{d["id"]}-{label[-2:]}.png',f'{label} | 1:{denominator:g}')
        basis=r.camera_basis([1,.8,-1]);projected=points@basis.T;extent=np.ptp(projected[:,:2],axis=0)
        iso_scale=min(315/extent[0],225/extent[1])
        r.drawview(c,polys,basis,(455,99,338,245),iso_scale,views/f'{d["id"]}-iso.png',f'ISOMETRIC | 1:{2834.6457/iso_scale:.1f}',False)
        r.line(c,(837,55),(837,737),'#bbcbd2',.6)
        x,y=855,720;r.txt(c,x,y,'NATIVE REFERENCE SETTINGS',10,r.BLUE,'Helvetica-Bold');y-=22
        for key,value in d['parameters'].items():
            r.txt(c,x,y,f'{key}: {value}',8.4);y-=14
        y-=8;y=r.lines(c,x,y,'Drawing dimensions are metres. Size/length settings retain their constructor units (including kW and counts).',49,8,11)-15
        r.txt(c,x,y,'INTERFACE ANCHORS / X, Y, Z (m)',9.5,r.BLUE,'Helvetica-Bold');y-=22
        rows=interfaces(d)
        for label,xyz,meaning in rows:
            if y<110:
                c.showPage();page+=1;header(c,title+' / interface schedule continued',manifest['snapshot'],page);x,y=40,710
            r.txt(c,x,y,label,8.2,r.INK,'Helvetica-Bold');y-=12
            r.txt(c,x,y,' / '.join(f'{float(v):.3f}' for v in xyz)+' | '+meaning,7.8);y-=18
        if not rows:r.txt(c,x,y,'No registered interfaces on this reference.',8.2)
        c.showPage()
        page=service_details.pages(c,d,polys,views,manifest['snapshot'],page,header)
        records.append(dict(id=d['id'],kind=d['kind'],parameters=d['parameters'],nativeEnvelope=[lo.tolist(),hi.tolist()],scale=denominator,polygons=len(polys),interfaces=[dict(label=a,xyz=b,meaning=m) for a,b,m in rows]))
    c.save()
    reader=PdfReader(pdf)
    if len(reader.pages)!=page:raise ValueError('PDF page count differs from generated schedule')
    if any('CURRENT EQUIPMENT MODEL' not in p.extract_text() for p in reader.pages):raise ValueError('Missing sheet header')
    (out/'measurements.json').write_text(json.dumps(dict(**{k:v for k,v in manifest.items() if k!='entries'},pages=page,equipment=records),indent=2))
    links=''.join(f'<li><b>{html.escape(d["id"])}</b>: {d["polygons"]} native polygons, scale 1:{d["scale"]:g}</li>' for d in records)
    (out/'index.html').write_text('<!doctype html><meta charset="utf-8"><title>IChemE equipment drawings</title><style>body{font:16px/1.6 system-ui;margin:3rem;max-width:1100px;color:#233b49}a{color:#137baf}</style><h1>Current equipment model drawings</h1><p>Native game geometry; no proposed-layout overlays. Source '+html.escape(manifest['snapshot'])+'</p><p><a href="equipment-drawings.pdf">Drawing book</a> | <a href="measurements.json">Dimensions and interfaces</a> | <a href="pages/">Rasterized pages</a></p><ul>'+links+'</ul><p>Flat engineering colours approximate materials. Texture/lighting changes also need the existing asset renderer or owner-run client check.</p>')
    return dict(pdf=str(pdf),pages=page,equipment=len(records),profile='native-current-model')
