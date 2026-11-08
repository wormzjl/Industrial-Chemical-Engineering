"""Native interface close-ups and example cable approaches. Every solid comes from Java owners."""
from pathlib import Path
import numpy as np
import rendering as r


def clip(polys,lo,hi):
    result=[]
    for v,m in polys:
        if np.any(v.max(0)<lo) or np.any(v.min(0)>hi):continue
        p=list(v)
        for axis in range(3):
            for at,positive in [(lo[axis],True),(hi[axis],False)]:
                if not p:break
                out=[];a=p[-1];da=(a[axis]-at)*(1 if positive else -1)
                for b in p:
                    db=(b[axis]-at)*(1 if positive else -1)
                    if (da>=0)!=(db>=0):out.append(a+(b-a)*da/(da-db))
                    if db>=0:out.append(b)
                    a=b;da=db
                p=out
        if len(p)>=3:result.append((np.array(p),m))
    return result


def panel(c,polys,basis,rect,path,title,markers=()):
    if not polys:return
    points=np.concatenate([v for v,m in polys]);span=np.ptp(points@basis.T,axis=0)
    scale=min((rect[2]-36)/max(span[0],.01),(rect[3]-35)/max(span[1],.01))
    origin,scale=r.drawview(c,polys,basis,rect,scale,path,title+f' | 1:{2834.6457/scale:.1f}',False)
    for text,point,colour in markers:
        q=origin+(np.array(point)@basis.T)[:2]*scale
        c.setStrokeColor(r.HexColor(colour));c.setLineWidth(.6);c.circle(q[0],q[1],4)
        r.txt(c,q[0]+6,q[1]+5,text,7.5,colour,'Helvetica-Bold')
    return origin,scale


def pages(c,d,polys,views,snapshot,page,header):
    probes=d.get('instrumentPlan',{}).get('probes',[])
    boxes=d.get('serviceEnclosures',[])
    socket=d.get('existingData')
    if not socket and not probes:return page
    page+=1;header(c,d['id']+' / SERVICE + INSTRUMENT DETAILS',snapshot,page)
    iso=r.camera_basis([1,.8,-1]);markers=[]
    for key,label,col in [('existingData','DATA',r.DATA),('existingPower','FE',r.POWER)]:
        s=d.get(key)
        if s:markers.append((label,[s[a] for a in ['x','y','z']],col))
    if boxes:
        lo=np.min([b['low'] for b in boxes],axis=0)-.45;hi=np.max([b['high'] for b in boxes],axis=0)+.45
    elif socket:
        q=np.array([socket[a] for a in ['x','y','z']]);lo=q-.65;hi=q+.65
    else:
        q=np.mean([p['centre'] for p in probes],axis=0);lo=q-1;hi=q+1
    local=clip(polys,lo,hi)
    side=np.array([1,.6,-1])
    if socket:
        normal=np.array([[0,-1,0],[0,1,0],[0,0,-1],[0,0,1],[-1,0,0],[1,0,0]][socket['face']]);side=normal+np.array([.25,.55,.25])
    basis=r.camera_basis(side)
    panel(c,local,basis,(45,442,500,245),views/f'{d["id"]}-mount-detail.png','NATIVE ENCLOSURE / MOUNT',markers)
    cables=[(np.array(p['v']).reshape(-1,3),p['m']) for p in d.get('cableExamplePolygons',[])]
    panel(c,local+cables,basis,(625,442,500,245),views/f'{d["id"]}-cable-detail.png','DATA / FE APPROACH EXAMPLES',markers)
    r.txt(c,625,421,'Cable paths illustrate connections; the player chooses the route with the shared laying tool.',8,r.MUTE)
    if probes:
        roots=np.array([p['tap'] for p in probes]);heads=np.array([p['centre'] for p in probes]);lo=np.minimum(roots.min(0),heads.min(0))-.5;hi=np.maximum(roots.max(0),heads.max(0))+.6
        reserved=d.get('instrumentPlan',{}).get('reserved',[])
        for box in reserved:
            for axis in [0,2]:lo[axis]=min(lo[axis],box['low'][axis]-.15);hi[axis]=max(hi[axis],box['high'][axis]+.15)
        top=clip(polys,lo,hi);tags=[(p['id'].replace('temperature_','T ').replace('pressure_','P ').replace('level_','L '),p['centre'],r.BLUE) for p in probes]
        plan_basis=np.array([[1,0,0],[0,0,-1],[0,1,0]])
        origin,scale=panel(c,top,plan_basis,(45,110,500,240),views/f'{d["id"]}-probe-plan-detail.png','SERVICE PROBE PLAN',tags)
        for box in reserved:
            low=np.array(box['low']);high=np.array(box['high']);a=origin+(low@plan_basis.T)[:2]*scale;b=origin+(high@plan_basis.T)[:2]*scale
            c.setStrokeColor(r.HexColor(r.POWER));c.setLineWidth(.6);c.setDash([3,2]);c.rect(min(a[0],b[0]),min(a[1],b[1]),abs(b[0]-a[0]),abs(b[1]-a[1]),fill=0,stroke=1);c.setDash([])
            r.txt(c,min(a[0],b[0]),max(a[1],b[1])+5,box['id']+' / '+box['purpose'],7,r.POWER)

        first=probes[0];lo=np.minimum(first['tap'],first['centre'])-.38;hi=np.maximum(first['tap'],first['centre'])+.38
        panel(c,clip(polys,lo,hi),iso,(625,110,500,240),views/f'{d["id"]}-root-detail.png','REINFORCED ROOT / PROTECTED SHORT LEAD',[(first['id'],first['centre'],r.BLUE)])
        r.txt(c,45,82,'Manual mount options are shown fitted for review. Factory probes are equipment-owned.',8,r.MUTE)
        r.txt(c,625,82,'Long runs use facet-conforming surface finish; structural passages stay concealed.',8,r.MUTE)
    else:
        panel(c,polys,iso,(45,105,500,245),views/f'{d["id"]}-service-overview.png','ENCLOSURE IN EQUIPMENT CONTEXT')
        if d['id']=='CONTROL_VALVE':
            r.lines(c,655,308,'The integral positioner owns the DATA connection for actuator access. Pipe-mounted accessory instruments use their existing separate mounts.',65,11,18)
            r.lines(c,655,225,'The pneumatic valve model requires no FE supply. The connection sits on the side-facing positioner.',65,10,17)
        else:
            r.lines(c,655,308,'The unit uses internal instrumentation. The DATA connection exposes accepted engine readings to the local controller / DCS.',65,11,18)
            r.lines(c,655,225,'There are no player-installed sensor mounts on this unit. DATA and, where present, FE use the approved native service housing / integrated connection geometry.',65,10,17)
    c.showPage();return page
